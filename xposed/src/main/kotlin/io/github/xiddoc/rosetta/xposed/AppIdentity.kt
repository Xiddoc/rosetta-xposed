/*
 * App identity — the O(1) selection + authenticity fields a JVM-side
 * module reads from `PackageManager`, expressed as a plain value so this
 * module stays Android-free at compile time (RFC 0001 Decision 3).
 *
 * How to fill this from inside an Xposed module (illustrative — this code
 * lives in the consuming module, not here, so we don't compile against
 * android.jar):
 *
 *     val pm = context.packageManager
 *     val info = pm.getPackageInfo(
 *         context.packageName,
 *         PackageManager.GET_SIGNING_CERTIFICATES,
 *     )
 *     val identity = AppIdentity(
 *         packageName = info.packageName,
 *         versionCode = info.longVersionCode,            // API 28+
 *         versionName = info.versionName,
 *         signerSha256 = info.signingInfo
 *             ?.apkContentsSigners
 *             ?.firstOrNull()
 *             ?.let { sha256Hex(it.toByteArray()) },     // your hash helper
 *     )
 *
 * `versionCode` selects the map; `signerSha256`, when both sides have it,
 * guards against a repackaged/spoofed build sharing a version_code.
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
