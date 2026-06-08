/*
 * Pins the published-artifact coordinates ([BuildInfo]) so they cannot drift
 * from the schema the library speaks or from the Gradle `version` it is
 * published under.
 *
 * The Gradle root build script reads the version from system property
 * `rosetta.version` (defaulting to the same literal), so this test holds the
 * in-code constant and the build's release line in lockstep: bump one and this
 * test forces the other.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun `group is the shared Maven coordinate`() {
        assertEquals("io.github.xiddoc.rosetta", BuildInfo.GROUP)
    }

    @Test
    fun `version is the released 0_1_0 line`() {
        assertEquals("0.1.0", BuildInfo.VERSION)
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
        assertEquals(2, BuildInfo.SCHEMA_VERSION)
    }

    @Test
    fun `minor line is coordinated with the schema version`() {
        // Documented scheme: the MINOR line tracks the map schema_version it
        // consumes (0.1.x ⇄ schema 2). Guard that invariant mechanically so a
        // schema bump that forgets to move the version is caught here.
        val minor = BuildInfo.VERSION.split(".")[1].toInt()
        assertEquals(BuildInfo.SCHEMA_VERSION - 1, minor)
    }
}
