/*
 * Attach-time health-check tests (xposed#14 M8). Exercises every branch on a
 * plain JVM with synthetic RosettaMap + AppIdentity values:
 *
 *   - right app / wrong app                      → ok / APP_MISMATCH
 *   - right version / wrong version_code         → ok / VERSION_MISMATCH
 *   - signer absent / pass / mismatch / missing  → ok / ok / SIGNER / SIGNER
 *     / malformed                                          / SIGNER
 *   - empty classes                              → EMPTY_MAP warning
 *   - blank obfuscated name                      → BLANK_OBFUSCATED_NAME warning
 *   - multiple hard failures accumulate          → all reported, ok=false
 *   - hard failure + warning accumulate          → independent loops, ok=false
 *   - multiple blank obfuscated names            → one warning each
 *   - reached via RosettaXposed.healthCheck      → same report (pass + fail)
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MalformedSignerException
import io.github.xiddoc.rosetta.core.MissingSignerException
import io.github.xiddoc.rosetta.core.SignerMismatchException
import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION
import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.MapStatus
import io.github.xiddoc.rosetta.core.model.RosettaMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthCheckTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private fun map(
        app: String = "com.example.app",
        versionCode: Long = 100L,
        signer: String? = null,
        status: MapStatus = MapStatus.ACTIVE,
        supersededBy: Long? = null,
        classes: Map<String, ClassEntry> = mapOf("com.example.RealClient" to ClassEntry(obfuscated = "aaaa")),
    ): RosettaMap =
        RosettaMap(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            app = app,
            version = "1.0.0",
            versionCode = versionCode,
            signerSha256s = signer?.let { listOf(it) },
            status = status,
            supersededBy = supersededBy,
            classes = classes,
        )

    private fun identity(
        packageName: String = "com.example.app",
        versionCode: Long = 100L,
        signers: Set<String> = emptySet(),
    ): AppIdentity = AppIdentity(packageName = packageName, versionCode = versionCode, signerSha256s = signers)

    @Test
    fun `a matching map and identity is ok with no failures or warnings`() {
        val report = HealthCheck.run(map(), identity())
        assertTrue(report.ok)
        assertTrue(report.hardFailures.isEmpty())
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun `wrong app is a hard APP_MISMATCH failure`() {
        val report = HealthCheck.run(map(), identity(packageName = "com.other.app"))
        assertFalse(report.ok)
        assertEquals(
            listOf(HealthCheckFailureKind.APP_MISMATCH),
            report.hardFailures.map { it.kind },
        )
        assertEquals(null, report.hardFailures.single().cause)
    }

    @Test
    fun `wrong version_code is a hard VERSION_MISMATCH failure`() {
        val report = HealthCheck.run(map(), identity(versionCode = 999L))
        assertFalse(report.ok)
        assertEquals(
            listOf(HealthCheckFailureKind.VERSION_MISMATCH),
            report.hardFailures.map { it.kind },
        )
        assertEquals(null, report.hardFailures.single().cause)
    }

    @Test
    fun `a map with no signer is not signer-checked`() {
        val report = HealthCheck.run(map(signer = null), identity(signers = emptySet()))
        assertTrue(report.ok)
    }

    @Test
    fun `a matching signer passes`() {
        val report = HealthCheck.run(map(signer = hashA), identity(signers = setOf(hashA)))
        assertTrue(report.ok)
        assertTrue(report.hardFailures.isEmpty())
    }

    @Test
    fun `a signer mismatch is a SIGNER failure carrying the core exception`() {
        val report = HealthCheck.run(map(signer = hashA), identity(signers = setOf(hashB)))
        assertFalse(report.ok)
        val failure = report.hardFailures.single()
        assertEquals(HealthCheckFailureKind.SIGNER, failure.kind)
        assertTrue(failure.cause is SignerMismatchException)
    }

    @Test
    fun `a SIGNER failure message reflects the specific cause`() {
        val report = HealthCheck.run(map(signer = hashA), identity(signers = setOf(hashB)))
        val failure = report.hardFailures.single()
        // The synthesized message must carry the specific exception's diagnostic
        // (the expected hash) rather than the generic fallback, while still
        // preserving the typed exception as the cause.
        assertEquals(failure.cause!!.message, failure.message)
        assertTrue(failure.message.contains(hashA))
    }

    @Test
    fun `a match-any signer passes when the app set also contains other hashes`() {
        // The map pins ONE hash; the app presents a SET that contains it plus
        // another — the match-any branch must pass with no SIGNER failure.
        val report = HealthCheck.run(map(signer = hashA), identity(signers = setOf(hashB, hashA)))
        assertTrue(report.ok)
        assertTrue(report.hardFailures.none { it.kind == HealthCheckFailureKind.SIGNER })
    }

    @Test
    fun `a map demanding a signer against an empty app set is a SIGNER failure`() {
        val report = HealthCheck.run(map(signer = hashA), identity(signers = emptySet()))
        assertFalse(report.ok)
        val failure = report.hardFailures.single()
        assertEquals(HealthCheckFailureKind.SIGNER, failure.kind)
        assertTrue(failure.cause is MissingSignerException)
    }

    @Test
    fun `a malformed map signer is a SIGNER failure`() {
        val report = HealthCheck.run(map(signer = "not-hex"), identity(signers = setOf(hashA)))
        assertFalse(report.ok)
        val failure = report.hardFailures.single()
        assertEquals(HealthCheckFailureKind.SIGNER, failure.kind)
        assertTrue(failure.cause is MalformedSignerException)
    }

    @Test
    fun `an empty class map warns EMPTY_MAP but stays ok`() {
        val report = HealthCheck.run(map(classes = emptyMap()), identity())
        assertTrue(report.ok)
        assertEquals(
            listOf(HealthCheckWarningKind.EMPTY_MAP),
            report.warnings.map { it.kind },
        )
    }

    @Test
    fun `a blank obfuscated name warns BLANK_OBFUSCATED_NAME but stays ok`() {
        val report =
            HealthCheck.run(
                map(classes = mapOf("com.example.RealClient" to ClassEntry(obfuscated = "  "))),
                identity(),
            )
        assertTrue(report.ok)
        assertEquals(
            listOf(HealthCheckWarningKind.BLANK_OBFUSCATED_NAME),
            report.warnings.map { it.kind },
        )
    }

    @Test
    fun `multiple hard failures all accumulate`() {
        val report =
            HealthCheck.run(
                map(signer = hashA),
                identity(packageName = "com.other.app", versionCode = 999L, signers = setOf(hashB)),
            )
        assertFalse(report.ok)
        assertEquals(
            setOf(
                HealthCheckFailureKind.APP_MISMATCH,
                HealthCheckFailureKind.VERSION_MISMATCH,
                HealthCheckFailureKind.SIGNER,
            ),
            report.hardFailures.map { it.kind }.toSet(),
        )
    }

    @Test
    fun `a mixed hard failure and warning accumulate independently`() {
        // Wrong package (HARD APP_MISMATCH) on an empty-class map (EMPTY_MAP
        // warning) — proves the hard-failure and warning loops run independently:
        // exactly one of each, and ok reflects only the hard failure.
        val report =
            HealthCheck.run(
                map(classes = emptyMap()),
                identity(packageName = "com.other.app"),
            )
        assertFalse(report.ok)
        assertEquals(
            listOf(HealthCheckFailureKind.APP_MISMATCH),
            report.hardFailures.map { it.kind },
        )
        assertEquals(
            listOf(HealthCheckWarningKind.EMPTY_MAP),
            report.warnings.map { it.kind },
        )
    }

    @Test
    fun `multiple blank obfuscated names each warn`() {
        val report =
            HealthCheck.run(
                map(
                    classes =
                        mapOf(
                            "com.example.RealClientA" to ClassEntry(obfuscated = ""),
                            "com.example.RealClientB" to ClassEntry(obfuscated = "  "),
                        ),
                ),
                identity(),
            )
        assertTrue(report.ok)
        assertEquals(2, report.warnings.size)
        assertTrue(report.warnings.all { it.kind == HealthCheckWarningKind.BLANK_OBFUSCATED_NAME })
    }

    @Test
    fun `RosettaXposed_healthCheck forwards to HealthCheck_run`() {
        val report = RosettaXposed.healthCheck(map(), identity())
        assertTrue(report.ok)
    }

    @Test
    fun `RosettaXposed_healthCheck forwards failures faithfully`() {
        // A deliberately mismatched packageName must surface as an APP_MISMATCH
        // hard failure through the forwarder — a swapped-argument delegate would
        // pass the happy-path test above undetected.
        val report = RosettaXposed.healthCheck(map(), identity(packageName = "com.other.app"))
        assertFalse(report.ok)
        assertEquals(
            listOf(HealthCheckFailureKind.APP_MISMATCH),
            report.hardFailures.map { it.kind },
        )
    }

    // ---- schema 3: superseded status warns (retracted is refused at load) ----

    @Test
    fun `a superseded map produces a SUPERSEDED_MAP warning but stays ok`() {
        val report = HealthCheck.run(map(status = MapStatus.SUPERSEDED, supersededBy = 200L), identity())
        // Warnings never affect ok; a superseded map is still usable.
        assertTrue(report.ok)
        assertEquals(1, report.warnings.size)
        val warning = report.warnings.single()
        assertEquals(HealthCheckWarningKind.SUPERSEDED_MAP, warning.kind)
        assertTrue(warning.message.contains("SUPERSEDED"))
        assertTrue(warning.message.contains("200"))
    }

    @Test
    fun `a superseded map with no superseded_by still warns`() {
        val report = HealthCheck.run(map(status = MapStatus.SUPERSEDED), identity())
        assertEquals(1, report.warnings.count { it.kind == HealthCheckWarningKind.SUPERSEDED_MAP })
    }

    @Test
    fun `an active map produces no superseded warning`() {
        val report = HealthCheck.run(map(status = MapStatus.ACTIVE), identity())
        assertTrue(report.warnings.none { it.kind == HealthCheckWarningKind.SUPERSEDED_MAP })
    }
}
