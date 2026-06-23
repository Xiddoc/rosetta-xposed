/*
 * :gradle-plugin — the build-time Rosetta map fetcher (xposed#39).
 *
 * A standalone Gradle plugin (`io.github.xiddoc.rosetta.maps`) that, at BUILD
 * time on the developer's / CI machine, pulls the published `schema_version: 5`
 * maps for an app from the rosetta-maps repo into a GENERATED resources dir
 * wired onto the consuming module's class path. The on-device runtime path is
 * byte-identical to today (plain bundled Java resources read by `BundledMaps`);
 * NOTHING here ever runs on a device, and no runtime download is added.
 *
 * Why it is NOT in the root 100% Kover gate (like :dexkit): the plugin glues
 * pure, fully unit-tested fetch/cache/filter/manifest LOGIC (tested with a fake
 * source + a hand-built tarball, no network) to Gradle's wiring API. The Gradle
 * task-graph wiring is exercised with `ProjectBuilder`, not measured for line
 * coverage, so it stays OUT of the aggregated gate while its logic is covered.
 *
 * It depends on :core ONLY for the schema-version gate (`CURRENT_SCHEMA_VERSION`
 * + `MapLoader`): a fetched map whose `schema_version` differs from the one this
 * client's loader would accept is filtered out before bundling, so a newer
 * upstream map can never get baked into a module that would reject it at runtime.
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-gradle-plugin`
}

dependencies {
    // The schema-version gate + map validation reuse the real client loader, so
    // the fetcher filters/validates against EXACTLY what the runtime accepts.
    implementation(project(":core"))

    // Lenient header parsing (schema_version / version_code) and the generated
    // `index.json` manifest. Same pinned coordinate :core already verifies.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

gradlePlugin {
    plugins {
        create("rosettaMaps") {
            id = "io.github.xiddoc.rosetta.maps"
            implementationClass = "io.github.xiddoc.rosetta.gradle.RosettaMapsPlugin"
            displayName = "Rosetta Maps build-time fetcher"
            description =
                "Fetches published Rosetta maps from rosetta-maps at build time into a " +
                "generated resources dir, so a module never hand-copies map JSON."
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
