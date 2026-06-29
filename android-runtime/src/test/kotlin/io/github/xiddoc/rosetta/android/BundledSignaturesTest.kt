package io.github.xiddoc.rosetta.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BundledSignaturesTest {
    @Test
    fun `loads bundled signatures from the class loader`() {
        val set = BundledSignatures.load("com.example.victim")

        assertEquals(listOf("com.example.victim.RemoteServiceClient"), set.realNames)
        assertEquals(
            "sessionId",
            set.classes
                .single()
                .signatures
                .single()
                .signature
                .trim('"'),
        )
    }

    @Test
    fun `defaults to this class's class loader`() {
        // Exercises the default-argument path; the resource is on the test class path.
        val set = BundledSignatures.load(app = "com.example.victim")
        assertEquals(1, set.classes.size)
    }

    @Test
    fun `accepts an explicit class loader`() {
        val set = BundledSignatures.load("com.example.victim", BundledSignaturesTest::class.java.classLoader)
        assertEquals(1, set.classes.size)
    }

    @Test
    fun `throws when the bundled signatures are missing`() {
        val ex = assertFailsWith<IllegalStateException> { BundledSignatures.load("com.absent.app") }
        assertTrue(ex.message!!.contains("signatures/com.absent.app.json"))
    }
}
