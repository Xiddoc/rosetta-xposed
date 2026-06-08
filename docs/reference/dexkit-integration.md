# DexKit integration (`:dexkit`)

The dynamic (self-healing) backend discovers obfuscated names live, by
*signature*, through the pure-JVM `DexKitIndex` seam (see
[Resolution backends](backends.md)). The `:dexkit` Gradle module is the **real,
device-side implementation** of that seam: a thin adapter,
`DexKitBackedIndex`, that translates the seam's neutral calls into
[DexKit](https://github.com/LuckyPray/DexKit) `DexKitBridge` queries.

It is the **only** module in the codebase that imports `org.luckypray.dexkit`.
DexKit remains an *optional* dependency (RFC 0001 Decision 5) — the module is
built and integration-tested against a committed obfuscated DEX fixture, but
the production `:xposed` binding never compiles against it, so `:core` and
`:xposed` keep building and unit-testing on a plain JVM with a fake index.
What is not yet proven is end-to-end on-device wiring with the native loaded
on Android.

## What the adapter does

`DexKitBackedIndex` implements each `DexKitIndex` strategy as a single
`DexKitBridge` query and collapses the result with `singleOrNull()` — a miss
(empty) **or** a non-unique match (more than one) both map to `null`, which the
discovery backend turns into a fail-closed `DiscoveryException`. This mirrors
the `FakeDexKitIndex` contract exactly, so the same discovery logic behaves
identically over the fake and the real bridge.

- Class lookups (`findClassByAnchors`, `findClassByAidlDescriptor`) match string
  literals with `StringMatchType.Equals` (an anchor/descriptor is an exact
  literal, not a fuzzy substring).
- `findMethod` matches on the **return-type, parameter-type, and using-strings**
  facets only. The JVM `MethodQuery.descriptor` is intentionally *not*
  decomposed — DexKit matches by dotted type names, which the discovery hints
  already supply.
- `membersOf` enumerates a class's methods, returning empty for a class absent
  from the dex.

The caller owns the `DexKitBridge` (it is `Closeable`; create it in a `use { }`
block and pass it in). The adapter never closes it, and calling adapter methods
after the bridge is closed is undefined.

## Build wiring

DexKit ships as an Android **AAR**, but `:dexkit` is a plain Kotlin/JVM module
(no Android Gradle plugin). The build (`dexkit/build.gradle.kts`) bridges that
gap:

- **AAR → `classes.jar` artifact transform.** An `ExtractClassesJar` transform
  unzips the `classes.jar` entry out of the AAR into a synthetic
  `dexkit-classes-jar` artifact type, which the pure-JVM compile/test classpaths
  consume. No Android plugin required.
- **`isTransitive = false`.** The `dexkitAar` configuration is declared
  non-transitive so Gradle never tries to resolve DexKit's Android-only
  transitive `dev.rikka.ndk.thirdparty:cxx` (which is not on Maven Central).
  DexKit's real runtime dep, `com.google.flatbuffers:flatbuffers-java`, is added
  explicitly for the test runtime and must track DexKit's bundled flatbuffers
  version (bump the two together).
- **`compileOnly` vs `testImplementation`.** DexKit is `compileOnly` for the
  adapter, so it is **not** a runtime dependency of the production binding —
  `:xposed` consumers never drag DexKit in transitively. It is
  `testImplementation` for the integration test, which needs the real bridge at
  runtime.

## The native library — built in CI (cached), not committed

`DexKitBridge` is JNI over a native `libdexkit.so`. The published Maven artifact
ships **only Android/bionic** `.so`s, which do not load on a desktop glibc JVM.
A **host-built** `libdexkit.so` (Linux x86_64, glibc) is therefore needed under
`dexkit/src/test/resources/native/linux-x86_64/` for the integration test — but
it is **NOT committed** (it would be binary bloat). Instead it is **built in CI,
cached**, and built on demand locally:

- **CI builds it (cached).** `.github/workflows/ci.yml` runs
  `tools/dexkit-native/build-libdexkit.sh` from pinned DexKit `2.2.0` source
  before the quality gate, and caches the resulting `.so` via `actions/cache`.
  The cache **key** is `dexkit-native-<os>-x86_64-2.2.0-<hash(build script)>`,
  so the slow source-build only reruns when the DexKit version or the build
  script changes; otherwise the prebuilt `.so` is restored in seconds. The JDK
  17/21 matrix legs share the key, so at most one cold build happens.
- **Git-ignored.** `dexkit/src/test/resources/native/` is in `.gitignore`, so a
  local build never gets committed by accident.
- **Local runs skip the real-DexKit test** unless you build the native first:
  run `bash tools/dexkit-native/build-libdexkit.sh` (after
  `apt-get install -y cmake ninja-build`), and the test then runs for real.

### Present ⇒ run, absent ⇒ skip

The build hands the test the native directory as the `rosetta.dexkit.nativeDir`
system property (alongside `-Djava.library.path`), so `@BeforeAll` probes for
`File(nativeDir, System.mapLibraryName("dexkit"))` **before** loading it:

- **Native ABSENT** → nothing to load → the suite **skips** cleanly via JUnit
  `assumeTrue` on **all** platforms, linux-x86_64 included. This is the normal
  local-dev / unsupported-arch path; CI never hits it because CI builds the lib.
- **Native PRESENT but unloadable** on a **supported platform** (Linux +
  `amd64`/`x86_64`, i.e. CI on `ubuntu-latest`) → a real binary is here but
  broken, so an `UnsatisfiedLinkError` is **fatal**: the suite fails with a
  pointer back to `build-libdexkit.sh`. CI thus runs every test for real and
  goes red if the native drifts.
- **Native PRESENT but unloadable** on other platforms (mac / arm / non-glibc)
  → benign; the suite skips, so an incompatible `.so` does not break the build.
- **GLIBC_2.38 floor.** The `.so` requires GLIBC_2.38 or newer at load time
  (CI's `ubuntu-latest` ships glibc 2.39).
- Bridge creation is treated separately from native loading: any failure *other
  than* a present-native load failure (e.g. `DexKitBridge.create` choking on a
  broken dex) is a real regression and is left to propagate, never skipped.

## Why `:dexkit` is excluded from the root Kover gate

The root build enforces a **100% line+branch** coverage gate over `:core` +
`:xposed` with no excludes. `:dexkit` is **not** wired into that aggregation:
its integration test legitimately *skips* on machines without the host native,
so its coverage is not deterministic across runners. Coverage of the adapter is
therefore enforced by *deliberate* tests (the
`DexKitBackedIndexIntegrationTest` cases) rather than the gate — every branch of
`DexKitBackedIndex` has an explicit real-bridge test.

## On-device dynamic-path e2e (`android-e2e.yml`, rosetta-xposed#22)

The `:dexkit` integration test proves the adapter against a *committed DEX
fixture* on the host JVM. What it cannot prove is the **full on-device path**:
the real `DexKitBridge` native loaded on Android **ART**, scanning a live APK,
driving self-healing discovery inside a hooked app, with the
`PersistentDiscoveryCache` surviving a real process restart. The
`android-e2e.yml` workflow — already the home of the **static**-path LSPatch
assertion — was extended to cover the **dynamic** path too.

### What it exercises

The toy victim (`examples/android/victim`) gained a second "obfuscated" class,
`com.example.victim.c.d#e` (real name `AuditService#auditTicket`), that is
**deliberately absent from the bundled map**, so the static lookup misses and
the module must fall through to discovery. The module
(`examples/android/module`) wires it via
`RosettaXposed.fromMapWithDiscovery(...)` with a real `DexKitBackedIndex`, a
`PersistentDiscoveryCache` over the app's `SharedPreferences`, and a
`DiscoveryHints` whose only signal is a **stable string anchor**
(`rosetta.audit.anchor.v1`) the victim class references — the cross-version
signal DexKit finds the class by even when `c.d`/`e` rotate.

Three logcat assertions, all driven by the **`DiscoveryObserver`** markers (see
[Resolution backends](backends.md)) the module's `LogcatDiscoveryObserver`
prints under tag `RosettaDiscovery`, plus the victim's own `RosettaVictimDyn`
result tag:

1. **Discovery + hook** — static miss → `DISCOVERED` (a fresh DexKit scan) →
   the hook fires (`DHOOKED(...)`), mirroring the static `HOOKED(...)` marker.
2. **Cache hit** — a second launch logs `SERVED_FROM_CACHE` and **no**
   `DISCOVERED` line: the persistent cache survived the process restart, so no
   rescan ran.
3. **Invalidation** — a version-bumped victim APK (`-PvictimVersionCode=101`,
   patched with the same module) is installed as an update; the cache's
   `(app, version_code, signer)` fingerprint changes, so the stale entry is
   dropped (`CACHE_INVALIDATED`) and the name is re-`DISCOVERED`.

### Where the testable value actually lives

The observer / cache-hit / invalidation **logic** is **not** in the Android
module — it is pushed down into the libraries behind the `DiscoveryObserver`
seam (`:xposed`) and `PersistentDiscoveryCache`'s invalidation reporting
(`:xposed-android`), both pure-JVM and **unit-tested on the always-green
`./gradlew build` gate** (`DiscoveryObserverTest`,
`DynamicDiscoveryCacheWiringTest`, `PersistentDiscoveryCacheTest`,
`CompositeDiscoveryWiringTest`). The module's `LogcatDiscoveryObserver` is the
*irreducible Android edge* — it only turns a `DiscoveryOutcome` into a `Log.i`
line, exactly like `SharedPreferencesStore` is the edge of the cache. So the
device run is a smoke test of wiring + the one genuinely ART-only thing (the
native load + a real DexKit scan), while every branch of the surrounding logic
is gated on a plain JVM.

### Static is asserted always; dynamic SKIPs when the native can't load

The assertion script (`examples/android/scripts/e2e-assert.sh`) always asserts
the **static** path as a hard requirement (`HOOKED(ticket:T-123)` must fire). It
asserts the **dynamic** path only when DexKit's native lib is actually loadable
on the device. LSPatch does **not** extract a legacy module's native `.so` to a
`nativeLibraryDir`, so `System.loadLibrary("dexkit")` finds nothing and the
module logs, under tag `LSPosed-Bridge`, `dynamic discovery unavailable: No
implementation found for ... nativeInitDexKit ... is the library loaded, e.g.
System.loadLibrary?`. The script greps the captured logcat for that sentinel
(`dynamic discovery unavailable` / `is the library loaded`) **before** the
dynamic assertions; if it is present, the dynamic assertions (discovery,
cache-hit, version-bump invalidation) **SKIP** with a loud notice and the job
exits 0 — an honest skip, **not** a silent pass or a false claim of discovery.
This mirrors the `:dexkit` integration-test convention exactly (present ⇒ run,
absent ⇒ skip): the discovery / cache / invalidation **logic** is covered by the
JVM unit tests above plus the `:dexkit` integration test against the committed
DEX fixture. The moment DexKit's native *can* load (a future loader, or a
non-LSPatch host), the sentinel is absent and every dynamic assertion runs as a
**hard** gate, so on-device discovery gets full coverage and a regression fails
the job.

### Advisory, not gated

The dynamic-path job stays **advisory** (`continue-on-error: true`), like the
existing static emulator job — in fact *more* so: it adds a real native `.so`
load and a DexKit scan on top of the already-flaky emulator-boot + LSPatch
loader, so it is strictly more device-dependent. Making it a required gate would
let emulator/native flakiness block merges for no correctness gain, because the
correctness it would catch is already covered by the JVM unit tests above. The
**only** always-required gate remains the no-Android-SDK `./gradlew build`. The
version-bump phase is itself skipped (not failed) if the workflow does not
provide the bumped APK, so a partial environment degrades gracefully.

> **Not executed in cloud sessions.** This job needs an emulator + the Android
> SDK + a loadable DexKit native — none of which exist in a web/cloud agent
> session. The wiring, workflow, and assertions are committed and ready to run
> nightly / on demand on a GitHub `ubuntu-latest` runner; a physical
> device/emulator pass is the remaining manual confirmation.

## Refreshing the fixtures

Two binaries back the integration test; both have a reproducible build script.
The DEX fixture is **committed**; the native is **built (CI-cached / on-demand),
not committed**:

- **The native `libdexkit.so`** — build with
  `tools/dexkit-native/build-libdexkit.sh [version]`. It is **not** committed
  (CI builds + caches it; build it locally to run the real test). Keep the built
  version aligned with the `org.luckypray:dexkit` Maven version on the test
  classpath so the JNI symbol names match. The script strips the result
  (`--strip-unneeded`, which keeps the `.dynsym` JNI exports) and **prints** the
  `sha256` for verification (it is not recorded as a committed fixture digest).
- **The DEX fixture `fixture.dex`** + its `fixture-mapping.json` — rebuild with
  `tools/dex-fixture/build.sh`. It compiles the small Java fixture, runs it
  through R8 to produce an obfuscated `classes.dex`, and emits the real→obf
  mapping the test asserts against.
