/*
 * SignatureLoader tests — parsing + validation of the community sigmatcher
 * dialect (the JSON form of a `signatures/<app>/signatures.yaml`).
 *
 * Mirrors the discipline of SchemaBoundsTest for maps: every bound and shape
 * rule is pinned, the DoS guards are exercised, and the leniency toward
 * authoring-only keys (the smali `count`) is proven in both directions.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.signature.ClassSignature
import io.github.xiddoc.rosetta.core.signature.SignatureLoader
import io.github.xiddoc.rosetta.core.signature.SignatureRule
import io.github.xiddoc.rosetta.core.signature.SignatureSet
import io.github.xiddoc.rosetta.core.signature.SignatureType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignatureLoaderTest {
    // ---- Happy path: the worked com.example.app shape ----------------------

    @Test
    fun `loads a multi-rule signatures file with class, field and method rules`() {
        val set =
            SignatureLoader.fromJson(
                """
                [
                  {
                    "name": "RemoteServiceClient",
                    "package": "com.example.app",
                    "signatures": [ { "signature": "sessionId", "type": "regex", "count": 1 } ],
                    "fields": [ { "name": "sessionId", "signatures": [ { "signature": "sessionId", "type": "regex" } ] } ],
                    "methods": [
                      { "name": "requestTicket",
                        "signatures": [ { "signature": "requestTicket\\(Landroid/os/Bundle;\\)", "type": "regex" } ] }
                    ]
                  },
                  {
                    "name": "IRemoteService${'$'}Stub",
                    "package": "com.example.app",
                    "signatures": [ { "signature": "\"com.example.app.IRemoteService\"", "type": "string" } ]
                  }
                ]
                """.trimIndent(),
            )
        assertEquals(2, set.classes.size)
        assertEquals(listOf("com.example.app.RemoteServiceClient", "com.example.app.IRemoteService\$Stub"), set.realNames)
        val first = set.classes.first()
        assertEquals("sessionId", first.signatures.single().signature)
        assertEquals(SignatureType.REGEX, first.signatures.single().type)
        assertEquals("sessionId", first.fields.single().name)
        assertEquals("requestTicket", first.methods.single().name)
        assertEquals(
            SignatureType.STRING,
            set.classes[1]
                .signatures
                .single()
                .type,
        )
    }

    @Test
    fun `tolerates the authoring-only count key in both int and range forms`() {
        // The real community files carry `count: 1` and `count: '1-2'`; the
        // runtime ignores it (ignoreUnknownKeys) and loads cleanly.
        val set =
            SignatureLoader.fromJson(
                """
                [
                  { "name": "A", "package": "com.example",
                    "signatures": [
                      { "signature": "\"a\"", "type": "regex", "count": 1 },
                      { "signature": "\"b\"", "type": "regex", "count": "1-2" }
                    ] }
                ]
                """.trimIndent(),
            )
        assertEquals(
            2,
            set.classes
                .single()
                .signatures.size,
        )
    }

    @Test
    fun `validate accepts a programmatically built set`() {
        val set =
            SignatureSet(
                listOf(ClassSignature("A", "com.example", listOf(SignatureRule("\"a\"", SignatureType.REGEX)))),
            )
        assertEquals(set, SignatureLoader.validate(set))
    }

    // ---- Parse failures -----------------------------------------------------

    @Test
    fun `rejects malformed JSON`() {
        val ex = assertFailsWith<SignatureValidationException> { SignatureLoader.fromJson("not json") }
        assertTrue(ex.message!!.contains("parse"))
        assertEquals(1, ex.issues.size)
    }

    @Test
    fun `rejects a signature missing the required type key`() {
        assertFailsWith<SignatureValidationException> {
            SignatureLoader.fromJson(
                """[ { "name": "A", "package": "com.example", "signatures": [ { "signature": "x" } ] } ]""",
            )
        }
    }

    @Test
    fun `rejects a rule missing the required name key`() {
        assertFailsWith<SignatureValidationException> {
            SignatureLoader.fromJson(
                """[ { "package": "com.example", "signatures": [ { "signature": "x", "type": "regex" } ] } ]""",
            )
        }
    }

    @Test
    fun `tolerates an unknown signature type by degrading it to UNKNOWN (forward-compat)`() {
        // A `type` value newer than this client must NOT fail the file — it
        // degrades per-rule to UNKNOWN (skipped at harvest), so a signatures
        // file from a newer maps revision still loads.
        val set =
            SignatureLoader.fromJson(
                """[ { "name": "A", "package": "com.example", "signatures": [ { "signature": "x", "type": "bogus" } ] } ]""",
            )
        assertEquals(
            SignatureType.UNKNOWN,
            set.classes
                .single()
                .signatures
                .single()
                .type,
        )
    }

    // ---- Validation bounds --------------------------------------------------

    @Test
    fun `rejects an empty class-level signatures list`() {
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson("""[ { "name": "A", "package": "com.example", "signatures": [] } ]""")
            }
        assertTrue(
            ex.issues
                .single()
                .message
                .contains("non-empty"),
        )
    }

    @Test
    fun `a class rule with no signatures key is an empty list and rejected`() {
        // `signatures` defaults to empty when absent, which the non-empty rule
        // catches — a rule that pins nothing can identify nothing.
        assertFailsWith<SignatureValidationException> {
            SignatureLoader.fromJson("""[ { "name": "A", "package": "com.example" } ]""")
        }
    }

    @Test
    fun `rejects a blank rule name`() {
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson(
                    """[ { "name": "  ", "package": "com.example", "signatures": [ { "signature": "x", "type": "regex" } ] } ]""",
                )
            }
        assertTrue(ex.issues.any { it.path.endsWith(".name") })
    }

    @Test
    fun `rejects a blank package`() {
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson(
                    """[ { "name": "A", "package": "", "signatures": [ { "signature": "x", "type": "regex" } ] } ]""",
                )
            }
        assertTrue(ex.issues.any { it.message.contains("not be empty") })
    }

    @Test
    fun `rejects a package that is not a dotted name`() {
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson(
                    """[ { "name": "A", "package": "nodots", "signatures": [ { "signature": "x", "type": "regex" } ] } ]""",
                )
            }
        assertTrue(ex.issues.any { it.message.contains("dotted package") })
    }

    @Test
    fun `rejects a blank member name`() {
        assertFailsWith<SignatureValidationException> {
            SignatureLoader.fromJson(
                """
                [ { "name": "A", "package": "com.example",
                    "signatures": [ { "signature": "x", "type": "regex" } ],
                    "methods": [ { "name": " ", "signatures": [ { "signature": "y", "type": "regex" } ] } ] } ]
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects a blank field-rule member signature`() {
        // Exercises the fields[] member path + the empty-signature leaf check.
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson(
                    """
                    [ { "name": "A", "package": "com.example",
                        "signatures": [ { "signature": "x", "type": "regex" } ],
                        "fields": [ { "name": "f", "signatures": [ { "signature": "", "type": "regex" } ] } ] } ]
                    """.trimIndent(),
                )
            }
        assertTrue(ex.issues.any { it.message.contains("not be empty") })
    }

    @Test
    fun `rejects an over-length signature pattern`() {
        val tooLong = "a".repeat(SignatureLoader.MAX_SIGNATURE_LEN + 1)
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson(
                    """[ { "name": "A", "package": "com.example", "signatures": [ { "signature": "$tooLong", "type": "regex" } ] } ]""",
                )
            }
        assertTrue(ex.issues.any { it.message.contains("exceeds") })
    }

    @Test
    fun `rejects an over-length rule name`() {
        val tooLong = "a".repeat(SignatureLoader.MAX_NAME_LEN + 1)
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson(
                    """[ { "name": "$tooLong", "package": "com.example", "signatures": [ { "signature": "x", "type": "regex" } ] } ]""",
                )
            }
        assertTrue(ex.issues.any { it.message.contains("exceeds") })
    }

    @Test
    fun `rejects an over-length package`() {
        val tooLong = "com." + "a".repeat(SignatureLoader.MAX_PACKAGE_LEN)
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson(
                    """[ { "name": "A", "package": "$tooLong", "signatures": [ { "signature": "x", "type": "regex" } ] } ]""",
                )
            }
        assertTrue(ex.issues.any { it.message.contains("exceeds") })
    }

    @Test
    fun `rejects an over-cap signatures list`() {
        val many = (0..SignatureLoader.MAX_SIGNATURES_PER_LIST).joinToString(",") { """{ "signature": "s$it", "type": "string" }""" }
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson("""[ { "name": "A", "package": "com.example", "signatures": [ $many ] } ]""")
            }
        assertTrue(ex.issues.any { it.message.contains("over the") })
    }

    @Test
    fun `reports multiple issues with a plural summary`() {
        val ex =
            assertFailsWith<SignatureValidationException> {
                SignatureLoader.fromJson(
                    """[ { "name": "", "package": "nodots", "signatures": [ { "signature": "x", "type": "regex" } ] } ]""",
                )
            }
        assertTrue(ex.issues.size >= 2)
        assertTrue(ex.message!!.contains("issues"))
    }

    // ---- DoS guard ----------------------------------------------------------

    @Test
    fun `rejects oversized input before parsing`() {
        val big = " ".repeat(MapLoader.MAX_INPUT_BYTES + 1)
        val ex = assertFailsWith<SignatureInputTooLargeException> { SignatureLoader.fromJson(big) }
        assertTrue(ex.message!!.contains("bytes"))
    }

    @Test
    fun `rejects deeply-nested input before parsing`() {
        val deep = "[".repeat(MapLoader.MAX_NESTING_DEPTH + 1) + "]".repeat(MapLoader.MAX_NESTING_DEPTH + 1)
        val ex = assertFailsWith<SignatureInputTooLargeException> { SignatureLoader.fromJson(deep) }
        assertTrue(ex.message!!.contains("depth"))
    }

    @Test
    fun `an empty top-level array is a valid empty set`() {
        assertEquals(emptyList(), SignatureLoader.fromJson("[]").classes)
    }
}
