/*
 * Asserts the signer authenticity guard end-to-end on a plain JVM.
 *
 * Every branch of SignerGuard is exercised via RosettaXposed.fromMap:
 *
 *   - MATCH        — correct signer in the identity set → binding succeeds,
 *                    real name resolves to the obfuscated member.
 *   - MISMATCH     — identity present but wrong hash → SignerMismatchException.
 *   - MISSING      — map demands a signer; identity has no hashes →
 *                    MissingSignerException.
 *   - MALFORMED    — map's signer_sha256 is not 64 hex chars → MalformedSignerException.
 *   - NORMALIZATION — hash supplied in uppercase + colon form in the map still
 *                    matches the plain lowercase form in the identity set.
 */
package com.example.victimhook

import io.github.xiddoc.rosetta.core.MalformedSignerException
import io.github.xiddoc.rosetta.core.MissingSignerException
import io.github.xiddoc.rosetta.core.SignerMismatchException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SignerWalkthroughTest {

    private val result: SignerWalkthroughResult by lazy { SignerWalkthrough.run() }

    // ---- cert hash shape -------------------------------------------------- //

    @Test
    fun `computed cert hash is exactly 64 lowercase hex chars`() {
        val hash = result.computedHash
        assertEquals(64, hash.length, "SHA-256 hex must be exactly 64 chars")
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' }, "hash must be lowercase hex: $hash")
    }

    @Test
    fun `cert hash matches expected value for deterministic input bytes`() {
        // Pinning the computed value guards against accidental algorithm changes.
        assertEquals(
            "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd",
            result.computedHash,
        )
    }

    // ---- MATCH ------------------------------------------------------------ //

    @Test
    fun `MATCH - binding succeeds and resolves real name to obfuscated member`() {
        // The obfuscated method behind formatTicket is `c` (as in maps/100.json).
        assertEquals("c", result.matchedMemberName)
    }

    @Test
    fun `MATCH - invoking the resolved member returns the expected result`() {
        assertEquals("ticket:S-001", result.matchedInvocation)
    }

    // ---- MISMATCH --------------------------------------------------------- //

    @Test
    fun `MISMATCH - throws SignerMismatchException when identity hash does not match map`() {
        assertIs<SignerMismatchException>(result.mismatch)
    }

    @Test
    fun `MISMATCH - exception carries the expected hash from the map`() {
        assertEquals(result.computedHash, result.mismatch.expected)
    }

    @Test
    fun `MISMATCH - exception actual field contains the wrong hash that was presented`() {
        // The wrong hash is 64 a's; it should appear in the actual field.
        assertTrue(
            result.mismatch.actual.contains("a".repeat(64)),
            "actual field should contain the wrong hash: ${result.mismatch.actual}",
        )
    }

    // ---- MISSING ---------------------------------------------------------- //

    @Test
    fun `MISSING - throws MissingSignerException when identity signer set is empty`() {
        assertIs<MissingSignerException>(result.missing)
    }

    @Test
    fun `MISSING - exception carries the expected hash the map declared`() {
        assertEquals(result.computedHash, result.missing.expected)
    }

    // ---- MALFORMED -------------------------------------------------------- //

    @Test
    fun `MALFORMED - throws MalformedSignerException when map signer_sha256 is not 64 hex chars`() {
        assertIs<MalformedSignerException>(result.malformed)
    }

    @Test
    fun `MALFORMED - exception carries the offending value`() {
        assertEquals("not-a-valid-hash", result.malformed.value)
    }

    // ---- NORMALIZATION ---------------------------------------------------- //

    @Test
    fun `NORMALIZATION - uppercase colon-separated hash in map matches lowercase form in identity`() {
        assertTrue(
            result.normalizationMatched,
            "SignerGuard must normalize colons and case before comparing hashes",
        )
    }
}
