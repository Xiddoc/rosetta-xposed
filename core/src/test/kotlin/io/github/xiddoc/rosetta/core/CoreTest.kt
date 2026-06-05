/*
 * Unit tests for the neutral core: schema gate, signature utilities,
 * method-overload round-tripping, and version_code selection.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.resolver.Descriptors
import io.github.xiddoc.rosetta.core.resolver.parseSignatureArgs
import io.github.xiddoc.rosetta.core.resolver.toJvmDescriptor
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.core.version.MatchedBy
import io.github.xiddoc.rosetta.core.version.VersionMatch
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val minimalMap =
        """
        {
          "schema_version": 2,
          "app": "com.example.app",
          "version": "1.0.0",
          "version_code": 100,
          "classes": { "com.example.Foo": { "obfuscated": "a" } }
        }
        """.trimIndent()

    @Test
    fun `loads a valid schema-2 map`() {
        val map = MapLoader.fromJson(minimalMap)
        assertEquals("com.example.app", map.app)
        assertEquals(100, map.versionCode)
        assertEquals("a", map.classes["com.example.Foo"]!!.obfuscated)
    }

    @Test
    fun `rejects a non-current schema version`() {
        val bad = minimalMap.replace("\"schema_version\": 2", "\"schema_version\": 1")
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson(bad) }
        assertTrue(ex.issues.any { it.path == "schema_version" })
    }

    @Test
    fun `rejects malformed json`() {
        assertFailsWith<MapValidationException> { MapLoader.fromJson("{ not json") }
    }

    @Test
    fun `toJvmDescriptor handles primitives, arrays, classes and passthrough`() {
        assertEquals("I", toJvmDescriptor("int") { it })
        assertEquals("[I", toJvmDescriptor("int[]") { it })
        assertEquals("[[Ljava/lang/String;", toJvmDescriptor("java.lang.String[][]") { it })
        assertEquals("Lbbbb;", toJvmDescriptor("com.example.IFoo") { "bbbb" })
        // Already-descriptor passthrough.
        assertEquals("Lcom/x;", toJvmDescriptor("Lcom/x;") { error("should not translate") })
    }

    @Test
    fun `Descriptors exposes the shared primitive table and object rendering`() {
        assertEquals("I", Descriptors.PRIMITIVE_DESCRIPTORS["int"])
        assertEquals("V", Descriptors.PRIMITIVE_DESCRIPTORS["void"])
        assertEquals(9, Descriptors.PRIMITIVE_DESCRIPTORS.size)
        assertEquals("I", Descriptors.primitive("int"))
        assertNull(Descriptors.primitive("com.example.Foo"))
        assertEquals("Landroid/os/Bundle;", Descriptors.objectDescriptor("android.os.Bundle"))
    }

    @Test
    fun `parseSignatureArgs splits objects, primitives and arrays`() {
        assertEquals(
            listOf("Landroid/os/Bundle;", "Lbbbb;", "I"),
            parseSignatureArgs("(Landroid/os/Bundle;Lbbbb;I)V"),
        )
        assertEquals(listOf("[Ljava/lang/String;", "[I"), parseSignatureArgs("([Ljava/lang/String;[I)V"))
        assertEquals(emptyList(), parseSignatureArgs("()V"))
    }

    @Test
    fun `parseSignatureArgs rejects a malformed signature`() {
        assertFailsWith<IllegalArgumentException> { parseSignatureArgs("no parens") }
    }

    @Test
    fun `parseSignatureArgs rejects an unknown primitive descriptor char`() {
        // The Frida twin validates [VZBCSIJFD] and throws; the Kotlin side
        // must not emit a bare `Q` (or any non-primitive char) verbatim.
        assertFailsWith<IllegalArgumentException> { parseSignatureArgs("(Q)V") }
        assertFailsWith<IllegalArgumentException> { parseSignatureArgs("(X)V") }
        // An unknown char inside an array element is rejected too.
        assertFailsWith<IllegalArgumentException> { parseSignatureArgs("([Q)V") }
    }

    @Test
    fun `parseSignatureArgs accepts every valid primitive descriptor letter`() {
        // V is a return type only, but parsing an arg list of each primitive
        // must succeed for the full closed set.
        assertEquals(
            listOf("Z", "B", "C", "S", "I", "J", "F", "D"),
            parseSignatureArgs("(ZBCSIJFD)V"),
        )
    }

    @Test
    fun `method overloads round-trip single-object and array forms faithfully`() {
        val single =
            """
            {"schema_version":2,"app":"a","version":"1","version_code":1,
             "classes":{"C":{"obfuscated":"c","methods":{"m":{"obfuscated":"a","signature":"()V"}}}}}
            """.trimIndent()
        val multi =
            """
            {"schema_version":2,"app":"a","version":"1","version_code":1,
             "classes":{"C":{"obfuscated":"c","methods":{"m":[{"obfuscated":"a","signature":"()V"},
             {"obfuscated":"b","signature":"(I)V"}]}}}}
            """.trimIndent()

        val singleMap = json.decodeFromString(RosettaMap.serializer(), single)
        val multiMap = json.decodeFromString(RosettaMap.serializer(), multi)
        assertEquals(
            1,
            singleMap.classes["C"]!!
                .methods!!["m"]!!
                .entries.size,
        )
        assertEquals(
            2,
            multiMap.classes["C"]!!
                .methods!!["m"]!!
                .entries.size,
        )

        // Re-emit and ensure the single form stays an object, the multi an array.
        assertTrue(json.encodeToString(RosettaMap.serializer(), singleMap).contains("\"m\":{"))
        assertTrue(json.encodeToString(RosettaMap.serializer(), multiMap).contains("\"m\":["))
    }

    @Test
    fun `version selection prefers version_code over label`() {
        val a = MapLoader.fromJson(minimalMap)
        val b =
            MapLoader.fromJson(
                minimalMap
                    .replace("\"version_code\": 100", "\"version_code\": 200")
                    .replace("\"version\": \"1.0.0\"", "\"version\": \"1.0.0-b\""),
            )
        val registry = MapRegistry.of(a, b)

        val byCode = VersionMatch.select(registry, versionCode = 200)
        assertEquals(200, byCode!!.map.versionCode)
        assertEquals(MatchedBy.VERSION_CODE, byCode.matchedBy)

        val byLabel = VersionMatch.select(registry, versionLabel = "1.0.0-b")
        assertEquals(MatchedBy.LABEL, byLabel!!.matchedBy)
        assertEquals(200, byLabel.map.versionCode)

        assertNull(VersionMatch.select(registry, versionCode = 999))
    }

    @Test
    fun `MapRegistry indexes by version_code (primary) and label (fallback)`() {
        val a = MapLoader.fromJson(minimalMap)
        val b =
            MapLoader.fromJson(
                minimalMap
                    .replace("\"version_code\": 100", "\"version_code\": 200")
                    .replace("\"version\": \"1.0.0\"", "\"version\": \"2.0.0\""),
            )
        val registry = MapRegistry.fromCollection(listOf(a, b))
        assertEquals(2, registry.size)
        // O(1) by version_code (the authoritative key).
        assertEquals(100, registry.byVersionCode(100)!!.versionCode)
        assertEquals(200, registry.byVersionCode(200)!!.versionCode)
        assertNull(registry.byVersionCode(999))
        // Label fallback index.
        assertEquals(100, registry.byLabel("1.0.0")!!.versionCode)
        assertEquals(200, registry.byLabel("2.0.0")!!.versionCode)
        assertNull(registry.byLabel("nope"))
    }
}
