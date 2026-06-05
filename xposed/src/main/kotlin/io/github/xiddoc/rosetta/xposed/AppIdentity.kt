/*
 * App identity — the O(1) selection + authenticity fields a JVM-side
 * module reads from `PackageManager`, expressed as a plain value so this
 * module stays Android-free at compile time (RFC 0001 Decision 3).
 *
 * How to fill this from inside an Xposed module (illustrative — this code
 * lives in the consuming module, not here, so we don't compile against
 * android.jar):
 *
 *     // Read ALL of the app's signing-certificate SHA-256 hashes. A real
 *     // app may be signed by multiple certs, and the map pins exactly one,
 *     // so collect the whole set and let SignerGuard match-any. On API 28+
 *     // use GET_SIGNING_CERTIFICATES (SigningInfo.apkContentsSigners); on
 *     // older devices fall back to the deprecated GET_SIGNATURES array.
 *     fun readSignerSha256s(pm: PackageManager, pkg: String): Set<String> =
 *         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
 *             val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
 *             info.signingInfo
 *                 ?.apkContentsSigners
 *                 ?.map { sha256Hex(it.toByteArray()) }   // your hash helper
 *                 ?.toSet()
 *                 ?: emptySet()
 *         } else {
 *             @Suppress("DEPRECATION")
 *             pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
 *                 .signatures
 *                 ?.map { sha256Hex(it.toByteArray()) }
 *                 ?.toSet()
 *                 ?: emptySet()
 *         }
 *
 *     val info = pm.getPackageInfo(context.packageName, 0)
 *     val identity = AppIdentity(
 *         packageName = info.packageName,
 *         versionCode = info.longVersionCode,            // API 28+
 *         versionName = info.versionName,
 *         signerSha256s = readSignerSha256s(pm, context.packageName),
 *     )
 *
 * `versionCode` selects the map; `signerSha256s`, when the map carries a
 * `signer_sha256`, is ENFORCED (fail-closed) by [SignerGuard.verify]
 * (used by [RosettaXposed.fromRegistry] and the identity-bearing
 * [RosettaXposed.fromMap]) to guard against a repackaged/spoofed build that
 * shares a version_code. The guard passes when the map's single expected
 * hash equals ANY hash in this set. Populate it whenever a map you bundle
 * declares a `signer_sha256`, or verification fails closed.
 */
package io.github.xiddoc.rosetta.xposed

public data class AppIdentity(
    /** Android package name (e.g. "com.example.app"). */
    val packageName: String,
    /** `PackageInfo.longVersionCode` (or the legacy `versionCode`). The selection key. */
    val versionCode: Long,
    /** `PackageInfo.versionName` — a human label, not authoritative. */
    val versionName: String? = null,
    /**
     * Hex SHA-256 hashes of ALL the app's signing certificates, if read.
     * A real app may carry several; the map pins one and [SignerGuard]
     * matches any. Empty when no signer hashes were read.
     */
    val signerSha256s: Set<String> = emptySet(),
)
