/*
 * DiscoveryCache — a persistence seam for dynamic (DexKit) discovery results
 * (rosetta-xposed#19).
 *
 * The dynamic backend's default [DiscoverySink] is NOOP, and its in-memory
 * memo only lives for ONE process: every app restart re-runs the expensive
 * DexKit scan from scratch. A [DiscoveryCache] lets a discovered
 * `realName -> ClassEntry` mapping survive across restarts, so the second and
 * subsequent launches skip the scan.
 *
 * This seam is deliberately PURE-JVM and tiny — get/put keyed by real name —
 * so it stays unit-testable without an emulator and `:core`/`:xposed` keep
 * their `android.jar`-free, 100%-covered invariant. The default is [NOOP]
 * (no persistence, exactly today's behaviour); [InMemoryDiscoveryCache] is a
 * fully-tested non-persistent implementation; the on-device,
 * `SharedPreferences`-backed implementation lives at the Android edge in
 * `:android-runtime` (`PersistentDiscoveryCache`), where the
 * `(app, version_code, signer)` invalidation that a persistent store needs is
 * applied — mirroring how `AppIdentity` assembly keeps the irreducible Android
 * call in the consumer.
 *
 * SCOPING + INVALIDATION. The cache instance is bound to ONE app identity for
 * its lifetime, so [get] / [put] are keyed by real name alone; the
 * `(app, version_code, signer)` scoping — and the invalidation that drops a
 * stale cache when the app updates — is the concrete (persistent)
 * implementation's responsibility, applied once when it is constructed, not on
 * every lookup. A discovered FQN read back from the cache is NOT trusted
 * blindly: it is realised through the same C1 target guard as a static name, so
 * a tampered store cannot widen the trust surface.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.ClassEntry

/**
 * A persistence seam the dynamic backend consults BEFORE scanning and writes
 * to AFTER a successful discovery. Keyed by real fully-qualified name within
 * the cache's bound app identity.
 */
public interface DiscoveryCache {
    /** The cached entry for [realName], or `null` on a miss. */
    public fun get(realName: String): ClassEntry?

    /** Persist [entry] under [realName] for a future lookup (this run or a later restart). */
    public fun put(
        realName: String,
        entry: ClassEntry,
    )

    public companion object {
        /**
         * A cache that persists nothing — every [get] misses and [put] is a
         * no-op. The default, preserving today's behaviour (the in-memory memo
         * in the dynamic backend still amortises within a single process).
         */
        public val NOOP: DiscoveryCache =
            object : DiscoveryCache {
                override fun get(realName: String): ClassEntry? = null

                override fun put(
                    realName: String,
                    entry: ClassEntry,
                ) {
                    // Intentionally no-op: no persistence in the default.
                }
            }
    }
}

/**
 * A process-lifetime, in-memory [DiscoveryCache]. It does NOT survive a
 * restart (use the `:android-runtime` `PersistentDiscoveryCache` for that), but
 * it is the fully-tested reference implementation of the seam and is useful for
 * sharing discoveries between several backend instances in one process.
 *
 * THREAD SAFETY. A self-healing module may discover from several hooked threads
 * at once, so reads and writes are guarded by a single lock.
 */
public class InMemoryDiscoveryCache : DiscoveryCache {
    private val lock = Any()
    private val entries = mutableMapOf<String, ClassEntry>()

    override fun get(realName: String): ClassEntry? = synchronized(lock) { entries[realName] }

    override fun put(
        realName: String,
        entry: ClassEntry,
    ) {
        synchronized(lock) { entries[realName] = entry }
    }

    /** A snapshot of everything cached so far, keyed by real name. */
    public fun snapshot(): Map<String, ClassEntry> = synchronized(lock) { entries.toMap() }
}
