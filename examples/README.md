# rosetta-xposed examples — dogfood the resolve→hook path end-to-end

A self-contained dogfood: a toy **victim app** whose one interesting class is
spelled like obfuscator output, and an **LSPosed module** that hooks it *by its
real name* through a bundled Rosetta map — no third-party app, no reversing, no
APK hosting.

There is no `a.b`/`c` hard-coded in the module. The module says
`TicketService#formatTicket`; the map (`maps/100.json`) carries
`TicketService → com.example.victim.a.b` and `formatTicket → c`; Rosetta does
the translation at attach time. That is the whole value proposition, exercised —
including across an obfuscation **version rotation** (v100 `a.b#c` → v101
`x.y#q`) and through the fail-closed **signer guard**.

> These are **standalone Gradle builds**, deliberately not wired into the
> repo-root `settings.gradle.kts`, so `./gradlew build` for `:core` / `:xposed`
> stays Android-free and green (the CLAUDE.md invariant). They consume
> `io.github.xiddoc.rosetta:xposed` (and `:android-runtime`) from the parent
> build via a composite `includeBuild("../..")` — the pre-Maven distribution
> story a real consumer uses today.

## On the "Android SDK" question

You do **not** need the Android SDK to prove the resolution path — not even
with real obfuscation. Obfuscating bytecode only needs the **standalone R8
compiler**, which is an ordinary artifact on Google's Maven
(`com.android.tools:r8`). The repo already relies on this for the dexkit
fixture (`tools/dex-fixture`, "No Android SDK required"); the `r8/` example
below wires the same lever straight into Gradle. The Android SDK is required
**only** to assemble the actual `victim`/`module` APKs and run them on a device
(the `android/` piece) — and even rosetta-frida's APK pipeline keeps that job
[advisory / non-blocking](../../rosetta-frida/.github/workflows/pipeline.yml)
because the Google Maven SDK hosts are routinely network-restricted in CI.

## Three pieces

| Dir | What | Runs where |
| --- | ---- | ---------- |
| [`harness/`](harness) | Pure-JVM walkthroughs **without R8** (obf names simulated by source spelling, so deterministic and offline): the full static resolve→hook path (`Walkthrough`) **and** the fail-closed signer guard — MATCH / MISMATCH / MISSING / MALFORMED / normalization (`SignerWalkthrough`). | **Anywhere, no Android.** Fast; the required gate. |
| [`r8/`](r8) | The same flow **with real R8 obfuscation** (`--classfile`), across **two versions**: `R8WalkthroughTest` proves resolution against genuine obfuscator output; `VersionRotationTest` proves ONE real-name hook resolves both v100 (`a.b#c`) and v101 (`x.y#q`) by selecting the right map from a `MapRegistry`. Also guards the maps against drifting from what R8 emits. | **Anywhere, no Android SDK.** Needs network once to fetch R8. |
| [`android/`](android) | The real thing: `victim/` app + `module/` LSPosed module (legacy `XposedBridge` wired live; modern libxposed shown side-by-side), consuming the optional `:android-runtime` module — now covering BOTH the static hook (`TicketService`, in the map) and the **dynamic** self-healing path (`AuditService`, absent from the map → live DexKit discovery + persistent cache, rosetta-xposed#22). The module commits **zero** map JSON: it **fetches** `com.example.victim`'s maps from rosetta-maps at build time via the `io.github.xiddoc.rosetta.maps` plugin (rosetta-xposed#39) — see [Build-time maps](../docs/getting-started/build-time-maps.md). | A device/emulator with LSPosed (Android SDK to build). |

### Run the JVM tests (no SDK needed)

```bash
# from the repo root, using the parent wrapper:
./gradlew -p examples/harness run     # prints BOTH the resolve and signer walkthroughs
./gradlew -p examples/harness test    # static-path + signer-guard assertions (no R8)
./gradlew -p examples/r8 test         # real R8 output + version-rotation assertions
```

The `r8` test fetches `com.android.tools:r8` from Google Maven on first run
(cached/offline afterwards), compiles `r8/victim/`, applies
`r8/victim/seed-v100.txt` / `seed-v101.txt` so the obfuscated names match
`maps/100.json` / `maps/101.json`, and loads the resulting jars —
`formatTicket` really becomes `c` on `a.b` (v100) and `q` on `x.y` (v101).

### Build + drive the Android dogfood (Android SDK + LSPosed required)

> Not compiled in the scaffolding environment (no Android SDK there). Treat the
> AGP/SDK versions and the Xposed-API coordinate as a starting point.

```bash
./gradlew -p examples/android :victim:assembleDebug
./gradlew -p examples/android :module:assembleDebug
# install both APKs, enable "Rosetta Example Module" in LSPosed, scope it to
# com.example.victim, force-stop + reopen the victim.
```

The `module` build **fetches** its maps from rosetta-maps at build time (pinned
`ref` in `module/build.gradle.kts`) into `build/generated/rosetta-maps/maps` and
bundles them — no committed map JSON. The first build needs network to
`codeload.github.com`; later builds reuse the ref-keyed cache (and
`offline`/`vendor` modes cover air-gapped builds). See
[Build-time maps](../docs/getting-started/build-time-maps.md).

Open the victim, tap **Call formatTicket("T-123")**:

- module **disabled** → `ticket:T-123`
- module **enabled**  → `HOOKED(ticket:T-123)`  ← the Rosetta-resolved hook fired

`adb logcat | grep rosetta-example` shows the module's attach log.

#### Real R8 in the Android app

`victim/build.gradle.kts` keeps `isMinifyEnabled = false` so the runtime names
equal the map. To dogfood real R8 in the APK too, turn minify on and feed R8 a
fixed input mapping (same seed idea as `r8/`) so the obfuscated names stay in
lockstep with the committed map:

1. `isMinifyEnabled = true` + `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`;
2. `proguard-rules.pro`: `-applymapping ../../r8/victim/seed-v100.txt` (+ a `-keep` for `MainActivity`).

## CI

In `.github/workflows/ci.yml`:

- `examples-harness` (`./gradlew -p examples/harness test`) → **required** —
  pure-JVM, no network beyond cached Maven Central.
- `examples-r8` (`./gradlew -p examples/r8 test`) → **advisory** (job-level
  `continue-on-error`), because it fetches R8 from Google Maven.
- `examples-android-build` (`assembleDebug` of victim + module) → **advisory** —
  needs the Android SDK (preinstalled on `ubuntu-latest`) and reaches
  google()/api.xposed.info. Catches compile/manifest/dex/registration
  regressions the pure-JVM jobs can't (e.g. the `@hide AndroidAppHelper`
  compile error a real build surfaced).

In `.github/workflows/android-e2e.yml` (nightly + on-demand + on `examples/android`
changes), **advisory**:

- `android-e2e` boots an emulator, uses **LSPatch** (non-root) to embed the
  module into the victim APK, launches it, and asserts via logcat against BOTH
  resolution paths. This is the only test that exercises the full on-device path.
  LSPosed-with-root isn't CI-feasible; LSPatch is. The workflow is authored for
  GitHub's KVM runners and may need first-run tuning of the LSPatch flags.
    - **Static** — `TicketService#formatTicket` IS in the bundled map; the
      victim's startup log (tag `RosettaVictim`) reads `HOOKED(ticket:T-123)`.
    - **Dynamic** (rosetta-xposed#22) — `AuditService#auditTicket` is
      *deliberately absent* from the map, so the module resolves it by live
      **DexKit** discovery. The job asserts: a static miss → `DISCOVERED` →
      hook fires (`DHOOKED(...)`); a relaunch → `SERVED_FROM_CACHE` (the
      `PersistentDiscoveryCache` survived the restart, no rescan); a
      version-bumped APK → `CACHE_INVALIDATED` → re-`DISCOVERED`. The resolve-path
      markers come from the module's `LogcatDiscoveryObserver` (tag
      `RosettaDiscovery`). The dynamic path is *more* device-dependent (it loads
      a real native + scans the APK), so it firmly stays advisory — the
      observer / cache / invalidation **logic** is unit-tested on the always-green
      JVM gate instead. The assertion script asserts the **static** path always
      (hard), and the **dynamic** path only when DexKit's native lib is loadable;
      under LSPatch-embedded modules the native isn't extractable to a
      `nativeLibraryDir`, so `System.loadLibrary("dexkit")` finds no `.so` and the
      dynamic assertions **SKIP** (an honest skip with a loud notice, exit 0 — not
      a silent pass), with the discovery/cache/invalidation logic covered by the
      JVM unit tests + the `:dexkit` integration test against the committed DEX
      fixture. See
      [`docs/reference/dexkit-integration.md`](../docs/reference/dexkit-integration.md).

### Building the Android APKs locally

Cloud/agent sessions start without an SDK; install one with the repo script
(see `CLAUDE.md` → "Building the Android example"):

```bash
./scripts/setup-android-sdk.sh
export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"
./gradlew -p examples/android :victim:assembleDebug :module:assembleDebug
```

## The `:android-runtime` module

The two pieces this dogfood originally re-implemented have been **promoted**
into an optional, pure-JVM, fully-tested module — `:android-runtime` (in the root
build and the 100% coverage gate, alongside `:core`/`:xposed`/`:dexkit`):

- **`BundledMaps`** — load a map bundled in the module APK via the module class
  loader (the victim's `AssetManager` is not ours cross-process).
- **`AndroidIdentities`** — hash signing certs and assemble an `AppIdentity`
  from primitives (`versionCode`, `versionName`, the raw cert byte arrays). All
  pure-JVM, so it is unit-tested against known SHA-256 vectors.

The Android example keeps only the **irreducible ~6-line `PackageManager` read**
(`AndroidAppIdentity.of` — the SDK-version branch that extracts `versionCode` /
`versionName` / cert bytes) and delegates hashing + assembly to
`AndroidIdentities.build`.

## Remaining gaps (not yet covered)

- **Reading identity early in legacy `handleLoadPackage`** is awkward
  (`AndroidAppHelper.currentApplication()` can be null), so signer enforcement
  is fiddly on the legacy path — see `LegacyEntry.identityOrFallback`. libxposed
  hands you a context cleanly (see `ModernEntry.kt.txt`).
- **Module registration** differs between legacy (`assets/xposed_init`) and
  libxposed (manifest/service) — both shown.
- Out of scope for this dogfood (static path only): the DexKit dynamic backend
  and deferred binding for late-loaded dex (those live in `:xposed`/`:dexkit`
  with their own unit/integration tests).
