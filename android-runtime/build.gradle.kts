/*
 * :android-runtime — an OPTIONAL, pure-JVM module of reusable Android-runtime
 * LOGIC for Xposed-family modules.
 *
 * Design note — why this module is PURE-JVM (no Android plugin, no android.jar):
 * the :core and :xposed invariant is "builds and unit-tests on a plain JVM, no
 * Android SDK" (CLAUDE.md Decision 4), and the root 100% Kover gate measures
 * every gated module. Code that imports `android.*` could neither build here nor
 * be fully unit-tested, so it must NOT live in a gated module. Instead this
 * module encapsulates the parts that ARE pure and fully testable — loading a
 * bundled map off the class loader (BundledMaps) and the signer-hash +
 * AppIdentity assembly from primitives (AndroidIdentities) — leaving only the
 * irreducible ~6-line PackageManager extraction in the consuming Android module.
 *
 * It depends on :xposed for the AppIdentity type (and transitively :core for the
 * map model + loader) and is wired into the root 100% coverage gate, which this
 * pure-JVM module fully reaches.
 */
plugins {
    kotlin("jvm")
}

dependencies {
    // AppIdentity (and transitively :core's MapLoader + RosettaMap).
    api(project(":xposed"))

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

// Maven-publishing wiring (xposed#21). Shared convention: built-in
// maven-publish + signing, a `-sources` jar, and an (empty) `-javadoc` jar —
// no new plugins, so the offline default build and strict dependency
// verification stay intact. See gradle/publishing.gradle.kts.
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
