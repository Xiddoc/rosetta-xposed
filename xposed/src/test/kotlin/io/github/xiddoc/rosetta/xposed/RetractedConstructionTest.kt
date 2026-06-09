/*
 * Construction-chokepoint retracted-refusal tests (maps#40).
 *
 * The authoritative, UNIFORM retraction gate lives at construction: every
 * `RosettaXposed` factory refuses a `status: retracted` map fail-closed,
 * orthogonally to the signer guard. These tests build a retracted map DIRECTLY
 * via the [RosettaMap] constructor (bypassing `MapLoader.fromJson`, which also
 * refuses) so each factory path is proven to gate on its own — including
 * `fromMapUnverified`, which only skips the SIGNER check, mirroring frida.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.RetractedMapException
import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.MapStatus
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.version.MapRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetractedConstructionTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"

    /** A well-formed, RETRACTED map built directly (fromJson would refuse first). */
    private val retracted =
        RosettaMap(
            schemaVersion = 3,
            app = "com.example.app",
            version = "1.0.0",
            versionCode = 100,
            status = MapStatus.RETRACTED,
            classes = mapOf("com.example.RealClient" to ClassEntry(obfuscated = obf)),
        )

    private val policy = TargetPolicy(allow = listOf(obf))
    private val identity = AppIdentity(packageName = "com.example.app", versionCode = 100, versionName = "1.0.0")

    @Test
    fun `fromMap refuses a retracted map`() {
        val ex =
            assertFailsWith<RetractedMapException> {
                RosettaXposed.fromMap(retracted, javaClass.classLoader, identity, policy)
            }
        assertTrue(ex.message.contains("RETRACTED"))
    }

    @Test
    fun `fromMapUnverified refuses a retracted map even with the signer check skipped`() {
        // fromMapUnverified skips only the SIGNER check; retraction is an
        // orthogonal fail-closed gate that still fires (mirroring frida).
        assertFailsWith<RetractedMapException> {
            RosettaXposed.fromMapUnverified(retracted, javaClass.classLoader, policy)
        }
    }

    @Test
    fun `fromRegistry refuses a retracted map selected by version_code`() {
        val registry = MapRegistry.of(retracted)
        assertFailsWith<RetractedMapException> {
            RosettaXposed.fromRegistry(registry, identity, javaClass.classLoader, policy)
        }
    }

    @Test
    fun `fromMapWithDiscovery refuses a retracted map (with identity)`() {
        assertFailsWith<RetractedMapException> {
            RosettaXposed.fromMapWithDiscovery(
                retracted,
                FakeDexKitIndex(),
                javaClass.classLoader,
                identity,
                policy = policy,
            )
        }
    }

    @Test
    fun `fromMapWithDiscovery refuses a retracted map (no identity, unverified)`() {
        // Even on the identity-less, allowUnverified path the retraction gate
        // fires before discovery is ever wired.
        assertFailsWith<RetractedMapException> {
            RosettaXposed.fromMapWithDiscovery(
                retracted,
                FakeDexKitIndex(),
                javaClass.classLoader,
                identity = null,
                policy = policy,
                allowUnverified = true,
            )
        }
    }

    @Test
    fun `an active map built directly still binds`() {
        // The gate must not over-refuse: an ACTIVE map (the default) constructs
        // normally through the same chokepoint.
        val active =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes = mapOf("com.example.RealClient" to ClassEntry(obfuscated = obf)),
            )
        val bound = RosettaXposed.fromMapUnverified(active, javaClass.classLoader, policy)
        assertEquals(obf, bound.useClass("com.example.RealClient").load().name)
    }
}
