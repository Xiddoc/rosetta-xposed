/*
 * LogcatDiscoveryObserver — the example module's thin adapter from the pure-JVM
 * [DiscoveryObserver] seam (rosetta-xposed#22) to logcat markers the e2e greps.
 *
 * The OBSERVABILITY LOGIC — distinguishing a fresh DexKit scan from a cache hit,
 * and reporting an invalidation on a version/signer change — lives in the
 * library and is fully unit-tested on a plain JVM (DiscoveryObserverTest,
 * PersistentDiscoveryCacheTest). This class is the irreducible Android edge:
 * it only formats each outcome as a `Log.i` line, exactly like
 * `SharedPreferencesStore` is the irreducible edge of the persistent cache.
 *
 * The markers (greppable, one token each so a dash `grep -F` matches):
 *
 *   - DISCOVERED        — a static miss fell through to a live DexKit scan.
 *   - SERVED_FROM_CACHE — a relaunch read the discovery back; NO scan ran.
 *   - CACHE_INVALIDATED — a version/signer change (or first run) dropped the
 *                         stale cache; the next resolve will re-discover.
 *
 * Each line is emitted under [DISCOVERY_TAG] so the e2e can assert the resolve
 * PATH (scan vs cache) independently of the HOOK firing (`DHOOKED(...)` under
 * the victim's own `RosettaVictimDyn` tag).
 */
package com.example.rosettamodule

import android.util.Log
import de.robv.android.xposed.XposedBridge
import io.github.xiddoc.rosetta.xposed.DiscoveryObserver
import io.github.xiddoc.rosetta.xposed.DiscoveryOutcome
import io.github.xiddoc.rosetta.xposed.InvalidationReason

internal class LogcatDiscoveryObserver : DiscoveryObserver {
    override fun onOutcome(
        realName: String,
        obfName: String,
        outcome: DiscoveryOutcome,
    ) {
        // Marker chosen so the e2e greps one stable token per outcome.
        val marker =
            when (outcome) {
                DiscoveryOutcome.DISCOVERED -> "DISCOVERED"
                DiscoveryOutcome.SERVED_FROM_CACHE -> "SERVED_FROM_CACHE"
            }
        val line = "$marker $realName -> $obfName"
        Log.i(DISCOVERY_TAG, line)
        // Also surface via the Xposed bridge log so it shows even if the app's
        // own logcat tag is filtered.
        XposedBridge.log("rosetta-example: $line")
    }

    override fun onCacheInvalidated(reason: InvalidationReason) {
        // Marker text kept IDENTICAL to the historical strings so the e2e greps
        // don't move; the exhaustive `when` just maps the typed reason to them.
        val kind =
            when (reason) {
                InvalidationReason.FINGERPRINT_CHANGED -> "version-or-signer-change"
                InvalidationReason.FIRST_RUN -> "first-run"
            }
        val line = "CACHE_INVALIDATED ($kind)"
        Log.i(DISCOVERY_TAG, line)
        XposedBridge.log("rosetta-example: $line")
    }

    companion object {
        /** logcat tag the e2e greps for the resolve-path markers. */
        const val DISCOVERY_TAG: String = "RosettaDiscovery"
    }
}
