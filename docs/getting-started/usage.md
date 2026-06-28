# Usage

rosetta-xposed resolves real names to concrete reflection members; **you**
apply the hook with whatever framework you already use.

## Resolve and hook

```kotlin
import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.xposed.RosettaXposed

// In your Xposed module, once the package is ready and you have its class
// loader (and the map for the running version_code):
val map = MapLoader.fromJson(mapJson)
val rosetta = RosettaXposed.fromMap(map, classLoader, identity)

// Resolve by REAL names; hook with the framework YOU already use.
rosetta
    .method(
        "com.example.app.RemoteServiceClient",
        "requestTicket",
        argTypes = listOf("android.os.Bundle", "com.example.app.IServiceCallback"),
    )
    .hook { member ->
        // legacy XposedBridge:
        val unhook = XposedBridge.hookMethod(member, myMethodHook)
        Unhook { unhook.unhook() }
    }
```

The `argTypes` argument picks a specific overload; omit it when the method
name is unambiguous. The lambda passed to `hook` is a `Hooker` — it receives
the resolved `java.lang.reflect.Member` and returns an opaque `Unhook`
handle. The modern libxposed API works the same way:

```kotlin
rosetta
    .method("com.example.app.RemoteServiceClient", "requestTicket")
    .hook { member -> hookParam.hook(member as Method, beforeAfter) }
```

## Swapping versions

When the app ships a new version, you swap in that version's map — the hook
source is unchanged. Map selection by `version_code` and the authenticity
guard (`signer_sha256`) come from the shared schema. Use
`RosettaXposed.fromRegistry(...)` to select a map from a registry by the
running app's identity.

## Self-healing for unmapped versions (community signatures)

When the running version has **no published map yet**, you don't have to wait
for one — ship Rosetta's community **signatures** (the same
`signatures/<app>/signatures.yaml` that `rosetta-maps` owns) and let the
binding discover the obfuscated names live via DexKit. The hook source is,
again, unchanged: you resolve by real name.

```kotlin
import io.github.xiddoc.rosetta.core.signature.SignatureLoader
import io.github.xiddoc.rosetta.dexkit.DexKitBackedIndex
import org.luckypray.dexkit.DexKitBridge

val signatures = SignatureLoader.fromJson(signaturesJson)   // the app's signatures, as JSON

DexKitBridge.create(app.applicationInfo.sourceDir).use { bridge ->
    val rosetta = RosettaXposed.fromMapWithSignatures(
        map = map,                       // a (maybe empty / older) static map; known names still win
        index = DexKitBackedIndex(bridge),
        signatures = signatures,         // drive discovery for everything the map misses
        classLoader = lpparam.classLoader,
        identity = identity,             // enforces the map's signer_sha256 before any discovery
    )
    // Resolve by REAL name — discovered the same as a mapped one, then cached.
    rosetta.method("com.example.app.RemoteServiceClient", "requestTicket")
        .hook { member -> XposedBridge.hookMethod(member, myHook) }
}
```

The binding harvests the signatures into discovery hints, resolves a static
hit when the map has one, and otherwise locates the class by its
rotation-stable string anchors (exact or regex) and writes the result back so
the next lookup is O(1). It inherits the full `fromMapWithDiscovery` posture
(signer guard, the C1 namespace guard over every discovered name, the
discovery cache/observer). See [Resolution backends](../reference/backends.md)
for the harvest rules and what a signature can and cannot drive.

## Signer enforcement (fail-closed)

When a map carries a `signer_sha256`, it is **enforced** (no opt-out):
`fromRegistry` and the identity-bearing `fromMap(map, classLoader, identity)`
compare the map's single expected hash against the app's set of
signing-certificate hashes (`AppIdentity.signerSha256s`). Hashes are
normalized — lowercase 64-hex, `:` separators stripped, surrounding
whitespace trimmed — and the match is **set membership**: the map's hash
must equal **any** hash the app presents (a real app may be signed by
several certs). It **fails closed** otherwise:

- map's hash matches no app hash → `SignerMismatchException`
  (expected vs the rendered app set);
- map demands a signer but the app set is empty →
  `MissingSignerException` (populate the app signer set);
- map's `signer_sha256` is not 64 hex chars after normalization →
  `MalformedSignerException`;
- map has no `signer_sha256` → no check (the guard is opt-in per map).

```kotlin
val identity = AppIdentity(
    packageName = "com.example.app",
    versionCode = 30405,
    signerSha256s = readSignerSha256s(),   // ALL certs; from PackageManager, see AppIdentity KDoc
)
// Enforces the map's signer_sha256 (if any) before binding:
val rosetta = RosettaXposed.fromMap(map, classLoader, identity)
```

The unchecked `RosettaXposed.fromMapUnverified(map, classLoader)` path
performs **no** signer check — use it only when no `PackageManager` /
`AppIdentity` is available; prefer the identity-bearing `fromMap`, or
`fromRegistry`, in production modules.

## Attach-time health check

Before the first hook you can run a structured sanity pass over the loaded
map against the running app's identity:

```kotlin
import io.github.xiddoc.rosetta.xposed.RosettaXposed

val report = RosettaXposed.healthCheck(map, identity)
if (!report.ok) {
    // report.hardFailures: APP_MISMATCH / VERSION_MISMATCH / SIGNER —
    // each carries a human-readable message and, where one exists, the
    // canonical core exception as `cause` (rethrow to fail loudly).
    report.hardFailures.forEach { log("rosetta health: ${it.kind} — ${it.message}") }
    return  // wrong/broken map — do not hook
}
// report.warnings (EMPTY_MAP / BLANK_OBFUSCATED_NAME) are non-blocking:
// the map is usable, but worth surfacing.
report.warnings.forEach { log("rosetta health (warn): ${it.message}") }
```

`healthCheck` never throws — it returns a `HealthCheckReport` whose `ok`
flag is **derived** from `hardFailures` (true iff that list is empty), so
it can never disagree with the failures it reports. The checks are the
runtime-independent subset (right app, right `version_code`, signer guard,
map sanity); the actual reflective load stays the consuming module's step.

> **Parity asymmetry with Frida (intentional).** The Frida client runs its
> health check **automatically** inside `rosetta.session()` (opt-out),
> whereas rosetta-xposed makes it **opt-in** by design: consistent with the
> thin-resolver stance, the construction factories (`fromMap`,
> `fromRegistry`, …) deliberately do **not** auto-run `healthCheck`. They
> already enforce the signer guard fail-closed; a module pays for the extra
> sanity pass only when it explicitly asks for one.

## Resolving classes and fields

Beyond methods, the entry point exposes:

- `rosetta.useClass(realClass)` — load the obfuscated `Class` behind a real name.
- `rosetta.field(realClass, realField)` — resolve a field to a `java.lang.reflect.Field`.
- `rosetta.knows(realClass)` — check whether the loaded map (or backend) knows a class.

## Target namespace guard (security)

A community map maps a real name to an **arbitrary** obfuscated string,
and that string is the FQN handed to `Class.forName` + `setAccessible`.
To stop a malicious or buggy map from redirecting a hook at a sensitive
framework class (e.g. `java.lang.Runtime`, `android.app.*`,
`dagger.internal.Provider`), every resolution target is checked against
a **namespace guard** (`TargetGuard` / `TargetPolicy`) before any load.

Decision order (fail-closed, strict — a denied target **throws**
`TargetPolicyException`, there is no warn-and-proceed):

1. normalize: strip array markers (`[L…;`, `…[]`) to the element class
   FQN; map internal `/`-separators to `.`; primitives / `void` / empty
   → ALLOW immediately (not loadable classes, never a threat);
2. exact-FQN allowlist (`TargetPolicy.allow`) → ALLOW — matched on the
   **normalized** element FQN (so `allow = ["java.lang.Runtime"]` also
   allows `[Ljava.lang.Runtime;`); matching is **case-sensitive**;
3. top-level prefix on the reserved denylist
   (`DEFAULT_DENY_PREFIXES` — `java.`, `android.`, `androidx.`,
   `kotlin.`, `dalvik.`, `dagger.`, …) → DENY (beats the app prefix —
   see note below);
4. package-local (no `.` in the element FQN) → ALLOW;
5. under the app's own prefix (first two labels of `map.app`,
   configurable via `appNamespaceLabels`) → ALLOW;
6. otherwise → DENY.

> **Reserved-prefix beats app-prefix.** If the app's own package falls
> inside a reserved tree — e.g. `app = "com.android.vending"` with
> app-prefix `com.android` — the reserved denylist still wins for
> `com.android.*` targets (step 3 fires before step 5). The app must use
> the exact-FQN `allow` escape hatch (step 2) to explicitly permit any of
> its own classes that live in a reserved namespace. This is intentional
> fail-closed behaviour, not a bug.

As defense-in-depth, after a successful `Class.forName` the binding
hard-denies a target realised by the boot/system/platform class loader
unless it was explicitly allowlisted (step 2).

```kotlin
// Default policy: app-owned + package-local targets only.
val rosetta = RosettaXposed.fromMap(map, classLoader, identity)

// Escape hatch for a legitimate framework hook:
val policy = TargetPolicy(allow = listOf("android.app.Activity"))
val rosetta = RosettaXposed.fromMap(map, classLoader, identity, policy)
```

The Frida-side twin mirrors these exact semantics and the same
`DEFAULT_DENY_PREFIXES`.
