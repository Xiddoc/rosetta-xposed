/*
 * Root build script for rosetta-xposed.
 *
 * The Kotlin JVM + kotlinx-serialization plugins are declared here with
 * `apply false`; each module applies what it needs. Versions are pinned
 * centrally so the two modules never drift.
 *
 * Quality tooling — the Kotlin equivalents of the rosetta-frida Node stack
 * (Prettier + ESLint + 100%-coverage + husky):
 *   - Spotless (ktlint) → formatting gate (`spotlessCheck` / `spotlessApply`)
 * Both modules and every `*.gradle.kts` script are formatted uniformly so
 * the gate never depends on which directory a file lives in.
 *
 * Kotlin 2.0.x is paired with Gradle 8.7 (see
 * gradle/wrapper/gradle-wrapper.properties).
 */
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    // Formatting (ktlint via Spotless). Applied at the root so it can format
    // the root build scripts, and re-applied per subproject below.
    id("com.diffplug.spotless") version "6.25.0"
}

allprojects {
    group = "io.github.xiddoc.rosetta"
    version = "0.0.0-dev"
}

// The ktlint engine version Spotless drives. Pinned so formatting is
// reproducible across developer machines and CI runners.
val ktlintVersion = "1.3.1"

// Format the root build scripts (settings.gradle.kts + build.gradle.kts),
// which belong to no subproject.
spotless {
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Format every module's Kotlin sources and its `*.gradle.kts` build script.
subprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion)
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}
