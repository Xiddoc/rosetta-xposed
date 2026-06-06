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
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.xiddoc.rosetta.android.BundledMaps
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.RosettaXposed

class LegacyEntry : IXposedHookLoadPackage {
    @SuppressLint("PrivateApi")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        // TEMP (e2e diagnosis): LSPatch swallows any throwable from module load
        // as a stack-less NoClassDefFoundError, hiding the real cause. Log the
        // actual exception to logcat (tag RosettaErr) before letting it
        // propagate, so the e2e job can name the failing class/member.
        try {
            hookFormatTicket(lpparam)
        } catch (t: Throwable) {
            android.util.Log.e("RosettaErr", "rosetta module load failed", t)
            XposedBridge.log(t)
            throw t
        }
    }

    @SuppressLint("PrivateApi")
    private fun hookFormatTicket(lpparam: XC_LoadPackage.LoadPackageParam) {
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
        // `android.app.AndroidAppHelper` is a @hide framework class: it exists at
        // runtime in the app process but is absent from both the Xposed API stub
        // and the public android.jar, so it cannot be referenced at compile time.
        // Reach it reflectively through XposedHelpers (which IS in the API jar);
        // this is the awkwardness that makes early identity reads fiddly on the
        // legacy path. With a real Application we read the full identity (incl. the
        // signer set) via AndroidAppIdentity.of; otherwise we fall back to the
        // known selection key (fine here — maps/100.json pins no signer_sha256).
        val app =
            runCatching {
                val helper = XposedHelpers.findClass("android.app.AndroidAppHelper", null)
                XposedHelpers.callStaticMethod(helper, "currentApplication") as? Application
            }.getOrNull()
        val pm = app?.packageManager
        return if (pm != null) {
            AndroidAppIdentity.of(pm, lpparam.packageName)
        } else {
            AppIdentity(packageName = lpparam.packageName, versionCode = TARGET_VERSION_CODE)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.victim"
        const val TARGET_VERSION_CODE = 100L
    }
}
