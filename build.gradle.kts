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
 *   - detekt            → static analysis (`detekt`)
 *   - Kover             → coverage, aggregated across both modules, gated at
 *                         100% line + branch (`koverVerify`)
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
    // Static analysis (detekt). Declared at the root and applied per subproject
    // so each module gets its own `detekt` task over its own sources.
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    // Coverage (Kover). Applied at the root, which aggregates both modules'
    // coverage into one report + verification gate (see below).
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
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
        // across both modules. `buildUponDefaultConfig` layers our overrides
        // on top of detekt's sensible defaults rather than replacing them.
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Formatting is Spotless/ktlint's job; detekt stays purely structural.
        ignoreFailures = false
    }
}

// Aggregate both modules' coverage into the root project. Kover merges the
// `:core` and `:xposed` measurements so a single `./gradlew koverVerify`
// gates the whole codebase (rosetta-frida's "100% or the build fails" rule).
dependencies {
    kover(project(":core"))
    kover(project(":xposed"))
}

kover {
    reports {
        filters {
            excludes {
                // The schema-mirror DTOs are pure data holders: their only
                // bytecode is compiler/kotlinx-serialization-generated
                // `equals`/`hashCode`/`copy`/`componentN`/`toString` and the
                // serializer's `write$Self` "skip this optional field at its
                // default?" branches. Exhaustively covering 2^n optional-field
                // combinations on generated code is busywork that measures the
                // compiler, not our logic — so they are excluded here, exactly
                // as a TypeScript project would not count generated Zod glue.
                //
                // Note: only DTOs with NO hand-written branches are listed.
                // MethodOverloads (its non-empty `require`), its custom
                // MethodOverloadsSerializer, MapLoader, the Resolver, the
                // signature helpers and all of :xposed stay fully in scope.
                classes(
                    "io.github.xiddoc.rosetta.core.model.RosettaMap",
                    "io.github.xiddoc.rosetta.core.model.ClassEntry",
                    "io.github.xiddoc.rosetta.core.model.FieldEntry",
                    "io.github.xiddoc.rosetta.core.model.MethodEntry",
                    "io.github.xiddoc.rosetta.core.model.MapSource",
                    "io.github.xiddoc.rosetta.core.model.Confidence",
                    "io.github.xiddoc.rosetta.core.model.ClassKind",
                )
                // The generated `serializer()` accessors on every DTO's
                // synthetic `$Companion` are likewise compiler glue. (The
                // hand-written MethodOverloads class itself stays in scope;
                // only its generated companion accessor is excluded.)
                classes("io.github.xiddoc.rosetta.core.model.*\$Companion")
            }
        }

        // Verification rule: the build fails below 100% on BOTH line and
        // branch coverage, aggregated across both modules. This is the hard
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
// it into .git/hooks and makes it executable. It is wired so a normal build
// installs it (the equivalent of npm's `prepare` lifecycle running husky), so
// contributors get the formatting gate without a manual setup step.
// ---------------------------------------------------------------------------
val installGitHooks by tasks.registering(Copy::class) {
    description = "Installs the tracked git pre-commit hook into .git/hooks."
    group = "git hooks"

    val gitDir = rootProject.layout.projectDirectory.dir(".git")
    // Skip silently when there is no .git dir (e.g. a source tarball or a CI
    // checkout that builds without repo metadata) so the build never fails for
    // lack of a hooks directory.
    onlyIf { gitDir.asFile.exists() }

    from(rootProject.layout.projectDirectory.file("gradle/hooks/pre-commit"))
    into(gitDir.dir("hooks"))
    // Mark the installed hook executable (rwxr-xr-x).
    fileMode = "755".toInt(radix = 8)
}

// Run the install as part of a build, like husky's `prepare` script.
tasks.named("build") {
    dependsOn(installGitHooks)
}
