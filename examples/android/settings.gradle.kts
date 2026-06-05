/*
 * Standalone settings for the Android dogfood: a toy "victim" app plus an
 * LSPosed module that hooks it through rosetta-xposed.
 *
 * Like the harness next door, this is its OWN Gradle build, deliberately NOT
 * included in the repo-root settings.gradle.kts — so `./gradlew build` for
 * :core / :xposed stays Android-free and green (CLAUDE.md invariant). This
 * build DOES require the Android SDK and the Android Gradle Plugin, which is
 * why it lives off to the side.
 *
 * Build it from the repo root with the parent wrapper (Android SDK required):
 *
 *     ./gradlew -p examples/android :victim:assembleDebug
 *     ./gradlew -p examples/android :module:assembleDebug
 *
 * NOTE: this Android build was NOT compiled in the scaffolding environment
 * (no Android SDK there). Treat the coordinates/versions below as a starting
 * point and adjust to your local SDK / AGP if needed.
 */
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Legacy Xposed API (de.robv.android.xposed:api). compileOnly only.
        maven("https://api.xposed.info/")
        // Modern libxposed API is published via JitPack. Uncomment if you wire
        // the libxposed entry (see module/build.gradle.kts):
        //   maven("https://jitpack.io")
    }
}

rootProject.name = "rosetta-xposed-example-android"

// Consume :xposed (and transitively :core) from the parent build.
includeBuild("../..")

include(":victim")
include(":module")
