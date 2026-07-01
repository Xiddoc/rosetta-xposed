# Self-healing under non-root LSPatch

Rosetta's **self-healing** path (discover an obfuscated name live with on-device
DexKit when no static map covers the running version) needs DexKit's native
library, `libdexkit.so`, loaded into the **host app's process**. Under rooted
LSPosed that is automatic. Under **non-root LSPatch** it is the one genuinely
hard part — this page explains why, and how Rosetta closes it.

> TL;DR — the module ships unchanged; `NativeLibraryLoadPlan` walks an ordered
> load ladder that includes the W^X-safe `apk!/entry` route; and a one-shot
> recipe (`tools/lspatch/embed-dexkit-native.sh`) embeds `libdexkit.so` into the
> patched host APK so that route has a file to hit. The static-map path never
> needs any of this.

## Why `System.loadLibrary` isn't enough

An Xposed-family module runs **inside the target app's process**.
`System.loadLibrary("dexkit")` searches that process's `nativeLibraryDir`.

- **Rooted LSPosed:** the module is a normally *installed* app, so the installer
  already extracted its libs to a real, exec-allowed `nativeLibraryDir`. The
  lookup succeeds (possibly after pointing the loader at the module's own
  `nativeLibraryDir`, which the module discovers via `PackageManager` /
  `createPackageContext` / the class loader's `DexPathList`).
- **Non-root LSPatch (embedded/local mode):** the module is **not installed**.
  It is an APK embedded as a host asset (`assets/lspatch/modules/<pkg>.apk`) and
  loaded through a side class loader. There is **no `nativeLibraryDir`** for it,
  so the name lookup misses.

## Why you can't just extract-and-load

The obvious workaround — copy `libdexkit.so` to the app's `cacheDir` and
`System.load()` it — **fails on stock Android 10+**. A process targeting API
level 29+ is SELinux-`neverallow`ed from executing *any* `app_data_file` (the
label on everything under the app's data directory), **regardless of the write
bit**. So `dlopen` of an extracted copy is denied. This is not a
hardened-ROM edge case; it is the default policy on every modern device.

## The route that works: `apk!/entry`

The bionic dynamic linker can map a `.so` **directly out of a ZIP/APK** using
the `path!/inner/entry` form:

```
System.load("/data/app/…/base.apk!/lib/arm64-v8a/libdexkit.so")
```

Crucially, an **installed** APK under `/data/app` carries the `apk_data_file`
SELinux label, which *is* exec-allowed — so this sidesteps W^X entirely. This is
exactly how **LSPatch loads its own `liblspatch.so`**
(`System.load(sourceDir + "!/assets/lspatch/so/<abi>/liblspatch.so")`).

The catch: the `!/` source must be an *installed* APK — the host's `base.apk`
or a split — **not** the embedded/extracted module APK (that lives under the
app's data dir and would be `app_data_file` again, back to square one). So
`libdexkit.so` must physically live inside the patched **host** APK.

## The load ladder — `NativeLibraryLoadPlan`

`io.github.xiddoc.rosetta.android.NativeLibraryLoadPlan` (in `:android-runtime`,
pure-JVM and 100%-covered) turns these facts into an ordered list of attempts
the consumer walks top-to-bottom, stopping at the first that loads:

1. **`System.loadLibrary(name)`** — hits when the module is installed (rooted)
   *or* the `.so` was merged into the host's own `lib/` at patch time.
2. **`System.load("<installedApkPath>!/<dir>/<abi>/lib<name>.so")`** over the
   host `sourceDir` + split dirs — the **W^X-safe LSPatch route**, tried for
   `assets/rosetta/native` and `lib`.
3. **`System.load("<moduleNativeLibDir>/lib<name>.so")`** over discovered module
   `nativeLibraryDir`s — a rooted host where an extracted copy already exists.

The plan is *pure data*; performing the load (and the reflection that discovers
the input paths) is the irreducible Android edge, kept in the consuming module
(e.g. TickPatch's `TickPatchHooks.ensureDexKitNativeLoaded`). Everything degrades
fail-soft: if the whole ladder misses, self-heal is simply unavailable — never a
host crash.

## The recipe: `tools/lspatch/embed-dexkit-native.sh`

For step 1/2 to have a file to load on non-root, embed `libdexkit.so` into the
patched host APK **after** LSPatch produces it:

```bash
# 1. Patch the target with your module the usual way (embedded/local mode):
java -jar lspatch.jar TickTick.apk -m TickPatch-arm64.apk -o out/ --force -l 2

# 2. Embed DexKit's arm64 native into the patched APK (source can be the module
#    APK — it already contains lib/arm64-v8a/libdexkit.so — or a bare .so):
tools/lspatch/embed-dexkit-native.sh out/TickTick-*.apk TickPatch-arm64.apk

# 3. Install the resulting *-dexkit.apk and force-stop the app so LSPatch reloads.
```

The script injects `lib/arm64-v8a/libdexkit.so` **stored (uncompressed)**,
`zipalign -p`-page-aligns it (required for the `!/` map and for
`extractNativeLibs=false` direct mapping), and re-signs (a debug keystore by
default; override via `KS_*`). arm64-v8a only — LSPatch warns that arm libs
under `lib/` crash the x86 native bridge on emulators, and every real device is
arm64.

On success the module logs:

```
TickPatch: DexKit native loaded via LOAD_LIBRARY dexkit
# or:  … via LOAD_PATH /data/app/…/base.apk!/lib/arm64-v8a/libdexkit.so
```

## Status

The mechanism is grounded in LSPatch's own loader and the recipe is verified to
produce a correctly stored + page-aligned + signed APK. **End-to-end validation
on a physical non-root device is the one remaining step** before treating
non-root self-heal as fully proven; until then the path is best-effort and
fail-soft. If the native still can't load on a given device, capture the log
(Android version + ABI) and file it — that is the data point that turns this
from "should work" into "proven".

## What does NOT need any of this

The fast **static-map** path (a bundled `maps/<version_code>.json` covers the
running version) resolves by O(1) lookup and **never builds a DexKit bridge**,
so a mapped version is fully functional on non-root LSPatch with zero native
code. Self-heal — and hence this whole page — only matters for an *unmapped*
version.
