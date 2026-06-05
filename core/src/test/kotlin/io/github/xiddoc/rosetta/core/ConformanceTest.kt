/*
 * Conformance suite runner.
 *
 * Reads the provider-neutral golden fixtures under
 * `src/test/resources/conformance/` and asserts the Kotlin resolver
 * produces the expected obfuscated names / signatures / errors. The same
 * fixtures are intended to be shared verbatim with rosetta-frida's
 * TypeScript resolver so both implementations stay behaviour-identical
 * (RFC 0001, Decision 1; the shared file format is tracked as future work
 * in the RFC's "Open questions").
 *
 * The fixture format is documented in `conformance/README.md`. Keep it
 * STRICTLY about resolution semantics: no signer / identity / Frida /
 * Xposed specifics, and no language-specific assertions. A faithful TS
 * runner should be implementable from the README alone.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.resolver.Resolver
import io.github.xiddoc.rosetta.core.resolver.parseSignatureArgs
import io.github.xiddoc.rosetta.core.resolver.toJvmDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConformanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every fixture file under `conformance/`. The runner discovers them
     * from this manifest; add a new file here when you add one. (Classpath
     * directory enumeration is brittle across build layouts, so we keep an
     * explicit list — the same approach a TS runner would take with an
     * import glob.)
     */
    private val fixtures =
        listOf(
            "/conformance/basic.json",
            "/conformance/classes.json",
            "/conformance/methods.json",
            "/conformance/overloads.json",
            "/conformance/argtypes.json",
            "/conformance/fields.json",
            "/conformance/signatures.json",
            "/conformance/type-translation.json",
            "/conformance/introspection.json",
            "/conformance/errors.json",
            "/conformance/validation.json",
        )

    @TestFactory
    fun `conformance fixtures`(): List<DynamicTest> {
        val tests = mutableListOf<DynamicTest>()
        for (resource in fixtures) {
            val text =
                requireNotNull(javaClass.getResource(resource)) { "missing fixture $resource" }
                    .readText()
            val root = json.parseToJsonElement(text).jsonObject

            // Map is optional: pure-utility fixtures (signature parsing /
            // descriptor conversion) need no map at all.
            val resolver =
                root["map"]?.let {
                    val map = json.decodeFromJsonElement(RosettaMap.serializer(), it)
                    Resolver(MapLoader.validate(map))
                }

            for (case in root["cases"]!!.jsonArray) {
                val obj = case.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: "case"
                tests += DynamicTest.dynamicTest("$resource :: $name") { runCase(resolver, obj) }
            }
        }
        return tests
    }

    private fun runCase(
        resolver: Resolver?,
        case: JsonObject,
    ) {
        val kind = case["kind"]!!.jsonPrimitive.content
        val expectError = case["expectError"]?.jsonPrimitive?.content

        // `validate` is a VALIDATION case (not resolution): it carries its own
        // inline `inputMap` and asserts the schema/bounds gate accepts or
        // rejects it. Handled before the resolution dispatch because it never
        // touches the shared resolver.
        if (kind == "validate") {
            runValidateCase(case, expectError)
            return
        }

        if (expectError != null) {
            assertExpectedError(expectError) { invoke(resolver, kind, case) }
            return
        }

        when (kind) {
            "class" -> assertClass(resolver(resolver), case)
            "method" -> assertMethod(resolver(resolver), case)
            "field" -> assertField(resolver(resolver), case)
            "hasClass" ->
                assertEquals(case.bool("expectResult"), resolver(resolver).hasClass(case.cls()))
            "reverseLookup" ->
                assertEquals(case["expectResult"]?.strOrNull(), resolver(resolver).reverseLookup(case.str("obf")))
            "translateType" ->
                assertEquals(case.str("expectResult"), resolver(resolver).translateType(case.str("type")))
            "toJvmDescriptor" -> assertDescriptor(resolver(resolver), case)
            "parseSignatureArgs" ->
                assertEquals(case.strList("expectList"), parseSignatureArgs(case.str("signature")))
            else -> error("unknown case kind '$kind'")
        }
    }

    private fun assertClass(
        resolver: Resolver,
        case: JsonObject,
    ) {
        val resolved = resolver.resolveClass(case.cls())
        assertEquals(case.str("expectObf"), resolved.obfName)
        case["expectExtends"]?.strOrNull()?.let { assertEquals(it, resolved.extends) }
    }

    private fun assertMethod(
        resolver: Resolver,
        case: JsonObject,
    ) {
        val m = resolver.resolveMethod(case.cls(), case.str("method"), case.argTypes())
        assertEquals(case.str("expectObf"), m.obfName)
        case["expectSignature"]?.strOrNull()?.let { assertEquals(it, m.signature) }
        case["expectClassName"]?.strOrNull()?.let { assertEquals(it, m.className) }
        // The shared golden fixture encodes the null->false PROJECTION of the
        // static flag (a map that omits `static` expects `false`). The resolved
        // type now preserves the tri-state Boolean? (null == "unknown"), so the
        // fixture's boolean is compared against the folded value here. This
        // keeps cross-language parity at the fixture boundary while the Kotlin
        // resolved type stays asserted-vs-unknown.
        case["expectStatic"]?.let { assertEquals(it.jsonPrimitive.boolean, m.static == true) }
        case["expectAidlTxn"]?.let { assertEquals((it as JsonPrimitive).int, m.aidlTxn) }
        case["expectOverloadCount"]?.let { assertEquals(it.jsonPrimitive.int, m.allOverloads.size) }
    }

    private fun assertField(
        resolver: Resolver,
        case: JsonObject,
    ) {
        val f = resolver.resolveField(case.cls(), case.str("field"))
        assertEquals(case.str("expectObf"), f.obfName)
        // See assertMethod: the fixture asserts the null->false projection; the
        // resolved field static flag is the tri-state Boolean?, folded here.
        case["expectStatic"]?.let { assertEquals(it.jsonPrimitive.boolean, f.static == true) }
        case["expectType"]?.strOrNull()?.let { assertEquals(it, f.type) }
        case["expectClassName"]?.strOrNull()?.let { assertEquals(it, f.className) }
    }

    private fun assertDescriptor(
        resolver: Resolver,
        case: JsonObject,
    ) {
        // toJvmDescriptor runs the bare class name through the resolver's
        // real -> obf translation, exactly as overload disambiguation does.
        val result = toJvmDescriptor(case.str("type")) { resolver.translateType(it) }
        assertEquals(case.str("expectResult"), result)
    }

    /**
     * Run a `validate`-kind case: decode the inline `inputMap` and run it
     * through [MapLoader.validate]. Either `expectError: "MapValidation"` (the
     * map must be rejected) or `expectValid: true` (the map must pass). This is
     * how the oracle covers VALIDATION semantics (e.g. the `minLength: 1`
     * non-empty `obfuscated` rule) on top of resolution semantics.
     */
    private fun runValidateCase(
        case: JsonObject,
        expectError: String?,
    ) {
        val input = case["inputMap"]!!.jsonObject
        val validate = {
            val map = json.decodeFromJsonElement(RosettaMap.serializer(), input)
            MapLoader.validate(map)
        }
        if (expectError != null) {
            assertEquals("MapValidation", expectError, "validate cases only support expectError 'MapValidation'")
            assertFailsWith<MapValidationException> { validate() }
        } else {
            assertEquals(
                true,
                case["expectValid"]?.jsonPrimitive?.boolean,
                "validate success case must set expectValid: true",
            )
            // Throws on failure → the case fails, which is the assertion.
            validate()
        }
    }

    private fun assertExpectedError(
        expectError: String,
        block: () -> Unit,
    ) {
        when (expectError) {
            "AmbiguousOverload" -> assertFailsWith<AmbiguousOverloadException> { block() }
            // UnknownArgType is the DISTINCT precise subtype; assert it before
            // the generic Resolve so a generic ResolveException can't satisfy
            // an UnknownArgType case (it is a ResolveException subtype).
            "UnknownArgType" -> assertFailsWith<UnknownArgTypeException> { block() }
            // A generic Resolve case must NOT be the precise UnknownArgType
            // subtype: because UnknownArgTypeException IS-A ResolveException,
            // a bare assertFailsWith<ResolveException> would also accept the
            // subtype and mask a resolver that wrongly fired UnknownArgType
            // here. Assert the negative explicitly, mirroring the Frida
            // runner's `expect(thrown).not.toBeInstanceOf(UnknownArgTypeError)`.
            "Resolve" -> {
                val ex = assertFailsWith<ResolveException> { block() }
                assertFalse(
                    ex is UnknownArgTypeException,
                    "Resolve case must not fire the distinct UnknownArgType subtype",
                )
            }
            "IllegalArgument" -> assertFailsWith<IllegalArgumentException> { block() }
            else -> error("unknown expectError '$expectError'")
        }
    }

    private fun invoke(
        resolver: Resolver?,
        kind: String,
        case: JsonObject,
    ) {
        when (kind) {
            "class" -> resolver(resolver).resolveClass(case.cls())
            "method" -> resolver(resolver).resolveMethod(case.cls(), case.str("method"), case.argTypes())
            "field" -> resolver(resolver).resolveField(case.cls(), case.str("field"))
            "translateType" -> resolver(resolver).translateType(case.str("type"))
            "toJvmDescriptor" -> toJvmDescriptor(case.str("type")) { resolver(resolver).translateType(it) }
            "parseSignatureArgs" -> parseSignatureArgs(case.str("signature"))
            else -> error("unknown case kind '$kind'")
        }
    }

    private fun resolver(resolver: Resolver?): Resolver = requireNotNull(resolver) { "case kind requires a 'map' in the fixture" }

    private fun JsonObject.cls(): String = str("class")

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

    private fun JsonObject.argTypes(): List<String>? = (this["argTypes"] as? JsonArray)?.map { it.jsonPrimitive.content }

    private fun JsonObject.strList(key: String): List<String> = (this[key] as JsonArray).map { it.jsonPrimitive.content }

    /**
     * A JSON value that may be the literal `null` (which maps to a Kotlin
     * `null`) or a string. `JsonNull.contentOrNull` is `null`, so an
     * `expectResult: null` in a fixture asserts an absent / null result.
     */
    private fun JsonElement.strOrNull(): String? = (this as JsonPrimitive).contentOrNull
}
