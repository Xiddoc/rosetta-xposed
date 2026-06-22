/*
 * Pins the published-artifact coordinates ([BuildInfo]) so they cannot drift
 * from the schema the library speaks or from the Gradle `version`/`group` it is
 * published under.
 *
 * [BuildInfo] is GENERATED from the Gradle `project.group`/`project.version` at
 * build time (see core/build.gradle.kts `generateBuildInfo`). The test task
 * hands those same values down as the `rosetta.version` / `rosetta.group`
 * system properties, so this test holds the generated constants and the build's
 * actual release line in lockstep: a release that bumps the Gradle version (the
 * `v*` tag via `-Prosetta.version`) but leaves anything stale FAILS here — the
 * "cannot silently drift" invariant is real.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun `group is the Gradle group the artifact is published under`() {
        // Injected by the test task from `project.group`; defaults to the known
        // coordinate when the property is absent (running outside Gradle).
        val gradleGroup = System.getProperty("rosetta.group", "io.github.xiddoc.rosetta")
        assertEquals(gradleGroup, BuildInfo.GROUP)
    }

    @Test
    fun `version tracks the Gradle release line`() {
        // The genuine anti-drift invariant: the generated constant equals the
        // version the build is publishing. The release tag (`-Prosetta.version`)
        // therefore flows straight into BuildInfo.VERSION, and a stale constant
        // can no longer pass the gate.
        val gradleVersion = System.getProperty("rosetta.version", "0.3.0")
        assertEquals(gradleVersion, BuildInfo.VERSION)
    }

    @Test
    fun `version is valid SemVer (major_minor_patch)`() {
        // A strict major.minor.patch core, optionally with a pre-release /
        // build-metadata suffix — the shape Maven Central and the publishing
        // wiring expect.
        val semver = Regex("""^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$""")
        assertTrue(semver.matches(BuildInfo.VERSION), "not SemVer: ${BuildInfo.VERSION}")
    }

    @Test
    fun `schema version mirrors the loader's hard gate`() {
        // The coordinate's schema line is bound to the loader constant so the
        // published artifact and the gate it enforces can never disagree.
        assertEquals(CURRENT_SCHEMA_VERSION, BuildInfo.SCHEMA_VERSION)
        assertEquals(5, BuildInfo.SCHEMA_VERSION)
    }

    @Test
    fun `minor floor moves with the schema version`() {
        // Documented scheme: a breaking schema bump MUST move the MINOR line
        // forward (0.4.x ⇄ schema 5). A library-only breaking change may ALSO
        // bump MINOR while schema_version holds, so the genuine invariant is a
        // floor (>=), not strict equality — encoding `minor == schema - 1`
        // would fail spuriously on a library-only minor bump.
        val minor = BuildInfo.VERSION.split(".")[1].toInt()
        assertTrue(
            minor >= BuildInfo.SCHEMA_VERSION - 1,
            "minor $minor is below the schema floor ${BuildInfo.SCHEMA_VERSION - 1}",
        )
    }
}
