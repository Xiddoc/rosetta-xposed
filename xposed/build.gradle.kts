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
 * The DexKit-backed dynamic (self-healing) backend is the one optional
 * Android-only piece; it is architected here (see DynamicResolutionBackend)
 * and built in a later phase, so its `org.luckypray:dexkit` dependency stays
 * commented out until then.
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

    // Phase 2 — on-device self-healing backend (RFC 0001 Decision 2):
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
