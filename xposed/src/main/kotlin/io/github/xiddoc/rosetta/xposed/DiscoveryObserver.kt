/*
 * DiscoveryObserver — the observability seam for the dynamic (self-healing)
 * backend (rosetta-xposed#22).
 *
 * The dynamic backend already distinguishes THREE resolution outcomes
 * internally — a fresh DexKit scan, a hit served from the persistent cache,
 * and (one layer up, in [io.github.xiddoc.rosetta.android.PersistentDiscoveryCache])
 * a stale cache dropped on an app update — but it had no way to SURFACE which
 * one happened. The on-device e2e (#22) needs exactly that signal: it asserts
 * via logcat that a static miss DISCOVERED a name, that a relaunch was SERVED
 * FROM the cache without re-scanning, and that a version bump INVALIDATED the
 * stale entry and re-discovered.
 *
 * Rather than scatter ad-hoc log strings through the backend (untestable, and
 * a violation of the "small, well-named, testable abstraction" bar), this seam
 * funnels every outcome through a single observer. The example module turns
 * each [DiscoveryOutcome] into a logcat marker the e2e greps for; tests assert
 * the SAME outcomes on a plain JVM with the [FakeDexKitIndex] + in-memory
 * stores. Resolution behaviour is unchanged — the observer is a side-channel.
 *
 * THREAD SAFETY. A self-healing module may discover from several hooked threads
 * at once (the backend runs inside the target app), so an implementation MUST
 * tolerate concurrent [onOutcome] calls. The bundled [RecordingDiscoveryObserver]
 * synchronizes; [NOOP] is trivially safe.
 *
 * FAIL-SOFT. Observability must never break resolution: an observer that throws
 * would turn a successful discovery into a failure. The backend invokes the
 * observer through [safe], which swallows any observer exception (the outcome is
 * a side-channel, not part of the resolve contract).
 */
package io.github.xiddoc.rosetta.xposed

/**
 * How a real name's obfuscated mapping was obtained on a given resolve. The
 * three cases mirror the dynamic backend's internal control flow.
 */
public enum class DiscoveryOutcome {
    /**
     * The name was DISCOVERED fresh: a static-map miss fell through to a live
     * DexKit scan that located the class/method. This is the expensive path
     * the persistent cache exists to amortise across restarts.
     */
    DISCOVERED,

    /**
     * The name was SERVED FROM the persistent cache: a discovery written by an
     * earlier process was read back, so NO DexKit scan ran this launch. The
     * "relaunch is cheap" proof.
     */
    SERVED_FROM_CACHE,
}

/**
 * Receives a [DiscoveryOutcome] each time the dynamic backend resolves a real
 * name through discovery (not via the static map — a pure static hit never
 * reaches the dynamic backend, so no outcome is emitted for it). The in-process
 * memo is transparent: once a name has produced an outcome this process, a
 * repeat lookup is served from the memo and emits NOTHING (the outcome already
 * fired for the first, authoritative resolution).
 *
 * Cache INVALIDATION is reported separately, at cache-construction time, via
 * [onCacheInvalidated] — it is a one-shot per-process event about the WHOLE
 * cache (the app changed), not a per-name resolution outcome.
 */
public interface DiscoveryObserver {
    /**
     * A real name resolved through discovery with the given [outcome].
     *
     * @param realName the real fully-qualified class name that resolved.
     * @param obfName the obfuscated FQN it resolved to.
     * @param outcome whether it was a fresh scan or a cache hit.
     */
    public fun onOutcome(
        realName: String,
        obfName: String,
        outcome: DiscoveryOutcome,
    )

    /**
     * The persistent cache was INVALIDATED at construction because the running
     * app's `(app, version_code, signer)` fingerprint differs from the stored
     * one (an update bumped the version_code, the signer changed, or this is a
     * first run). Every previously cached entry was dropped, so the next
     * resolve of each name will be a fresh [DiscoveryOutcome.DISCOVERED].
     *
     * Emitted at most once per cache instance, BEFORE any resolution. A first
     * run (no prior fingerprint) also invalidates — the consumer distinguishes
     * "fresh install" from "update" by [hadPriorFingerprint] if it cares.
     *
     * @param hadPriorFingerprint false on a first run (no fingerprint stored
     *   yet), true when a DIFFERENT build's fingerprint was found and cleared
     *   (a genuine update / signer change).
     */
    public fun onCacheInvalidated(hadPriorFingerprint: Boolean)

    public companion object {
        /** An observer that ignores every signal — the default. */
        public val NOOP: DiscoveryObserver =
            object : DiscoveryObserver {
                override fun onOutcome(
                    realName: String,
                    obfName: String,
                    outcome: DiscoveryOutcome,
                ) {
                    // Intentionally no-op.
                }

                override fun onCacheInvalidated(hadPriorFingerprint: Boolean) {
                    // Intentionally no-op.
                }
            }

        /**
         * Invoke [block] on [observer], swallowing any exception it throws.
         * Observability is a side-channel: an observer must never be able to
         * fail a discovery. Used by the dynamic backend at every emit point and
         * by `:xposed-android`'s `PersistentDiscoveryCache` when it reports an
         * invalidation, so it is `public` (cross-module).
         */
        public fun safe(
            observer: DiscoveryObserver,
            block: (DiscoveryObserver) -> Unit,
        ) {
            try {
                block(observer)
            } catch (_: Throwable) {
                // Deliberately swallowed — see KDoc / FAIL-SOFT above.
            }
        }
    }
}

/**
 * A [DiscoveryObserver] that records every signal in memory for assertions.
 * The reference implementation of the seam — the example module wraps a thin
 * logcat observer instead, but the SAME outcomes are exercised here on a plain
 * JVM, which is where #22's testable value lives.
 *
 * THREAD SAFETY. Records under a single lock so concurrent discovery threads
 * produce a consistent [outcomes] / [invalidations] snapshot.
 */
public class RecordingDiscoveryObserver : DiscoveryObserver {
    /** One recorded [onOutcome] call. */
    public data class Outcome(
        val realName: String,
        val obfName: String,
        val outcome: DiscoveryOutcome,
    )

    private val lock = Any()
    private val recordedOutcomes = mutableListOf<Outcome>()
    private val recordedInvalidations = mutableListOf<Boolean>()

    override fun onOutcome(
        realName: String,
        obfName: String,
        outcome: DiscoveryOutcome,
    ) {
        synchronized(lock) { recordedOutcomes += Outcome(realName, obfName, outcome) }
    }

    override fun onCacheInvalidated(hadPriorFingerprint: Boolean) {
        synchronized(lock) { recordedInvalidations += hadPriorFingerprint }
    }

    /** A snapshot of every [onOutcome] call, in order. */
    public fun outcomes(): List<Outcome> = synchronized(lock) { recordedOutcomes.toList() }

    /**
     * A snapshot of every [onCacheInvalidated] call (each element is its
     * `hadPriorFingerprint` flag), in order.
     */
    public fun invalidations(): List<Boolean> = synchronized(lock) { recordedInvalidations.toList() }
}
