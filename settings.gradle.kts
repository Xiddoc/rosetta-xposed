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

// :xposed-android — an OPTIONAL, pure-JVM module of reusable Android-helper
// LOGIC (bundled-map loading + signer-hash/AppIdentity assembly from
// PackageManager primitives). It applies NO Android plugin and never compiles
// against android.jar, so it stays in the plain-JVM build AND in the root 100%
// coverage gate; the irreducible PackageManager read stays in the consumer.
include(":xposed-android")

// :dexkit — the REAL on-device DexKit adapter that implements the `DexKitIndex`
// seam (RFC 0001 Decision 5 — DexKit is an OPTIONAL later-phase dependency,
// kept out of the default :xposed build). It is the SOLE place
// `org.luckypray.dexkit` is imported, plus a hermetic integration test that
// runs REAL DexKit against a committed obfuscated classes.dex fixture.
include(":dexkit")
