/*
 * Schema-hardening bounds + DoS-guard tests for the neutral core.
 *
 * These exercise the entry-count caps, string-length caps, the `app`
 * package-name shape, JS reserved-key rejection, and the pre-parse
 * denial-of-service guard (max input size + max nesting depth) added to
 * [MapLoader]. The caps mirror the canonical rosetta-maps JSON Schema
 * (the authoritative reference; the frida Zod and this Kotlin client
 * track it), so each assertion here pins that shared contract.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.FieldEntry
import io.github.xiddoc.rosetta.core.model.MapSource
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.MethodOverloads
import io.github.xiddoc.rosetta.core.model.RosettaMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemaBoundsTest {
    private val base =
        RosettaMap(
            schemaVersion = 2,
            app = "com.example.app",
            version = "1.0.0",
            versionCode = 100,
            classes = mapOf("com.example.Foo" to ClassEntry(obfuscated = "a")),
        )

    private fun classEntry(name: String): Pair<String, ClassEntry> = name to ClassEntry(obfuscated = "a")

    // ---- A rich-but-valid map still loads -----------------------------------

    @Test
    fun `a fully-populated in-bounds map validates and returns unchanged`() {
        val map =
            base.copy(
                capturedAt = "2026-01-01",
                fridaMinVersion = "16.0.0",
                fridaMaxVersion = "17.0.0",
                sources = listOf(MapSource("sigmatcher", config = "cfg", notes = "ok")),
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry(
                                obfuscated = "a",
                                extends = "zzzz",
                                dex = "classes6.dex",
                                aidlDescriptor = "com.example.IFoo",
                                source = "sigmatcher",
                                anchors = listOf("anchor-1", "anchor-2"),
                                methods =
                                    mapOf(
                                        "m" to
                                            MethodOverloads(
                                                listOf(
                                                    MethodEntry("c", "(I)V"),
                                                    MethodEntry("c", "(J)V"),
                                                ),
                                            ),
                                    ),
                                fields = mapOf("id" to FieldEntry("f", "Ljava/lang/String;")),
                            ),
                    ),
            )
        assertSame(map, MapLoader.validate(map))
    }

    // ---- Entry-count caps ---------------------------------------------------

    @Test
    fun `rejects more than MAX_CLASSES classes`() {
        val classes = (0..MapLoader.MAX_CLASSES).associate { classEntry("com.example.C$it") }
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(base.copy(classes = classes)) }
        assertTrue(ex.issues.any { it.path == "classes" })
    }

    @Test
    fun `rejects more than MAX_METHODS_PER_CLASS methods on a class`() {
        val methods =
            (0..MapLoader.MAX_METHODS_PER_CLASS).associate {
                "m$it" to MethodOverloads(listOf(MethodEntry("c", "()V")))
            }
        val map = base.copy(classes = mapOf("com.example.Foo" to ClassEntry("a", methods = methods)))
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods" })
    }

    @Test
    fun `rejects more than MAX_FIELDS_PER_CLASS fields on a class`() {
        val fields = (0..MapLoader.MAX_FIELDS_PER_CLASS).associate { "f$it" to FieldEntry("g", "I") }
        val map = base.copy(classes = mapOf("com.example.Foo" to ClassEntry("a", fields = fields)))
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].fields" })
    }

    @Test
    fun `rejects more than MAX_OVERLOADS_PER_METHOD overloads`() {
        val overloads = (0..MapLoader.MAX_OVERLOADS_PER_METHOD).map { MethodEntry("c", "()V") }
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry("a", methods = mapOf("m" to MethodOverloads(overloads))),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods[m]" })
    }

    @Test
    fun `rejects more than MAX_ANCHORS_PER_CLASS anchors`() {
        val anchors = (0..MapLoader.MAX_ANCHORS_PER_CLASS).map { "anchor-$it" }
        val map = base.copy(classes = mapOf("com.example.Foo" to ClassEntry("a", anchors = anchors)))
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].anchors" })
    }

    @Test
    fun `rejects more than MAX_SOURCES sources`() {
        val sources = (0..MapLoader.MAX_SOURCES).map { MapSource("sigmatcher") }
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(base.copy(sources = sources)) }
        assertTrue(ex.issues.any { it.path == "sources" })
    }

    // ---- String-length caps -------------------------------------------------

    @Test
    fun `rejects an over-length obfuscated short name`() {
        val long = "a".repeat(MapLoader.MAX_SHORT_NAME_LEN + 1)
        val map = base.copy(classes = mapOf("com.example.Foo" to ClassEntry(obfuscated = long)))
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].obfuscated" })
    }

    @Test
    fun `rejects an over-length extends name`() {
        val long = "z".repeat(MapLoader.MAX_FREE_STRING_LEN + 1)
        val map = base.copy(classes = mapOf("com.example.Foo" to ClassEntry(obfuscated = "a", extends = long)))
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].extends" })
    }

    @Test
    fun `accepts an extends name at exactly MAX_FREE_STRING_LEN characters`() {
        val atMax = "z".repeat(MapLoader.MAX_FREE_STRING_LEN)
        val map = base.copy(classes = mapOf("com.example.Foo" to ClassEntry(obfuscated = "a", extends = atMax)))
        assertSame(map, MapLoader.validate(map))
    }

    @Test
    fun `rejects an over-length method signature`() {
        val long = "(" + "I".repeat(MapLoader.MAX_SIGNATURE_LEN) + ")V"
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry(
                                "a",
                                methods = mapOf("m" to MethodOverloads(listOf(MethodEntry("c", long)))),
                            ),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods[m][0].signature" })
    }

    @Test
    fun `rejects an over-length method obfuscated name`() {
        val long = "c".repeat(MapLoader.MAX_SHORT_NAME_LEN + 1)
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry(
                                "a",
                                methods = mapOf("m" to MethodOverloads(listOf(MethodEntry(long, "()V")))),
                            ),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods[m][0].obfuscated" })
    }

    @Test
    fun `rejects an over-length field obfuscated name and type`() {
        val longName = "g".repeat(MapLoader.MAX_SHORT_NAME_LEN + 1)
        val longType = "L" + "x".repeat(MapLoader.MAX_SIGNATURE_LEN) + ";"
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry("a", fields = mapOf("f" to FieldEntry(longName, longType))),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].fields[f].obfuscated" })
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].fields[f].type" })
    }

    // ---- minLength: 1 (non-empty required leaves) ---------------------------

    @Test
    fun `rejects an empty class obfuscated name`() {
        val map = base.copy(classes = mapOf("com.example.Foo" to ClassEntry(obfuscated = "")))
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].obfuscated" && it.message.contains("empty") })
    }

    @Test
    fun `rejects an empty method obfuscated name and signature`() {
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry(
                                "a",
                                methods = mapOf("m" to MethodOverloads(listOf(MethodEntry(obfuscated = "", signature = "")))),
                            ),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods[m][0].obfuscated" && it.message.contains("empty") })
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods[m][0].signature" && it.message.contains("empty") })
    }

    @Test
    fun `rejects an empty method obfuscated name independently of the signature`() {
        // F7: obfuscated empty but signature valid — the obfuscated check must
        // fire on its own (no short-circuit hiding it behind the signature one).
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry(
                                "a",
                                methods = mapOf("m" to MethodOverloads(listOf(MethodEntry(obfuscated = "", signature = "()V")))),
                            ),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods[m][0].obfuscated" && it.message.contains("empty") })
        assertTrue(ex.issues.none { it.path == "classes[com.example.Foo].methods[m][0].signature" })
    }

    @Test
    fun `rejects an empty method signature independently of the obfuscated name`() {
        // F7: signature empty but obfuscated valid — the signature check must
        // fire on its own.
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry(
                                "a",
                                methods = mapOf("m" to MethodOverloads(listOf(MethodEntry(obfuscated = "c", signature = "")))),
                            ),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods[m][0].signature" && it.message.contains("empty") })
        assertTrue(ex.issues.none { it.path == "classes[com.example.Foo].methods[m][0].obfuscated" })
    }

    @Test
    fun `rejects an empty field obfuscated name and type`() {
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry("a", fields = mapOf("f" to FieldEntry(obfuscated = "", type = ""))),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].fields[f].obfuscated" && it.message.contains("empty") })
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].fields[f].type" && it.message.contains("empty") })
    }

    @Test
    fun `rejects an empty source tool`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.validate(base.copy(sources = listOf(MapSource(tool = ""))))
            }
        assertTrue(ex.issues.any { it.path == "sources[0].tool" && it.message.contains("empty") })
    }

    @Test
    fun `rejects an over-length app`() {
        // Keep the package shape valid so the length check is what fires.
        val long = "com." + "a".repeat(MapLoader.MAX_APP_LEN)
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(base.copy(app = long)) }
        assertTrue(ex.issues.any { it.path == "app" && it.message.contains("exceeds") })
    }

    @Test
    fun `rejects an over-length version`() {
        val long = "1".repeat(MapLoader.MAX_VERSION_LEN + 1)
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(base.copy(version = long)) }
        assertTrue(ex.issues.any { it.path == "version" && it.message.contains("exceeds") })
    }

    @Test
    fun `rejects a version_code above MAX_VERSION_CODE`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.validate(base.copy(versionCode = MapLoader.MAX_VERSION_CODE + 1))
            }
        assertTrue(ex.issues.any { it.path == "version_code" })
    }

    @Test
    fun `accepts a version_code at exactly MAX_VERSION_CODE`() {
        val map = base.copy(versionCode = MapLoader.MAX_VERSION_CODE)
        assertSame(map, MapLoader.validate(map))
    }

    @Test
    fun `MAX_VERSION_CODE is 2 pow 53 minus 1 (Number MAX_SAFE_INTEGER)`() {
        assertEquals(9_007_199_254_740_991L, MapLoader.MAX_VERSION_CODE)
    }

    @Test
    fun `accepts full 64-bit longVersionCode values past the old int32 cap`() {
        // 2^31 - 1 (old int32 cap), 2^31 (just past), 2^32 (versionCodeMajor = 1).
        for (code in listOf(2_147_483_647L, 2_147_483_648L, 4_294_967_296L)) {
            val map = base.copy(versionCode = code)
            assertSame(map, MapLoader.validate(map), "version_code $code should validate")
        }
    }

    @Test
    fun `rejects a negative version_code`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.validate(base.copy(versionCode = -1L))
            }
        assertTrue(ex.issues.any { it.path == "version_code" })
    }

    @Test
    fun `rejects over-length free strings on the map and on sources`() {
        val long = "x".repeat(MapLoader.MAX_FREE_STRING_LEN + 1)
        val map =
            base.copy(
                capturedAt = long,
                fridaMinVersion = long,
                fridaMaxVersion = long,
                sources = listOf(MapSource(tool = long, config = long, notes = long)),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "captured_at" })
        assertTrue(ex.issues.any { it.path == "frida_min_version" })
        assertTrue(ex.issues.any { it.path == "frida_max_version" })
        assertTrue(ex.issues.any { it.path == "sources[0].tool" })
        assertTrue(ex.issues.any { it.path == "sources[0].config" })
        assertTrue(ex.issues.any { it.path == "sources[0].notes" })
    }

    @Test
    fun `rejects over-length class free strings`() {
        val long = "x".repeat(MapLoader.MAX_FREE_STRING_LEN + 1)
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry(
                                obfuscated = "a",
                                dex = long,
                                aidlDescriptor = long,
                                source = long,
                                anchors = listOf(long),
                            ),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].dex" })
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].aidl_descriptor" })
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].source" })
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].anchors[0]" })
    }

    // ---- app package-name shape --------------------------------------------

    @Test
    fun `rejects an app without a dotted package shape`() {
        // Single segment, no dot → fails the pattern (but is non-blank).
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(base.copy(app = "notpackage")) }
        assertTrue(ex.issues.any { it.path == "app" && it.message.contains("dotted") })
    }

    @Test
    fun `rejects an app starting with a digit or containing illegal chars`() {
        assertFailsWith<MapValidationException> { MapLoader.validate(base.copy(app = "1com.example")) }
        assertFailsWith<MapValidationException> { MapLoader.validate(base.copy(app = "com.exa mple")) }
    }

    @Test
    fun `accepts a valid dotted package name`() {
        assertSame(base, MapLoader.validate(base))
        // Underscores and digits within segments are allowed.
        val map = base.copy(app = "com.example_2.app3")
        assertSame(map, MapLoader.validate(map))
    }

    // ---- Reserved-key rejection (M1) ---------------------------------------

    @Test
    fun `rejects reserved keys in classes`() {
        for (key in listOf("__proto__", "constructor", "prototype")) {
            val map = base.copy(classes = mapOf(key to ClassEntry(obfuscated = "a")))
            val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
            assertTrue(ex.issues.any { it.path == "classes" && it.message.contains(key) })
        }
    }

    @Test
    fun `rejects reserved keys in methods`() {
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry(
                                "a",
                                methods = mapOf("__proto__" to MethodOverloads(listOf(MethodEntry("c", "()V")))),
                            ),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].methods" && it.message.contains("__proto__") })
    }

    @Test
    fun `rejects reserved keys in fields`() {
        val map =
            base.copy(
                classes =
                    mapOf(
                        "com.example.Foo" to
                            ClassEntry("a", fields = mapOf("prototype" to FieldEntry("g", "I"))),
                    ),
            )
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map) }
        assertTrue(ex.issues.any { it.path == "classes[com.example.Foo].fields" && it.message.contains("prototype") })
    }

    // ---- DoS guard: input size + nesting depth (fromJson) -------------------

    @Test
    fun `rejects an oversized input before parsing`() {
        // A valid-ish JSON prefix padded past the byte cap; the size guard
        // fires first, before any deserialization.
        val big = " ".repeat(MapLoader.MAX_INPUT_BYTES + 1)
        val ex = assertFailsWith<MapInputTooLargeException> { MapLoader.fromJson(big) }
        assertTrue(ex.message!!.contains("bytes"))
    }

    @Test
    fun `byte-length guard counts multi-byte UTF-8 in place and still loads in-bounds input`() {
        // xposed#14 L4: the size guard counts UTF-8 bytes without a toByteArray
        // copy. A small map carrying a 2-byte char (é, U+00E9) and a 3-byte char
        // (€, U+20AC) in `version` exercises the 2-byte and 3-byte counting
        // branches; the input is well under the cap so it loads cleanly.
        val json =
            """
            {"schema_version":2,"app":"com.example.app","version":"1.0-é€",
             "version_code":100,"classes":{}}
            """.trimIndent()
        val loaded = MapLoader.fromJson(json)
        assertEquals("1.0-é€", loaded.version)
    }

    @Test
    fun `byte-length guard rejects multi-byte input that overflows the cap`() {
        // A 3-byte char (€) repeated past the byte cap: the running count crosses
        // MAX_INPUT_BYTES well before the char count would, proving the guard
        // measures BYTES (multi-byte aware) and short-circuits over the cap.
        val big = "€".repeat(MapLoader.MAX_INPUT_BYTES / 3 + 1)
        val ex = assertFailsWith<MapInputTooLargeException> { MapLoader.fromJson(big) }
        assertTrue(ex.message!!.contains("bytes"))
    }

    @Test
    fun `rejects an unknown top-level key`() {
        // xposed#14 M6: strict parsing (ignoreUnknownKeys = false) mirrors the
        // canonical schema's additionalProperties:false — a stray/typo key is a
        // hard parse failure surfaced as a MapValidationException.
        val json =
            """
            {"schema_version":2,"app":"com.example.app","version":"1.0.0",
             "version_code":100,"classes":{},"totallyUnknownKey":true}
            """.trimIndent()
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson(json) }
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test
    fun `rejects an unknown nested key on a class entry`() {
        // A typo on a class entry (e.g. "obfuscated") is rejected too, so a
        // mistyped field can never silently become a no-op default.
        val json =
            """
            {"schema_version":2,"app":"com.example.app","version":"1.0.0","version_code":100,
             "classes":{"com.example.Foo":{"obfuscated":"a","mystery":1}}}
            """.trimIndent()
        assertFailsWith<MapValidationException> { MapLoader.fromJson(json) }
    }

    @Test
    fun `rejects deeply-nested input before parsing`() {
        val deep = "[".repeat(MapLoader.MAX_NESTING_DEPTH + 1) + "]".repeat(MapLoader.MAX_NESTING_DEPTH + 1)
        val ex = assertFailsWith<MapInputTooLargeException> { MapLoader.fromJson(deep) }
        assertTrue(ex.message!!.contains("depth"))
    }

    @Test
    fun `nesting scan ignores brackets inside string literals and escapes`() {
        // Brackets and an escaped quote live inside a string value, so they
        // must NOT count toward structural depth: this in-bounds map loads.
        val json =
            """
            {"schema_version":2,"app":"com.example.app","version":"1.0.0","version_code":1,
             "classes":{"com.example.Foo":{"obfuscated":"a","dex":"[[[ {\" }]]]"}}}
            """.trimIndent()
        val map = MapLoader.fromJson(json)
        assertEquals("[[[ {\" }]]]", map.classes["com.example.Foo"]!!.dex)
    }

    @Test
    fun `unbalanced closing brackets do not underflow the depth counter`() {
        // Leading closers (depth would go negative without the guard) then a
        // single object: the deepest real level is 1, well under the cap, so
        // the size/depth guard passes and the parse fails on the JSON itself.
        val text = "]]]{}"
        assertFailsWith<MapValidationException> { MapLoader.fromJson(text) }
    }

    @Test
    fun `a normal map sits well under the nesting cap`() {
        val json =
            """
            {"schema_version":2,"app":"com.example.app","version":"1.0.0","version_code":1,
             "classes":{"com.example.Foo":{"obfuscated":"a",
              "methods":{"m":[{"obfuscated":"c","signature":"()V"}]}}}}
            """.trimIndent()
        assertEquals("com.example.app", MapLoader.fromJson(json).app)
    }
}
