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
 * The MAP's `signer_sha256` is the CANONICAL form: bare lowercase 64-hex (one
 * value OR a match-any list, schema 3). It is checked against that exact regex
 * WITHOUT normalization — a non-canonical map value (uppercase / colon) is
 * rejected as malformed, so a guard-accepted map value can never be
 * schema-invalid (the maps#32 drift fix). The APP-presented hashes are observed
 * runtime data (PackageManager may render them uppercase / colon-separated), so
 * THOSE are normalized (see [normalize]) before comparison. Match is set
 * membership: `someMapHash ∈ { normalize(a) | a ∈ app signer set }`.
 *
 * Fail-closed semantics, given a map M and an [AppIdentity] I:
 *   - M.signerSha256s == null/empty          → no guard declared; pass.
 *   - any M hash malformed (not bare
 *     lowercase 64-hex)                       → MalformedSignerException.
 *   - M hashes valid, I signer set
 *     empty                                   → MissingSignerException
 *                                               (map demands auth the app
 *                                               can't satisfy).
 *   - M hashes valid, no app hash
 *     matches any                             → SignerMismatchException
 *                                               (actual = rendered app set).
 *   - M hashes valid, some app hash
 *     matches one                             → pass.
 *
 * The "no identity available" case is NOT modeled here: it is modeled by
 * simply not calling [verify] (the unchecked
 * [RosettaXposed.fromMapUnverified] construction path).
 *
 * Malformed *app-set* hashes are skipped (ignored) rather than rejected —
 * the app's set is observed runtime data we don't control, so a stray
 * entry shouldn't sink an otherwise-valid match. The MAP hashes, which we
 * author and bundle, are the load-bearing well-formedness check.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MalformedSignerException
import io.github.xiddoc.rosetta.core.MissingSignerException
import io.github.xiddoc.rosetta.core.SignerMismatchException
import io.github.xiddoc.rosetta.core.model.RosettaMap

/**
 * Verifies a map's `signer_sha256` guard against an [AppIdentity].
 *
 * The map's hash(es) are the CANONICAL bare-lowercase-64-hex form and are
 * checked against that regex directly (no normalization), so a guard-accepted
 * map value is always schema-valid. The app's hashes ARE normalized (lowercased,
 * `:` stripped, trimmed) before comparison. The match is "some map hash ∈ app's
 * normalized signer set" (the map may pin several, the app may present several).
 * This is the canonical contract the Frida client mirrors.
 */
public object SignerGuard {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    /**
     * Enforce [map]'s `signer_sha256` (if any) against [identity]'s signer
     * set. Passes when the map declares no signer, or when ANY of the map's
     * canonical expected hashes equals ANY normalized hash in the app's set.
     *
     * @throws MalformedSignerException if any map `signer_sha256` value is not
     *   bare lowercase 64 hex characters (the canonical schema form — checked
     *   WITHOUT normalization, so a non-canonical map value is rejected).
     * @throws MissingSignerException if the map declares a signer hash but
     *   [identity]'s signer set is empty, so the guard cannot be satisfied.
     * @throws SignerMismatchException if the map declares signer hashes and
     *   the app's set is non-empty but no member matches any of them.
     */
    public fun verify(
        map: RosettaMap,
        identity: AppIdentity,
    ) {
        val expectedRaw = map.signerSha256s?.takeIf { it.isNotEmpty() } ?: return
        // The MAP value is the canonical form: validate it against the bare
        // lowercase 64-hex regex WITHOUT normalizing, so a non-canonical map
        // value (uppercase / colon) is rejected as malformed rather than
        // silently accepted — a guard-accepted map value is then always
        // schema-valid (maps#32 drift fix).
        for (h in expectedRaw) {
            if (!HEX_64.matches(h)) {
                throw MalformedSignerException(
                    value = h,
                    reason = "map signer_sha256 must be bare lowercase 64 hex chars, got '$h'",
                )
            }
        }
        val expected = expectedRaw.toSet()

        // Validate well-formed app hashes; malformed app entries are skipped
        // (observed runtime data we don't control), not rejected. The app set
        // IS normalized first (PackageManager may render uppercase / colon).
        val actuals =
            identity.signerSha256s
                .map(::normalize)
                .filter(HEX_64::matches)
                .toSet()

        // Render the map's demand: a single hash renders bare (the common
        // canonical case), several render as a bracketed set.
        val rendered =
            if (expected.size == 1) {
                expected.first()
            } else {
                expected.sorted().joinToString(prefix = "[", postfix = "]")
            }
        if (actuals.isEmpty()) {
            throw MissingSignerException(
                "Map for ${map.app}@${map.version} (version_code=${map.versionCode}) " +
                    "declares signer_sha256=$rendered but the running app presented no signing-certificate " +
                    "hashes; populate AppIdentity.signerSha256s from PackageManager to verify it.",
                expected = rendered,
            )
        }

        if (expected.none { it in actuals }) {
            val renderedApp = actuals.sorted().joinToString(prefix = "[", postfix = "]")
            throw SignerMismatchException(
                "Signer mismatch for ${map.app}@${map.version} (version_code=${map.versionCode}): " +
                    "map expected signer_sha256=$rendered but the running app is signed with $renderedApp.",
                expected = rendered,
                actual = renderedApp,
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
