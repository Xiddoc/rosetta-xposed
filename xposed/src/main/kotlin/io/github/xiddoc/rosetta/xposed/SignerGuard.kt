/*
 * Signer authenticity guard — enforces a map's optional `signer_sha256`
 * against the running app's signing-certificate hashes (RFC 0001
 * Decision 3: `version_code` selects, `signer_sha256` guards).
 *
 * This is identity/authenticity, not resolution: a wrong-signer map is for
 * a build signed by a different (possibly repackaged/spoofed) certificate
 * that happens to share a `version_code`, so its obfuscated names cannot be
 * trusted against this process. The guard is opt-in *per map*: a map with
 * no `signer_sha256` is not checked at all. There is no global opt-out:
 * when a map carries a signer and the verified path is used, enforcement is
 * unconditional and fail-closed.
 *
 * --- Canonical contract (the Frida client mirrors this) ---
 *
 * Hashes are compared as lowercase 64-hex with `:` separators stripped and
 * surrounding whitespace trimmed (see [normalize]). The map pins exactly
 * ONE expected hash; the app may present a SET of signer hashes (a real app
 * can be signed by several certs). Match is set membership:
 * `normalize(expected) ∈ { normalize(a) | a ∈ app signer set }`.
 *
 * Fail-closed semantics, given a map M and an [AppIdentity] I:
 *   - M.signerSha256 == null                 → no guard declared; pass.
 *   - M.signerSha256 malformed (not 64-hex
 *     after normalization)                   → MalformedSignerException.
 *   - M.signerSha256 valid, I signer set
 *     empty                                   → MissingSignerException
 *                                               (map demands auth the app
 *                                               can't satisfy).
 *   - M.signerSha256 valid, no app hash
 *     matches                                 → SignerMismatchException
 *                                               (actual = rendered app set).
 *   - M.signerSha256 valid, some app hash
 *     matches                                 → pass.
 *
 * The "no identity available" case is NOT modeled here: it is modeled by
 * simply not calling [verify] (the unchecked
 * [RosettaXposed.fromMapUnverified] construction path).
 *
 * Malformed *app-set* hashes are skipped (ignored) rather than rejected —
 * the app's set is observed runtime data we don't control, so a stray
 * entry shouldn't sink an otherwise-valid match. The MAP hash, which we
 * author and bundle, is the load-bearing well-formedness check.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MalformedSignerException
import io.github.xiddoc.rosetta.core.MissingSignerException
import io.github.xiddoc.rosetta.core.SignerMismatchException
import io.github.xiddoc.rosetta.core.model.RosettaMap

/**
 * Verifies a map's `signer_sha256` guard against an [AppIdentity].
 *
 * Hashes are compared as lowercase 64-hex with `:` separators stripped and
 * surrounding whitespace trimmed; the match is "expected ∈ app's signer
 * set" (the map pins one hash, the app may present several). This is the
 * canonical contract the Frida client mirrors.
 */
public object SignerGuard {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    /**
     * Enforce [map]'s `signer_sha256` (if any) against [identity]'s signer
     * set. Passes when the map declares no signer, or when the map's single
     * normalized expected hash equals ANY normalized hash in the app's set.
     *
     * @throws MalformedSignerException if the map's `signer_sha256` is not
     *   64 lowercase hex characters after normalization.
     * @throws MissingSignerException if the map declares a signer hash but
     *   [identity]'s signer set is empty, so the guard cannot be satisfied.
     * @throws SignerMismatchException if the map declares a signer hash and
     *   the app's set is non-empty but no member matches it.
     */
    public fun verify(
        map: RosettaMap,
        identity: AppIdentity,
    ) {
        val expectedRaw = map.signerSha256 ?: return
        val expected = normalize(expectedRaw)
        if (!HEX_64.matches(expected)) {
            throw MalformedSignerException(
                value = expectedRaw,
                reason = "map signer_sha256 must be 64 hex chars after normalization, got ${expected.length}",
            )
        }

        // Validate well-formed app hashes; malformed app entries are skipped
        // (observed runtime data we don't control), not rejected.
        val actuals =
            identity.signerSha256s
                .map(::normalize)
                .filter(HEX_64::matches)
                .toSet()

        if (actuals.isEmpty()) {
            throw MissingSignerException(
                "Map for ${map.app}@${map.version} (version_code=${map.versionCode}) " +
                    "declares signer_sha256=$expected but the running app presented no signing-certificate " +
                    "hashes; populate AppIdentity.signerSha256s from PackageManager to verify it.",
                expected = expected,
            )
        }

        if (expected !in actuals) {
            val rendered = actuals.sorted().joinToString(prefix = "[", postfix = "]")
            throw SignerMismatchException(
                "Signer mismatch for ${map.app}@${map.version} (version_code=${map.versionCode}): " +
                    "map expected signer_sha256=$expected but the running app is signed with $rendered.",
                expected = expected,
                actual = rendered,
            )
        }
    }

    /**
     * Normalize a signer hash for comparison: trim surrounding whitespace,
     * strip `:` separators, and lowercase. Interior whitespace is NOT
     * stripped, so it survives into the 64-hex well-formedness check and is
     * rejected as malformed. Comparison and that check both operate on this
     * normalized form.
     */
    private fun normalize(hash: String): String =
        buildString(hash.length) {
            for (ch in hash.trim()) {
                if (ch == ':') continue
                append(ch.lowercaseChar())
            }
        }
}
