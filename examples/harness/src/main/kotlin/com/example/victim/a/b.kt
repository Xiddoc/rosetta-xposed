/*
 * Stand-in for an R8-obfuscated app class.
 *
 * Its real-world role is "TicketService" but, like a class that has been
 * through obfuscation, it is spelled `com.example.victim.a.b` with a
 * single-letter method `c`. The Rosetta map (maps/100.json) carries the
 * real <- obf translation; the walkthrough resolves the human name
 * `com.example.victim.TicketService#formatTicket` down to this `b#c`.
 *
 * Keeping the package under `com.example.victim.*` means it sits inside the
 * app's own namespace, so the C1 target guard allows it on a real device
 * without an escape hatch (the prefix is derived from the map's `app`).
 */
package com.example.victim.a

public class b {
    public fun c(input: String): String = "ticket:$input"
}
