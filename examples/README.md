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

## Two pieces

| Dir | What | Runs where |
| --- | ---- | ---------- |
| [`harness/`](harness) | Pure-JVM walkthrough of the FULL static path (load map → select by `version_code` → signer guard → resolve real name → hand the obfuscated `Member` to a `Hooker`). | **Anywhere, no Android.** Verified green in CI. |
| [`android/`](android) | The real thing: `victim/` app + `module/` LSPosed module (legacy `XposedBridge` wired live; modern libxposed shown side-by-side as a reference). | A device/emulator with LSPosed (or LSPatch). |

### Run the harness (no SDK needed)

```bash
# from the repo root, using the parent wrapper:
./gradlew -p examples/harness run     # prints the resolve walkthrough
./gradlew -p examples/harness test    # asserts real → obf resolution end-to-end
```

Expected `run` output:

```
real method      : formatTicket   ->   obfuscated member: c
invocation       : formatTicket("T-123")  ->  ticket:T-123
```

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

## LOOP #2 — real R8 obfuscation

Loop #1 keeps R8 off so the runtime names equal what the map declares
(deterministic, no chicken-and-egg). To dogfood *real* obfuscation without
re-authoring the map each build, pin the obfuscated names with an input
mapping so R8 reproduces exactly the names the committed map already uses:

1. in `victim/build.gradle.kts` set `isMinifyEnabled = true` and add
   `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`;
2. in `victim/proguard-rules.pro` add
   `-applymapping fixed-mapping.txt` plus a `-keep` for `MainActivity`;
3. author `victim/fixed-mapping.txt` so
   `com.example.victim.TicketService -> com.example.victim.a.b:` and its method
   `... formatTicket(...) -> c`.

Now R8 emits the same `a.b#c` the map points at, and the module keeps working
unchanged. (The fully general path — read R8's emitted `mapping.txt` and
*generate* the map — is the rosetta-maps authoring story; this example pins the
mapping instead so it stays one self-contained repo.)

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
