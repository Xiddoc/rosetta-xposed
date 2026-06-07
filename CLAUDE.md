# CLAUDE.md — rosetta-xposed

@../CLAUDE.md

## What this project is

The **layer-4 Xposed / LSPosed / LSPatch binding** of the Rosetta
cross-framework architecture (RFC 0001, which lives in the
`rosetta-frida` repo under `docs/rfcs/`). rosetta-frida solved
obfuscation-name rotation for Frida; this repo gives the same benefit to
Xposed-family **modules** — which run *inside the app JVM*, not as an
injected Frida JS host — by consuming the **exact same per-version maps**.

This repo is a **client** of the map schema, not its owner. The canonical,
language-neutral `schema_version: 2` schema is owned by `rosetta-maps`
(`schema/rosetta-map.schema.json`, the single source of truth);
rosetta-frida (TS/Zod) and rosetta-xposed (Kotlin) are both clients that
must track it.

It is a **thin resolver, not a hook framework** (RFC 0001 Decision 2): it
resolves a real name to a concrete `java.lang.reflect.Member` and hands it
to the developer's chosen hook API (libxposed or legacy `XposedHelpers`)
via the `Hooker` SAM. It does **not** own the hook call. This is the JVM
mirror of rosetta-frida's "just makes `Java.use` smarter" stance.

## Layout

Three-module Gradle (Kotlin/JVM) build:

- **`:core`** — pure-JVM Kotlin, the framework-neutral layers (RFC 0001
  layers 2–3): the `schema_version: 2` map model
  (`core/.../model/RosettaMap.kt`), a strict-JSON `MapLoader`, and the
  `Resolver` (`core/.../resolver/`). A faithful twin of rosetta-frida's
  `src/types/map.ts` + `src/validate/schema.ts` + `src/resolver/`. No
  Android, no Xposed: it builds and tests on any JVM.
- **`:xposed`** — the layer-4 binding (`xposed/.../`): `ResolutionBackend`
  (static + dynamic/composite, both implemented and unit-tested with a
  `FakeDexKitIndex`), `RosettaXposed` entry point, bind `Targets`, the
  `Hooker` seam, `AppIdentity`, `DeferredBinding`, and
  `ClassAvailabilityWatcher` — all logic implemented and unit-tested on
  the JVM. What remains: end-to-end on-device native wiring (the committed
  DEX fixture in `:dexkit` covers the adapter path, but a physical-device
  run has not been validated) and Maven publishing.
- **`:dexkit`** (optional module) — the thin `DexKitBackedIndex` adapter
  that wires the `DexKitIndex` seam to the real `DexKitBridge` native.
  Built and has an integration test that runs real DexKit against a
  committed obfuscated DEX fixture; the test skips automatically when the
  native `.so` is absent (CI builds the `.so` from pinned source and
  caches it).

## Decisions already settled (don't re-litigate)

These come from RFC 0001 and were confirmed with the project owner.

1. **Unify at the map artifact, not at the signature.** The same
   `schema_version: 2` JSON that rosetta-frida emits/consumes is loaded
   here. That format is owned by `rosetta-maps`
   (`schema/rosetta-map.schema.json`); this Kotlin side is a client that
   tracks it. Keep the Kotlin model in lockstep with the schema (and with
   rosetta-frida's `src/types/map.ts`); `CURRENT_SCHEMA_VERSION` is
   hard-gated in `MapLoader`. The schema is language-neutral JSON Schema,
   consumed directly / via codegen — **no** npm or git-URL dependency on it
   today; that waits for a distribution phase.
2. **Two resolver implementations (TS + Kotlin), one conformance suite.**
   Behaviour parity is enforced by shared golden fixtures
   (`core/src/test/resources/conformance/`), not shared runtime code.
   Add cases there when you change resolution semantics on *either* side.
3. **Thin resolver; the developer owns the hook call.** The binding returns
   a `Member`; `Hooker` applies the hook. Do **not** pull libxposed /
   XposedBridge into `:core` or make `:xposed` depend on them at compile
   time — keep the binding pure-JVM and framework-agnostic so it stays
   unit-testable without an emulator.
4. **App identity: `version_code` selects, `signer_sha256` guards
   (enforced).** `AppIdentity` is a plain value the consuming module fills
   from `PackageManager`; `:xposed` must not compile against `android.jar`.
   When a map carries a `signer_sha256`, it is enforced fail-closed
   (`SignerGuard.verify`, used by `fromRegistry` and the identity-bearing
   `fromMap`); a map without one is not checked. `AppIdentity` carries the
   app's signing-cert hashes as a SET (`signerSha256s`) and the guard
   matches-any (the map pins one hash, a real app may present several).
   The unchecked construction path is the explicit `fromMapUnverified`.
5. **Static + dynamic backends are implemented; on-device native wiring is
   the remaining step.** The dynamic (self-healing) backend
   (`DynamicResolutionBackend`), composite backend
   (`CompositeResolutionBackend`), and deferred binding (`DeferredBinding`
   + `ClassAvailabilityWatcher`) are fully implemented and unit-tested via
   a `FakeDexKitIndex`. The `:dexkit` module (`DexKitBackedIndex`) is also
   built and has an integration test against a committed obfuscated DEX
   fixture; the test skips when the native lib is absent. What is not yet
   proven: end-to-end on-device wiring with a real DexKit native loaded on
   Android, and wiring `AppIdentity` from a real `PackageManager` in the
   consuming module. Maven publishing is also pending. Do not add the
   native DexKit dependency to the default build.

## When picking up work here

1. **Read RFC 0001** in the `rosetta-frida` repo end-to-end first — this
   repo is one of its four layers; the design rationale lives there.
2. **Keep `:core` Android-free and conformance-clean.** Any resolution
   change must keep the shared conformance suite green and should add a
   case if it introduces new behaviour. Mirror it on the Frida side.
3. **`./gradlew build`** must stay green on a plain JVM (no Android SDK).
   The build auto-provisions the JDK 17 toolchain via the foojay resolver.
4. **`explicitApi()` is on** for both modules — declare visibility on every
   public symbol.

## Testing mandate

We strive for maximum coverage: **everything that can be tested must be
tested.** Every change adds or extends unit tests that prove it, and
`./gradlew build` (compile + detekt + tests, under `explicitApi()`) must
stay green. Co-locate tests with the module they cover; do **not** touch
the shared conformance fixtures (`core/src/test/resources/conformance/`) —
those are owned by the cross-repo parity agent that keeps the TS and
Kotlin resolvers in lockstep.

## Building the Android example (Android SDK)

The library and the JVM example builds need **no Android SDK** — `:core`,
`:xposed`, `:xposed-android`, and `examples/{harness,r8}` all build/test on a
plain JVM (R8 in `examples/r8` is just the `com.android.tools:r8` Maven jar
with `--classfile`). Only the **APKs** under `examples/android` (the victim app
+ the LSPosed module) need the Android SDK / Android Gradle Plugin.

Cloud / web agent sessions start **without** an SDK. To build the APKs:

```bash
./scripts/setup-android-sdk.sh                 # idempotent; installs into ~/android-sdk
export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"
./gradlew -p examples/android :victim:assembleDebug :module:assembleDebug
```

`dl.google.com` (command-line tools) and Google Maven (AGP) must be reachable —
they are under the default network policy. GitHub-hosted `ubuntu-latest` runners
ship the SDK preinstalled, so CI does **not** run the script. What the SDK does
**not** give you here is a way to *run* the module: that needs a device/emulator
with LSPosed (or an LSPatch-patched APK) — see `examples/README.md`.

## Anti-scope

- **Not a hook framework.** It doesn't define what a hook is; it makes the
  developer's existing hook API obfuscation-resilient.
- **Not a deobfuscator.** It consumes maps; it does not produce them.
  Signatures and generated maps live in `rosetta-maps`.
- **Not a Frida bridge.** The execution models differ; do not try to make
  Frida drive Xposed or vice versa. The shared surface is the *map*.

## Related repos

- **`rosetta-maps`** — **owns** the canonical, language-neutral map schema
  (`schema/rosetta-map.schema.json`, source of truth for `schema_version: 2`)
  and is the community knowledge base of signatures + generated maps both
  clients consume.
- **`rosetta-frida`** — the Frida adapter and the other, first-class client
  of the maps-owned schema; canonical home of the resolver reference and
  RFC 0001.
