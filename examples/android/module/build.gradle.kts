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
    // Build-time map fetch (rosetta-xposed#39). Resolved from the parent build via
    // the composite `includeBuild("../..")` in settings.gradle.kts. The maps are
    // pulled from rosetta-maps at build time into build/generated/rosetta-maps/maps
    // and bundled into the APK — this module commits ZERO map JSON.
    id("io.github.xiddoc.rosetta.maps")
}

// Declare WHAT to bundle; the build fetches it. Pinned to a rosetta-maps commit
// SHA for reproducibility/provenance (git content-addressing = integrity). With
// `versions` left at its default the build pulls every published version under
// maps/com.example.victim/ — here 100.json + 101.json, the static map (100) the
// hook reads and the version-bump map (101) the e2e exercises.
rosettaMaps {
    app.set("com.example.victim")
    ref.set("8000d2b93e12b9b6f8b88a4297156d01b686041a")
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

    // Bundle the fetched maps as Java resources, exactly where the hand-copied
    // maps/<version_code>.json used to live — so BundledMaps.load("100.json")
    // reads them off the module class loader at runtime, unchanged. The plugin
    // does NOT auto-wire AGP source sets (it never compiles against the Android
    // toolchain); the consumer adds the one srcDir line, where AGP's types are
    // on the classpath. See docs/getting-started/build-time-maps.md.
    sourceSets["main"].resources.srcDirs(layout.buildDirectory.dir("generated/rosetta-maps"))
}

// Fetch the maps before anything that consumes resources/dex. `preBuild` is the
// AGP anchor every variant build depends on, so this guarantees the generated
// maps exist before they are packaged — without depending on AGP-version-specific
// generated-source wiring.
tasks.named("preBuild") { dependsOn("fetchRosettaMaps") }

dependencies {
    // Resolution layer — the one Rosetta coordinate (pulls :core transitively).
    // Resolved from the parent build via the composite includeBuild in settings.
    implementation("io.github.xiddoc.rosetta:xposed")

    // Optional Android-runtime LOGIC (BundledMaps + AndroidIdentities). Pure JVM;
    // resolved via the same composite includeBuild. Keeps the module from
    // re-implementing bundled-map loading and signer-hash/AppIdentity assembly.
    implementation("io.github.xiddoc.rosetta:android-runtime")

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
