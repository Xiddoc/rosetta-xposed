/*
 * DiscoveryObserver seam tests (rosetta-xposed#22).
 *
 * The observer is the small, testable abstraction the on-device e2e leans on
 * to assert "DISCOVERED" vs "SERVED FROM CACHE" vs "INVALIDATED" without
 * scattering log strings through the backend. These tests pin every contract
 * the e2e (and the example module's logcat markers) rely on, on a plain JVM via
 * the FakeDexKitIndex + the in-memory cache:
 *
 *   - a fresh static miss → DISCOVERED, exactly once;
 *   - a cache hit → SERVED_FROM_CACHE, with NO index scan;
 *   - the in-process memo is transparent (a repeat emits nothing);
 *   - the observer is a pure side-channel — a throwing observer never breaks
 *     resolution (FAIL-SOFT);
 *   - NOOP / RecordingDiscoveryObserver / DiscoveryConfig defaults.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.ClassEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiscoveryObserverTest {
    /** A specific exception the fail-soft tests throw (detekt forbids generic ones). */
    private class ObserverBlewUp : RuntimeException("observer blew up")

    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"
    private val real = "com.example.RealClient"
    private val aidl = "Lcom/example/IFoo;"

    private fun backend(
        index: DexKitIndex,
        cache: DiscoveryCache = DiscoveryCache.NOOP,
        observer: DiscoveryObserver = DiscoveryObserver.NOOP,
    ) = DynamicResolutionBackend(
        index = index,
        hints = mapOf(real to DiscoveryHints(aidlDescriptor = aidl)),
        cache = cache,
        observer = observer,
    )

    @Test
    fun `a fresh discovery reports DISCOVERED exactly once with the obf name`() {
        val observer = RecordingDiscoveryObserver()
        val index = FakeDexKitIndex(byAidl = mapOf(aidl to obf))
        backend(index, observer = observer).resolveClass(real)

        assertEquals(
            listOf(RecordingDiscoveryObserver.Outcome(real, obf, DiscoveryOutcome.DISCOVERED)),
            observer.outcomes(),
        )
    }

    @Test
    fun `a cache hit reports SERVED_FROM_CACHE and never scans the index`() {
        val cache = InMemoryDiscoveryCache()
        cache.put(real, ClassEntry(obfuscated = obf))
        val observer = RecordingDiscoveryObserver()
        val index = FakeDexKitIndex(byAidl = mapOf(aidl to obf))

        backend(index, cache = cache, observer = observer).resolveClass(real)

        assertEquals(0, index.calls)
        assertEquals(
            listOf(RecordingDiscoveryObserver.Outcome(real, obf, DiscoveryOutcome.SERVED_FROM_CACHE)),
            observer.outcomes(),
        )
    }

    @Test
    fun `the in-process memo is transparent - a repeat lookup emits no second outcome`() {
        val observer = RecordingDiscoveryObserver()
        val index = FakeDexKitIndex(byAidl = mapOf(aidl to obf))
        val backend = backend(index, observer = observer)

        backend.resolveClass(real)
        backend.resolveClass(real)

        // One DISCOVERED for the first resolve; the memoized repeat is silent.
        assertEquals(1, observer.outcomes().size)
        assertEquals(DiscoveryOutcome.DISCOVERED, observer.outcomes().single().outcome)
    }

    @Test
    fun `a throwing observer never breaks resolution (fail-soft side-channel)`() {
        val throwing =
            object : DiscoveryObserver {
                override fun onOutcome(
                    realName: String,
                    obfName: String,
                    outcome: DiscoveryOutcome,
                ): Unit = throw ObserverBlewUp()

                override fun onCacheInvalidated(reason: InvalidationReason): Unit = throw ObserverBlewUp()
            }
        val index = FakeDexKitIndex(byAidl = mapOf(aidl to obf))

        // Resolution must still succeed despite the observer throwing.
        val resolved = backend(index, observer = throwing).resolveClass(real)
        assertEquals(obf, resolved.obfName)
    }

    @Test
    fun `safe swallows an observer exception`() {
        // Direct unit of the fail-soft helper used at every emit point.
        DiscoveryObserver.safe(
            object : DiscoveryObserver {
                override fun onOutcome(
                    realName: String,
                    obfName: String,
                    outcome: DiscoveryOutcome,
                ): Unit = throw ObserverBlewUp()

                override fun onCacheInvalidated(reason: InvalidationReason) = Unit
            },
        ) { it.onOutcome(real, obf, DiscoveryOutcome.DISCOVERED) }
        // Reaching here without an exception IS the assertion.
        assertTrue(true)
    }

    @Test
    fun `NOOP ignores every signal`() {
        // Exercise both NOOP methods for coverage; neither records anything.
        DiscoveryObserver.NOOP.onOutcome(real, obf, DiscoveryOutcome.DISCOVERED)
        DiscoveryObserver.NOOP.onCacheInvalidated(InvalidationReason.FINGERPRINT_CHANGED)
        assertTrue(true)
    }

    @Test
    fun `RecordingDiscoveryObserver records outcomes and invalidations in order`() {
        val observer = RecordingDiscoveryObserver()
        observer.onCacheInvalidated(InvalidationReason.FIRST_RUN)
        observer.onOutcome(real, obf, DiscoveryOutcome.DISCOVERED)
        observer.onCacheInvalidated(InvalidationReason.FINGERPRINT_CHANGED)

        assertEquals(
            listOf(InvalidationReason.FIRST_RUN, InvalidationReason.FINGERPRINT_CHANGED),
            observer.invalidations(),
        )
        assertEquals(
            listOf(RecordingDiscoveryObserver.Outcome(real, obf, DiscoveryOutcome.DISCOVERED)),
            observer.outcomes(),
        )
    }

    @Test
    fun `DiscoveryConfig defaults its observer to NOOP and carries a custom one`() {
        assertSame(DiscoveryObserver.NOOP, DiscoveryConfig().observer)
        val custom = RecordingDiscoveryObserver()
        assertSame(custom, DiscoveryConfig(observer = custom).observer)
    }
}
