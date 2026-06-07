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
    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Assembles an [AppIdentity] from primitives the caller already pulled from
     * `PackageManager`. Each DER-encoded signing certificate in [signerCertsDer]
     * is hashed via [sha256Hex] into the [AppIdentity.signerSha256s] set
     * (duplicates collapse); an empty collection yields an empty set. The
     * incoming cert byte arrays are read (hashed) immediately and are neither
     * retained nor mutated.
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

    /**
     * Composes the full Android `longVersionCode` from its two 32-bit halves,
     * exactly as the platform does: `(versionCodeMajor.toLong() shl 32) or
     * (versionCode unsigned-widened to 32 bits)`.
     *
     * This is the canonical RFC 0001 Decision 3 selection key. A consumer on
     * API < 28 (where `longVersionCode` does not exist) reads the legacy
     * `PackageInfo.versionCode` (the low 32 bits, `versionCodeMajor == 0`) and
     * passes it as [versionCode] with [versionCodeMajor] = 0, yielding the same
     * value the manifest's `android:versionCode` carried.
     *
     * The low half is treated as UNSIGNED: a `versionCode` with the high bit
     * set (a value `>= 0x8000_0000` packed into the platform `int`) must not
     * sign-extend into the major half, so it is masked with `0xFFFF_FFFFL`
     * before being OR-ed in. The result never exceeds
     * `MapLoader.MAX_VERSION_CODE` for realistic inputs but is NOT bounds-checked
     * here — that is the map loader's job; this is pure bit composition.
     *
     * The major half, by contrast, is NOT masked: a negative [versionCodeMajor]
     * shifts its sign bit into bit 63 and yields a NEGATIVE result, which is not
     * a valid `longVersionCode` and which `MapLoader` rejects with a validation
     * error — a negative major is a caller bug, surfaced downstream rather than
     * silently coerced here.
     *
     * @param versionCode the low 32 bits (manifest `android:versionCode`).
     * @param versionCodeMajor the high 32 bits (manifest `android:versionCodeMajor`,
     *   `0` when unset / on API < 28). Defaults to `0`.
     */
    public fun longVersionCode(
        versionCode: Int,
        versionCodeMajor: Int = 0,
    ): Long = (versionCodeMajor.toLong() shl Int.SIZE_BITS) or (versionCode.toLong() and LOW_32_MASK)

    /**
     * End-to-end factory: assemble an [AppIdentity] straight from the PRIMITIVE
     * values a consumer reads off `PackageManager`, composing the
     * `longVersionCode` from its two halves ([longVersionCode]) and hashing the
     * signing certs ([build]) in one call.
     *
     * This is the recommended entry point for a consumer that has the legacy
     * split [versionCode] / [versionCodeMajor] integers in hand (i.e. it has not
     * already read `PackageInfo.longVersionCode`). It leaves the consumer with
     * only the irreducible `PackageManager` extraction:
     *
     *     // in the Android module, with a PackageManager in hand:
     *     val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
     *     val certs = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
     *     val identity = AndroidIdentities.fromPackageManagerPrimitives(
     *         packageName = pkg,
     *         versionCode = info.versionCode,            // low 32 bits
     *         versionCodeMajor = info.versionCodeMajor,  // high 32 bits (API 28+; else 0)
     *         versionName = info.versionName,
     *         signerCertsDer = certs,
     *     )
     *
     * A consumer that already holds the composed `longVersionCode` can skip the
     * composition and call [build] directly.
     *
     * @param packageName the Android package name (e.g. "com.example.app").
     * @param versionCode the low 32 bits (`PackageInfo.versionCode`).
     * @param versionCodeMajor the high 32 bits (`PackageInfo.versionCodeMajor`,
     *   `0` on API < 28). Defaults to `0`.
     * @param versionName `PackageInfo.versionName` — a human label, nullable.
     * @param signerCertsDer raw DER bytes of the app's signing certificates.
     */
    public fun fromPackageManagerPrimitives(
        packageName: String,
        versionCode: Int,
        versionCodeMajor: Int = 0,
        versionName: String? = null,
        signerCertsDer: Collection<ByteArray> = emptyList(),
    ): AppIdentity =
        build(
            packageName = packageName,
            versionCode = longVersionCode(versionCode, versionCodeMajor),
            versionName = versionName,
            signerCertsDer = signerCertsDer,
        )

    /** Mask isolating the low 32 bits, so an int's sign bit never bleeds upward. */
    private const val LOW_32_MASK: Long = 0xFFFF_FFFFL
}
