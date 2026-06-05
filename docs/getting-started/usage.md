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

1. exact-FQN allowlist (`TargetPolicy.allow`) → ALLOW;
2. top-level prefix on the reserved denylist
   (`DEFAULT_DENY_PREFIXES` — `java.`, `android.`, `androidx.`,
   `kotlin.`, `dalvik.`, `dagger.`, …) → DENY (even under the app prefix);
3. package-local (no `.`) → ALLOW;
4. under the app's own prefix (first two labels of `map.app`,
   configurable via `appNamespaceLabels`) → ALLOW;
5. otherwise → DENY.

Targets are normalized first: array markers are stripped to the element
class, primitives/`void` are always allowed, and the namespace is split
on `.` (so `com.example.app.Foo$Bar` is allowed but `android.os.Foo$Bar`
is denied). Matching is case-sensitive. As defense-in-depth, after a
successful `Class.forName` the binding hard-denies a target realised by
the boot/system/platform class loader unless it was explicitly
allowlisted.

```kotlin
// Default policy: app-owned + package-local targets only.
val rosetta = RosettaXposed.fromMap(map, classLoader, identity)

// Escape hatch for a legitimate framework hook:
val policy = TargetPolicy(allow = listOf("android.app.Activity"))
val rosetta = RosettaXposed.fromMap(map, classLoader, identity, policy)
```

The Frida-side twin mirrors these exact semantics and the same
`DEFAULT_DENY_PREFIXES`.
