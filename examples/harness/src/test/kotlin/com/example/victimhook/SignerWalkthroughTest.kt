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
        // The wrong hash is 64 a's; it is the only hash in the set so it is
        // rendered as the exact form [aaa...aaa] (sorted, bracketed).
        assertEquals("[" + "a".repeat(64) + "]", result.mismatch.actual)
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

    // ---- MULTI-SIGNER ----------------------------------------------------- //

    @Test
    fun `MULTI-SIGNER - identity set with extra hashes still matches when the right hash is present`() {
        assertTrue(
            result.multiSignerMatched,
            "fromMap must succeed when the correct hash is present alongside other hashes",
        )
    }

    // ---- REGISTRY --------------------------------------------------------- //

    @Test
    fun `REGISTRY - fromRegistry path binds and resolves the real name`() {
        assertTrue(
            result.registryMatched,
            "fromRegistry must bind successfully and resolve to the same obfuscated member as fromMap",
        )
    }

    // ---- sha256Hex unit tests --------------------------------------------- //

    @Test
    fun `sha256Hex - negative byte input 0xff produces correct 64-char lowercase hex without sign extension`() {
        // SHA-256(0xff) is a known value that includes multiple digest bytes
        // >= 0x80 (negative as Kotlin Byte), exercising the high-byte formatting
        // path and confirming %02x does not sign-extend them.
        assertEquals(
            "a8100ae6aa1940d0b663bb31cd466142ebbdbd5187131b92d93818987832eb89",
            SignerWalkthrough.sha256Hex(byteArrayOf(-1)),
        )
    }
}
