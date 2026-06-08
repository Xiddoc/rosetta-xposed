/*
 * Fills a Rosetta [AppIdentity] from Android's PackageManager.
 *
 * :xposed stays Android-free on purpose (it must not compile against
 * android.jar — CLAUDE.md Decision 4), so the irreducible PackageManager read
 * — the SDK-version branch that pulls versionCode / versionName and the raw
 * signing-certificate byte arrays — can only live in a consuming Android module.
 * This is that thin extraction; the hashing + AppIdentity assembly is delegated
 * to the shipping, fully-tested `io.github.xiddoc.rosetta.android.AndroidIdentities`
 * in the optional pure-JVM `:android-runtime` module.
 *
 *   - `versionCode` is the O(1) map selection key (longVersionCode on API 28+).
 *   - the cert byte arrays become `signerSha256s`; SignerGuard matches the map's
 *     single pinned hash against ANY of them.
 */
package com.example.rosettamodule

import android.content.pm.PackageManager
import android.os.Build
import io.github.xiddoc.rosetta.android.AndroidIdentities
import io.github.xiddoc.rosetta.xposed.AppIdentity

internal object AndroidAppIdentity {
    fun of(
        pm: PackageManager,
        packageName: String,
    ): AppIdentity {
        val versionCode: Long
        val versionName: String?
        val certs: List<ByteArray>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            versionCode = info.longVersionCode
            versionName = info.versionName
            certs =
                info.signingInfo
                    ?.apkContentsSigners
                    ?.map { it.toByteArray() }
                    .orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            // Pre-28 has no longVersionCode / versionCodeMajor: the legacy
            // `versionCode` int IS the low 32 bits (major == 0). Compose it
            // through the helper instead of `.toLong()` so a high-bit
            // versionCode is widened UNSIGNED rather than sign-extended.
            @Suppress("DEPRECATION")
            versionCode = AndroidIdentities.longVersionCode(info.versionCode, 0)
            versionName = info.versionName
            @Suppress("DEPRECATION")
            certs =
                info.signatures
                    ?.map { it.toByteArray() }
                    .orEmpty()
        }

        // Delegate hashing + assembly to the tested :android-runtime module.
        return AndroidIdentities.build(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            signerCertsDer = certs,
        )
    }
}
