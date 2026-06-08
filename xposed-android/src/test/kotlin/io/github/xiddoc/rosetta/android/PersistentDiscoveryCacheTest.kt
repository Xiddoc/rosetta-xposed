/*
 * PersistentDiscoveryCache tests (rosetta-xposed#19).
 *
 * The Android-coupled `SharedPreferences` object is replaced by an in-memory
 * [KeyValueStore], so the whole cache — JSON round-trip, fail-soft decode, and
 * the `(app, version_code, signer)` invalidation — runs on a plain JVM and
 * joins the 100% Kover gate without an emulator. Covers: a discovery surviving
 * a "restart" (a fresh cache over the same store), a corrupt value treated as a
 * miss and pruned, and invalidation across each identity field that must drop a
 * stale cache (first run, version_code bump, signer change) while leaving
 * unrelated keys untouched.
 */
package io.github.xiddoc.rosetta.android

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.DiscoveryObserver
import io.github.xiddoc.rosetta.xposed.DiscoveryOutcome
import io.github.xiddoc.rosetta.xposed.RecordingDiscoveryObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistentDiscoveryCacheTest {
    /** A specific exception the fail-soft test throws (detekt forbids generic ones). */
    private class ObserverBlewUp : RuntimeException("observer blew up")

    /** A plain in-memory stand-in for the consumer's SharedPreferences adapter. */
    private class FakeStore(
        private val map: MutableMap<String, String> = mutableMapOf(),
    ) : KeyValueStore {
        override fun getString(key: String): String? = map[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun keys(): Set<String> = map.keys.toSet()
    }

    private val identity =
        AppIdentity(
            packageName = "com.example.app",
            versionCode = 100,
            signerSha256s = setOf("a".repeat(64)),
        )
    private val real = "com.example.RealClient"
    private val entry = ClassEntry(obfuscated = "aaaa", extends = "zzzz")

    @Test
    fun `put then get round-trips an entry`() {
        val cache = PersistentDiscoveryCache.create(FakeStore(), identity)
        assertNull(cache.get(real))
        cache.put(real, entry)
        assertEquals(entry, cache.get(real))
    }

    @Test
    fun `a discovery survives a restart over the same store with the same identity`() {
        val store = FakeStore()
        PersistentDiscoveryCache.create(store, identity).put(real, entry)
        // A fresh cache instance over the same store == a new process launch.
        val restarted = PersistentDiscoveryCache.create(store, identity)
        assertEquals(entry, restarted.get(real))
    }

    @Test
    fun `a corrupt stored value is a miss and is pruned`() {
        val store = FakeStore()
        val cache = PersistentDiscoveryCache.create(store, identity)
        // Write a garbage value under the entry's real key (post-fingerprint so
        // it is not cleared on construction).
        store.putString(PersistentDiscoveryCache.ENTRY_PREFIX + real, "not json")
        assertNull(cache.get(real))
        // It was removed, so a re-read still misses (and the slot is free to refill).
        assertNull(store.getString(PersistentDiscoveryCache.ENTRY_PREFIX + real))
    }

    @Test
    fun `a version_code change drops the cache but keeps unrelated keys`() {
        val store = FakeStore()
        PersistentDiscoveryCache.create(store, identity).put(real, entry)
        // A foreign key in the same store must NOT be cleared by invalidation.
        store.putString("consumer.unrelated", "keep-me")

        val updated = identity.copy(versionCode = 101)
        val cache = PersistentDiscoveryCache.create(store, updated)
        assertNull(cache.get(real))
        assertEquals("keep-me", store.getString("consumer.unrelated"))
    }

    @Test
    fun `a signer change drops the cache`() {
        val store = FakeStore()
        PersistentDiscoveryCache.create(store, identity).put(real, entry)
        val resigned = identity.copy(signerSha256s = setOf("b".repeat(64)))
        assertNull(PersistentDiscoveryCache.create(store, resigned).get(real))
    }

    @Test
    fun `the same identity across restarts does not clear the cache`() {
        val store = FakeStore()
        PersistentDiscoveryCache.create(store, identity).put(real, entry)
        // Same fingerprint → invalidateIfStale returns early, entry preserved.
        assertEquals(entry, PersistentDiscoveryCache.create(store, identity).get(real))
    }

    @Test
    fun `fingerprint is stable regardless of signer set order`() {
        val a = identity.copy(signerSha256s = setOf("a".repeat(64), "b".repeat(64)))
        val b = identity.copy(signerSha256s = setOf("b".repeat(64), "a".repeat(64)))
        assertEquals(
            PersistentDiscoveryCache.fingerprintOf(a),
            PersistentDiscoveryCache.fingerprintOf(b),
        )
    }

    @Test
    fun `fingerprint changes when the package changes`() {
        val other = identity.copy(packageName = "com.example.other")
        assertTrue(
            PersistentDiscoveryCache.fingerprintOf(identity) !=
                PersistentDiscoveryCache.fingerprintOf(other),
        )
    }

    // ---- observer / invalidation reporting (rosetta-xposed#22) --------------

    @Test
    fun `a first run reports invalidation with hadPriorFingerprint false`() {
        // No fingerprint stored yet: the cache invalidates (clears nothing) and
        // reports a first run. This is the e2e's fresh-install signal.
        val observer = RecordingDiscoveryObserver()
        PersistentDiscoveryCache.create(FakeStore(), identity, observer)
        assertEquals(listOf(false), observer.invalidations())
    }

    @Test
    fun `a version_code bump reports invalidation with hadPriorFingerprint true`() {
        // The e2e's "bump versionCode → stale entry dropped → re-discovered"
        // assertion: a DIFFERENT build's fingerprint was present, so the flag is
        // true and the stale entry is gone.
        val store = FakeStore()
        PersistentDiscoveryCache.create(store, identity).put(real, entry)
        val observer = RecordingDiscoveryObserver()
        val cache = PersistentDiscoveryCache.create(store, identity.copy(versionCode = 101), observer)
        assertNull(cache.get(real))
        assertEquals(listOf(true), observer.invalidations())
    }

    @Test
    fun `a warm relaunch of the same build reports NO invalidation`() {
        // Same fingerprint → no invalidation event, so a "served from cache"
        // launch is cleanly distinguishable from an "invalidated" one.
        val store = FakeStore()
        PersistentDiscoveryCache.create(store, identity).put(real, entry)
        val observer = RecordingDiscoveryObserver()
        PersistentDiscoveryCache.create(store, identity, observer)
        assertTrue(observer.invalidations().isEmpty())
    }

    @Test
    fun `a throwing observer never breaks cache construction (fail-soft)`() {
        val throwing =
            object : DiscoveryObserver {
                override fun onOutcome(
                    realName: String,
                    obfName: String,
                    outcome: DiscoveryOutcome,
                ) = Unit

                override fun onCacheInvalidated(hadPriorFingerprint: Boolean): Unit = throw ObserverBlewUp()
            }
        // Construction (which invalidates on a first run) must still succeed.
        val cache = PersistentDiscoveryCache.create(FakeStore(), identity, throwing)
        cache.put(real, entry)
        assertEquals(entry, cache.get(real))
    }

    @Test
    fun `the fingerprint is stamped on first construction`() {
        val store = FakeStore()
        assertNull(store.getString(PersistentDiscoveryCache.FINGERPRINT_KEY))
        PersistentDiscoveryCache.create(store, identity)
        assertEquals(
            PersistentDiscoveryCache.fingerprintOf(identity),
            store.getString(PersistentDiscoveryCache.FINGERPRINT_KEY),
        )
    }
}
