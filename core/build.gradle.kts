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

// ---------------------------------------------------------------------------
// Generated BuildInfo coordinates (xposed#21 review MAJOR 2).
//
// `BuildInfo.GROUP` / `BuildInfo.VERSION` are GENERATED from the Gradle
// `project.group` / `project.version` at build time, so they can NEVER drift
// from the published coordinate: a `-Prosetta.version=0.2.0` (or the release
// tag) re-generates the constant, and a test pins it back to the Gradle line.
// Uses only a plain `tasks.register` + a generated source set wired into the
// Kotlin compilation — no new Gradle plugins, so strict dependency
// verification and the offline default build stay intact.
//
// `SCHEMA_VERSION` is NOT generated: it mirrors the loader's
// `CURRENT_SCHEMA_VERSION` Kotlin constant (a compile-time hard gate), so it
// must reference that symbol in source, not be string-substituted here.
// ---------------------------------------------------------------------------
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/buildInfo/kotlin")

val generateBuildInfo by tasks.registering {
    description = "Generates BuildInfo.kt with the Gradle group/version baked in."
    val groupValue = project.group.toString()
    val versionValue = project.version.toString()
    val outputDir = generatedBuildInfoDir
    inputs.property("group", groupValue)
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().dir("io/github/xiddoc/rosetta/core").asFile
        pkgDir.mkdirs()
        pkgDir.resolve("BuildInfo.kt").writeText(
            """
            |/*
            | * GENERATED FILE — do not edit by hand.
            | *
            | * Written by the `generateBuildInfo` task in core/build.gradle.kts from the
            | * Gradle `project.group` / `project.version` at build time, so the published
            | * Maven coordinate and these constants can NEVER silently drift. Override the
            | * version for a release with `-Prosetta.version=<x.y.z>` (the release tag does
            | * this); a unit test pins VERSION back to the Gradle line so a mismatch fails
            | * the gate.
            | *
            | * SCHEMA_VERSION is NOT generated: it mirrors the loader's
            | * CURRENT_SCHEMA_VERSION compile-time hard gate. See docs/reference/building.md.
            | */
            |package io.github.xiddoc.rosetta.core
            |
            |import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION
            |
            |/**
            | * Build coordinates of the published library artifact: its Maven [GROUP], its
            | * released [VERSION] (both injected from the Gradle build), and the map
            | * [SCHEMA_VERSION] that version tracks (mirrored from the loader's hard gate).
            | * These let consumers and tooling read the coordinates the artifact was
            | * published under without parsing build metadata.
            | */
            |public object BuildInfo {
            |    /** The Maven group all three published modules share (from Gradle `group`). */
            |    public const val GROUP: String = "$groupValue"
            |
            |    /**
            |     * The released library version (SemVer), injected from the Gradle `version`
            |     * — i.e. the release tag via `-Prosetta.version`. The MINOR line is
            |     * coordinated with [SCHEMA_VERSION]; see docs/reference/building.md.
            |     */
            |    public const val VERSION: String = "$versionValue"
            |
            |    /**
            |     * The map `schema_version` this library release consumes, mirrored from
            |     * [CURRENT_SCHEMA_VERSION] so the published coordinate and the loader's
            |     * hard gate cannot drift apart.
            |     */
            |    public const val SCHEMA_VERSION: Int = CURRENT_SCHEMA_VERSION
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    // Hand the Gradle release line to BuildInfoTest so it can assert the
    // generated BuildInfo.VERSION equals the version the build is publishing —
    // a tag↔constant mismatch then FAILS the gate (xposed#21 review MAJOR 2).
    systemProperty("rosetta.version", project.version.toString())
    systemProperty("rosetta.group", project.group.toString())
}

// Maven-publishing wiring (xposed#21). Shared convention: built-in
// maven-publish + signing, a `-sources` jar, and an (empty) `-javadoc` jar —
// no new plugins, so the offline default build and strict dependency
// verification stay intact. See gradle/publishing.gradle.kts.
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
