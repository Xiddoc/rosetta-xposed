/*
 * :xposed — the layer-4 binding for the Xposed / LSPosed / LSPatch family
 * (RFC 0001 Decision 1, the "rosetta-xposed" adapter).
 *
 * Design note — why this module has no Android / Xposed compile dependency:
 * per RFC 0001 Decision 2, rosetta-xposed is a *thin resolver*, not a hook
 * framework. It turns a resolved real → obf name into a `java.lang.reflect`
 * `Member` and hands it to the developer's chosen hook API via the [Hooker]
 * SAM — it does NOT own the hook call. App identity arrives as a plain
 * [io.github.xiddoc.rosetta.xposed.AppIdentity] the caller fills from their
 * `Context`. That keeps the binding pure-JVM: it builds and unit-tests on
 * any runner without the Android SDK, an emulator, or the Xposed API on the
 * classpath. The README shows how to wire libxposed / XposedHelpers in.
 *
 * The dynamic (self-healing) backend's discovery LOGIC ships now
 * (DynamicResolutionBackend, B.1) behind a pure-JVM `DexKitIndex` seam with a
 * fake for tests; the one Android-only piece — the real DexKit adapter that
 * implements that seam on a device — is a thin follow-up, so its
 * `org.luckypray:dexkit` dependency stays commented out until then.
 */
plugins {
    kotlin("jvm")
    // Android API-signature gate (xposed#16). Checks this module's `main`
    // byte-code against the android-api-level-24 signature. This module is the
    // most exposed to the trap the gate guards against — it runs INSIDE the app
    // JVM on Android ART (e.g. the boot/platform-loader handling in Targets.kt
    // deliberately avoids `ClassLoader.getPlatformClassLoader()`, a JDK-9 API
    // absent on ART); the gate makes that constraint mechanical, not a comment.
    id("ru.vyarus.animalsniffer")
}

// Repositories are declared centrally in settings.gradle.kts. Phase 2 (the
// dynamic backend) will add the Xposed API + DexKit repos there:
//   maven("https://api.xposed.info/")   // de.robv.android.xposed:api
//   mavenCentral()                       // org.luckypray:dexkit

dependencies {
    api(project(":core"))

    // Dynamic backend (B.1) — the H4 ReDoS chokepoint; see SafePattern.
    // The current live path passes contributor strings to the DexKitIndex seam
    // as literals (bounded by checkLen/checkBounds). RE2J is the ready,
    // tested seam for when the device adapter routes contributor-supplied
    // regex anchors through SafePattern.compile / compileAll (linear-time;
    // no catastrophic backtracking, unlike java.util.regex / kotlin.text.Regex).
    // RE2 is pure-JVM, so it keeps the module device-free and unit-testable.
    implementation("com.google.re2j:re2j:1.7")

    // The Android API contract :xposed's `main` byte-code is checked against
    // (xposed#16). API level 24 matches the example app's minSdk.
    signature("net.sf.androidscents.signature:android-api-level-24:7.0_r2@signature")

    // The real DexKit-backed adapter that implements the `DexKitIndex` seam on
    // a device is a thin, on-device follow-up (RFC 0001 Decision 5 — DexKit is
    // an OPTIONAL later-phase dependency); it stays commented out here:
    //   compileOnly("de.robv.android.xposed:api:82")
    //   compileOnly("org.luckypray:dexkit:2.0.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

// Animal-sniffer (xposed#16): police only OUR `main` byte-code against the
// android-api-level-24 contract. The plugin checks only this module's compiled
// classes — it does NOT police the byte-code of `implementation` dependencies
// like RE2J — so the gate is inherently confined to the code that actually runs
// on ART inside the app. (The 1.7.1 extension exposes no `ignoreDependencies`
// knob; that confinement is the plugin's built-in behaviour, not a setting.)
animalsniffer {
    ignore("java.time.*")
}

// Police only `main` (the byte-code that ships and runs on Android ART), not
// the JVM-only test sources (which legitimately use APIs absent on ART, e.g.
// Method.getParameterCount / ClassLoader.getPlatformClassLoader). The
// auto-registered `animalsnifferTest` task is disabled accordingly.
tasks.named("animalsnifferTest") { enabled = false }

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
