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
        // from assets/xposed_init and the manifest) survive. (R8 minification was
        // trialled to rule out the module's multi-dex shape as the LSPatch
        // embedded-load crash cause; it made no difference, so it is reverted to
        // keep the example simple.)
        debug { isMinifyEnabled = false }
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

    // DYNAMIC path (rosetta-xposed#22): the on-device DexKit adapter
    // (DexKitBackedIndex). Pulls :xposed transitively. The adapter declares
    // DexKit `compileOnly`, so the AAR is NOT dragged in transitively — this
    // Android module adds the real DexKit AAR itself (next line) so the bridge
    // is present at runtime on a device. This is the device-only dependency #22
    // wires; the JVM library build never sees it.
    implementation("io.github.xiddoc.rosetta:dexkit")

    // The real DexKit native bridge AAR. Ships an Android `.so` that loads on
    // ART (NOT on a desktop JVM — see :dexkit's CI-built host native for tests).
    // The example app is the right place for the runtime dependency: only the
    // module-as-app needs the native, never the rosetta-xposed library itself.
    implementation("org.luckypray:dexkit:2.2.0")

    // Legacy Xposed API — provided by the framework at runtime, so compileOnly.
    // This is the path LegacyEntry uses and the one wired live in LOOP #1.
    compileOnly("de.robv.android.xposed:api:82")

    // Modern libxposed API (ModernEntry). Published on JitPack; enable the
    // jitpack repo in settings.gradle.kts and pick the tag you target. The
    // coordinate/version below is illustrative — verify against
    // https://github.com/libxposed/api before relying on it.
    //   compileOnly("com.github.libxposed:api:<tag>")
}
