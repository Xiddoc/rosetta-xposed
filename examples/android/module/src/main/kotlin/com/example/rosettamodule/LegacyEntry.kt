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
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.xiddoc.rosetta.android.BundledMaps
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.RosettaXposed

class LegacyEntry : IXposedHookLoadPackage {
    @SuppressLint("PrivateApi")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        val map = BundledMaps.load("$TARGET_VERSION_CODE.json")

        // Identity: read it properly from PackageManager when we can reach a
        // context; otherwise fall back to the known selection key. Reading the
        // signer set requires a PackageManager, which is awkward this early in
        // legacy handleLoadPackage — that awkwardness is itself a gap worth
        // noting. ModernEntry shows the clean libxposed path.
        val identity = identityOrFallback(lpparam)

        val rosetta = RosettaXposed.fromRegistry(MapRegistry.of(map), identity, lpparam.classLoader)
        if (rosetta == null) {
            XposedBridge.log("rosetta-example: no map for version_code ${identity.versionCode}; skipping")
            return
        }

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
    }

    private fun identityOrFallback(lpparam: XC_LoadPackage.LoadPackageParam): AppIdentity {
        val app = runCatching { de.robv.android.xposed.AndroidAppHelper.currentApplication() }.getOrNull()
        val pm = app?.packageManager
        return if (pm != null) {
            AndroidAppIdentity.of(pm, lpparam.packageName)
        } else {
            // currentApplication() is null very early in the process; fall back
            // to the selection key. A map pinning a signer_sha256 would need the
            // PackageManager path above (or it fails closed) — see README.
            AppIdentity(packageName = lpparam.packageName, versionCode = TARGET_VERSION_CODE)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.victim"
        const val TARGET_VERSION_CODE = 100L
    }
}
