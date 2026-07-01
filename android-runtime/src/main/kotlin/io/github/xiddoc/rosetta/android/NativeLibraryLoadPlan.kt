/*
 * Builds the ORDERED LADDER of attempts for loading a native library that is
 * NOT reachable through the ordinary `System.loadLibrary` search path — the
 * exact bind an Xposed-family module hits when it runs INSIDE a host app's
 * process and needs its OWN `.so` (e.g. DexKit's `libdexkit.so`, used only on
 * the self-healing fallback).
 *
 * Why a ladder, and why this is the hard part
 * -------------------------------------------
 * `System.loadLibrary("dexkit")` searches the CURRENT process's
 * `nativeLibraryDir`. That works when the module is a normally-INSTALLED app
 * (rooted LSPosed): the installer extracted the module's libs to a real,
 * exec-allowed dir. It does NOT work under **embedded LSPatch (non-root)**: the
 * module is not installed — it is an APK embedded as a host asset and loaded
 * through a side class loader — so there is no `nativeLibraryDir` for it, and
 * the name lookup misses.
 *
 * The obvious fix — extract the `.so` to a writable dir and `System.load` it —
 * is BLOCKED on stock Android 10+: a process targeting API 29+ is SELinux
 * `neverallow`ed from executing ANY `app_data_file` (the label on everything
 * under the app's data dir), regardless of the write bit, so `dlopen` of an
 * extracted copy fails. See `docs/reference/lspatch-non-root.md`.
 *
 * What DOES work — and is how LSPatch loads its own `liblspatch.so` — is mapping
 * the `.so` DIRECTLY out of an already-INSTALLED APK via bionic's `apk!/entry`
 * linker form: `System.load("<installedApkPath>!/lib/<abi>/libdexkit.so")`. An
 * installed APK under `/data/app` carries the `apk_data_file` label, which IS
 * exec-allowed, so this sidesteps W^X entirely. The catch is that the entry
 * must live inside an *installed* APK (the host's `base.apk` / a split), not the
 * embedded module APK — which is why the non-root recipe injects the `.so` into
 * the patched host APK (`docs/reference/lspatch-non-root.md`).
 *
 * This object turns those facts into a pure, ordered list of [NativeLoadStep]s
 * the consumer executes top-to-bottom, stopping at the first that loads. It is
 * PURE (no `android.*`, no `System.load`): the actual load + the reflection that
 * discovers the input paths are the irreducible Android edge and stay in the
 * consuming module — exactly like [AndroidIdentities] keeps the `PackageManager`
 * read out of this gated, fully-unit-tested module.
 */
package io.github.xiddoc.rosetta.android

/** How the consumer must perform a [NativeLoadStep]. */
public enum class NativeLoadKind {
    /** Call `System.loadLibrary(argument)` — [NativeLoadStep.argument] is the base name (`"dexkit"`). */
    LOAD_LIBRARY,

    /** Call `System.load(argument)` — [NativeLoadStep.argument] is an absolute `.so` path OR an `apk!/entry` path. */
    LOAD_PATH,
}

/**
 * One attempt in a native-load ladder: perform [kind] with [argument]. Value
 * type so a plan compares by structure in tests and de-duplicates cleanly.
 */
public data class NativeLoadStep(
    val kind: NativeLoadKind,
    val argument: String,
)

/**
 * Produces the ordered [NativeLoadStep] ladder for loading a native library
 * from inside a host process. Pure logic; see the file header for the physics.
 */
public object NativeLibraryLoadPlan {
    /**
     * Default internal-APK directories probed for `<dir>/<abi>/lib<name>.so`,
     * most-likely first: the dedicated asset dir the non-root recipe injects
     * into (`assets/rosetta/native`), then the standard `lib` dir (in case the
     * `.so` was merged into the host's `lib/<abi>/` at patch time instead).
     */
    public val DEFAULT_APK_ENTRY_DIRS: List<String> = listOf("assets/rosetta/native", "lib")

    /** The Android shared-object file name for [libraryName] (always `lib<name>.so`). */
    public fun libraryFileName(libraryName: String): String = "lib$libraryName.so"

    /**
     * Build the load ladder for [libraryName] (e.g. `"dexkit"`).
     *
     * Order (first that loads wins):
     *  1. [NativeLoadKind.LOAD_LIBRARY] `libraryName` — the ordinary path; hits
     *     when the module is installed (rooted LSPosed) OR the `.so` was merged
     *     into the host's own `lib/` at patch time.
     *  2. `apk!/entry` maps out of each INSTALLED APK in [installedApkPaths]
     *     (host `sourceDir` + split dirs) for each [apkEntryDirs] × [supportedAbis]
     *     combination — the W^X-safe LSPatch path.
     *  3. [NativeLoadKind.LOAD_PATH] of `<dir>/lib<name>.so` for each
     *     [extractedNativeDirs] — a module `nativeLibraryDir` discovered on a
     *     rooted host where an extracted, exec-allowed copy already exists.
     *
     * Blank entries in any input list are ignored, and duplicate steps collapse
     * to their first occurrence, so the returned list is safe to iterate blindly.
     *
     * @param supportedAbis `Build.SUPPORTED_ABIS`, most-preferred ABI first.
     * @param installedApkPaths installed-APK paths (`applicationInfo.sourceDir`
     *   plus `splitSourceDirs`) — the only W^X-safe source for `apk!/entry`.
     * @param extractedNativeDirs candidate module `nativeLibraryDir`s (rooted).
     * @param apkEntryDirs internal-APK dirs to probe; defaults to
     *   [DEFAULT_APK_ENTRY_DIRS].
     */
    public fun forLibrary(
        libraryName: String,
        supportedAbis: List<String>,
        installedApkPaths: List<String>,
        extractedNativeDirs: List<String>,
        apkEntryDirs: List<String> = DEFAULT_APK_ENTRY_DIRS,
    ): List<NativeLoadStep> {
        val soName = libraryFileName(libraryName)
        val abis = supportedAbis.filter { it.isNotBlank() }
        val steps = LinkedHashSet<NativeLoadStep>()

        // 1. The ordinary name lookup.
        steps += NativeLoadStep(NativeLoadKind.LOAD_LIBRARY, libraryName)

        // 2. Map directly out of each installed APK — the W^X-safe LSPatch path.
        for (apkPath in installedApkPaths.filter { it.isNotBlank() }) {
            for (dir in apkEntryDirs.filter { it.isNotBlank() }) {
                for (abi in abis) {
                    steps += NativeLoadStep(NativeLoadKind.LOAD_PATH, "$apkPath!/$dir/$abi/$soName")
                }
            }
        }

        // 3. An extracted, exec-allowed copy in a discovered nativeLibraryDir (rooted).
        for (dir in extractedNativeDirs.filter { it.isNotBlank() }) {
            steps += NativeLoadStep(NativeLoadKind.LOAD_PATH, "$dir/$soName")
        }

        return steps.toList()
    }
}
