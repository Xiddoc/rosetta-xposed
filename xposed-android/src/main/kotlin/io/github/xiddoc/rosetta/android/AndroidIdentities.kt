/*
 * Builds a Rosetta [AppIdentity] from PRIMITIVES a consuming Xposed module
 * already extracted from `PackageManager`, plus the SHA-256 hashing helper.
 *
 * :xposed (and :core) stay Android-free on purpose — they must not compile
 * against android.jar (CLAUDE.md Decision 4) — so the raw PackageManager read
 * can only live in the consuming Android module. But the part that is NOT
 * Android-specific — hashing the signing certs and assembling the AppIdentity —
 * does not need `android.*` at all: it operates on plain `ByteArray`s and
 * primitives. That logic is pulled out here so it is fully unit-testable and
 * lives in the gated module, leaving the consumer with only the irreducible
 * ~6-line PackageManager extraction:
 *
 *     // in the Android module, with a PackageManager in hand:
 *     val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
 *     val certs = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
 *     val identity = AndroidIdentities.build(
 *         packageName = pkg,
 *         versionCode = info.longVersionCode,
 *         versionName = info.versionName,
 *         signerCertsDer = certs,
 *     )
 *
 * `versionCode` is the O(1) map selection key; the signing-cert hashes guard
 * authenticity (SignerGuard matches the map's single pinned hash against ANY of
 * the set this produces).
 */
package io.github.xiddoc.rosetta.android

import io.github.xiddoc.rosetta.xposed.AppIdentity
import java.security.MessageDigest

/** Pure-JVM signer-hash + [AppIdentity] assembly from PackageManager primitives. */
public object AndroidIdentities {
    /** Lowercase hex SHA-256 of [bytes] (the form SignerGuard compares against). */
    public fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Assembles an [AppIdentity] from primitives the caller already pulled from
     * `PackageManager`. Each DER-encoded signing certificate in [signerCertsDer]
     * is hashed via [sha256Hex] into the [AppIdentity.signerSha256s] set
     * (duplicates collapse); an empty collection yields an empty set.
     *
     * @param packageName the Android package name (e.g. "com.example.app").
     * @param versionCode `PackageInfo.longVersionCode` (the selection key).
     * @param versionName `PackageInfo.versionName` — a human label, nullable.
     * @param signerCertsDer raw DER bytes of the app's signing certificates
     *   (from `SigningInfo.apkContentsSigners` or the legacy `signatures`).
     */
    public fun build(
        packageName: String,
        versionCode: Long,
        versionName: String? = null,
        signerCertsDer: Collection<ByteArray> = emptyList(),
    ): AppIdentity =
        AppIdentity(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            signerSha256s = signerCertsDer.mapTo(mutableSetOf()) { sha256Hex(it) },
        )
}
