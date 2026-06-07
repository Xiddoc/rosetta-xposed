/*
 * Signer authenticity guard — end-to-end walkthrough on a plain JVM.
 *
 * Today the existing Walkthrough uses a map without `signer_sha256`, which
 * makes the SignerGuard a no-op. This walkthrough exercises EVERY branch of
 * the guard defined in RFC 0001 Decision 3 and SignerGuard.kt:
 *
 *   MATCH    — map + identity agree on the hash → binding succeeds; real name
 *              resolved to prove the translation still works.
 *   MISMATCH — identity present but hash not in the map's expected set →
 *              SignerMismatchException (fail-closed).
 *   MISSING  — map demands a signer but identity carries no hashes →
 *              MissingSignerException (fail-closed).
 *   MALFORMED — map's signer_sha256 is not 64 hex chars after normalization →
 *              MalformedSignerException (fail-closed).
 *
 * An optional normalization case shows that a hash carrying uppercase letters
 * or colon separators is treated as identical to the canonical lowercase form.
 *
 * ---- How the cert hash is produced ----
 *
 * We pick a deterministic 32-byte stand-in for a signing certificate and
 * compute its SHA-256 with java.security.MessageDigest — the same approach
 * a real Xposed module uses after reading PackageManager (see AppIdentity
 * KDoc). The computed hex is then interpolated directly into the map JSON
 * string, mirroring the style used in RosettaXposedTest.
 *
 * Computed cert hash (for reproducibility):
 *   input : ByteArray of 0x00..0x1f (32 bytes)
 *   SHA-256: 630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd
 */
package com.example.victimhook

import io.github.xiddoc.rosetta.core.MalformedSignerException
import io.github.xiddoc.rosetta.core.MissingSignerException
import io.github.xiddoc.rosetta.core.SignerMismatchException
import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.RosettaXposed
import io.github.xiddoc.rosetta.xposed.TargetPolicy
import java.security.MessageDigest

/** Results returned to the test so every assertion is in the test class. */
public data class SignerWalkthroughResult(
    /** The cert hash computed from [CERT_BYTES]. */
    val computedHash: String,
    /** The obfuscated member name resolved in the MATCH scenario. */
    val matchedMemberName: String,
    /** Return value of invoking the resolved member in the MATCH scenario. */
    val matchedInvocation: String,
    /** The exception thrown in the MISMATCH scenario. */
    val mismatch: SignerMismatchException,
    /** The exception thrown in the MISSING scenario. */
    val missing: MissingSignerException,
    /** The exception thrown in the MALFORMED scenario. */
    val malformed: MalformedSignerException,
    /** True when the normalised uppercase+colon form matched the lowercase form. */
    val normalizationMatched: Boolean,
    /** True when the multi-signer identity (containing extra hashes) matched. */
    val multiSignerMatched: Boolean,
    /** True when the fromRegistry path bound and resolved the real name. */
    val registryMatched: Boolean,
)

public object SignerWalkthrough {
    /**
     * Deterministic stand-in for a signing certificate (32 bytes, 0x00–0x1f).
     * A real Xposed module reads this from PackageManager; here we fix it so
     * the test is reproducible without any Android SDK.
     */
    public val CERT_BYTES: ByteArray = ByteArray(32) { it.toByte() }

    private const val APP = "com.example.victim"
    private const val VERSION = "1.0.0"
    private const val VERSION_CODE = 100L

    // The obfuscated fixture class that maps/100.json already points at.
    private const val OBF_FQN = "com.example.victim.a.b"
    private const val REAL_CLASS = "com.example.victim.TicketService"
    private const val REAL_METHOD = "formatTicket"

    /**
     * Compute the lowercase 64-hex SHA-256 of [bytes], the same way a real
     * module computes a signing-certificate hash from PackageManager.
     */
    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the inline JSON for a map that includes [signerSha256] in the
     * `signer_sha256` field, reusing the same TicketService → com.example.victim.a.b
     * class shape as maps/100.json.
     */
    private fun mapJsonWithSigner(signerSha256: String): String =
        """
        {
          "schema_version": 2,
          "app": "$APP",
          "version": "$VERSION",
          "version_code": $VERSION_CODE,
          "captured_at": "2026-06-06",
          "signer_sha256": "$signerSha256",
          "sources": [
            { "tool": "hand-authored", "classes": 1, "notes": "signer walkthrough fixture" }
          ],
          "classes": {
            "$REAL_CLASS": {
              "obfuscated": "$OBF_FQN",
              "kind": "class",
              "methods": {
                "$REAL_METHOD": {
                  "obfuscated": "c",
                  "signature": "(Ljava/lang/String;)Ljava/lang/String;"
                }
              }
            }
          }
        }
        """.trimIndent()

    public fun run(): SignerWalkthroughResult {
        // Step 1 — compute the hash from a deterministic cert stand-in.
        val certHash = sha256Hex(CERT_BYTES)

        // The policy allowlist is needed because the victim class is on the
        // SYSTEM classpath in this pure-JVM harness; on a real device the app
        // class loader carries it and the default app-prefix policy allows it.
        val policy = TargetPolicy(allow = listOf(OBF_FQN))

        // ------------------------------------------------------------------ //
        // MATCH: identity signer set contains the map's hash → should succeed //
        // ------------------------------------------------------------------ //
        val matchMap = MapLoader.fromJson(mapJsonWithSigner(certHash))
        val matchIdentity = AppIdentity(
            packageName = APP,
            versionCode = VERSION_CODE,
            versionName = VERSION,
            signerSha256s = setOf(certHash),
        )
        val matchRosetta = RosettaXposed.fromMap(matchMap, SignerWalkthrough::class.java.classLoader!!, matchIdentity, policy)

        // Resolve the real name to prove the guard did not block the binding.
        val method = matchRosetta.method(REAL_CLASS, REAL_METHOD).member() as java.lang.reflect.Method
        val invoked = method.invoke(com.example.victim.a.b(), "S-001") as String

        // Also verify with a SET containing OTHER hashes PLUS the right one —
        // guard is match-any, so extra hashes must not cause a failure.
        val multiSetIdentity = AppIdentity(
            packageName = APP,
            versionCode = VERSION_CODE,
            signerSha256s = setOf("a".repeat(64), certHash, "b".repeat(64)),
        )
        val multiSignerMatched: Boolean = try {
            RosettaXposed.fromMap(matchMap, SignerWalkthrough::class.java.classLoader!!, multiSetIdentity, policy)
            true
        } catch (_: Exception) {
            false
        }

        // ------------------------------------------------------------------ //
        // MATCH-via-registry: fromRegistry path enforces the signer guard too //
        // ------------------------------------------------------------------ //
        val registry = MapRegistry.of(matchMap)
        val registryRosetta = RosettaXposed.fromRegistry(registry, matchIdentity, SignerWalkthrough::class.java.classLoader!!, policy)
            ?: error("fromRegistry returned null — map not indexed by version_code $VERSION_CODE")
        val registryResolved = registryRosetta.method(REAL_CLASS, REAL_METHOD).member() as java.lang.reflect.Method
        val registryMatched = registryResolved.name == method.name

        // ------------------------------------------------------------------ //
        // MISMATCH: identity present but contains a WRONG hash                //
        // ------------------------------------------------------------------ //
        val wrongHash = "a".repeat(64) // valid 64-hex, but not the map's hash
        val mismatchIdentity = AppIdentity(
            packageName = APP,
            versionCode = VERSION_CODE,
            signerSha256s = setOf(wrongHash),
        )
        val mismatch: SignerMismatchException = try {
            RosettaXposed.fromMap(matchMap, SignerWalkthrough::class.java.classLoader!!, mismatchIdentity, policy)
            error("Expected SignerMismatchException but binding succeeded")
        } catch (ex: SignerMismatchException) {
            ex
        }

        // ------------------------------------------------------------------ //
        // MISSING: map demands a signer but identity carries NO hashes        //
        // ------------------------------------------------------------------ //
        val noSignerIdentity = AppIdentity(
            packageName = APP,
            versionCode = VERSION_CODE,
            signerSha256s = emptySet(),
        )
        val missing: MissingSignerException = try {
            RosettaXposed.fromMap(matchMap, SignerWalkthrough::class.java.classLoader!!, noSignerIdentity, policy)
            error("Expected MissingSignerException but binding succeeded")
        } catch (ex: MissingSignerException) {
            ex
        }

        // ------------------------------------------------------------------ //
        // MALFORMED: map's signer_sha256 is not 64 hex chars                  //
        // ------------------------------------------------------------------ //
        val malformedMap = MapLoader.fromJson(mapJsonWithSigner("not-a-valid-hash"))
        val malformed: MalformedSignerException = try {
            RosettaXposed.fromMap(malformedMap, SignerWalkthrough::class.java.classLoader!!, matchIdentity, policy)
            error("Expected MalformedSignerException but binding succeeded")
        } catch (ex: MalformedSignerException) {
            ex
        }

        // ------------------------------------------------------------------ //
        // NORMALIZATION: uppercase + colon-separated hash must match          //
        // ------------------------------------------------------------------ //
        // Build the colon-separated uppercase form of the same cert hash.
        val colonUpperHash = certHash.chunked(2).joinToString(":") { it.uppercase() }
        val normMap = MapLoader.fromJson(mapJsonWithSigner(colonUpperHash))
        // Identity carries the plain lowercase form — the guard normalizes both.
        val normIdentity = AppIdentity(
            packageName = APP,
            versionCode = VERSION_CODE,
            signerSha256s = setOf(certHash),
        )
        val normalizationMatched: Boolean = try {
            RosettaXposed.fromMap(normMap, SignerWalkthrough::class.java.classLoader!!, normIdentity, policy)
            true
        } catch (_: SignerMismatchException) {
            false
        }

        return SignerWalkthroughResult(
            computedHash = certHash,
            matchedMemberName = method.name,
            matchedInvocation = invoked,
            mismatch = mismatch,
            missing = missing,
            malformed = malformed,
            normalizationMatched = normalizationMatched,
            multiSignerMatched = multiSignerMatched,
            registryMatched = registryMatched,
        )
    }
}

public fun signerMain() {
    val r = SignerWalkthrough.run()
    println("=== rosetta-xposed signer guard walkthrough (plain JVM) ===")
    println()
    println("cert SHA-256 : ${r.computedHash}")
    println()
    println("MATCH        : resolved ${r.matchedMemberName}, invoked -> \"${r.matchedInvocation}\" (guard passed)")
    println("MULTI-SIGNER : extra hashes in identity set, still matched = ${r.multiSignerMatched}")
    println("REGISTRY     : fromRegistry path bound and resolved = ${r.registryMatched}")
    println("MISMATCH     : ${r.mismatch.message}")
    println("MISSING      : ${r.missing.message}")
    println("MALFORMED    : ${r.malformed.message}")
    println("NORMALIZED   : colon/uppercase hash matched = ${r.normalizationMatched}")
    println()
    println("OK — every signer guard branch exercised end-to-end.")
}
