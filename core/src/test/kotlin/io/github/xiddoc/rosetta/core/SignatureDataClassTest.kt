/*
 * Value-semantics + serialization-branch coverage for the community-signature
 * DTOs — the SignatureLoader's model. Mirrors DataClassSemanticsTest: it walks
 * the generated `equals`/`hashCode`/`copy`/`toString`, the kotlinx
 * `write$Self` optional-field arms, the `$Companion.serializer()` accessors,
 * and the missing-required-field deserializer branch — so the model needs no
 * Kover excludes.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.signature.ClassSignature
import io.github.xiddoc.rosetta.core.signature.MemberSignature
import io.github.xiddoc.rosetta.core.signature.SignatureRule
import io.github.xiddoc.rosetta.core.signature.SignatureSet
import io.github.xiddoc.rosetta.core.signature.SignatureType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SignatureDataClassTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonEncodeDefaults = Json { encodeDefaults = true }

    private fun <T : Any> assertValueSemantics(
        base: T,
        identical: T,
        variants: List<T>,
    ) {
        assertEquals(base, base)
        assertEquals(base, identical)
        assertEquals(base.hashCode(), identical.hashCode())
        assertEquals(base.hashCode(), base.hashCode())
        assertNotEquals<Any?>(base, null)
        assertNotEquals<Any?>(base, "not the same type")
        for (v in variants) {
            assertNotEquals(base, v)
            assertNotEquals(v, base)
        }
        assertTrue(base.toString().isNotEmpty())
    }

    private fun <T : Any> assertWriteSelfBranches(
        serializer: KSerializer<T>,
        allDefaults: T,
        allSet: T,
    ) {
        val skipArm = json.encodeToString(serializer, allDefaults)
        val encodeArm = json.encodeToString(serializer, allSet)
        assertTrue(encodeArm.length > skipArm.length)
        assertEquals(allDefaults, json.decodeFromString(serializer, skipArm))
        assertEquals(allSet, json.decodeFromString(serializer, encodeArm))
        val forced = jsonEncodeDefaults.encodeToString(serializer, allDefaults)
        assertTrue(forced.length > skipArm.length)
        assertTrue(jsonEncodeDefaults.encodeToString(serializer, allSet).isNotEmpty())
    }

    @Test
    fun `SignatureRule has value semantics`() {
        val base = SignatureRule("x", SignatureType.REGEX)
        assertValueSemantics(
            base = base,
            identical = SignatureRule("x", SignatureType.REGEX),
            variants =
                listOf(
                    base.copy(signature = "y"),
                    base.copy(type = SignatureType.STRING),
                ),
        )
        assertEquals("y", base.copy(signature = "y").signature)
    }

    @Test
    fun `MemberSignature has value semantics`() {
        val base = MemberSignature("m", listOf(SignatureRule("x", SignatureType.REGEX)))
        assertValueSemantics(
            base = base,
            identical = MemberSignature("m", listOf(SignatureRule("x", SignatureType.REGEX))),
            variants =
                listOf(
                    base.copy(name = "n"),
                    base.copy(signatures = emptyList()),
                ),
        )
        assertEquals("n", base.copy(name = "n").name)
    }

    @Test
    fun `ClassSignature has value semantics across every field`() {
        val base =
            ClassSignature(
                name = "A",
                pkg = "com.example",
                signatures = listOf(SignatureRule("x", SignatureType.REGEX)),
                fields = listOf(MemberSignature("f", listOf(SignatureRule("y", SignatureType.STRING)))),
                methods = listOf(MemberSignature("m", listOf(SignatureRule("z", SignatureType.SMALI)))),
            )
        assertValueSemantics(
            base = base,
            identical =
                ClassSignature(
                    name = "A",
                    pkg = "com.example",
                    signatures = listOf(SignatureRule("x", SignatureType.REGEX)),
                    fields = listOf(MemberSignature("f", listOf(SignatureRule("y", SignatureType.STRING)))),
                    methods = listOf(MemberSignature("m", listOf(SignatureRule("z", SignatureType.SMALI)))),
                ),
            variants =
                listOf(
                    base.copy(name = "B"),
                    base.copy(pkg = "com.other"),
                    base.copy(signatures = emptyList()),
                    base.copy(fields = emptyList()),
                    base.copy(methods = emptyList()),
                ),
        )
        assertEquals("B", base.copy(name = "B").name)
    }

    @Test
    fun `SignatureSet has value semantics`() {
        val base = SignatureSet(listOf(ClassSignature("A", "com.example", listOf(SignatureRule("x", SignatureType.REGEX)))))
        assertValueSemantics(
            base = base,
            identical =
                SignatureSet(listOf(ClassSignature("A", "com.example", listOf(SignatureRule("x", SignatureType.REGEX))))),
            variants = listOf(base.copy(classes = emptyList())),
        )
        assertEquals(emptyList(), base.copy(classes = emptyList()).classes)
    }

    @Test
    fun `SignatureRule round-trips and covers its serializer`() {
        // No optional fields, so a round-trip (both keys present) plus the
        // missing-required-field arm is the full surface.
        val r = SignatureRule("x", SignatureType.STRING)
        assertEquals(r, json.decodeFromString(SignatureRule.serializer(), json.encodeToString(SignatureRule.serializer(), r)))
    }

    @Test
    fun `MemberSignature write$Self covers both arms of the optional signatures`() {
        assertWriteSelfBranches(
            MemberSignature.serializer(),
            allDefaults = MemberSignature("m"),
            allSet = MemberSignature("m", listOf(SignatureRule("x", SignatureType.REGEX))),
        )
    }

    @Test
    fun `ClassSignature write$Self covers both arms of every optional`() {
        assertWriteSelfBranches(
            ClassSignature.serializer(),
            // Every optional (signatures / fields / methods) at its empty default
            // so each field's skip arm is exercised.
            allDefaults = ClassSignature("A", "com.example"),
            allSet =
                ClassSignature(
                    name = "A",
                    pkg = "com.example",
                    signatures = listOf(SignatureRule("x", SignatureType.REGEX)),
                    fields = listOf(MemberSignature("f", listOf(SignatureRule("y", SignatureType.STRING)))),
                    methods = listOf(MemberSignature("m", listOf(SignatureRule("z", SignatureType.SMALI)))),
                ),
        )
    }

    @Test
    fun `SignatureType serializes to its dialect names`() {
        assertEquals("\"regex\"", json.encodeToString(SignatureType.serializer(), SignatureType.REGEX))
        assertEquals("\"string\"", json.encodeToString(SignatureType.serializer(), SignatureType.STRING))
        assertEquals("\"smali\"", json.encodeToString(SignatureType.serializer(), SignatureType.SMALI))
        assertEquals(SignatureType.SMALI, json.decodeFromString(SignatureType.serializer(), "\"smali\""))
    }

    @Test
    fun `decoding a DTO missing a required field throws (deserializer bitmask arm)`() {
        // SignatureRule requires signature + type.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(SignatureRule.serializer(), """{"signature":"x"}""")
        }
        // MemberSignature requires name.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(MemberSignature.serializer(), """{"signatures":[]}""")
        }
        // ClassSignature requires name + package.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(ClassSignature.serializer(), """{"name":"A"}""")
        }
    }
}
