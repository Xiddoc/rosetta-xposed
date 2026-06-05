# DexKit integration (`:dexkit`)

The dynamic (self-healing) backend discovers obfuscated names live, by
*signature*, through the pure-JVM `DexKitIndex` seam (see
[Resolution backends](backends.md)). The `:dexkit` Gradle module is the **real,
device-side implementation** of that seam: a thin adapter,
`DexKitBackedIndex`, that translates the seam's neutral calls into
[DexKit](https://github.com/LuckyPray/DexKit) `DexKitBridge` queries.

It is the **only** module in the codebase that imports `org.luckypray.dexkit`.
DexKit stays an *optional, later-phase* dependency (RFC 0001 Decision 5): the
production `:xposed` binding never compiles against it, so `:core` and `:xposed`
keep building and unit-testing on a plain JVM with a fake index.

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

## The native library + supported-platform / skip semantics

`DexKitBridge` is JNI over a native `libdexkit.so`. The published Maven artifact
ships **only Android/bionic** `.so`s, which do not load on a desktop glibc JVM.
So a **host-built** `libdexkit.so` (Linux x86_64, glibc) is committed under
`dexkit/src/test/resources/native/linux-x86_64/` for the integration test.

- The build **prepends** that directory to the test JVM's
  `-Djava.library.path`, and the test calls `System.loadLibrary("dexkit")`
  (which resolves the platform name `dexkit` → `libdexkit.so`).
- **GLIBC_2.38 floor.** The committed `.so` requires GLIBC_2.38 or newer at load
  time (CI's `ubuntu-latest` ships glibc 2.39).
- **Supported platform = Linux + `amd64`/`x86_64`.** There, the native is
  expected to load, so an `UnsatisfiedLinkError` is **fatal** — the suite fails
  with a pointer back to the refresh script. CI thus runs every test for real
  and goes red if the native drifts.
- **Other platforms** (mac / arm / non-glibc) **skip cleanly** via JUnit
  `assumeTrue`, so a missing/incompatible `.so` does not break the build there.
- Bridge creation is treated separately from native loading: any failure *other
  than* a load failure (e.g. `DexKitBridge.create` choking on a broken dex) is a
  real regression and is left to propagate, never skipped.

## Why `:dexkit` is excluded from the root Kover gate

The root build enforces a **100% line+branch** coverage gate over `:core` +
`:xposed` with no excludes. `:dexkit` is **not** wired into that aggregation:
its integration test legitimately *skips* on machines without the host native,
so its coverage is not deterministic across runners. Coverage of the adapter is
therefore enforced by *deliberate* tests (the
`DexKitBackedIndexIntegrationTest` cases) rather than the gate — every branch of
`DexKitBackedIndex` has an explicit real-bridge test.

## Refreshing the fixtures

Two committed binaries back the integration test; both have a reproducible build
script:

- **The native `libdexkit.so`** — rebuild with
  `tools/dexkit-native/build-libdexkit.sh [version]`. Keep the built version
  aligned with the `org.luckypray:dexkit` Maven version on the test classpath so
  the JNI symbol names match. The script strips the result
  (`--strip-unneeded`, which keeps the `.dynsym` JNI exports) and prints the
  `sha256`; record it in `tools/dexkit-native/README.md`.
- **The DEX fixture `fixture.dex`** + its `fixture-mapping.json` — rebuild with
  `tools/dex-fixture/build.sh`. It compiles the small Java fixture, runs it
  through R8 to produce an obfuscated `classes.dex`, and emits the real→obf
  mapping the test asserts against.
