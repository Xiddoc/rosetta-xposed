# rosetta-xposed examples — dogfood the static path end-to-end

A self-contained dogfood: a toy **victim app** whose one interesting class is
spelled like obfuscator output, and an **LSPosed module** that hooks it *by its
real name* through a bundled Rosetta map — no third-party app, no reversing, no
APK hosting.

There is no `a.b`/`c` hard-coded in the module. The module says
`TicketService#formatTicket`; the map (`maps/100.json`) carries
`TicketService → com.example.victim.a.b` and `formatTicket → c`; Rosetta does
the translation at attach time. That is the whole value proposition, exercised.

> These are **standalone Gradle builds**, deliberately not wired into the
> repo-root `settings.gradle.kts`, so `./gradlew build` for `:core` / `:xposed`
> stays Android-free and green (the CLAUDE.md invariant). They consume
> `io.github.xiddoc.rosetta:xposed` from the parent build via a composite
> `includeBuild("../..")` — the pre-Maven distribution story a real consumer
> uses today.

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
| [`harness/`](harness) | Pure-JVM walkthrough of the full static path, **without R8** (the obf names are simulated by source spelling, so it is deterministic and needs no network). | **Anywhere, no Android.** Fast; the required gate. |
| [`r8/`](r8) | The same flow **with real R8 obfuscation**: a victim source is compiled and run through standalone R8 (`--classfile`) at build time, and the test loads the genuinely-obfuscated bytecode. Proves Rosetta resolves across real obfuscator output *and* guards the map against drifting from what R8 emits. | **Anywhere, no Android SDK.** Needs network once to fetch R8. |
| [`android/`](android) | The real thing: `victim/` app + `module/` LSPosed module (legacy `XposedBridge` wired live; modern libxposed shown side-by-side). | A device/emulator with LSPosed (Android SDK to build). |

### Run the JVM tests (no SDK needed)

```bash
# from the repo root, using the parent wrapper:
./gradlew -p examples/harness run     # prints the resolve walkthrough (no R8)
./gradlew -p examples/harness test    # asserts real -> obf resolution (no R8)
./gradlew -p examples/r8 test         # asserts the same against REAL R8 output
```

The `r8` test fetches `com.android.tools:r8` from Google Maven on first run
(cached/offline afterwards), compiles `r8/victim/`, applies
`r8/victim/seed.txt` so the obfuscated names match `maps/100.json`, and loads
the resulting jar — `formatTicket` really becomes `c` on `a.b`.

### Build + drive the Android dogfood (Android SDK + LSPosed required)

> Not compiled in the scaffolding environment (no Android SDK there). Treat the
> AGP/SDK versions and the Xposed-API coordinate as a starting point.

```bash
./gradlew -p examples/android :victim:assembleDebug
./gradlew -p examples/android :module:assembleDebug
# install both APKs, enable "Rosetta Example Module" in LSPosed, scope it to
# com.example.victim, force-stop + reopen the victim.
```

Open the victim, tap **Call formatTicket("T-123")**:

- module **disabled** → `ticket:T-123`
- module **enabled**  → `HOOKED(ticket:T-123)`  ← the Rosetta-resolved hook fired

`adb logcat | grep rosetta-example` shows the module's attach log.

#### Real R8 in the Android app

`victim/build.gradle.kts` keeps `isMinifyEnabled = false` so the runtime names
equal the map. To dogfood real R8 in the APK too, turn minify on and feed R8 a
fixed input mapping (same `seed.txt` idea as `r8/`) so the obfuscated names stay
in lockstep with the committed map:

1. `isMinifyEnabled = true` + `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`;
2. `proguard-rules.pro`: `-applymapping ../../r8/victim/seed.txt` (+ a `-keep` for `MainActivity`).

## CI wiring (suggested)

- `harness` test → **required** check (fast, deterministic, no network).
- `r8` test → **required if** your CI can reach Google Maven; otherwise mark it
  `continue-on-error` (advisory) like rosetta-frida's `pipeline.yml`, since it
  depends on fetching R8.
- `android` assemble → **advisory**, for the same network-restricted-SDK reason.

## Gaps this dogfood deliberately surfaces

Things a real module needs that **do not ship in `:xposed`** (which stays
Android-free by design), each re-implemented here and worth promoting into an
optional `:xposed-android` artifact later:

- **`BundledMaps`** — loading a map bundled in the module APK via the module
  class loader (the victim's `AssetManager` is not ours cross-process).
- **`AndroidAppIdentity`** — filling `AppIdentity` from `PackageManager`,
  including the API-28 `longVersionCode` / `GET_SIGNING_CERTIFICATES` branch and
  the signer-hash computation. Currently only a KDoc snippet in `AppIdentity`.
- **Reading identity early in legacy `handleLoadPackage`** is awkward
  (`AndroidAppHelper.currentApplication()` can be null), so signer enforcement
  is hard to wire on the legacy path — see `LegacyEntry.identityOrFallback`.
  libxposed hands you a context cleanly (see `ModernEntry.kt.txt`).
- **Module registration** differs between legacy (`assets/xposed_init`) and
  libxposed (manifest/service) — both shown.

Out of scope for this first loop (static path only): the DexKit dynamic backend
and deferred binding for late-loaded dex.
