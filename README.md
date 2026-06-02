# 🗿 rosetta-xposed

> Hook Android apps from **Xposed / LSPosed / LSPatch** modules against
> **real** Java class, method, and field names. A per-version translation
> layer resolves them to the obfuscated names that actually exist at
> runtime — reusing the exact same maps as
> [rosetta-frida](https://github.com/Xiddoc/rosetta-frida).
>
> Write once, hook many versions — now on the JVM side too.

[![CI](https://github.com/Xiddoc/rosetta-xposed/actions/workflows/ci.yml/badge.svg)](https://github.com/Xiddoc/rosetta-xposed/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Status](https://img.shields.io/badge/status-scaffolding-orange)](#status)

## What this is

`rosetta-xposed` is the **layer-4 Xposed binding** of the Rosetta
cross-framework architecture described in
[RFC 0001](https://github.com/Xiddoc/rosetta-frida/blob/master/docs/rfcs/0001-unified-cross-framework-signatures.md).
rosetta-frida already solved obfuscation-rotation **for Frida**; the
dominant obfuscation-resilient *modules* in the wild (WaEnhancer,
InstaEclipse, …) are Xposed modules running **inside the app JVM**, so
they can't consume the Frida library. This repo gives them the same
benefit from the **same maps**.

It is a **thin resolver, not a hook framework** (RFC 0001 Decision 2). It
resolves a real name to a concrete `java.lang.reflect.Member` and hands it
to *your* hook API — the modern libxposed API or legacy `XposedHelpers`. It
does **not** own the hook call. This mirrors rosetta-frida's "just makes
`Java.use` smarter" philosophy.

## The four-layer picture

Only the bottom layer is framework-specific. rosetta-xposed reuses layers
1–3 (as a Kotlin twin of rosetta-frida's TypeScript core) and adds a JVM
layer-4 binding:

| Layer | What | Here |
|---|---|---|
| 1. Signature authoring | sigmatcher / DexKit | shared (lives in `rosetta-maps`) |
| 2. Canonical map artifact | `schema_version: 2` JSON, keyed by `(app, version_code)` | `:core` model + loader |
| 3. Resolution semantics | real + version → obf + overload | `:core` resolver (conformance-tested) |
| 4. Runtime binding | apply the resolved name to actually hook | **`:xposed`** (this repo) |

## Modules

- **`:core`** — pure-JVM Kotlin. The framework-neutral map model, strict
  JSON loader/validator, and resolver — a faithful twin of rosetta-frida's
  TypeScript core. Kept honest by a **shared conformance suite**
  (`core/src/test/resources/conformance/`) that both resolver
  implementations must satisfy. No Android, no Xposed, no emulator: builds
  and tests on any JVM.
- **`:xposed`** — the layer-4 binding. Selects a map by `version_code`,
  resolves real → obf, and binds to a concrete `Member`. Stays Android-free
  at compile time by abstracting the framework-specific bits behind
  [`Hooker`](xposed/src/main/kotlin/io/github/xiddoc/rosetta/xposed/Hooker.kt)
  and [`AppIdentity`](xposed/src/main/kotlin/io/github/xiddoc/rosetta/xposed/AppIdentity.kt).

## Sketch

```kotlin
import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.xposed.RosettaXposed

// In your Xposed module, once the package is ready and you have its
// class loader (and the map for the running version_code):
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

When the app ships a new version, you swap in that version's map — the
hook source is unchanged. Map selection by `version_code` and the
authenticity guard (`signer_sha256`) come from the shared schema.

## Two backends (RFC 0001 Decision 2)

A single resolution API sits in front of two interchangeable backends:

- **Static (default, built)** — a pre-computed map exists for this
  `version_code` → O(1) lookup, no DexKit, no native `.so`, no scan.
- **Dynamic (self-healing, planned)** — no map for this version → run
  DexKit-dialect signatures on-device, resolve, emit a
  `rosetta-runtime-discovered` source, cache, and optionally contribute
  upstream. Architected now (see `DynamicResolutionBackend`), built in a
  later phase; DexKit stays an **optional** dependency.

## Build

```sh
./gradlew build          # compile + test both modules (JVM only)
./gradlew :core:test     # run the shared conformance suite
```

The build requests a JDK 17 toolchain and auto-provisions it (foojay
resolver) if your machine only has a newer JDK. CI runs on JDK 17 and 21.

## Status

**Scaffolding.** The neutral `:core` (model + loader + resolver +
conformance suite) and the static `:xposed` binding (resolve → bind →
`Hooker`) are implemented and tested on the JVM. Not yet built:

- the DexKit **dynamic backend** and **deferred binding** for late-loaded
  dex (both architected as skeletons today);
- on-device `signer_sha256` enforcement and version-code map selection
  wired to a real `PackageManager` read;
- publishing to a Maven coordinate.

## Related repos

- **[rosetta-frida](https://github.com/Xiddoc/rosetta-frida)** — the Frida
  (layer-4) adapter and the original home of the map schema, resolver, and
  RFC 0001.
- **[rosetta-maps](https://github.com/Xiddoc/rosetta-maps)** — the
  community knowledge base of signatures and generated maps that both
  adapters consume.

## License

[MIT](LICENSE)
