/*
 * Persistent, on-device discovery cache (rosetta-xposed#19) — the Android-edge
 * implementation of the pure-JVM [DiscoveryCache] seam.
 *
 * The dynamic (DexKit) backend re-discovers obfuscated names from scratch on
 * every process start because its only durable home was NOOP. This caches each
 * discovered `realName -> ClassEntry` in a key/value store so the next launch
 * reads it back instead of re-scanning.
 *
 * WHY THE LOGIC LIVES HERE (and not the SharedPreferences object): exactly like
 * [AndroidIdentities] keeps the irreducible `PackageManager` read in the
 * consumer while hosting the testable hashing/assembly here, this class keeps
 * the irreducible `android.content.SharedPreferences` object in the consumer
 * (behind the tiny [KeyValueStore] seam) and hosts the parts that ARE pure and
 * fully testable: JSON (de)serialization of an entry and — the piece a
 * persistent store genuinely needs — INVALIDATION when the app changes. So
 * `:core`/`:xposed`/`:xposed-android` all stay `android.jar`-free and inside the
 * 100% Kover gate; the consumer writes a ~3-line `SharedPreferences` adapter
 * (see the example module's `SharedPreferencesStore`).
 *
 * INVALIDATION (the correctness crux). A map is selected by `version_code` and
 * guarded by `signer_sha256`; a cached obfuscated name is only valid for the
 * exact build it was discovered against. So the cache is stamped with a
 * FINGERPRINT of `(app, version_code, signer set)` taken from the running
 * [AppIdentity] at construction: if the stored fingerprint differs (an app
 * update bumped the version_code, or the signer changed, or this is a first
 * run) every cached entry is dropped before the cache is used. A stale mapping
 * therefore cannot survive an update. Trust is still defence-in-depth: a
 * restored FQN is realised through the same C1 target guard as a static name,
 * so even a tampered store cannot widen the trust surface.
 */
package io.github.xiddoc.rosetta.android

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.ClassEntryCodec
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.DiscoveryCache
import io.github.xiddoc.rosetta.xposed.DiscoveryObserver

/**
 * The minimal string key/value store the persistent cache needs. A consumer
 * implements it over `android.content.SharedPreferences` in ~3 lines (the only
 * irreducible Android call); tests implement it with a plain map. Kept here (a
 * pure-JVM module) so the cache logic is fully unit-testable without an
 * emulator.
 */
public interface KeyValueStore {
    /** The value stored under [key], or `null` if absent. */
    public fun getString(key: String): String?

    /** Store [value] under [key], replacing any previous value. */
    public fun putString(
        key: String,
        value: String,
    )

    /** Remove [key] (a no-op if absent). */
    public fun remove(key: String)

    /** A snapshot of every key currently present (used to clear stale entries). */
    public fun keys(): Set<String>
}

/**
 * A [DiscoveryCache] backed by a [KeyValueStore], invalidated whenever the
 * running app's `(app, version_code, signer)` fingerprint changes. Construct it
 * with [create], which performs the one-time invalidation check; the resulting
 * cache is then keyed by real name alone (its identity is fixed for the run).
 */
public class PersistentDiscoveryCache private constructor(
    private val store: KeyValueStore,
) : DiscoveryCache {
    override fun get(realName: String): ClassEntry? {
        val raw = store.getString(entryKey(realName)) ?: return null
        val entry = ClassEntryCodec.decodeOrNull(raw)
        if (entry == null) {
            // A corrupt / schema-drifted value is a miss: drop it so a later put
            // can replace it and the backend simply re-discovers this run.
            store.remove(entryKey(realName))
            return null
        }
        return entry
    }

    override fun put(
        realName: String,
        entry: ClassEntry,
    ) {
        store.putString(entryKey(realName), ClassEntryCodec.encode(entry))
    }

    private fun entryKey(realName: String): String = ENTRY_PREFIX + realName

    public companion object {
        /** The store key holding the `(app, version_code, signer)` fingerprint. */
        internal const val FINGERPRINT_KEY: String = "io.github.xiddoc.rosetta.cache.fingerprint"

        /** Prefix namespacing a cached entry's key from the fingerprint (and any consumer keys). */
        internal const val ENTRY_PREFIX: String = "io.github.xiddoc.rosetta.cache.entry."

        /**
         * Build a cache over [store] for the running app [identity], dropping
         * any entries left over from a DIFFERENT build first (an app update, a
         * signer change, or a first run all invalidate). After this returns the
         * store holds only entries valid for [identity].
         *
         * When the stale entries are cleared, [observer]'s
         * [DiscoveryObserver.onCacheInvalidated] fires once (rosetta-xposed#22)
         * — the e2e's "version bump → stale entry dropped → re-discovered"
         * signal. It is NOT called when the fingerprint already matched (a warm
         * relaunch of the same build), so a "served from cache" launch is
         * cleanly distinguishable from an "invalidated" one. The flag reports
         * whether a DIFFERENT build's fingerprint was found (true) versus a
         * first run with none stored yet (false). [observer] defaults to NOOP,
         * preserving the existing call sites.
         */
        public fun create(
            store: KeyValueStore,
            identity: AppIdentity,
            observer: DiscoveryObserver = DiscoveryObserver.NOOP,
        ): PersistentDiscoveryCache {
            invalidateIfStale(store, fingerprintOf(identity), observer)
            return PersistentDiscoveryCache(store)
        }

        /**
         * A stable fingerprint of the identity fields that must invalidate the
         * cache when they change: the package, the authoritative `version_code`
         * selection key, and the signing-cert set (sorted so set order is
         * irrelevant). Two runs of the SAME build produce the same string;
         * any version_code or signer change produces a different one.
         */
        internal fun fingerprintOf(identity: AppIdentity): String {
            val signers = identity.signerSha256s.sorted().joinToString(",")
            return "${identity.packageName}|${identity.versionCode}|$signers"
        }

        private fun invalidateIfStale(
            store: KeyValueStore,
            fingerprint: String,
            observer: DiscoveryObserver,
        ) {
            val stored = store.getString(FINGERPRINT_KEY)
            if (stored == fingerprint) return
            // First run or a changed build: drop every cached entry so a stale
            // mapping can't survive, then stamp the new fingerprint. Only the
            // entry keys are cleared (the fingerprint key and any unrelated
            // consumer keys in the same store are left untouched).
            store
                .keys()
                .filter { it.startsWith(ENTRY_PREFIX) }
                .forEach { store.remove(it) }
            store.putString(FINGERPRINT_KEY, fingerprint)
            // Report the invalidation (#22). `stored != null` means a DIFFERENT
            // build's fingerprint was present — a genuine update / signer change
            // — versus a first run with nothing stored. Routed through `safe` so
            // a throwing observer can't break cache construction.
            DiscoveryObserver.safe(observer) { it.onCacheInvalidated(hadPriorFingerprint = stored != null) }
        }
    }
}
