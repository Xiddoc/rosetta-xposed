/*
 * Signer-guard enforcement tests (RFC 0001 Decision 3: version_code selects,
 * signer_sha256 guards). These exercise every fail-closed branch on a plain
 * JVM — no Android, no device — by driving SignerGuard / RosettaXposed with
 * synthetic AppIdentity values:
 *
 *   - match (normalized-equal, single signer)  → passes
 *   - multi-signer match-any (non-first cert)   → passes
 *   - mismatch                                  → SignerMismatchException
 *   - map demands signer, app set empty         → MissingSignerException
 *   - map has no signer                         → no check (passes)
 *   - normalization (colons + case + trim)      → equal hashes match
 *   - malformed map hash                        → MalformedSignerException
 *   - unverified path performs no signer check
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MalformedSignerException
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

    // Full 64-char hex SHA-256 fixtures.
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val hashC = "c".repeat(64)

    private fun mapJson(signer: String?): String {
        val signerLine = if (signer == null) "" else """"signer_sha256": "$signer","""
        return """
            {
              "schema_version": 4,
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

    /** A map whose `signer_sha256` is a JSON ARRAY of hashes (schema 3 match-any). */
    private fun mapJsonMultiSigner(signers: List<String>): String {
        val arr = signers.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return """
            {
              "schema_version": 4,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "signer_sha256": $arr,
              "classes": {
                "com.example.RealClient": { "obfuscated": "$obf" }
              }
            }
            """.trimIndent()
    }

    private fun multiMap(signers: List<String>) = MapLoader.fromJson(mapJsonMultiSigner(signers))

    private fun identity(vararg signers: String) =
        AppIdentity(
            packageName = "com.example.app",
            versionCode = 100,
            versionName = "1.0.0",
            signerSha256s = signers.toSet(),
        )

    private val loader = javaClass.classLoader

    // ---- match.

    @Test
    fun `matching signer passes and binds`() {
        val bound = RosettaXposed.fromMap(map(hashA), loader, identity(hashA))
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `verify is a no-op on a match`() {
        // verify() returns Unit and throws nothing on a match.
        SignerGuard.verify(map(hashA), identity(hashA))
    }

    // ---- multi-signer match-any.

    @Test
    fun `multi-signer set matches a non-first certificate`() {
        // Map pins hashC; the app is signed by {hashA, hashB, hashC}.
        val bound = RosettaXposed.fromMap(map(hashC), loader, identity(hashA, hashB, hashC))
        assertTrue(bound.knows("com.example.RealClient"))
    }

    // ---- schema 3: map signer as an ARRAY of hashes (match-any).

    @Test
    fun `a map signer array decodes to multiple hashes and matches any`() {
        val m = multiMap(listOf(hashA, hashB))
        assertEquals(listOf(hashA, hashB), m.signerSha256s)
        // App presents only hashB; the map's array still matches (match-any).
        val bound = RosettaXposed.fromMap(m, loader, identity(hashB))
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `a single-string map signer decodes to a one-element list`() {
        assertEquals(listOf(hashA), map(hashA).signerSha256s)
    }

    @Test
    fun `a map signer array with no app match fails closed`() {
        val ex =
            assertFailsWith<SignerMismatchException> {
                RosettaXposed.fromMap(multiMap(listOf(hashA, hashB)), loader, identity(hashC))
            }
        // The bracketed-set rendering names both demanded hashes.
        assertTrue(ex.expected.contains(hashA))
        assertTrue(ex.expected.contains(hashB))
    }

    @Test
    fun `a malformed entry in a map signer array is rejected`() {
        // Every map hash must be canonical bare 64-hex; one bad entry fails.
        assertFailsWith<MalformedSignerException> {
            SignerGuard.verify(multiMap(listOf(hashA, "nothex")), identity(hashA))
        }
    }

    @Test
    fun `an empty signer_sha256 list is treated as no guard`() {
        // A model-constructed map with an EMPTY signer list declares no guard:
        // verify passes (takeIf { isNotEmpty } short-circuits to no-op).
        val m =
            io.github.xiddoc.rosetta.core.model.RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                signerSha256s = emptyList(),
                classes =
                    mapOf(
                        "com.example.RealClient" to
                            io.github.xiddoc.rosetta.core.model
                                .ClassEntry("obf"),
                    ),
            )
        SignerGuard.verify(m, identity())
    }

    @Test
    fun `a multi-hash map demand with an empty app set renders the bracketed set`() {
        val ex =
            assertFailsWith<MissingSignerException> {
                SignerGuard.verify(multiMap(listOf(hashA, hashB)), identity())
            }
        assertTrue(ex.expected.startsWith("["))
        assertTrue(ex.expected.contains(hashA))
        assertTrue(ex.expected.contains(hashB))
    }

    // ---- mismatch.

    @Test
    fun `mismatched signer fails closed with expected vs actual`() {
        val ex =
            assertFailsWith<SignerMismatchException> {
                RosettaXposed.fromMap(map(hashA), loader, identity(hashB))
            }
        assertEquals(hashA, ex.expected)
        assertTrue(ex.actual.contains(hashB))
        assertTrue(ex.message!!.contains(hashA))
        assertTrue(ex.message!!.contains(hashB))
    }

    @Test
    fun `multi-signer set with no member matching fails closed`() {
        val ex =
            assertFailsWith<SignerMismatchException> {
                RosettaXposed.fromMap(map(hashC), loader, identity(hashA, hashB))
            }
        assertEquals(hashC, ex.expected)
        assertTrue(ex.actual.contains(hashA))
        assertTrue(ex.actual.contains(hashB))
    }

    @Test
    fun `mismatch through fromRegistry fails closed`() {
        val registry: MapRegistry = MapRegistry.of(map(hashA))
        assertFailsWith<SignerMismatchException> {
            RosettaXposed.fromRegistry(registry, identity(hashB), loader)
        }
    }

    // ---- map demands signer, app set is empty.

    @Test
    fun `map demands signer but app set is empty fails closed`() {
        val ex =
            assertFailsWith<MissingSignerException> {
                RosettaXposed.fromMap(map(hashA), loader, identity())
            }
        assertEquals(hashA, ex.expected)
        assertTrue(ex.message!!.contains("populate AppIdentity.signerSha256s"))
    }

    @Test
    fun `map demands signer but app set is empty through fromRegistry fails closed`() {
        val registry: MapRegistry = MapRegistry.of(map(hashA))
        assertFailsWith<MissingSignerException> {
            RosettaXposed.fromRegistry(registry, identity(), loader)
        }
    }

    @Test
    fun `app set with only malformed entries is treated as empty`() {
        // Malformed app-set hashes are skipped; if nothing well-formed
        // remains, the map's demand can't be satisfied → MissingSigner.
        assertFailsWith<MissingSignerException> {
            RosettaXposed.fromMap(map(hashA), loader, identity("not-hex", "abcd"))
        }
    }

    // ---- map has no signer → opt-in guard skipped.

    @Test
    fun `map without signer skips the check even when app set has hashes`() {
        val bound = RosettaXposed.fromMap(map(null), loader, identity(hashA))
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `map without signer skips the check when app set is empty`() {
        SignerGuard.verify(map(null), identity())
    }

    // ---- unverified path performs no signer check.

    @Test
    fun `unverified fromMap does not enforce a signer guard`() {
        // Even a signer-bearing map binds via the unverified overload — that
        // path deliberately performs no check.
        val bound = RosettaXposed.fromMapUnverified(map(hashA), loader)
        assertTrue(bound.knows("com.example.RealClient"))
    }

    // ---- malformed map hash → MalformedSignerException.

    @Test
    fun `too-short map hash is malformed`() {
        val ex =
            assertFailsWith<MalformedSignerException> {
                SignerGuard.verify(map("deadbeef"), identity(hashA))
            }
        assertEquals("deadbeef", ex.value)
        assertTrue(ex.reason.contains("64"))
    }

    @Test
    fun `non-hex map hash is malformed`() {
        assertFailsWith<MalformedSignerException> {
            // 64 chars but contains non-hex letters.
            SignerGuard.verify(map("z".repeat(64)), identity(hashA))
        }
    }

    @Test
    fun `empty map hash is malformed`() {
        assertFailsWith<MalformedSignerException> {
            SignerGuard.verify(map(""), identity(hashA))
        }
    }

    @Test
    fun `interior-whitespace map hash is malformed`() {
        // The map value is the CANONICAL bare 64-hex form: interior whitespace
        // makes it not match the regex, so it is rejected as malformed.
        val withInterior = "a".repeat(32) + " " + "a".repeat(31)
        assertFailsWith<MalformedSignerException> {
            SignerGuard.verify(map(withInterior), identity(hashA))
        }
    }

    // ---- maps#32 drift fix: a NON-CANONICAL map value is malformed (the map
    // value is checked WITHOUT normalization, so a guard-accepted map value is
    // always schema-valid). Normalization applies ONLY to the app-presented set.

    @Test
    fun `uppercase map hash is malformed (canonical is bare lowercase)`() {
        // The OLD behaviour normalized the map value; the v3 fix rejects an
        // uppercase map value as malformed so it can never be schema-invalid.
        assertFailsWith<MalformedSignerException> {
            SignerGuard.verify(map(hashA.uppercase()), identity(hashA))
        }
    }

    @Test
    fun `colon-separated map hash is malformed (canonical strips no separators)`() {
        val colon = "ab".let { p -> List(32) { p }.joinToString(":") }
        assertFailsWith<MalformedSignerException> {
            SignerGuard.verify(map(colon), identity("ab".repeat(32)))
        }
    }

    @Test
    fun `app-set hash is normalized (colon + uppercase) and still matches a canonical map hash`() {
        // The map value is canonical bare lowercase; the APP presents the same
        // cert as colon-grouped uppercase. The app set IS normalized, so it
        // matches.
        val canonical = "ab".repeat(32)
        val appColonUpper = "AB".let { p -> List(32) { p }.joinToString(":") }
        val bound = RosettaXposed.fromMap(map(canonical), loader, identity(appColonUpper))
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `app-set whitespace is trimmed before the match`() {
        SignerGuard.verify(map(hashA), identity("  " + hashA.uppercase() + "\t"))
    }

    // ---- exception field accessors (coverage of the typed taxonomy).

    @Test
    fun `MissingSignerException exposes the expected hash`() {
        val ex = MissingSignerException("nope", expected = hashA)
        assertEquals(hashA, ex.expected)
        assertNotNull(ex.message)
    }

    @Test
    fun `MalformedSignerException exposes value and reason`() {
        val ex = MalformedSignerException(value = "bad", reason = "too short")
        assertEquals("bad", ex.value)
        assertEquals("too short", ex.reason)
        assertTrue(ex.message!!.contains("bad"))
        assertTrue(ex.message!!.contains("too short"))
    }
}
