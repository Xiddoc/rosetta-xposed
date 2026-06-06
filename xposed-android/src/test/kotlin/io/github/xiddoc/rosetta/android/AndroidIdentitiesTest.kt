package io.github.xiddoc.rosetta.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidIdentitiesTest {
    @Test
    fun `sha256Hex matches the known empty-input vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AndroidIdentities.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `sha256Hex matches the known abc vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AndroidIdentities.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `sha256Hex zero-pads bytes that are below 0x10`() {
        // A leading 0x00 byte forces the "%02x" zero-pad branch; assert the hex
        // is full-width (64 chars) and starts with the padded byte.
        val hex = AndroidIdentities.sha256Hex(byteArrayOf(0))
        assertEquals(64, hex.length)
        assertEquals(
            "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
            hex,
        )
    }

    @Test
    fun `build with no certs yields an empty signer set`() {
        val identity =
            AndroidIdentities.build(
                packageName = "com.example.app",
                versionCode = 42L,
                versionName = "1.2.3",
            )

        assertEquals("com.example.app", identity.packageName)
        assertEquals(42L, identity.versionCode)
        assertEquals("1.2.3", identity.versionName)
        assertEquals(emptySet(), identity.signerSha256s)
    }

    @Test
    fun `build hashes each signing cert into the set`() {
        val identity =
            AndroidIdentities.build(
                packageName = "com.example.app",
                versionCode = 7L,
                signerCertsDer = listOf("abc".toByteArray(), ByteArray(0)),
            )

        assertEquals(
            setOf(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
            identity.signerSha256s,
        )
    }

    @Test
    fun `build collapses duplicate certs into one hash`() {
        val identity =
            AndroidIdentities.build(
                packageName = "com.example.app",
                versionCode = 1L,
                signerCertsDer = listOf("abc".toByteArray(), "abc".toByteArray()),
            )

        assertEquals(
            setOf("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            identity.signerSha256s,
        )
    }

    @Test
    fun `build allows a null versionName`() {
        val identity =
            AndroidIdentities.build(
                packageName = "com.example.app",
                versionCode = 1L,
                versionName = null,
            )
        assertNull(identity.versionName)
    }
}
