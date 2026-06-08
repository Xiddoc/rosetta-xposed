/*
 * Root build script for rosetta-xposed.
 *
 * The Kotlin JVM + kotlinx-serialization plugins are declared here with
 * `apply false`; each module applies what it needs. Versions are pinned
 * centrally so the four modules never drift.
 *
 * Quality tooling — the Kotlin equivalents of the rosetta-frida Node stack
 * (Prettier + ESLint + 100%-coverage + husky):
 *   - Spotless (ktlint) → formatting gate (`spotlessCheck` / `spotlessApply`)
 *   - detekt            → static analysis (`detekt`)
 *   - Kover             → coverage, aggregated across the modules, gated at
 *                         100% line + branch (`koverVerify`)
 * All modules and every `*.gradle.kts` script are formatted uniformly so
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
    // Static analysis (detekt). Declared at the root and applied per subproject
    // so each module gets its own `detekt` task over its own sources.
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    // Coverage (Kover). Applied at the root, which aggregates both modules'
    // coverage into one report + verification gate (see below).
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
    // Android API-signature gate (animal-sniffer, xposed#16). Declared at the
    // root with `apply false`; the pure-JVM library modules (:core, :xposed)
    // apply it themselves and check their `main` byte-code against the
    // android-api-level-24 signature. This catches JVM-only APIs (e.g.
    // `ClassLoader.getPlatformClassLoader()`) that compile on JDK 17 but throw
    // `NoSuchMethodError` on Android ART, where this binding runs on-device.
    id("ru.vyarus.animalsniffer") version "1.7.1" apply false
}

// The released version. This `version` is the single source of truth: the
// `:core` build GENERATES `io.github.xiddoc.rosetta.core.BuildInfo.VERSION` from
// it at build time (and a unit test pins the constant back to it), so the
// in-code coordinate can never drift from what is published. The release
// workflow overrides it from the pushed git tag via
// `-Prosetta.version=<tag-without-v>` so a `v0.1.0` tag publishes `0.1.0`; an
// ordinary local build uses the default below. Version scheme: SemVer with the
// MINOR line coordinated to the map `schema_version` (0.1.x ⇄ schema 2) — see
// docs/reference/building.md.
val rosettaVersion: String = (findProperty("rosetta.version") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0"

allprojects {
    group = "io.github.xiddoc.rosetta"
    version = rosettaVersion
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

// Format every module's Kotlin sources and its `*.gradle.kts` build script,
// and run detekt static analysis over each module's sources.
subprojects {
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    // Kover must be applied in each module so it instruments that module's
    // own test task; the root aggregates the per-module measurements below.
    apply(plugin = "org.jetbrains.kotlinx.kover")

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

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        // One shared config at the repo root keeps the rule set identical
        // across all modules. `buildUponDefaultConfig` layers our overrides
        // on top of detekt's sensible defaults rather than replacing them.
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Formatting is Spotless/ktlint's job; detekt stays purely structural.
        ignoreFailures = false
    }
}

// Aggregate all modules' coverage into the root project. Kover merges the
// `:core`, `:xposed`, and `:xposed-android` measurements so a single
// `./gradlew koverVerify` gates the whole codebase (rosetta-frida's "100% or
// the build fails" rule).
dependencies {
    kover(project(":core"))
    kover(project(":xposed"))
    // :xposed-android is pure JVM (no Android plugin), so its helper LOGIC is
    // fully unit-testable and joins the 100% line + branch gate. :dexkit stays
    // OUT (its integration test legitimately skips without the native lib).
    kover(project(":xposed-android"))
}

kover {
    reports {
        // No coverage excludes: the schema-mirror DTOs (and their generated
        // equals/hashCode/copy/toString + serializer write$Self optional-field
        // branches) are covered legitimately by value-semantics and
        // serialization-branch tests (see core's DataClassSemanticsTest). The
        // gate measures real behaviour, not a filtered subset.

        // Verification rule: the build fails below 100% on BOTH line and
        // branch coverage, aggregated across all modules. This is the hard
        // gate `koverVerify` enforces.
        verify {
            rule("100% line + branch coverage (aggregated)") {
                minBound(100, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(100, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Git hooks — the Kotlin-native analogue of rosetta-frida's husky install.
//
// The tracked hook lives at gradle/hooks/pre-commit; `installGitHooks` copies
// it into .git/hooks and makes it executable.
//
// SECURITY (audit H5): this is now OPT-IN. The task is no longer wired into
// the default `build` lifecycle, because writing into `.git/hooks` as a side
// effect of an ordinary build is a build-time filesystem write outside the
// project tree (a supply-chain footgun — a compromised dependency that runs
// at configuration/build time could ride that write to plant a hook). Install
// hooks explicitly instead:
//
//     ./gradlew installGitHooks
//
// One-time per clone; no need to repeat it on every build. The worktree-safe
// `isDirectory` guard below is preserved.
// ---------------------------------------------------------------------------
val installGitHooks by tasks.registering(Copy::class) {
    description = "Installs the tracked git pre-commit hook into .git/hooks (run explicitly; not part of `build`)."
    group = "git hooks"

    val gitDir = rootProject.layout.projectDirectory.dir(".git")
    // Skip silently unless `.git` is a real directory. In a `git worktree`,
    // `.git` is a POINTER FILE (not a directory) and has no `hooks/` subdir to
    // copy into, so an `.exists()` guard would wrongly pass and then fail the
    // copy. `isDirectory` also covers the source-tarball / CI-without-metadata
    // case (no `.git` at all), so the build never fails for lack of hooks.
    onlyIf { gitDir.asFile.isDirectory }

    from(rootProject.layout.projectDirectory.file("gradle/hooks/pre-commit"))
    into(gitDir.dir("hooks"))
    // Mark the installed hook executable (rwxr-xr-x).
    fileMode = "755".toInt(radix = 8)
}
