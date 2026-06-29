package io.github.xiddoc.rosetta.gradle.internal

import io.github.xiddoc.rosetta.core.signature.SignatureLoader
import io.github.xiddoc.rosetta.core.signature.SignatureType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignatureYamlConverterTest {
    @Test
    fun `converts the sigmatcher dialect to JSON the loader accepts`() {
        val yaml =
            """
            - name: 'RemoteServiceClient'
              package: 'com.example.app'
              signatures:
                  - signature: 'sessionId'
                    type: regex
                    count: 1
              methods:
                  - name: 'requestTicket'
                    signatures:
                        - signature: 'requestTicket\(Landroid/os/Bundle;\)'
                          type: regex
                          count: '1-2'
            """.trimIndent()

        val json = SignatureYamlConverter.toJson(yaml)
        // Round-trips through the REAL loader (the whole point of the conversion).
        val set = SignatureLoader.fromJson(json)
        assertEquals("com.example.app.RemoteServiceClient", set.realNames.single())
        assertEquals(
            SignatureType.REGEX,
            set.classes
                .single()
                .signatures
                .single()
                .type,
        )
        assertEquals(
            "requestTicket",
            set.classes
                .single()
                .methods
                .single()
                .name,
        )
    }

    @Test
    fun `preserves a quoted regex string verbatim (backslashes and quotes intact)`() {
        val yaml =
            """
            - name: 'Config'
              package: 'com.example.app'
              signatures:
                  - signature: '"https://.*\.example/api"'
                    type: regex
            """.trimIndent()
        val rules = Json.parseToJsonElement(SignatureYamlConverter.toJson(yaml)).jsonArray
        val sig =
            rules
                .single()
                .jsonObject["signatures"]!!
                .jsonArray
                .single()
                .jsonObject["signature"]!!
                .jsonPrimitive.content
        // single-quoted YAML keeps backslashes literal → the regex survives intact.
        assertEquals("\"https://.*\\.example/api\"", sig)
    }

    @Test
    fun `keeps count as int or string and carries scalars through`() {
        val yaml =
            """
            - name: 'A'
              package: 'com.example.app'
              signatures:
                  - signature: 'a'
                    type: regex
                    count: 3
              flag: true
              note: null
            """.trimIndent()
        val obj =
            Json
                .parseToJsonElement(SignatureYamlConverter.toJson(yaml))
                .jsonArray
                .single()
                .jsonObject
        assertEquals(
            3,
            obj["signatures"]!!
                .jsonArray
                .single()
                .jsonObject["count"]!!
                .jsonPrimitive.int,
        )
        assertTrue(obj["flag"]!!.jsonPrimitive.boolean)
        assertTrue(obj["note"]!!.jsonPrimitive.content == "null" || obj["note"].toString() == "null")
    }

    @Test
    fun `malformed YAML fails fast`() {
        // An unterminated flow sequence is a hard YAML parse error.
        val ex = assertFailsWith<IllegalStateException> { SignatureYamlConverter.toJson("[unclosed") }
        assertTrue(ex.message!!.contains("valid YAML"))
    }
}
