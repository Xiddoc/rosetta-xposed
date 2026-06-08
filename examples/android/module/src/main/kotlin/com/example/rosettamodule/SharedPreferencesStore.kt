/*
 * The irreducible Android edge of the persistent discovery cache
 * (rosetta-xposed#19): a ~handful-of-lines [KeyValueStore] over
 * `android.content.SharedPreferences`.
 *
 * Everything testable about the cache — JSON (de)serialization of a discovered
 * entry and the `(app, version_code, signer)` invalidation — lives in the
 * pure-JVM, 100%-covered `:android-runtime` `PersistentDiscoveryCache`. The ONE
 * thing that genuinely needs `android.*` is reading/writing the backing store,
 * so that (and only that) lives here in the consuming module, exactly like
 * `AndroidAppIdentity` keeps the irreducible `PackageManager` read out of the
 * gated modules.
 *
 * Wiring it into a self-healing module:
 *
 *     val prefs = context.getSharedPreferences("rosetta_disc_cache", Context.MODE_PRIVATE)
 *     val cache = PersistentDiscoveryCache.create(SharedPreferencesStore(prefs), identity)
 *     val rosetta = RosettaXposed.fromMapWithDiscovery(
 *         map, index, lpparam.classLoader, identity,
 *         discovery = DiscoveryConfig(hints = hints, cache = cache),
 *     )
 *
 * NOTE on storage location: an Xposed module's `SharedPreferences` here lives in
 * the HOOKED app's data dir, which that app could in principle tamper with. That
 * is fine — `PersistentDiscoveryCache` treats an undecodable value as a miss,
 * and every restored obfuscated FQN is still routed through Rosetta's C1 target
 * guard before any class load, so a poisoned cache cannot widen the trust
 * surface (it can at worst force a re-discovery).
 */
package com.example.rosettamodule

import android.content.SharedPreferences
import io.github.xiddoc.rosetta.android.KeyValueStore

internal class SharedPreferencesStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys
}
