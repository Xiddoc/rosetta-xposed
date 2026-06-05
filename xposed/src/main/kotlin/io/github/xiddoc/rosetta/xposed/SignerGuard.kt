/*
 * Signer authenticity guard — enforces a map's optional `signer_sha256`
 * against the running app's signing-certificate hash (RFC 0001 Decision 4:
 * `version_code` selects, `signer_sha256` guards).
 *
 * This is identity/authenticity, not resolution: a wrong-signer map is for
 * a build signed by a different (possibly repackaged/spoofed) certificate
 * that happens to share a `version_code`, so its obfuscated names cannot be
 * trusted against this process. The guard is opt-in *per map*: a map with
 * no `signer_sha256` is not checked at all.
 *
 * Fail-closed semantics, given a map M and an [AppIdentity] I:
 *   - M.signerSha256 == null            → no guard declared; pass (no check).
 *   - M.signerSha256 != null, I == null → cannot verify; pass (no identity
 *     supplied — the no-identity `fromMap` path deliberately performs no
 *     check, see [RosettaXposed.fromMap]).
 *   - M.signerSha256 != null, I.signerSha256 == null
 *                                       → map demands auth the caller can't
 *                                         satisfy → MissingSignerException.
 *   - both present, normalized-equal    → pass.
 *   - both present, normalized-differ   → SignerMismatchException.
 *
 * Both hashes are normalized before comparison: lowercased, with any colon
 * separators and ASCII whitespace stripped, so `AB:CD ...` and `abcd` match.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MissingSignerException
import io.github.xiddoc.rosetta.core.SignerMismatchException
import io.github.xiddoc.rosetta.core.model.RosettaMap

/** Verifies a map's `signer_sha256` guard against an [AppIdentity]. */
public object SignerGuard {
    /**
     * Enforce [map]'s `signer_sha256` (if any) against [identity].
     *
     * @throws MissingSignerException if the map declares a signer hash but
     *   [identity] (or its `signerSha256`) is `null`, so the guard cannot
     *   be satisfied.
     * @throws SignerMismatchException if both hashes are present but differ
     *   after normalization.
     */
    public fun verify(
        map: RosettaMap,
        identity: AppIdentity?,
    ) {
        val expectedRaw = map.signerSha256 ?: return
        // No identity at all = the no-identity `fromMap` path, which
        // deliberately performs no check (documented). Only an identity that
        // was supplied-but-without-a-hash is a fail-closed condition.
        if (identity == null) return
        val expected = normalize(expectedRaw)

        val actualRaw = identity.signerSha256
        if (actualRaw == null) {
            throw MissingSignerException(
                "Map for ${map.app}@${map.version} (version_code=${map.versionCode}) " +
                    "declares signer_sha256=$expected but no app signer hash was supplied; " +
                    "supply AppIdentity.signerSha256 (the app signing certificate SHA-256) to verify it.",
                expected = expected,
            )
        }

        val actual = normalize(actualRaw)
        if (actual != expected) {
            throw SignerMismatchException(
                "Signer mismatch for ${map.app}@${map.version} (version_code=${map.versionCode}): " +
                    "map expected signer_sha256=$expected but the running app is signed with $actual.",
                expected = expected,
                actual = actual,
            )
        }
    }

    /** Lowercase the hex and strip colon separators / ASCII whitespace. */
    private fun normalize(hash: String): String =
        buildString(hash.length) {
            for (ch in hash) {
                if (ch == ':' || ch.isWhitespace()) continue
                append(ch.lowercaseChar())
            }
        }
}
