# 🗿 rosetta-xposed

> Hook obfuscated Android apps from **Xposed / LSPosed / LSPatch** modules
> using **real** Java class, method, and field names.

[![CI](https://github.com/Xiddoc/rosetta-xposed/actions/workflows/ci.yml/badge.svg)](https://github.com/Xiddoc/rosetta-xposed/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Status](https://img.shields.io/badge/status-scaffolding-orange)](https://xiddoc.github.io/rosetta-xposed/reference/status/)

Large Android apps rotate their obfuscation every release, so Xposed hooks
written against hard-coded obfuscated names break on the next update.
`rosetta-xposed` loads a **per-version map** that translates real names to
the obfuscated names the running build actually uses, so a module can keep
referencing stable, real names across versions. It is a **thin resolver,
not a hook framework**: it resolves a real name to a
`java.lang.reflect.Member` and hands it to *your* hook API — you still own
the hook call.

It is the JVM/Xposed **client** of the same `schema_version: 2` maps used by
[rosetta-frida](https://github.com/Xiddoc/rosetta-frida). The map schema is
owned by [rosetta-maps](https://github.com/Xiddoc/rosetta-maps); this repo
tracks and consumes it from the Kotlin side.

## Usage

```kotlin
import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.RosettaXposed

// In your Xposed module, once the package is ready: load the map for the
// running version_code, capture the app class loader and identity. The
// map's signer_sha256 guard (if any) is enforced fail-closed.
val rosetta = RosettaXposed.fromMap(MapLoader.fromJson(mapJson), classLoader, identity)

// Resolve by REAL names; hook with the framework YOU already use.
rosetta
    .method("com.example.app.RemoteServiceClient", "requestTicket")
    .hook { member -> Unhook { XposedBridge.hookMethod(member, myHook).unhook() } }
```

When the app ships a new version, swap in that version's map — the hook
source is unchanged.

## Install / Use

This is an **Xposed-family module dependency**, not an npm package.
Distribution is via the usual Xposed/LSPosed/LSPatch mechanisms (build the
module APK and load it through your framework manager) or by depending on
the Kotlin library once it is published to a Maven coordinate. Publishing is
**planned** — see [Status](https://xiddoc.github.io/rosetta-xposed/reference/status/).
For now, clone and build locally:

```sh
./gradlew build          # compile + test both modules (JVM only)
```

## Documentation

Full docs live at **<https://xiddoc.github.io/rosetta-xposed/>**:

- [Concepts](https://xiddoc.github.io/rosetta-xposed/getting-started/concepts/) — the four-layer picture and modules
- [Usage](https://xiddoc.github.io/rosetta-xposed/getting-started/usage/) — resolve-and-hook walkthrough
- [Resolution backends](https://xiddoc.github.io/rosetta-xposed/reference/backends/) — static (built) and dynamic (planned)
- [Building](https://xiddoc.github.io/rosetta-xposed/reference/building/) — toolchain and tasks
- [Status](https://xiddoc.github.io/rosetta-xposed/reference/status/) — what is built vs. planned
- [Related repos](https://xiddoc.github.io/rosetta-xposed/reference/related/) — frida, maps, and schema ownership

## License

[MIT](LICENSE)
