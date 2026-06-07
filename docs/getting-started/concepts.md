# Concepts

`rosetta-xposed` is the **layer-4 Xposed binding** of the Rosetta
cross-framework architecture.
rosetta-frida already solved obfuscation-rotation **for Frida**; the
dominant obfuscation-resilient *modules* in the wild (WaEnhancer,
InstaEclipse, …) are Xposed modules running **inside the app JVM**, so they
can't consume the Frida library. This repo gives them the same benefit from
the **same maps**.

## The four-layer picture

Only the bottom layer is framework-specific. rosetta-xposed reuses
layers 1–3 (as a Kotlin twin of rosetta-frida's TypeScript core) and adds a
JVM layer-4 binding:

| Layer | What | Here |
|---|---|---|
| 1. Signature authoring | sigmatcher / DexKit | shared (lives in `rosetta-maps`) |
| 2. Canonical map artifact | `schema_version: 2` JSON, keyed by `(app, version_code)` | `:core` model + loader |
| 3. Resolution semantics | real + version → obf + overload | `:core` resolver (conformance-tested) |
| 4. Runtime binding | apply the resolved name to actually hook | **`:xposed`** (this repo) |

The `schema_version: 2` format is **owned by**
[rosetta-maps](https://github.com/Xiddoc/rosetta-maps); rosetta-xposed is a
client that tracks it. See [Related repos](../reference/related.md).

## Modules

The build is a four-module Gradle (Kotlin/JVM) project.

### `:core`

Pure-JVM Kotlin. The framework-neutral map model, strict JSON
loader/validator, and resolver — a faithful twin of rosetta-frida's
TypeScript core. Kept honest by a **shared conformance suite**
(`core/src/test/resources/conformance/`) that both resolver implementations
must satisfy. No Android, no Xposed, no emulator: it builds and tests on any
JVM.

### `:xposed`

The layer-4 binding. Selects a map by `version_code`, resolves real → obf,
and binds to a concrete `Member`. It stays Android-free at compile time by
abstracting the framework-specific bits behind
[`Hooker`](https://github.com/Xiddoc/rosetta-xposed/blob/master/xposed/src/main/kotlin/io/github/xiddoc/rosetta/xposed/Hooker.kt)
and
[`AppIdentity`](https://github.com/Xiddoc/rosetta-xposed/blob/master/xposed/src/main/kotlin/io/github/xiddoc/rosetta/xposed/AppIdentity.kt).

## App identity

Map selection by `version_code` and the authenticity guard
(`signer_sha256`) come from the shared schema. `AppIdentity` is a plain
value the consuming module fills from `PackageManager`; `:xposed` itself
does not compile against `android.jar`, which keeps it unit-testable
without an emulator.

The `signer_sha256` guard is **enforced** (fail-closed, no opt-out) when a
map carries one: `RosettaXposed.fromRegistry` and the identity-bearing
`fromMap(map, classLoader, identity)` compare the map's single expected
hash against the app's set of signing-certificate hashes
(`AppIdentity.signerSha256s`), matching **any** member, and throw
`SignerMismatchException` / `MissingSignerException` /
`MalformedSignerException` rather than binding against a map for a
differently-signed (possibly repackaged) build. The guard is opt-in per
map — a map with no `signer_sha256` is not checked — and the unchecked
construction path is the explicit `fromMapUnverified`.
