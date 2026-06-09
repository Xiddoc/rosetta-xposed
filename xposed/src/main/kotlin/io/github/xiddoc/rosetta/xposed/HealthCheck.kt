/*
 * Attach-time health check (xposed#14 M8) — the JVM mirror of rosetta-frida's
 * `src/session/health-check.ts`.
 *
 * Run this ONCE, BEFORE the first hook, against the loaded [RosettaMap] and the
 * running app's [AppIdentity]. It answers "is this the map I think it is, for
 * the app I think I'm in?" and surfaces obviously-broken maps early, so a module
 * fails (or warns) at attach instead of mis-hooking deep inside a callback.
 *
 * PARITY OF INTENT (not code) with the Frida client: the Frida health check
 * runs on a live `Java` runtime and probes each obfuscated class through
 * `Java.use` (plus AIDL-descriptor / anchor checks), reporting a pass RATE
 * against a threshold. This module deliberately does NOT touch a JVM/Android
 * runtime — :xposed must not compile against `android.jar` or libxposed
 * (CLAUDE.md Decision 3/4), and the actual reflective load is the consuming
 * module's irreducible step. So the checks here are the runtime-INDEPENDENT
 * subset that we CAN verify with only the map + identity in hand:
 *
 *   - right app     — `identity.packageName == map.app`        (HARD failure)
 *   - right version — `identity.versionCode == map.versionCode` (HARD failure;
 *                     version_code is the authoritative O(1) selection key, so a
 *                     mismatch means the wrong map was selected entirely)
 *   - signer guard  — when the map carries `signer_sha256`, it is enforced
 *                     fail-closed via [SignerGuard] (HARD failure on mismatch /
 *                     missing / malformed)
 *   - map sanity    — non-empty `classes`, and every class carries a non-blank
 *                     obfuscated name (WARNINGS, not hard failures: a partially
 *                     odd map can still resolve the targets a given module uses)
 *
 * The result is a STRUCTURED [HealthCheckReport] — this function never throws;
 * the caller decides whether to log the warnings, proceed, or treat a non-`ok`
 * report as fatal. (The hard-failure objects each carry the matching core
 * exception where one exists, so a caller that wants to fail loudly can rethrow
 * the canonical error type instead of inventing its own.)
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.RosettaException
import io.github.xiddoc.rosetta.core.model.MapStatus
import io.github.xiddoc.rosetta.core.model.RosettaMap

/** A blocking problem found by the [HealthCheck]: the map must not be used as-is. */
public data class HealthCheckFailure(
    /** A stable machine-readable code for the failed check. */
    val kind: HealthCheckFailureKind,
    /** A human-readable explanation of what mismatched. */
    val message: String,
    /**
     * The canonical core exception that describes this failure, when one
     * exists (the signer-guard failures reuse [SignerGuard]'s thrown types).
     * A caller that wants to fail loudly can rethrow this instead of wrapping
     * the report. `null` for the app/version checks, which have no dedicated
     * core exception type.
     */
    val cause: RosettaException? = null,
)

/** Which hard check failed (machine-readable companion to [HealthCheckFailure.message]). */
public enum class HealthCheckFailureKind {
    /** `identity.packageName != map.app`. */
    APP_MISMATCH,

    /** `identity.versionCode != map.versionCode` (the authoritative selection key). */
    VERSION_MISMATCH,

    /** The map's `signer_sha256` guard did not pass against the app's signer set. */
    SIGNER,
}

/** A non-blocking observation: the map is usable, but something looks off. */
public data class HealthCheckWarning(
    /** A stable machine-readable code for the warning. */
    val kind: HealthCheckWarningKind,
    /** A human-readable explanation. */
    val message: String,
)

/** Which soft check produced a warning. */
public enum class HealthCheckWarningKind {
    /** The map has no class entries at all. */
    EMPTY_MAP,

    /** A class entry carries a blank obfuscated name. */
    BLANK_OBFUSCATED_NAME,

    /**
     * The map declares `status: superseded` (schema 3, maps#40): a newer map
     * exists (`superseded_by`). Usable, but the caller should prefer the newer
     * one. (A `retracted` map never reaches here — it is refused at load time.)
     */
    SUPERSEDED_MAP,
}

/**
 * The structured outcome of [HealthCheck.run]. [ok] is DERIVED from
 * [hardFailures] (true iff it is empty) so the two can never drift out of sync;
 * it is intentionally NOT a constructor parameter, so `copy(...)` cannot
 * manufacture an inconsistent instance. Warnings never affect [ok].
 */
public data class HealthCheckReport(
    /** Blocking problems — when non-empty the map should not be used as-is. */
    val hardFailures: List<HealthCheckFailure>,
    /** Non-blocking observations the caller may surface to the user. */
    val warnings: List<HealthCheckWarning>,
) {
    /** Derived from [hardFailures]: true iff it is empty. Warnings do not affect this. */
    val ok: Boolean get() = hardFailures.isEmpty()
}

/**
 * Pure-JVM attach-time health check. See the file header for the parity
 * rationale and the exact set of checks.
 */
public object HealthCheck {
    /**
     * Verify [map] against [identity] BEFORE the first hook and return a
     * structured [HealthCheckReport]. Never throws: hard problems are collected
     * into [HealthCheckReport.hardFailures] (with their canonical core exception
     * attached where one exists) and soft ones into
     * [HealthCheckReport.warnings]. The caller decides what to do with a
     * non-`ok` report.
     */
    public fun run(
        map: RosettaMap,
        identity: AppIdentity,
    ): HealthCheckReport {
        val hardFailures =
            buildList {
                // Right app — the package the map was authored for must be the one
                // we're attached to.
                if (identity.packageName != map.app) {
                    add(
                        HealthCheckFailure(
                            kind = HealthCheckFailureKind.APP_MISMATCH,
                            message =
                                "Map is for app '${map.app}' but the running process is " +
                                    "'${identity.packageName}'.",
                        ),
                    )
                }

                // Right version — version_code is the authoritative O(1) selection
                // key; a mismatch means an entirely wrong map was picked.
                if (identity.versionCode != map.versionCode) {
                    add(
                        HealthCheckFailure(
                            kind = HealthCheckFailureKind.VERSION_MISMATCH,
                            message =
                                "Map is for version_code ${map.versionCode} but the running " +
                                    "app reports version_code ${identity.versionCode}.",
                        ),
                    )
                }

                // Signer guard — only when the map declares one; fail-closed, but
                // structured: we reuse SignerGuard and translate its thrown type
                // into a hard-failure entry instead of propagating it.
                signerFailureOrNull(map, identity)?.let(::add)
            }

        val warnings =
            buildList {
                if (map.status == MapStatus.SUPERSEDED) {
                    val supersededBy = map.supersededBy
                    val by = if (supersededBy != null) " by version_code $supersededBy" else ""
                    add(
                        HealthCheckWarning(
                            kind = HealthCheckWarningKind.SUPERSEDED_MAP,
                            message =
                                "Map for ${map.app} (version_code=${map.versionCode}) is SUPERSEDED$by; " +
                                    "a newer map exists — prefer it if available.",
                        ),
                    )
                }
                if (map.classes.isEmpty()) {
                    add(
                        HealthCheckWarning(
                            kind = HealthCheckWarningKind.EMPTY_MAP,
                            message =
                                "Map for ${map.app} (version_code=${map.versionCode}) has no class " +
                                    "entries; nothing can be resolved through it.",
                        ),
                    )
                }
                for ((realName, entry) in map.classes) {
                    if (entry.obfuscated.isBlank()) {
                        add(
                            HealthCheckWarning(
                                kind = HealthCheckWarningKind.BLANK_OBFUSCATED_NAME,
                                message =
                                    "Class '$realName' has a blank obfuscated name; it cannot be " +
                                        "loaded reflectively.",
                            ),
                        )
                    }
                }
            }

        return HealthCheckReport(
            hardFailures = hardFailures,
            warnings = warnings,
        )
    }

    /**
     * Run [SignerGuard.verify] and fold its fail-closed exceptions into a
     * structured [HealthCheckFailure], or `null` when the guard passes (or the
     * map declares no signer). The canonical exception is preserved in
     * [HealthCheckFailure.cause] so a caller can rethrow it.
     */
    private fun signerFailureOrNull(
        map: RosettaMap,
        identity: AppIdentity,
    ): HealthCheckFailure? =
        try {
            SignerGuard.verify(map, identity)
            null
        } catch (e: RosettaException) {
            // SignerGuard.verify's only failure modes are its three signer
            // exceptions (mismatch / missing / malformed), all RosettaException
            // subtypes; this single catch folds every one into a structured
            // SIGNER failure. We surface the specific exception message (which
            // carries the expected/actual hash, the missing-signer hint, etc.)
            // so the human-readable summary is diagnostic, falling back to a
            // generic line only if the typed exception has no message; the
            // canonical exception is preserved as the cause for a caller that
            // wants to rethrow it.
            HealthCheckFailure(
                kind = HealthCheckFailureKind.SIGNER,
                // RosettaException's constructor mandates a non-null message, so
                // every SignerGuard failure carries its specific diagnostic
                // (expected/actual hash, the missing-signer hint, etc.); read it
                // directly so the human-readable summary stays diagnostic. The
                // canonical exception is still preserved as the cause.
                message = e.message,
                cause = e,
            )
        }
}
