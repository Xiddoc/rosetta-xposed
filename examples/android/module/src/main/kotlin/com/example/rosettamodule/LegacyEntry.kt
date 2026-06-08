/*
 * The live LSPosed entry point (LOOP #1), registered via assets/xposed_init.
 *
 * On load into the victim app it:
 *   1. reads the running app's identity from PackageManager,
 *   2. selects + loads the bundled map by version_code (signer guard runs
 *      fail-closed inside fromRegistry — a no-op here since maps/100.json pins
 *      no signer_sha256),
 *   3. resolves `com.example.victim.TicketService#formatTicket` by REAL name,
 *   4. hands the resolved Member to XposedBridge via the Rosetta Hooker.
 *
 * The hook rewrites the result so the effect is visible in the victim UI:
 * `ticket:T-123` becomes `HOOKED(ticket:T-123)`. Note there is not a single
 * hard-coded obfuscated name (`a.b`, `c`) anywhere in this file — that is the
 * whole point.
 */
package com.example.rosettamodule

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.xiddoc.rosetta.android.BundledMaps
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.RosettaXposed
import java.util.concurrent.atomic.AtomicBoolean

class LegacyEntry : IXposedHookLoadPackage {
    /**
     * Guards the dynamic-path install so it runs EXACTLY ONCE even though
     * `Application#onCreate` may be invoked more than once across the process
     * lifetime (and our after-callback fires on every invocation).
     */
    private val dynamicInstalled = AtomicBoolean(false)

    @SuppressLint("PrivateApi")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        // Identity for the STATIC path: there is NO running Application this early
        // in handleLoadPackage, so we use the known selection key directly. That
        // is fine here — maps/{100,101}.json pin no signer_sha256, so the signer
        // guard is a no-op. The DYNAMIC path below reads the full identity (incl.
        // the signer set) from a real Application once one exists.
        val staticIdentity = AppIdentity(packageName = lpparam.packageName, versionCode = TARGET_VERSION_CODE)

        // Select the bundled map by the version_code; the version-bump APK (101)
        // is detected in the deferred dynamic path, but the static map contents
        // are identical across 100/101 (both carry TicketService, omit
        // AuditService), so the static hook uses the base map unconditionally.
        val map = BundledMaps.load("$TARGET_VERSION_CODE.json")

        val rosetta = RosettaXposed.fromRegistry(MapRegistry.of(map), staticIdentity, lpparam.classLoader)
        if (rosetta == null) {
            XposedBridge.log("rosetta-example: no map for version_code ${staticIdentity.versionCode}; skipping")
            return
        }

        // STATIC path: TicketService IS in the bundled map. Installed eagerly in
        // handleLoadPackage — it does not need a Context.
        rosetta
            .method("com.example.victim.TicketService", "formatTicket")
            .hook(
                RosettaHooks.legacy(
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = "HOOKED(${param.result})"
                        }
                    },
                ),
            )
        XposedBridge.log("rosetta-example: hooked TicketService.formatTicket in ${lpparam.packageName}")

        // DYNAMIC path (#22): AuditService is ABSENT from the map, so it is
        // resolved by live DexKit discovery — which needs a real Application for
        // SharedPreferences (the persistent cache) + the APK path (DexKit scans).
        // That Application does NOT exist yet during handleLoadPackage, so we
        // DEFER the install to the app's own lifecycle: hook Application#onCreate
        // and run discovery in the after-callback, when a real Context is up.
        // This runs before MainActivity.onCreate (the Application is created
        // first), so the discovered AuditService hook is in place by the time the
        // victim calls AuditService#auditTicket.
        deferDynamicInstall(lpparam.classLoader)
    }

    /**
     * Schedule [DiscoveryHooks.install] to run once a real [Application] /
     * [Context] is available, by hooking `Application#onCreate` on the app class
     * loader and installing discovery in the after-callback. The install is
     * guarded by [dynamicInstalled] so it happens exactly once even if onCreate
     * fires multiple times. Wrapped in `runCatching` so a wiring failure can
     * never crash the victim — discovery itself is independently fail-soft.
     */
    private fun deferDynamicInstall(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        installDynamicOnce(app, classLoader)
                    }
                },
            )
        }.onFailure { e ->
            XposedBridge.log("rosetta-example: could not defer dynamic-path discovery: ${e.message}")
        }
    }

    /**
     * Run the dynamic-path install for [app] exactly once. Reads the full
     * identity from the now-available [Context], selects the map by the DETECTED
     * version_code (so the version-bump APK's `101.json` is used and its bumped
     * cache fingerprint invalidates correctly), and hands off to
     * [DiscoveryHooks.install] (itself fail-soft).
     */
    private fun installDynamicOnce(
        app: Application,
        classLoader: ClassLoader,
    ) {
        if (!dynamicInstalled.compareAndSet(false, true)) return
        runCatching {
            val identity = identityFor(app, app.packageName)
            val mapVersion =
                if (identity.versionCode == BUMPED_VERSION_CODE) BUMPED_VERSION_CODE else TARGET_VERSION_CODE
            val map = BundledMaps.load("$mapVersion.json")
            DiscoveryHooks.install(app, map, identity, classLoader)
        }.onFailure { e ->
            XposedBridge.log("rosetta-example: dynamic-path wiring failed: ${e.message}")
        }
    }

    /**
     * Read the full [AppIdentity] (incl. the signer set) from the running app's
     * [PackageManager] when reachable; otherwise fall back to the known selection
     * key (fine here — the bundled maps pin no signer_sha256).
     */
    private fun identityFor(
        context: Context,
        packageName: String,
    ): AppIdentity {
        val pm = context.packageManager
        return if (pm != null) {
            AndroidAppIdentity.of(pm, packageName)
        } else {
            AppIdentity(packageName = packageName, versionCode = TARGET_VERSION_CODE)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.victim"
        const val TARGET_VERSION_CODE = 100L

        /** The e2e's version-bump APK (#22) ships this versionCode; its map is `101.json`. */
        const val BUMPED_VERSION_CODE = 101L
    }
}
