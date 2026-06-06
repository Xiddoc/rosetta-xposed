/*
 * Proves the harness walkthrough end-to-end. This is the part of the example
 * that is verifiable WITHOUT an emulator, so it runs in ordinary CI and pins
 * that the bundled map resolves the documented real names to the right
 * obfuscated members.
 */
package com.example.victimhook

import io.github.xiddoc.rosetta.core.version.MatchedBy
import kotlin.test.Test
import kotlin.test.assertEquals

class WalkthroughTest {
    @Test
    fun `resolves the real name to the obfuscated member and invokes it`() {
        val r = Walkthrough.run()

        // Selected by the authoritative version_code key, not the label.
        assertEquals(MatchedBy.VERSION_CODE, r.matchedBy)

        // formatTicket -> obfuscated method `c` on `com.example.victim.a.b`.
        assertEquals("com.example.victim.a.b", r.obfClass)
        assertEquals("c", r.memberName)

        // Invoking the resolved member runs the real obfuscated method body.
        assertEquals("ticket:T-123", r.invoked)
    }
}
