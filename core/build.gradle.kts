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
}

// Repositories are declared centrally in settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS).

dependencies {
    // Strict-JSON map parsing. The on-disk artifact is the exact same
    // schema-v2 JSON that rosetta-frida emits and consumes.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

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
