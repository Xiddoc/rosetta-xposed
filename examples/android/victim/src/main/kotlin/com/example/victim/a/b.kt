/*
 * The "obfuscated" client class — identical in spirit to the harness
 * stand-in. Its real role is a TicketService; it is spelled `a.b#c` to mimic
 * obfuscator output. The Rosetta map (module's maps/100.json) carries the
 * real <- obf translation so the module hooks `c` without ever naming it.
 */
package com.example.victim.a

class b {
    fun c(input: String): String = "ticket:$input"
}
