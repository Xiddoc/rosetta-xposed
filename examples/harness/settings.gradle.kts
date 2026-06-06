/*
 * Standalone settings for the pure-JVM Rosetta walkthrough harness.
 *
 * This is its OWN Gradle build — deliberately NOT wired into the repo-root
 * settings.gradle.kts — so the `./gradlew build` invariant for :core / :xposed
 * stays Android-free and green (CLAUDE.md "keep `:core` Android-free"). The
 * harness consumes the published-shaped `io.github.xiddoc.rosetta:xposed`
 * coordinate straight from the parent build via a composite `includeBuild`,
 * which is exactly the pre-Maven distribution story a real external consumer
 * would use today (git checkout + composite build) before a Maven phase exists.
 *
 * Run it from the repo root with the parent wrapper:
 *
 *     ./gradlew -p examples/harness run     # prints the resolve walkthrough
 *     ./gradlew -p examples/harness test    # asserts the end-to-end flow
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rosetta-xposed-example-harness"

// Consume :core / :xposed from the parent build. Gradle substitutes the
// `io.github.xiddoc.rosetta:xposed` module dependency below with the included
// build's `:xposed` project automatically (group = io.github.xiddoc.rosetta).
includeBuild("../..")
