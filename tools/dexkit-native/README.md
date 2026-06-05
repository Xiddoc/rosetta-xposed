# Host-native `libdexkit.so` (Linux x86_64, glibc)

This directory builds a **desktop/host** build of DexKit's JNI native library so
a plain headless JVM can `System.load(...)` it and use
`org.luckypray.dexkit.DexKitBridge` off-device.

> **Not committed — built in CI (cached).** The `.so` this script produces is
> **not** checked into the repo (it would be binary bloat). CI builds it from
> pinned DexKit `2.2.0` source on each run and **caches** the result, keyed on
> `runner.os` + `x86_64` + the DexKit version (`2.2.0`) + a hash of this build
> script — so the slow source-build only reruns when the version or the script
> changes, and the JDK 17/21 matrix legs share one cold build. Locally, the
> integration test **skips** unless you run `build-libdexkit.sh` first to place
> the native (see "present ⇒ run / absent ⇒ skip" below). The built `.so` is
> git-ignored (`dexkit/src/test/resources/native/`), so a local build never
> accidentally gets committed.

## Why this exists

The published Maven artifact `org.luckypray:dexkit:2.2.0` ships **only**
Android/bionic `.so`s (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` for
**Android**). None of them load on a desktop glibc JVM — they `dlopen`
against bionic `libc.so` / `liblog.so`. The only way to get a host-loadable
library is to build it from the DexKit C++ source against the desktop toolchain.
That is what `build-libdexkit.sh` does.

## What was built

| Item | Value |
|------|-------|
| DexKit repo | https://github.com/LuckyPray/DexKit |
| Tag built | **`2.2.0`** (matches Maven `org.luckypray:dexkit:2.2.0`) |
| Commit | `ffa6c51c38fe3ecfddb18d8949c30c48dbfbfd6a` ("bump version to 2.2.0") |
| Target | Linux **x86_64**, **glibc** |
| Output | `dexkit/src/test/resources/native/linux-x86_64/libdexkit.so` |

## Toolchain used (recorded for reproducibility)

| Tool | Version |
|------|---------|
| cmake | 3.28.3 |
| ninja | 1.11.1 |
| g++ | 13.3.0 (Ubuntu 13.3.0-6ubuntu2~24.04.1) — the active CMake compiler |
| JDK (JNI headers) | OpenJDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`) |
| OS | Ubuntu 24.04 (glibc) |

Build dependencies (all present in the build container; install on a fresh
host with `apt-get install -y cmake ninja-build g++ git zlib1g-dev default-jdk`):

- `zlib1g-dev` — DexKit's Core links `z` (zlib) dynamically.
- A JDK — only for the JNI headers (`include/jni.h`, `include/linux/jni_md.h`);
  CMake's `find_package(JNI)` needs them.

`flatbuffers` and `slicer` are vendored as DexKit git submodules and are
compiled **statically** into the `.so` (Core builds `dexkit_static` from the
slicer sources + bundles the flatbuffers headers), so the library is
self-contained apart from the standard desktop system libraries below.

## Exact commands

This mirrors DexKit's own host pipeline (the `:dexkit-dev` `cmakeBuild` Gradle
task, which configures `dexkit/src/main/cpp/CMakeLists.txt` for the host machine
with the ninja generator at `-O3`/Release and the internal-metrics flags). We
call cmake+ninja directly to avoid pulling in the Android `:demo` Gradle module
(which would require the Android SDK/NDK).

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

git clone --recursive https://github.com/LuckyPray/DexKit /tmp/dexkit-src
cd /tmp/dexkit-src
git checkout 2.2.0
git submodule update --init --recursive

cmake -S dexkit/src/main/cpp -B /tmp/dexkit-build -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG" \
  -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG" \
  -DDEXKIT_ENABLE_INTERNAL_METRICS=ON \
  -DDEXKIT_ENABLE_INTERNAL_METRICS_API=ON \
  -DJAVA_HOME=$JAVA_HOME
ninja -C /tmp/dexkit-build

cp /tmp/dexkit-build/libdexkit.so \
   dexkit/src/test/resources/native/linux-x86_64/libdexkit.so
```

Or just run the wrapper (defaults to version `2.2.0`):

```sh
tools/dexkit-native/build-libdexkit.sh           # build 2.2.0
tools/dexkit-native/build-libdexkit.sh 2.2.0     # explicit
```

## How to refresh / change version

Run `tools/dexkit-native/build-libdexkit.sh <version>`. The script tries tag
`<version>` then `v<version>`, clones outside the repo (`/tmp/dexkit-src`),
builds, copies the `.so` into place, and re-prints the verification block. Keep
the built version aligned with the `org.luckypray:dexkit` Maven version the test
classpath uses, so the JNI symbol names match the Kotlin `native*` declarations.

## How the test consumes the library

The build (`dexkit/build.gradle.kts`) PREPENDS this directory to the test JVM's
`-Djava.library.path`:

```
dexkit/src/test/resources/native/linux-x86_64/
```

The test then calls `System.loadLibrary("dexkit")` (NOT `System.load(<abs
path>)`) on a plain (non-Android) JVM, and the JVM resolves the platform library
name `dexkit` → `libdexkit.so` from that path. The file MUST therefore be named
exactly `libdexkit.so` (the `lib` prefix + `.so` suffix is the Linux mapping
`System.mapLibraryName("dexkit")` applies); renaming it breaks `loadLibrary`.

Present ⇒ run, absent ⇒ skip (see `DexKitBackedIndexIntegrationTest`). The build
hands the test the native directory as the `rosetta.dexkit.nativeDir` system
property (in addition to `-Djava.library.path`), so the test can probe for the
file before trying to load it:

- **Native ABSENT** (the normal local-dev path, or an arch CI never builds for):
  there is nothing to load, so the test `assumeTrue`-**skips** cleanly on **all**
  platforms, linux-x86_64 included. Build it with `build-libdexkit.sh` to run the
  real tests.
- **Native PRESENT but unloadable** on a **supported platform** — Linux on an
  `amd64`/`x86_64` JVM (e.g. CI on `ubuntu-latest`, which builds it) — an
  `UnsatisfiedLinkError` is **fatal**: a real binary is here but broken (e.g.
  glibc below the GLIBC_2.38 floor, or a drifted build), so the test fails with
  a pointer back to this script. CI thus runs the full suite for real and goes
  red if the native drifts or breaks.
- **Native PRESENT but unloadable** on any **other platform** (mac / arm /
  non-glibc): a load failure is benign and the test `assumeTrue`-skips, so an
  incompatible `.so` does not break the build there.

### GLIBC floor

This `.so` is built against the host's glibc and requires **GLIBC_2.38 or
newer** at load time (CI's `ubuntu-latest` ships glibc 2.39). An older glibc
will surface as an `UnsatisfiedLinkError` (version-symbol mismatch) — rebuild on
a host with the required glibc via `build-libdexkit.sh` if you need a lower
floor.

## Verification (reference build)

The built `.so` is **stripped** (`strip --strip-unneeded`, the final step of
`build-libdexkit.sh`) to shrink it; this preserves the `.dynsym` table so all 39
`Java_org_luckypray_dexkit_*` JNI exports remain (verified below). The script
**prints** the `sha256` of the result for ad-hoc verification — the `.so` is not
committed, so the hash below is a reference value from one reproducible build,
not a recorded fixture digest.

| Item | Value |
|------|-------|
| sha256 (stripped, reference build) | `4f182433f23ffcea9e6e9320bd46755dba5300642f38721263c84a1bfa946fef` |
| GLIBC floor | **GLIBC_2.38** (built on Ubuntu 24.04 / glibc 2.39) |

`file`:

```
libdexkit.so: ELF 64-bit LSB shared object, x86-64, version 1 (GNU/Linux),
dynamically linked, BuildID[sha1]=3c9ab0e149a08ea5bb817c954d58d37f7e4295dc,
stripped
```

`readelf -d libdexkit.so | grep NEEDED` — all **desktop** libs, no Android/bionic:

```
libz.so.1
libstdc++.so.6
libgcc_s.so.1
libc.so.6
ld-linux-x86-64.so.2
```

`nm -D libdexkit.so | grep -ic Java_org_luckypray_dexkit` → **39** JNI exports.
Sample:

```
Java_org_luckypray_dexkit_DexKitBridge_nativeInitDexKit          (see below)
Java_org_luckypray_dexkit_DexKitBridge_nativeFindClass
Java_org_luckypray_dexkit_DexKitBridge_nativeFindMethod
Java_org_luckypray_dexkit_DexKitBridge_nativeFindField
Java_org_luckypray_dexkit_DexKitBridge_nativeBatchFindClassUsingStrings
Java_org_luckypray_dexkit_DexKitBridge_nativeExportDexFile
```

Smoke load test (temporary, not committed): a minimal `Smoke.java` that calls
`System.load("<abs path>/libdexkit.so")` on OpenJDK 21 printed `OK` with **no
`UnsatisfiedLinkError`**, confirming host-loadability.
