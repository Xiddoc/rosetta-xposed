/*
 * DiscoveryHooks — the DYNAMIC (self-healing) example wiring (rosetta-xposed#22).
 *
 * Where [LegacyEntry] proves the STATIC path (`TicketService#formatTicket`,
 * `a.b#c`, which IS in the bundled map), this proves the DYNAMIC one:
 * `AuditService#auditTicket` (`c.d#e`) is DELIBERATELY ABSENT from
 * `maps/100.json`, so the static lookup MISSES and the binding falls through to
 * live DexKit discovery — the on-device path #22 validates.
 *
 * The flow, exactly as a real self-healing module would wire it:
 *
 *   1. Build a real [DexKitBridge] over the running app's APK (the device-only
 *      native), wrap it in [DexKitBackedIndex] (the only DexKit-importing file
 *      in the codebase, in :dexkit).
 *   2. Build a [PersistentDiscoveryCache] over the app's SharedPreferences via
 *      the ~3-line [SharedPreferencesStore] adapter and the running [identity]
 *      — so a discovery survives a process restart, and a version/signer bump
 *      invalidates it.
 *   3. Attach a [LogcatDiscoveryObserver] so each resolve emits a greppable
 *      marker (DISCOVERED / SERVED_FROM_CACHE / CACHE_INVALIDATED) the e2e
 *      asserts on.
 *   4. `fromMapWithDiscovery(... DiscoveryConfig(hints, cache, observer))`,
 *      then resolve `AuditService#auditTicket` by REAL name and hook it via the
 *      same framework-agnostic [RosettaHooks.legacy] seam the static path uses.
 *
 * The DiscoveryHints carry the ONLY thing that survives obfuscation: the stable
 * string anchor the victim's `c.d` class references (`AUDIT_ANCHOR`). DexKit
 * finds the class by that literal; an R8 rename of `c.d`/`e` is transparent.
 *
 * DEVICE-ONLY. This needs the real DexKit native loaded on ART against a live
 * APK; it cannot run on a plain JVM (the :dexkit integration test exercises the
 * adapter against a committed DEX fixture, but a physical-device run is what #22
 * adds). The OBSERVABILITY + cache + invalidation LOGIC it leans on is, by
 * contrast, all pure-JVM and unit-tested in the library. Any failure here is
 * caught and logged (never crashes the host app) so the static-path assertion
 * still runs even if discovery is unavailable on a given device.
 */
package com.example.rosettamodule

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.xiddoc.rosetta.android.PersistentDiscoveryCache
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.dexkit.DexKitBackedIndex
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.DiscoveryConfig
import io.github.xiddoc.rosetta.xposed.DiscoveryHints
import io.github.xiddoc.rosetta.xposed.MethodDiscoveryHint
import io.github.xiddoc.rosetta.xposed.RosettaXposed
import org.luckypray.dexkit.DexKitBridge

internal object DiscoveryHooks {
    /** Real name of the class deliberately absent from the bundled map. */
    private const val AUDIT_CLASS = "com.example.victim.AuditService"

    /** Real method name discovered + hooked on [AUDIT_CLASS]. */
    private const val AUDIT_METHOD = "auditTicket"

    /**
     * The stable string anchor discovery searches by. MUST equal the victim's
     * `com.example.victim.c.d.AUDIT_ANCHOR` — the cross-version contract that
     * lets DexKit find `c.d` without ever naming it. Duplicated as a literal
     * here (not referenced) because the module does not compile against the
     * victim.
     */
    private const val AUDIT_ANCHOR = "rosetta.audit.anchor.v1"

    /**
     * Wire and run the dynamic-path hook. [app] is the running victim
     * [Application] (needed for SharedPreferences + the APK path); [map] is the
     * already-loaded bundled map (the SAME one the static path uses; it simply
     * does not contain AuditService); [identity] is the running app identity;
     * [classLoader] is the app class loader targets are realised through.
     *
     * Fail-soft: any discovery/DexKit error is logged and swallowed so the
     * static-path assertion is unaffected.
     */
    fun install(
        app: Application,
        map: RosettaMap,
        identity: AppIdentity,
        classLoader: ClassLoader,
    ) {
        runCatching {
            // 1. Real DexKit over the running APK. Owned here (Closeable) — but
            // discovery happens eagerly below, BEFORE we close it, so the
            // adapter never outlives the bridge.
            val apkPath = app.applicationInfo.sourceDir
            DexKitBridge.create(apkPath).use { bridge ->
                val index = DexKitBackedIndex(bridge)

                // 2. Persistent cache + 3. observer. The observer fires
                // CACHE_INVALIDATED here if the app changed since last launch.
                val observer = LogcatDiscoveryObserver()
                val prefs = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                val cache =
                    PersistentDiscoveryCache.create(SharedPreferencesStore(prefs), identity, observer)

                // 4. Self-healing binding. AuditService is not in `map`, so this
                // resolves it by the anchor below.
                val rosetta =
                    RosettaXposed.fromMapWithDiscovery(
                        map = map,
                        index = index,
                        classLoader = classLoader,
                        identity = identity,
                        discovery =
                            DiscoveryConfig(
                                hints =
                                    mapOf(
                                        AUDIT_CLASS to
                                            DiscoveryHints(
                                                // Locate the class by the stable anchor it references.
                                                anchors = listOf(AUDIT_ANCHOR),
                                                // Locate `auditTicket` within it by the SAME anchor
                                                // string the method body references + its return type.
                                                // Both survive an R8 rename of `c.d`/`e`.
                                                methods =
                                                    listOf(
                                                        MethodDiscoveryHint(
                                                            realName = AUDIT_METHOD,
                                                            returnType = "java.lang.String",
                                                            paramTypes = listOf("java.lang.String"),
                                                            usingStrings = listOf(AUDIT_ANCHOR),
                                                        ),
                                                    ),
                                            ),
                                    ),
                                cache = cache,
                                observer = observer,
                            ),
                    )

                // Resolve by REAL name (triggers discovery / a cache hit, which
                // emits the resolve-path marker) and hook via the same seam the
                // static path uses. The hook rewrites the result to DHOOKED(...).
                rosetta
                    .method(AUDIT_CLASS, AUDIT_METHOD)
                    .hook(
                        RosettaHooks.legacy(
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    param.result = "DHOOKED(${param.result})"
                                }
                            },
                        ),
                    )
                XposedBridge.log("rosetta-example: dynamic-path hook installed for $AUDIT_CLASS.$AUDIT_METHOD")
            }
        }.onFailure { e ->
            // Device-only path: never crash the host on a discovery failure.
            XposedBridge.log("rosetta-example: dynamic discovery unavailable: ${e.message}")
        }
    }

    /** SharedPreferences file the persistent discovery cache lives in. */
    private const val CACHE_PREFS = "rosetta_disc_cache"
}
