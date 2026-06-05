/*
 * Signer-guard enforcement tests (RFC 0001 Decision 4: version_code selects,
 * signer_sha256 guards). These exercise every fail-closed branch on a plain
 * JVM — no Android, no device — by driving SignerGuard / RosettaXposed with
 * synthetic AppIdentity values:
 *
 *   - match (normalized-equal)                → passes
 *   - mismatch                                → SignerMismatchException
 *   - map demands signer, identity has none   → MissingSignerException
 *   - map has no signer                       → no check (passes)
 *   - normalization (colons + case)           → equal hashes match
 *   - no identity at all (fromMap no-identity)→ no check (passes)
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.MissingSignerException
import io.github.xiddoc.rosetta.core.SignerMismatchException
import io.github.xiddoc.rosetta.core.version.MapRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignerGuardTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"

    private fun mapJson(signer: String?): String {
        val signerLine = if (signer == null) "" else """"signer_sha256": "$signer","""
        return """
            {
              "schema_version": 2,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              $signerLine
              "classes": {
                "com.example.RealClient": {
                  "obfuscated": "$obf",
                  "methods": {
                    "single": { "obfuscated": "c", "signature": "(Ljava/lang/String;)Ljava/lang/String;" }
                  }
                }
              }
            }
            """.trimIndent()
    }

    private fun map(signer: String?) = MapLoader.fromJson(mapJson(signer))

    private fun identity(signer: String?) =
        AppIdentity(
            packageName = "com.example.app",
            versionCode = 100,
            versionName = "1.0.0",
            signerSha256 = signer,
        )

    private val loader = javaClass.classLoader

    // ---- match.

    @Test
    fun `matching signer passes and binds`() {
        val bound = RosettaXposed.fromMap(map("abcd1234"), loader, identity("abcd1234"))
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `verifySigner is a no-op on a match`() {
        // verify() returns Unit and throws nothing on a match.
        RosettaXposed.verifySigner(map("abcd1234"), identity("abcd1234"))
    }

    // ---- mismatch.

    @Test
    fun `mismatched signer fails closed with expected vs actual`() {
        val ex =
            assertFailsWith<SignerMismatchException> {
                RosettaXposed.fromMap(map("aaaa"), loader, identity("bbbb"))
            }
        assertEquals("aaaa", ex.expected)
        assertEquals("bbbb", ex.actual)
        assertTrue(ex.message!!.contains("aaaa"))
        assertTrue(ex.message!!.contains("bbbb"))
    }

    @Test
    fun `mismatch through fromRegistry fails closed`() {
        val registry: MapRegistry = mapOf("1.0.0" to map("aaaa"))
        assertFailsWith<SignerMismatchException> {
            RosettaXposed.fromRegistry(registry, identity("ffff"), loader)
        }
    }

    // ---- map demands signer, identity supplies none.

    @Test
    fun `map demands signer but identity is null fails closed`() {
        val ex =
            assertFailsWith<MissingSignerException> {
                RosettaXposed.fromMap(map("abcd"), loader, identity(null))
            }
        assertEquals("abcd", ex.expected)
        assertTrue(ex.message!!.contains("supply AppIdentity.signerSha256"))
    }

    @Test
    fun `map demands signer but identity is null through fromRegistry fails closed`() {
        val registry: MapRegistry = mapOf("1.0.0" to map("abcd"))
        assertFailsWith<MissingSignerException> {
            RosettaXposed.fromRegistry(registry, identity(null), loader)
        }
    }

    // ---- map has no signer → opt-in guard skipped.

    @Test
    fun `map without signer skips the check even when identity has one`() {
        val bound = RosettaXposed.fromMap(map(null), loader, identity("abcd"))
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `map without signer skips the check when identity is null`() {
        RosettaXposed.verifySigner(map(null), identity(null))
    }

    // ---- no-identity fromMap performs no signer check (documented).

    @Test
    fun `no-identity fromMap does not enforce a signer guard`() {
        // Even a signer-bearing map binds via the no-identity overload — that
        // path deliberately performs no check.
        val bound = RosettaXposed.fromMap(map("abcd"), loader)
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `SignerGuard verify with null identity is a no-op`() {
        // The identity==null branch of SignerGuard.verify (used by the
        // no-identity path) skips silently for a signer-bearing map.
        SignerGuard.verify(map("abcd"), null)
    }

    // ---- normalization: colons + case differences still match.

    @Test
    fun `normalization strips colons and lowercases`() {
        // Map carries colon-grouped uppercase; identity carries lowercase
        // contiguous hex. They normalize to the same value → match.
        val bound =
            RosettaXposed.fromMap(
                map("AB:CD:EF:01"),
                loader,
                identity("abcdef01"),
            )
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `normalization strips whitespace`() {
        RosettaXposed.verifySigner(map("ab cd\tef"), identity("ABCDEF"))
    }

    @Test
    fun `normalized mismatch still throws and reports normalized forms`() {
        val ex =
            assertFailsWith<SignerMismatchException> {
                RosettaXposed.verifySigner(map("AA:BB"), identity("ccdd"))
            }
        assertEquals("aabb", ex.expected)
        assertEquals("ccdd", ex.actual)
    }

    // ---- exception field accessors (coverage of the typed taxonomy).

    @Test
    fun `MissingSignerException exposes the expected hash`() {
        val ex = MissingSignerException("nope", expected = "abcd")
        assertEquals("abcd", ex.expected)
        assertNotNull(ex.message)
    }
}
