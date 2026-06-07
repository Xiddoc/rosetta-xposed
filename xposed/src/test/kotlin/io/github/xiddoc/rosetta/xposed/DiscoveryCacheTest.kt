/*
 * DiscoveryCache seam tests (rosetta-xposed#19).
 *
 * Covers the NOOP default (every get misses, put is a no-op) and the
 * fully-tested in-memory reference implementation, including concurrent puts
 * (the dynamic backend may discover from several hooked threads at once).
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.ClassEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryCacheTest {
    @Test
    fun `the NOOP cache misses every get and ignores put`() {
        val entry = ClassEntry(obfuscated = "aaaa")
        DiscoveryCache.NOOP.put("com.example.C", entry)
        assertNull(DiscoveryCache.NOOP.get("com.example.C"))
    }

    @Test
    fun `the in-memory cache returns what was put and snapshots it`() {
        val cache = InMemoryDiscoveryCache()
        assertNull(cache.get("com.example.C"))
        val entry = ClassEntry(obfuscated = "aaaa", extends = "zzzz")
        cache.put("com.example.C", entry)
        assertEquals(entry, cache.get("com.example.C"))
        assertEquals(mapOf("com.example.C" to entry), cache.snapshot())
    }

    @Test
    fun `the in-memory cache overwrites an existing entry`() {
        val cache = InMemoryDiscoveryCache()
        cache.put("com.example.C", ClassEntry(obfuscated = "aaaa"))
        cache.put("com.example.C", ClassEntry(obfuscated = "bbbb"))
        assertEquals("bbbb", cache.get("com.example.C")?.obfuscated)
        assertEquals(1, cache.snapshot().size)
    }

    @Test
    fun `the in-memory cache accepts concurrent puts without losing entries`() {
        val cache = InMemoryDiscoveryCache()
        val threads = 8
        val perThread = 250
        val workers =
            (0 until threads).map { t ->
                Thread {
                    repeat(perThread) { i -> cache.put("com.example.C$t-$i", ClassEntry(obfuscated = "o$t$i")) }
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(threads * perThread, cache.snapshot().size)
        assertTrue(cache.get("com.example.C0-0") != null)
    }
}
