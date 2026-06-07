/*
 * Dynamic backend ↔ DiscoveryCache wiring tests (rosetta-xposed#19).
 *
 * Split from DynamicResolutionBackendTest (which is already at its LargeClass
 * budget) so the persistence-seam wiring has a focused home. Covers: a cache
 * hit short-circuiting the DexKit scan, a successful discovery being persisted,
 * a cache hit being promoted to the in-process memo without re-emitting to the
 * sink, and the DiscoveryConfig cache default. Runs on a plain JVM via the
 * FakeDexKitIndex + the in-memory cache.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.ClassEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicDiscoveryCacheWiringTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"
    private val real = "com.example.RealClient"

    @Test
    fun `a cache hit short-circuits discovery so the index is never scanned`() {
        // A discovery persisted by a prior process pre-populates the cache; the
        // backend must return it WITHOUT touching the index.
        val cache = InMemoryDiscoveryCache()
        cache.put(real, ClassEntry(obfuscated = obf, extends = "zzzz"))
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(
                index = index,
                hints = mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")),
                cache = cache,
            )
        val resolved = backend.resolveClass(real)
        assertEquals(obf, resolved.obfName)
        assertEquals("zzzz", resolved.extends)
        assertEquals(0, index.calls)
    }

    @Test
    fun `a successful discovery is written to the cache for a later restart`() {
        val cache = InMemoryDiscoveryCache()
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(
                index = index,
                hints = mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")),
                cache = cache,
            )
        assertNull(cache.get(real))
        backend.resolveClass(real)
        assertEquals(obf, cache.get(real)?.obfuscated)
    }

    @Test
    fun `a cache hit is promoted to the memo and not re-recorded to the sink`() {
        // The cache hit must not re-emit to the sink (the sink records only what
        // THIS run freshly discovered), and a second lookup is served from the
        // promoted in-process memo (still no index scan).
        val cache = InMemoryDiscoveryCache()
        cache.put(real, ClassEntry(obfuscated = obf))
        val sink = MapDiscoverySink()
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(
                index = index,
                hints = mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")),
                sink = sink,
                cache = cache,
            )
        backend.resolveClass(real)
        backend.resolveClass(real)
        assertEquals(0, index.calls)
        assertTrue(sink.entries().isEmpty())
    }

    @Test
    fun `DiscoveryConfig defaults its cache to NOOP and carries a custom one`() {
        assertSame(DiscoveryCache.NOOP, DiscoveryConfig().cache)
        val custom = DiscoveryConfig(cache = InMemoryDiscoveryCache())
        assertTrue(custom.cache is InMemoryDiscoveryCache)
    }
}
