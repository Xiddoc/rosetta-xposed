/*
 * The LSPosed module. An Xposed-family module is itself an installable app,
 * so this is `com.android.application`. It depends on rosetta-xposed for
 * resolution and on the Xposed API only at COMPILE time (`compileOnly`) — the
 * framework provides the API at runtime, exactly as RFC 0001 Decision 2 /
 * CLAUDE.md require (Rosetta resolves; the developer's framework owns the hook).
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.rosettamodule"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.rosettamodule"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        // Keep the module un-minified so its entry-point class names (referenced
        // from assets/xposed_init and the manifest) survive.
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Resolution layer — the one Rosetta coordinate (pulls :core transitively).
    // Resolved from the parent build via the composite includeBuild in settings.
    implementation("io.github.xiddoc.rosetta:xposed")

    // Optional Android-helper LOGIC (BundledMaps + AndroidIdentities). Pure JVM;
    // resolved via the same composite includeBuild. Keeps the module from
    // re-implementing bundled-map loading and signer-hash/AppIdentity assembly.
    implementation("io.github.xiddoc.rosetta:xposed-android")

    // Legacy Xposed API — provided by the framework at runtime, so compileOnly.
    // This is the path LegacyEntry uses and the one wired live in LOOP #1.
    compileOnly("de.robv.android.xposed:api:82")

    // Modern libxposed API (ModernEntry). Published on JitPack; enable the
    // jitpack repo in settings.gradle.kts and pick the tag you target. The
    // coordinate/version below is illustrative — verify against
    // https://github.com/libxposed/api before relying on it.
    //   compileOnly("com.github.libxposed:api:<tag>")
}
