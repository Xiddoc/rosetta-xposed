/*
 * :core — the framework-neutral Rosetta layers in pure Kotlin/JVM.
 *
 * This module has NO Android and NO Xposed dependency on purpose: it is
 * the Kotlin twin of rosetta-frida's TypeScript core (layers 2–3 of
 * RFC 0001 — map artifact + resolution semantics). It must stay buildable
 * and testable on any plain JVM so the shared conformance suite can run
 * in ordinary CI without an emulator.
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    // Android API-signature gate (xposed#16). Checks this module's `main`
    // byte-code against the android-api-level-24 signature so a JVM-only API
    // that compiles on JDK 17 but is absent on Android ART fails the build.
    id("ru.vyarus.animalsniffer")
}

// Repositories are declared centrally in settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS).

dependencies {
    // Strict-JSON map parsing. The on-disk artifact is the exact same
    // schema-v2 JSON that rosetta-frida emits and consumes.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // The Android API contract :core's `main` byte-code is checked against
    // (xposed#16). API level 24 matches the example app's minSdk; a reference
    // to an API absent from this signature fails `animalsnifferMain` → `check`.
    signature("net.sf.androidscents.signature:android-api-level-24:7.0_r2@signature")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    // A non-JSON serialization format, used only to exercise the
    // "this serializer can only be read from / written to JSON" guard in
    // MethodOverloadsSerializer (the `decoder as? JsonDecoder ?: error(...)`
    // and the encoder twin) — paths a JSON-only test could never hit.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-properties:1.7.3")
}

// Animal-sniffer (xposed#16): check only `main` (production code that ships
// on-device), not tests. The plugin checks only THIS module's compiled classes
// against the signature — it never policies the byte-code of `implementation`
// dependencies like kotlinx-serialization-json — so the gate is inherently
// focused on OUR code, which is what runs on ART. (The 1.7.1 extension exposes
// no `ignoreDependencies` knob; that focus is the plugin's built-in behaviour.)
animalsniffer {
    // java.time.* is reached only via Android's core-library desugaring on
    // older API levels; it is not in the api-24 signature. We don't currently
    // use it, but pre-declare the desugared package so an additive use does not
    // wrongly trip the gate. Trim if it ever masks a real miss.
    ignore("java.time.*")
}

// Police only `main` (the byte-code that ships and runs on Android ART), not
// the JVM-only test sources. Tests run on a desktop JVM and legitimately reach
// APIs absent on ART (e.g. Method.getParameterCount), so the auto-registered
// `animalsnifferTest` task is disabled — it would fail the build on code that
// never reaches a device.
tasks.named("animalsnifferTest") { enabled = false }

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
