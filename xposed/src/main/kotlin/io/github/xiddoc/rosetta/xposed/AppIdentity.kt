/*
 * App identity — the O(1) selection + authenticity fields a JVM-side
 * module reads from `PackageManager`, expressed as a plain value so this
 * module stays Android-free at compile time (RFC 0001 Decision 3).
 *
 * How to fill this from inside an Xposed module (illustrative — this code
 * lives in the consuming module, not here, so we don't compile against
 * android.jar):
 *
 *     // Read the signing certificate's SHA-256. On API 28+ use
 *     // GET_SIGNING_CERTIFICATES (SigningInfo); on older devices fall back
 *     // to the deprecated GET_SIGNATURES.
 *     fun readSignerSha256(pm: PackageManager, pkg: String): String? =
 *         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
 *             val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
 *             info.signingInfo
 *                 ?.apkContentsSigners
 *                 ?.firstOrNull()
 *                 ?.let { sha256Hex(it.toByteArray()) }   // your hash helper
 *         } else {
 *             @Suppress("DEPRECATION")
 *             pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
 *                 .signatures
 *                 ?.firstOrNull()
 *                 ?.let { sha256Hex(it.toByteArray()) }
 *         }
 *
 *     val info = pm.getPackageInfo(context.packageName, 0)
 *     val identity = AppIdentity(
 *         packageName = info.packageName,
 *         versionCode = info.longVersionCode,            // API 28+
 *         versionName = info.versionName,
 *         signerSha256 = readSignerSha256(pm, context.packageName),
 *     )
 *
 * `versionCode` selects the map; `signerSha256`, when the map carries one,
 * is ENFORCED (fail-closed) by [RosettaXposed.verifySigner] /
 * [RosettaXposed.fromRegistry] to guard against a repackaged/spoofed build
 * that shares a version_code. Supply it whenever a map you bundle declares
 * a `signer_sha256`, or verification fails closed.
 */
package io.github.xiddoc.rosetta.xposed

public data class AppIdentity(
    /** Android package name (e.g. "com.example.app"). */
    val packageName: String,
    /** `PackageInfo.longVersionCode` (or the legacy `versionCode`). The selection key. */
    val versionCode: Long,
    /** `PackageInfo.versionName` — a human label, not authoritative. */
    val versionName: String? = null,
    /** Lowercase hex SHA-256 of the signing certificate, if read. */
    val signerSha256: String? = null,
)
