/*
 * schema_version: 4 migration tests (rosetta-maps#36/#38/#39/#40, #43, #32 plus
 * the v4 pure-real→obfuscated-mapping bump).
 *
 * Pins, in BOTH directions, the version gate plus every field invariant the
 * Kotlin client still carries:
 *   - the version gate (4 accepted, 3 rejected — v4 dropped the AIDL facets
 *     `aidl_descriptor` / `aidl_txn` / `anchors` and the AIDL `kind` values,
 *     leaving the map a pure real→obfuscated mapping),
 *   - `confidence` removed (a map carrying it is rejected under strict parsing),
 *   - `captured_at` ISO-date shape check,
 *   - `signer_sha256` accepts a single string OR an array (match-any),
 *   - `generated_from` { signatures_rev } accepted; a bad shape rejected,
 *   - `status` active|superseded|retracted enum accepted; a bad value rejected,
 *   - `superseded_by` version_code,
 *   - a `retracted` map REFUSED at load; a `superseded` one loads (warning is a
 *     health-check concern, exercised in :xposed).
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.GeneratedFrom
import io.github.xiddoc.rosetta.core.model.MapStatus
import io.github.xiddoc.rosetta.core.model.SignerSha256Serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaV4Test {
    private fun mapJson(
        extra: String = "",
        schemaVersion: Int = 4,
    ): String =
        """
        {
          "schema_version": $schemaVersion,
          "app": "com.example.app",
          "version": "1.0.0",
          "version_code": 100,
          $extra
          "classes": { "com.example.Foo": { "obfuscated": "a" } }
        }
        """.trimIndent()

    // ---- version gate (both directions) -------------------------------------

    @Test
    fun `schema_version 4 is accepted`() {
        assertEquals(4, MapLoader.fromJson(mapJson()).schemaVersion)
    }

    @Test
    fun `schema_version 3 is rejected (previous version)`() {
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson(mapJson(schemaVersion = 3)) }
        assertTrue(ex.issues.any { it.path == "schema_version" })
    }

    // ---- v4 dropped the AIDL facets (strict-parse rejection) ----------------

    @Test
    fun `a class-entry aidl_descriptor key is rejected under strict parsing`() {
        // v4 made the map a pure real→obfuscated mapping; aidl_descriptor is no
        // longer a class field, so a map carrying it is rejected (a stale v3 map
        // must be re-emitted at v4). The AIDL anchor stays runtime-discovery
        // evidence (DiscoveryHints), never a map field.
        val json =
            mapJson()
                .replace("\"obfuscated\": \"a\"", "\"obfuscated\": \"a\", \"aidl_descriptor\": \"Lcom/example/IFoo;\"")
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson(json) }
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test
    fun `a class-entry anchors key is rejected under strict parsing`() {
        val json =
            mapJson()
                .replace("\"obfuscated\": \"a\"", "\"obfuscated\": \"a\", \"anchors\": [\"login_token\"]")
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson(json) }
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test
    fun `a method-entry aidl_txn key is rejected under strict parsing`() {
        val json =
            mapJson()
                .replace(
                    "\"classes\": { \"com.example.Foo\": { \"obfuscated\": \"a\" } }",
                    "\"classes\": { \"com.example.Foo\": { \"obfuscated\": \"a\", \"methods\": " +
                        "{ \"m\": [{ \"obfuscated\": \"c\", \"signature\": \"()V\", \"aidl_txn\": 2 }] } } }",
                )
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson(json) }
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test
    fun `the aidl_stub class kind value is rejected under strict parsing`() {
        // v4's `kind` enum is class|interface|enum|synthetic|anonymous — the old
        // aidl_stub / aidl_callback values are gone.
        val json =
            mapJson()
                .replace("\"obfuscated\": \"a\"", "\"obfuscated\": \"a\", \"kind\": \"aidl_stub\"")
        assertFailsWith<MapValidationException> { MapLoader.fromJson(json) }
    }

    // ---- confidence removed (#43) -------------------------------------------

    @Test
    fun `a class-entry confidence key is rejected under strict parsing`() {
        val json =
            mapJson(extra = """"sources": [{ "tool": "sigmatcher" }],""")
                .replace("\"obfuscated\": \"a\"", "\"obfuscated\": \"a\", \"confidence\": \"low\"")
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson(json) }
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test
    fun `a source confidence key is rejected under strict parsing`() {
        val json = mapJson(extra = """"sources": [{ "tool": "sigmatcher", "confidence": "high" }],""")
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson(json) }
        assertTrue(ex.message!!.contains("parse"))
    }

    // ---- captured_at ISO-date shape (#39) -----------------------------------

    @Test
    fun `a well-formed captured_at date is accepted`() {
        val map = MapLoader.fromJson(mapJson(extra = """"captured_at": "2026-06-09","""))
        assertEquals("2026-06-09", map.capturedAt)
    }

    @Test
    fun `a non-ISO captured_at is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"captured_at": "06/09/2026","""))
            }
        assertTrue(ex.issues.any { it.path == "captured_at" })
    }

    @Test
    fun `an absent captured_at is fine`() {
        assertNull(MapLoader.fromJson(mapJson()).capturedAt)
    }

    @Test
    fun `an impossible month captured_at is rejected`() {
        // Shape-valid (YYYY-MM-DD) but month 13 does not exist — LocalDate rejects.
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"captured_at": "2026-13-40","""))
            }
        assertTrue(ex.issues.any { it.path == "captured_at" })
    }

    @Test
    fun `an impossible day captured_at is rejected`() {
        // Feb 30 never exists — shape passes, calendar parse fails.
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"captured_at": "2026-02-30","""))
            }
        assertTrue(ex.issues.any { it.path == "captured_at" })
    }

    @Test
    fun `a leap-day captured_at on a non-leap year is rejected`() {
        // 2025 is not a leap year, so 2025-02-29 is not a real date.
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"captured_at": "2025-02-29","""))
            }
        assertTrue(ex.issues.any { it.path == "captured_at" })
    }

    @Test
    fun `a leap-day captured_at on a leap year is accepted`() {
        // 2024 IS a leap year, so 2024-02-29 is a real date.
        val map = MapLoader.fromJson(mapJson(extra = """"captured_at": "2024-02-29","""))
        assertEquals("2024-02-29", map.capturedAt)
    }

    // ---- signer_sha256 single string OR array (#38, #32) --------------------

    @Test
    fun `a single-string signer_sha256 decodes to a one-element list`() {
        val hash = "a".repeat(64)
        val map = MapLoader.fromJson(mapJson(extra = """"signer_sha256": "$hash","""))
        assertEquals(listOf(hash), map.signerSha256s)
    }

    @Test
    fun `an array signer_sha256 decodes to a list (match-any)`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val map = MapLoader.fromJson(mapJson(extra = """"signer_sha256": ["$a", "$b"],"""))
        assertEquals(listOf(a, b), map.signerSha256s)
    }

    @Test
    fun `an empty signer_sha256 array is rejected`() {
        // The schema pins minItems: 1; an empty array pins no signer and is meaningless.
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"signer_sha256": [],"""))
            }
        assertTrue(ex.issues.any { it.path == "signer_sha256" })
    }

    @Test
    fun `a non-string signer_sha256 value is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"signer_sha256": 123,"""))
            }
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test
    fun `a non-string signer_sha256 array entry is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"signer_sha256": ["${"a".repeat(64)}", 5],"""))
            }
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test
    fun `signer_sha256 round-trips a single hash back to a bare string`() {
        val hash = "a".repeat(64)
        val map = MapLoader.fromJson(mapJson(extra = """"signer_sha256": "$hash","""))
        val out =
            kotlinx.serialization.json.Json.encodeToString(
                io.github.xiddoc.rosetta.core.model.RosettaMap
                    .serializer(),
                map,
            )
        assertTrue(out.contains("\"signer_sha256\":\"$hash\""))
    }

    @Test
    fun `signer_sha256 round-trips multiple hashes back to an array`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val map = MapLoader.fromJson(mapJson(extra = """"signer_sha256": ["$a", "$b"],"""))
        val out =
            kotlinx.serialization.json.Json.encodeToString(
                io.github.xiddoc.rosetta.core.model.RosettaMap
                    .serializer(),
                map,
            )
        assertTrue(out.contains("\"signer_sha256\":[\"$a\",\"$b\"]"))
    }

    // ---- generated_from (#36) -----------------------------------------------

    @Test
    fun `a well-formed generated_from is accepted`() {
        // A valid abbreviated git hash is 7-40 lowercase hex chars.
        val map = MapLoader.fromJson(mapJson(extra = """"generated_from": { "signatures_rev": "abc1234" },"""))
        assertEquals(GeneratedFrom("abc1234"), map.generatedFrom)
    }

    @Test
    fun `a too-short signatures_rev is rejected`() {
        // 6 chars is below the 7-char minimum the canonical schema + frida pin.
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"generated_from": { "signatures_rev": "abc123" },"""))
            }
        assertTrue(ex.issues.any { it.path == "generated_from.signatures_rev" })
    }

    @Test
    fun `a non-hex signatures_rev is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"generated_from": { "signatures_rev": "zzzzzzz" },"""))
            }
        assertTrue(ex.issues.any { it.path == "generated_from.signatures_rev" })
    }

    @Test
    fun `a generated_from with an unknown key is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"generated_from": { "rev": "abc123" },"""))
            }
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test
    fun `a generated_from missing signatures_rev is rejected`() {
        // An EMPTY generated_from object reaches the generated missing-required-
        // field constructor arm (no unknown key to trip strict parsing first).
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"generated_from": {},"""))
            }
        assertTrue(ex.message!!.contains("parse"))
    }

    // ---- status enum + superseded_by (#40) ----------------------------------

    @Test
    fun `an absent status defaults to active`() {
        assertEquals(MapStatus.ACTIVE, MapLoader.fromJson(mapJson()).status)
    }

    @Test
    fun `status active is accepted`() {
        assertEquals(MapStatus.ACTIVE, MapLoader.fromJson(mapJson(extra = """"status": "active",""")).status)
    }

    @Test
    fun `status superseded is accepted and loads with superseded_by`() {
        val map = MapLoader.fromJson(mapJson(extra = """"status": "superseded", "superseded_by": 200,"""))
        assertEquals(MapStatus.SUPERSEDED, map.status)
        assertEquals(200L, map.supersededBy)
    }

    @Test
    fun `an unknown status value is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"status": "deprecated","""))
            }
        assertTrue(ex.message!!.contains("parse"))
    }

    // ---- status / superseded_by cross-field rule (#40, both directions) -----

    @Test
    fun `superseded status with superseded_by is accepted`() {
        val map = MapLoader.fromJson(mapJson(extra = """"status": "superseded", "superseded_by": 200,"""))
        assertEquals(MapStatus.SUPERSEDED, map.status)
        assertEquals(200L, map.supersededBy)
    }

    @Test
    fun `superseded status without superseded_by is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"status": "superseded","""))
            }
        assertTrue(ex.issues.any { it.path == "superseded_by" })
    }

    @Test
    fun `superseded_by on an active (absent-status) map is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"superseded_by": 200,"""))
            }
        assertTrue(ex.issues.any { it.path == "superseded_by" })
    }

    @Test
    fun `superseded_by on an explicit active map is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"status": "active", "superseded_by": 200,"""))
            }
        assertTrue(ex.issues.any { it.path == "superseded_by" })
    }

    // ---- superseded_by numeric bounds (both directions) ---------------------

    @Test
    fun `a negative superseded_by is rejected`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"status": "superseded", "superseded_by": -1,"""))
            }
        assertTrue(ex.issues.any { it.path == "superseded_by" })
    }

    @Test
    fun `an over-max superseded_by is rejected`() {
        val tooBig = MapLoader.MAX_VERSION_CODE + 1
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.fromJson(mapJson(extra = """"status": "superseded", "superseded_by": $tooBig,"""))
            }
        assertTrue(ex.issues.any { it.path == "superseded_by" })
    }

    @Test
    fun `a superseded_by at the max bound is accepted`() {
        val max = MapLoader.MAX_VERSION_CODE
        val map = MapLoader.fromJson(mapJson(extra = """"status": "superseded", "superseded_by": $max,"""))
        assertEquals(max, map.supersededBy)
    }

    // ---- retracted refused / superseded loads -------------------------------

    @Test
    fun `a retracted map is refused at load`() {
        val ex =
            assertFailsWith<RetractedMapException> {
                MapLoader.fromJson(mapJson(extra = """"status": "retracted","""))
            }
        assertTrue(ex.message!!.contains("RETRACTED"))
    }

    @Test
    fun `a superseded map loads without refusal`() {
        // Superseded is a soft signal (warned at health-check), not a load-time
        // refusal — it must load. (The cross-field rule requires superseded_by.)
        val map = MapLoader.fromJson(mapJson(extra = """"status": "superseded", "superseded_by": 200,"""))
        assertEquals(MapStatus.SUPERSEDED, map.status)
    }

    @Test
    fun `a structurally-invalid retracted map reports its structural issue first`() {
        // A retracted map that is ALSO malformed (negative version_code) fails on
        // the structural validation before the retraction refusal, so the caller
        // sees the validation issue (not the retraction) — validate runs first.
        val bad = mapJson(extra = """"status": "retracted",""").replace("\"version_code\": 100", "\"version_code\": -1")
        assertFailsWith<MapValidationException> { MapLoader.fromJson(bad) }
    }

    // ---- SignerSha256Serializer direct branch coverage ----------------------

    @Test
    fun `SignerSha256Serializer decodes a bare string and an array directly`() {
        val one = strictJson.decodeFromString(SignerSha256Serializer, "\"deadbeef\"")
        assertEquals(listOf("deadbeef"), one)
        val many = strictJson.decodeFromString(SignerSha256Serializer, """["a","b"]""")
        assertEquals(listOf("a", "b"), many)
    }

    @Test
    fun `SignerSha256Serializer rejects a non-string primitive`() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            strictJson.decodeFromString(SignerSha256Serializer, "42")
        }
    }

    @Test
    fun `SignerSha256Serializer rejects a JSON null and a JSON object`() {
        // The non-primitive, non-array `else` arm (JsonNull / JsonObject).
        assertFailsWith<kotlinx.serialization.SerializationException> {
            strictJson.decodeFromString(SignerSha256Serializer, "null")
        }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            strictJson.decodeFromString(SignerSha256Serializer, "{}")
        }
    }

    @Test
    fun `SignerSha256Serializer rejects a non-primitive array entry`() {
        // An array element that is an object (not a JsonPrimitive) is rejected.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            strictJson.decodeFromString(SignerSha256Serializer, """[{}]""")
        }
    }

    @Test
    fun `SignerSha256Serializer rejects a non-string array primitive`() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            strictJson.decodeFromString(SignerSha256Serializer, """["ok", 5]""")
        }
    }

    @Test
    fun `SignerSha256Serializer encodes single bare and multi as array`() {
        assertEquals("\"x\"", strictJson.encodeToString(SignerSha256Serializer, listOf("x")))
        assertEquals("""["x","y"]""", strictJson.encodeToString(SignerSha256Serializer, listOf("x", "y")))
    }

    @Test
    fun `SignerSha256Serializer rejects a non-JSON encoder and decoder`() {
        // JSON-only, like ClientHintsSerializer: a non-JSON format trips both guards.
        assertFailsWith<IllegalStateException> {
            kotlinx.serialization.properties.Properties
                .encodeToMap(SignerSha256Serializer, listOf("x"))
        }
        assertFailsWith<IllegalStateException> {
            kotlinx.serialization.properties.Properties
                .decodeFromMap(SignerSha256Serializer, emptyMap())
        }
    }

    // ---- GeneratedFrom serialization round-trip -----------------------------

    @Test
    fun `GeneratedFrom round-trips and exposes its serializer`() {
        val gf = GeneratedFrom("rev-1")
        val out = strictJson.encodeToString(GeneratedFrom.serializer(), gf)
        assertTrue(out.contains("signatures_rev"))
        assertEquals(gf, strictJson.decodeFromString(GeneratedFrom.serializer(), out))
    }

    private companion object {
        private val strictJson = kotlinx.serialization.json.Json
    }
}
