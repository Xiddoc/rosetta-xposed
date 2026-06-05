/*
 * Fills a Rosetta [AppIdentity] from Android's PackageManager.
 *
 * :xposed stays Android-free on purpose (it must not compile against
 * android.jar — CLAUDE.md Decision 4), so the AppIdentity-from-PackageManager
 * code can only live in a consuming module. This is that code, promoted from
 * the doc snippet in AppIdentity's KDoc to something real and reusable:
 *
 *   - `versionCode` is the O(1) map selection key (longVersionCode on API 28+).
 *   - `signerSha256s` is the FULL set of the app's signing-cert SHA-256 hashes;
 *     SignerGuard matches the map's single pinned hash against ANY of them.
 *
 * GAP THIS EXPOSES: like BundledMaps, there is no shipping helper for this —
 * every module re-implements the SDK-version branching and the hashing. Good
 * candidate for an optional `:xposed-android` artifact.
 */
package com.example.rosettamodule

import android.content.pm.PackageManager
import android.os.Build
import io.github.xiddoc.rosetta.xposed.AppIdentity
import java.security.MessageDigest

internal object AndroidAppIdentity {
    fun of(
        pm: PackageManager,
        packageName: String,
    ): AppIdentity {
        val versionCode: Long
        val versionName: String?
        val signers: Set<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            versionCode = info.longVersionCode
            versionName = info.versionName
            signers =
                info.signingInfo
                    ?.apkContentsSigners
                    ?.map { sha256Hex(it.toByteArray()) }
                    ?.toSet()
                    .orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            versionCode = info.versionCode.toLong()
            versionName = info.versionName
            @Suppress("DEPRECATION")
            signers =
                info.signatures
                    ?.map { sha256Hex(it.toByteArray()) }
                    ?.toSet()
                    .orEmpty()
        }

        return AppIdentity(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            signerSha256s = signers,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
