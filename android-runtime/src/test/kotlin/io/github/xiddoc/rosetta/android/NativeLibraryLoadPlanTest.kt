package io.github.xiddoc.rosetta.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeLibraryLoadPlanTest {
    private fun path(arg: String) = NativeLoadStep(NativeLoadKind.LOAD_PATH, arg)

    private fun name(arg: String) = NativeLoadStep(NativeLoadKind.LOAD_LIBRARY, arg)

    @Test
    fun `library file name follows the Android lib_ so convention`() {
        assertEquals("libdexkit.so", NativeLibraryLoadPlan.libraryFileName("dexkit"))
    }

    @Test
    fun `the ordinary loadLibrary attempt is always first`() {
        val plan =
            NativeLibraryLoadPlan.forLibrary(
                libraryName = "dexkit",
                supportedAbis = emptyList(),
                installedApkPaths = emptyList(),
                extractedNativeDirs = emptyList(),
            )
        // With no APKs or dirs to probe, the ladder is JUST the name lookup.
        assertEquals(listOf(name("dexkit")), plan)
    }

    @Test
    fun `builds the W^X-safe apk-entry ladder over installed APKs, abis and dirs`() {
        val plan =
            NativeLibraryLoadPlan.forLibrary(
                libraryName = "dexkit",
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
                installedApkPaths = listOf("/data/app/base.apk", "/data/app/split_config.arm64_v8a.apk"),
                extractedNativeDirs = emptyList(),
                apkEntryDirs = listOf("assets/rosetta/native", "lib"),
            )

        // First the name lookup, then apkPath-major / dir-middle / abi-inner so the
        // most likely hit (host base.apk + injected asset dir + primary abi) is the
        // first apk!/entry attempt.
        assertEquals(
            listOf(
                name("dexkit"),
                path("/data/app/base.apk!/assets/rosetta/native/arm64-v8a/libdexkit.so"),
                path("/data/app/base.apk!/assets/rosetta/native/armeabi-v7a/libdexkit.so"),
                path("/data/app/base.apk!/lib/arm64-v8a/libdexkit.so"),
                path("/data/app/base.apk!/lib/armeabi-v7a/libdexkit.so"),
                path("/data/app/split_config.arm64_v8a.apk!/assets/rosetta/native/arm64-v8a/libdexkit.so"),
                path("/data/app/split_config.arm64_v8a.apk!/assets/rosetta/native/armeabi-v7a/libdexkit.so"),
                path("/data/app/split_config.arm64_v8a.apk!/lib/arm64-v8a/libdexkit.so"),
                path("/data/app/split_config.arm64_v8a.apk!/lib/armeabi-v7a/libdexkit.so"),
            ),
            plan,
        )
    }

    @Test
    fun `appends extracted nativeLibraryDir attempts last (rooted path)`() {
        val plan =
            NativeLibraryLoadPlan.forLibrary(
                libraryName = "dexkit",
                supportedAbis = listOf("arm64-v8a"),
                installedApkPaths = emptyList(),
                extractedNativeDirs = listOf("/data/app/~~mod==/base.apk!lib", "/data/data/mod/lib"),
            )
        assertEquals(
            listOf(
                name("dexkit"),
                path("/data/app/~~mod==/base.apk!lib/libdexkit.so"),
                path("/data/data/mod/lib/libdexkit.so"),
            ),
            plan,
        )
    }

    @Test
    fun `uses the default apk entry dirs when the argument is omitted`() {
        val plan =
            NativeLibraryLoadPlan.forLibrary(
                libraryName = "dexkit",
                supportedAbis = listOf("arm64-v8a"),
                installedApkPaths = listOf("/data/app/base.apk"),
                extractedNativeDirs = emptyList(),
            )
        assertEquals(
            listOf(
                name("dexkit"),
                path("/data/app/base.apk!/assets/rosetta/native/arm64-v8a/libdexkit.so"),
                path("/data/app/base.apk!/lib/arm64-v8a/libdexkit.so"),
            ),
            plan,
        )
        // The default surface is public so a consumer can inspect/extend it.
        assertEquals(listOf("assets/rosetta/native", "lib"), NativeLibraryLoadPlan.DEFAULT_APK_ENTRY_DIRS)
    }

    @Test
    fun `blank entries in every input list are ignored`() {
        val plan =
            NativeLibraryLoadPlan.forLibrary(
                libraryName = "dexkit",
                supportedAbis = listOf("", "arm64-v8a"),
                installedApkPaths = listOf("", "/data/app/base.apk"),
                extractedNativeDirs = listOf("", "/data/data/mod/lib"),
                apkEntryDirs = listOf("", "lib"),
            )
        assertEquals(
            listOf(
                name("dexkit"),
                path("/data/app/base.apk!/lib/arm64-v8a/libdexkit.so"),
                path("/data/data/mod/lib/libdexkit.so"),
            ),
            plan,
        )
    }

    @Test
    fun `duplicate inputs collapse to a single step, preserving first order`() {
        val plan =
            NativeLibraryLoadPlan.forLibrary(
                libraryName = "dexkit",
                supportedAbis = listOf("arm64-v8a", "arm64-v8a"),
                installedApkPaths = listOf("/data/app/base.apk", "/data/app/base.apk"),
                extractedNativeDirs = listOf("/data/data/mod/lib", "/data/data/mod/lib"),
                apkEntryDirs = listOf("lib", "lib"),
            )
        assertEquals(
            listOf(
                name("dexkit"),
                path("/data/app/base.apk!/lib/arm64-v8a/libdexkit.so"),
                path("/data/data/mod/lib/libdexkit.so"),
            ),
            plan,
        )
    }

    @Test
    fun `an empty abi list yields no apk-entry attempts even with installed APKs`() {
        val plan =
            NativeLibraryLoadPlan.forLibrary(
                libraryName = "dexkit",
                supportedAbis = emptyList(),
                installedApkPaths = listOf("/data/app/base.apk"),
                extractedNativeDirs = emptyList(),
            )
        assertEquals(listOf(name("dexkit")), plan)
    }

    @Test
    fun `NativeLoadStep has value semantics for dedup and assertions`() {
        // Pin the generated data-class members the gate measures: equality,
        // hashCode, toString, copy and componentN.
        val a = NativeLoadStep(NativeLoadKind.LOAD_PATH, "/x!/lib/arm64-v8a/libdexkit.so")
        val b = a.copy()
        val c = a.copy(kind = NativeLoadKind.LOAD_LIBRARY)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertEquals(NativeLoadKind.LOAD_PATH, a.component1())
        assertEquals("/x!/lib/arm64-v8a/libdexkit.so", a.component2())
        assertTrue(a.toString().contains("LOAD_PATH"))
        assertFalse(a.equals("not a step"))
        // Both enum constants are referenced (values() coverage).
        assertEquals(2, NativeLoadKind.entries.size)
    }
}
