/*
 * Community-signature MODEL tests — the typed twin of the sigmatcher dialect.
 *
 * Covers the value types (field exposure, defaults) and the derived
 * `realFqn` / `realNames`; the loading + validation surface lives in the
 * sibling SignatureLoaderTest.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.signature.ClassSignature
import io.github.xiddoc.rosetta.core.signature.MemberSignature
import io.github.xiddoc.rosetta.core.signature.SignatureRule
import io.github.xiddoc.rosetta.core.signature.SignatureSet
import io.github.xiddoc.rosetta.core.signature.SignatureType
import kotlin.test.Test
import kotlin.test.assertEquals

class SignatureModelTest {
    @Test
    fun `SignatureRule exposes its fields`() {
        val rule = SignatureRule(signature = "\"x\"", type = SignatureType.REGEX)
        assertEquals("\"x\"", rule.signature)
        assertEquals(SignatureType.REGEX, rule.type)
    }

    @Test
    fun `MemberSignature exposes name and signatures and defaults to empty`() {
        val empty = MemberSignature(name = "m")
        assertEquals("m", empty.name)
        assertEquals(emptyList(), empty.signatures)

        val full = MemberSignature(name = "m", signatures = listOf(SignatureRule("s", SignatureType.STRING)))
        assertEquals(1, full.signatures.size)
    }

    @Test
    fun `ClassSignature realFqn joins package and name and fields default empty`() {
        val rule =
            ClassSignature(
                name = "IRemoteService\$Stub",
                pkg = "com.example.app",
                signatures = listOf(SignatureRule("\"x\"", SignatureType.REGEX)),
            )
        assertEquals("com.example.app.IRemoteService\$Stub", rule.realFqn)
        assertEquals(emptyList(), rule.fields)
        assertEquals(emptyList(), rule.methods)
    }

    @Test
    fun `SignatureSet realNames lists every rule fqn in order`() {
        val set =
            SignatureSet(
                listOf(
                    ClassSignature("A", "com.example", listOf(SignatureRule("\"a\"", SignatureType.REGEX))),
                    ClassSignature("B", "com.example", listOf(SignatureRule("\"b\"", SignatureType.REGEX))),
                ),
            )
        assertEquals(listOf("com.example.A", "com.example.B"), set.realNames)
    }

    @Test
    fun `SignatureType has the three sigmatcher matcher kinds`() {
        assertEquals(
            setOf(SignatureType.REGEX, SignatureType.STRING, SignatureType.SMALI),
            SignatureType.entries.toSet(),
        )
    }
}
