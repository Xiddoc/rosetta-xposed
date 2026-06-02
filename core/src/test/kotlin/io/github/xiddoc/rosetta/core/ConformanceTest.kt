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
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.resolver.Resolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConformanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @TestFactory
    fun `conformance fixtures`(): List<DynamicTest> {
        val tests = mutableListOf<DynamicTest>()
        for (resource in listOf("/conformance/basic.json")) {
            val text =
                requireNotNull(javaClass.getResource(resource)) { "missing fixture $resource" }
                    .readText()
            val root = json.parseToJsonElement(text).jsonObject
            val map = json.decodeFromJsonElement(RosettaMap.serializer(), root["map"]!!)
            val resolver = Resolver(MapLoader.validate(map))

            for (case in root["cases"]!!.jsonArray) {
                val obj = case.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: "case"
                tests += DynamicTest.dynamicTest("$resource :: $name") { runCase(resolver, obj) }
            }
        }
        return tests
    }

    private fun runCase(
        resolver: Resolver,
        case: JsonObject,
    ) {
        val kind = case["kind"]!!.jsonPrimitive.content
        val expectError = case["expectError"]?.jsonPrimitive?.content

        if (expectError != null) {
            when (expectError) {
                "AmbiguousOverload" -> assertFailsWith<AmbiguousOverloadException> { invoke(resolver, kind, case) }
                "Resolve" -> assertFailsWith<ResolveException> { invoke(resolver, kind, case) }
                else -> error("unknown expectError '$expectError'")
            }
            return
        }

        val expectObf = case["expectObf"]?.jsonPrimitive?.content
        val expectSignature = case["expectSignature"]?.jsonPrimitive?.content
        when (kind) {
            "class" -> assertEquals(expectObf, resolver.resolveClass(case.cls()).obfName)
            "method" -> {
                val m = resolver.resolveMethod(case.cls(), case.str("method"), case.argTypes())
                assertEquals(expectObf, m.obfName)
                if (expectSignature != null) assertEquals(expectSignature, m.signature)
            }
            "field" -> assertEquals(expectObf, resolver.resolveField(case.cls(), case.str("field")).obfName)
            else -> error("unknown case kind '$kind'")
        }
    }

    private fun invoke(
        resolver: Resolver,
        kind: String,
        case: JsonObject,
    ) {
        when (kind) {
            "class" -> resolver.resolveClass(case.cls())
            "method" -> resolver.resolveMethod(case.cls(), case.str("method"), case.argTypes())
            "field" -> resolver.resolveField(case.cls(), case.str("field"))
            else -> error("unknown case kind '$kind'")
        }
    }

    private fun JsonObject.cls(): String = str("class")

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.argTypes(): List<String>? = (this["argTypes"] as? JsonArray)?.map { it.jsonPrimitive.content }
}
