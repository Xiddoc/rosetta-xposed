package io.github.xiddoc.rosetta.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BundledMapsTest {
    @Test
    fun `loads a bundled map from the class loader`() {
        val map = BundledMaps.load("100.json")

        assertEquals("com.example.victim", map.app)
        assertEquals(100L, map.versionCode)
        assertTrue(map.classes.containsKey("com.example.victim.TicketService"))
    }

    @Test
    fun `defaults to this class's class loader`() {
        // Calling without an explicit ClassLoader exercises the default-argument
        // path; the resource lives on the test class path so it resolves.
        val map = BundledMaps.load(fileName = "100.json")
        assertEquals(100L, map.versionCode)
    }

    @Test
    fun `accepts an explicit class loader`() {
        val map = BundledMaps.load("100.json", BundledMapsTest::class.java.classLoader)
        assertEquals(100L, map.versionCode)
    }

    @Test
    fun `throws when the bundled map is missing`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                BundledMaps.load("does-not-exist.json")
            }
        assertTrue(ex.message!!.contains("maps/does-not-exist.json"))
    }
}
