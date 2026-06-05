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
}

// Repositories are declared centrally in settings.gradle.kts. Phase 2 (the
// dynamic backend) will add the Xposed API + DexKit repos there:
//   maven("https://api.xposed.info/")   // de.robv.android.xposed:api
//   mavenCentral()                       // org.luckypray:dexkit

dependencies {
    api(project(":core"))

    // Dynamic backend (B.1) — contributor-supplied discovery patterns are
    // compiled ONLY through RE2 (linear-time; no catastrophic backtracking),
    // never java.util.regex / kotlin.text.Regex. This is the ReDoS chokepoint
    // (audit H4); see SafePattern. RE2 is a small, pure-JVM library, so adding
    // it keeps the module device-free and unit-testable.
    implementation("com.google.re2j:re2j:1.7")

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

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
