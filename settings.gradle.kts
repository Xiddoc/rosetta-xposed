/*
 * Root settings for rosetta-xposed — the Xposed / LSPosed / LSPatch
 * layer-4 binding for the Rosetta cross-framework architecture
 * (RFC 0001 in the rosetta-frida repo).
 *
 * Two modules:
 *   :core   — pure-JVM Kotlin. The framework-neutral layers (map model,
 *             loader/validator, resolver) — a faithful Kotlin twin of
 *             rosetta-frida's TypeScript core, kept honest by a shared
 *             conformance suite. Builds and tests on any JVM; no Android
 *             SDK, no Xposed API.
 *   :xposed  — the layer-4 binding skeleton. Turns a resolved real → obf
 *             name into a hookable `Member` and hands it to the
 *             developer's chosen hook API (libxposed or legacy
 *             XposedHelpers). It does NOT own the hook call.
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Lets Gradle auto-provision the JDK 17 toolchain the modules request, so a
// machine with only a newer JDK installed still builds without a manual setup.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rosetta-xposed"

include(":core")
include(":xposed")
