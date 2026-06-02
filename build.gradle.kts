/*
 * Root build script for rosetta-xposed.
 *
 * The Kotlin JVM + kotlinx-serialization plugins are declared here with
 * `apply false`; each module applies what it needs. Versions are pinned
 * centrally so the two modules never drift.
 *
 * Kotlin 2.0.x is paired with Gradle 8.7 (see
 * gradle/wrapper/gradle-wrapper.properties).
 */
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    group = "io.github.xiddoc.rosetta"
    version = "0.0.0-dev"
}
