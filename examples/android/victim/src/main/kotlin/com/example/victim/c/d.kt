/*
 * A SECOND "obfuscated" client class — the DYNAMIC-path counterpart to
 * `a.b#c` (rosetta-xposed#22). Its real role is an AuditService; it is spelled
 * `c.d#e` to mimic obfuscator output, exactly like `a.b#c`.
 *
 * The crucial difference: this class is DELIBERATELY ABSENT from the bundled
 * map (`maps/100.json` carries only TicketService). So when the module asks to
 * hook `AuditService#auditTicket` by real name, the static lookup MISSES and
 * the binding must fall through to live DexKit discovery — the on-device path
 * #22 exists to validate.
 *
 * Discovery needs a stable, cross-version SIGNATURE to find this class by,
 * since its obfuscated name (`c.d`) is exactly what rotates. We give it one: a
 * stable string literal [AUDIT_ANCHOR] that the class references. The module's
 * DiscoveryHints(anchors = [AUDIT_ANCHOR]) point DexKit at it; an R8 rename of
 * `c.d`/`e` does not touch the literal, so discovery still locates the class.
 */
package com.example.victim.c

class d {
    fun e(input: String): String {
        // Reference the stable anchor so it lands in the dex string pool and
        // DexKit's findClassByAnchors can locate this class by it. Concatenated
        // into the result so R8 cannot prove it dead and strip it.
        return "audit[$AUDIT_ANCHOR]:$input"
    }

    companion object {
        /**
         * The stable string anchor discovery searches by. It is part of the
         * library contract between the victim and the module's DiscoveryHints —
         * keep the two in sync. Survives obfuscation (R8 does not rewrite string
         * literals), which is the whole point.
         */
        const val AUDIT_ANCHOR: String = "rosetta.audit.anchor.v1"
    }
}
