/*
 * Hooker — the seam by which the developer's chosen hook framework owns
 * the actual hook call (RFC 0001 Decision 2: "It does NOT own the hook
 * call").
 *
 * rosetta-xposed resolves a real name to a concrete `java.lang.reflect`
 * [java.lang.reflect.Member] and hands it to a [Hooker]. The developer
 * implements the SAM with whatever they already use:
 *
 *   // modern libxposed API:
 *   rosetta.method("com.example.app.RemoteServiceClient", "requestTicket")
 *       .hook { member -> hookParam.hook(member as Method, beforeAfter) }
 *
 *   // legacy XposedBridge:
 *   rosetta.method("com.example.app.RemoteServiceClient", "requestTicket")
 *       .hook { member -> XposedBridge.hookMethod(member, methodHook) }
 *
 * The returned [Unhook] is opaque to rosetta-xposed; it just carries the
 * framework's unhook handle back to the caller.
 */
package io.github.xiddoc.rosetta.xposed

import java.lang.reflect.Member

/** Opaque handle the developer's framework returns to undo a hook. */
public fun interface Unhook {
    public fun unhook()
}

/** Applies a hook to a resolved [Member]; returns an [Unhook] or null. */
public fun interface Hooker {
    public fun hook(member: Member): Unhook?
}
