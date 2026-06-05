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
val rosetta = RosettaXposed.fromMap(map, classLoader)

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

When a map carries a `signer_sha256`, it is **enforced**: `fromRegistry`
and the identity-bearing `fromMap(map, classLoader, identity)` overload
compare it against `AppIdentity.signerSha256` (normalized: lowercase hex,
colons/whitespace stripped) and **fail closed** on a problem:

- hashes differ → `SignerMismatchException` (names expected vs actual);
- map demands a signer but `AppIdentity.signerSha256` is `null` →
  `MissingSignerException` (supply the app signer hash);
- map has no `signer_sha256` → no check (the guard is opt-in per map).

```kotlin
val identity = AppIdentity(
    packageName = "com.example.app",
    versionCode = 30405,
    signerSha256 = readSignerSha256(),   // from PackageManager, see AppIdentity KDoc
)
// Enforces the map's signer_sha256 (if any) before binding:
val rosetta = RosettaXposed.fromMap(map, classLoader, identity)
```

The no-identity `fromMap(map, classLoader)` overload performs **no** signer
check (there is no identity to verify against) — prefer the
identity-bearing overload, or `fromRegistry`, in production modules.

## Resolving classes and fields

Beyond methods, the entry point exposes:

- `rosetta.useClass(realClass)` — load the obfuscated `Class` behind a real name.
- `rosetta.field(realClass, realField)` — resolve a field to a `java.lang.reflect.Field`.
- `rosetta.knows(realClass)` — check whether the loaded map (or backend) knows a class.
