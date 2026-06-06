/*
 * Standalone build for the "WITH real R8 obfuscation" example test.
 *
 * The key idea (already proven by tools/dex-fixture for the dexkit fixture):
 * obfuscating bytecode does NOT need the Android SDK — the standalone R8
 * compiler is an ordinary artifact on Google's Maven. Here it is resolved as a
 * normal Gradle dependency (`com.android.tools:r8`, cached and offline after
 * the first fetch), so this test runs real R8 over a victim source and proves
 * Rosetta resolves real -> obf names across GENUINE obfuscator output, on a
 * plain JVM, with no SDK and no emulator.
 *
 * Run it from the repo root with the parent wrapper (network needed once to
 * fetch R8; offline thereafter):
 *
 *     ./gradlew -p examples/r8 test
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google() // standalone R8 lives here (com.android.tools:r8)
        mavenCentral()
    }
}

rootProject.name = "rosetta-xposed-example-r8"

includeBuild("../..")
