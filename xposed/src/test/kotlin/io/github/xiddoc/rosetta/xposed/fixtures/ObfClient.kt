/*
 * A stand-in for an obfuscated app class. Its real JVM name plays the role
 * of the "obfuscated" name a map points at, and its members carry the exact
 * descriptors the binding test asserts against. Methods `c` / `d` mimic
 * single-letter obfuscated names; `d` is overloaded to exercise descriptor
 * disambiguation.
 */
package io.github.xiddoc.rosetta.xposed.fixtures

@Suppress("unused")
class ObfClient {
    @JvmField var a: String = ""

    // A no-arg constructor plus a (String) one. The binding coverage test
    // resolves a `<init>` target against the (String) signature, so the
    // constructor-matching path has a real constructor to bind to.
    constructor()

    constructor(s: String) {
        a = s
    }

    fun c(s: String): String = s

    fun d(s: String) {
        a = s
    }

    fun d(
        s: String,
        n: Long,
    ) {
        a = "$s$n"
    }
}
