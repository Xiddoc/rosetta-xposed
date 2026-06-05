/*
 * The Hooker glue — the SAME resolved Member, applied through TWO different
 * hook frameworks, to demonstrate that rosetta-xposed's [Hooker] seam is
 * genuinely framework-agnostic (RFC 0001 Decision 2: Rosetta resolves, the
 * developer owns the hook call).
 *
 * `legacy(...)` is fully wired and is what LegacyEntry uses in LOOP #1.
 * `libxposed(...)` is the modern-API counterpart — a reference sketch, since
 * the libxposed API surface varies by tag and was not compiled in the
 * scaffolding environment. Both produce a Rosetta [Unhook] from their
 * framework's own unhook handle; nothing about the resolution differs.
 */
package com.example.rosettamodule

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.xiddoc.rosetta.xposed.Hooker
import io.github.xiddoc.rosetta.xposed.Unhook

internal object RosettaHooks {
    /** Apply a legacy XposedBridge callback to the resolved member. */
    fun legacy(callback: XC_MethodHook): Hooker =
        Hooker { member ->
            val unhook = XposedBridge.hookMethod(member, callback)
            Unhook { unhook.unhook() }
        }

    // ---- Modern libxposed counterpart (reference sketch) --------------------
    //
    // The libxposed API hooks via the XposedInterface handed to your
    // XposedModule, e.g.:
    //
    //   fun libxposed(xposed: XposedInterface, hooker: Class<out Hooker>): Hooker =
    //       Hooker { member ->
    //           val unhooker = xposed.hook(member as Method, hooker)
    //           Unhook { unhooker.unhook() }
    //       }
    //
    // It is intentionally NOT compiled here (no libxposed on the classpath in
    // LOOP #1). Enable the libxposed dependency + repo and uncomment to wire
    // ModernEntry. The resolution call (`rosetta.method(...).hook(...)`) is
    // identical to the legacy path — only this glue changes.
}
