/*
 * Inherited-member fixtures for xposed#12. `ObfChild` stands in for an
 * obfuscated app class whose hooked method/field is declared on its PARENT
 * (`ObfParent`), not on the class itself — the shipped example shape
 * (`RemoteServiceClient extends AbstractServiceClient`). The binding must walk
 * the superclass chain to bind these; `declaredMethods` / `declaredFields` on
 * the child alone would miss them.
 */
package io.github.xiddoc.rosetta.xposed.fixtures

@Suppress("unused")
open class ObfParent {
    // A field + method declared ONLY on the parent. The child inherits both.
    @JvmField var p: String = ""

    fun e(s: String): String = "parent:$s"
}

@Suppress("unused")
class ObfChild : ObfParent() {
    // The child declares its own member too, so a test can confirm the walk
    // still finds a member that IS on the child (nearest-class-wins).
    fun f(n: Long): Long = n + 1
}
