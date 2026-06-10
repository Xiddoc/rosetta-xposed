/*
 * Value-semantics + serialization-branch coverage for the schema-mirror DTOs.
 *
 * The map model is a set of Kotlin `data class`es and `@Serializable` types.
 * Beyond the happy-path round-trips in CoreTest, two families of generated
 * bytecode need exercising to reach 100%:
 *
 *  1. The generated `equals`/`hashCode`/`copy`/`toString` of every data class.
 *     `equals` in particular branches once PER FIELD (a == b only if every
 *     component is equal), so we assert: equality to self and to an identical
 *     instance; inequality to null and to a foreign type; and inequality when
 *     EACH field differs one-at-a-time — which walks every per-field branch.
 *     `hashCode` consistency, a `copy(...)` with a changed field, and a
 *     non-empty `toString` round it out. A small table-driven helper keeps the
 *     per-field-difference assertions compact.
 *
 *  2. The kotlinx-serialization `write$Self` of each `@Serializable` DTO.
 *     With `encodeDefaults = false`, every optional field compiles to an
 *     `if (value != default) encodeElement(...)` guard. We serialize each DTO
 *     twice — once with every optional at its default (the skip arm) and once
 *     with every optional set to a non-default value (the encode arm) — so both
 *     arms of every guard are hit. The synthetic `$Companion.serializer()`
 *     accessors are covered for free by naming each `serializer()`.
 *
 * This is the honest, value-level way to cover the generated members: it
 * exercises real semantics (equality, copying, JSON shape), not a coverage
 * trick, and it is why the build needs NO kover excludes on these DTOs.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.ClassKind
import io.github.xiddoc.rosetta.core.model.ClientHints
import io.github.xiddoc.rosetta.core.model.FieldEntry
import io.github.xiddoc.rosetta.core.model.GeneratedFrom
import io.github.xiddoc.rosetta.core.model.MapSource
import io.github.xiddoc.rosetta.core.model.MapStatus
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.MethodOverloads
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.resolver.DiscoveredClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DataClassSemanticsTest {
    private val json = Json { ignoreUnknownKeys = true }

    // A second format with encodeDefaults = true. Each optional field in a
    // generated `write$Self` compiles to `if (shouldEncodeElementDefault(...)
    // || value != default) encode`. With the default `encodeDefaults = false`
    // the first operand is always false (only the `value != default` arm can
    // fire); encoding the same value through this format flips it true, so the
    // short-circuit's other arm is exercised too.
    private val jsonEncodeDefaults = Json { encodeDefaults = true }

    // ---- Generic value-semantics harness ------------------------------------

    /**
     * Asserts the full data-class contract for [base]:
     *  - reflexive + symmetric equality to an [identical] copy,
     *  - inequality to null and to a foreign type,
     *  - inequality to every [variants] instance (each differs in one field,
     *    which forces a distinct branch of the generated `equals`),
     *  - `hashCode` agreement between equal instances and consistency,
     *  - a non-empty `toString`.
     */
    private fun <T : Any> assertValueSemantics(
        base: T,
        identical: T,
        variants: List<T>,
    ) {
        // Reflexive + structural equality.
        assertEquals(base, base)
        assertEquals(base, identical)
        assertEquals(identical, base)
        // Equal instances agree on hashCode; hashCode is stable across calls.
        assertEquals(base.hashCode(), identical.hashCode())
        assertEquals(base.hashCode(), base.hashCode())
        // Inequality to null and to a value of a different type.
        assertNotEquals<Any?>(base, null)
        assertNotEquals<Any?>(base, "not the same type")
        // Differ-one-field-at-a-time walks each per-field branch of equals.
        for (v in variants) {
            assertNotEquals(base, v)
            assertNotEquals(v, base)
        }
        // toString renders something.
        assertTrue(base.toString().isNotEmpty())
    }

    @Test
    fun `MethodEntry has value semantics across every field`() {
        val base = MethodEntry("m", "()V", static = true, synthetic = true, isConstructor = true)
        assertValueSemantics(
            base = base,
            identical = MethodEntry("m", "()V", static = true, synthetic = true, isConstructor = true),
            variants =
                listOf(
                    base.copy(obfuscated = "x"),
                    base.copy(signature = "(I)V"),
                    base.copy(static = false),
                    base.copy(synthetic = false),
                    base.copy(isConstructor = false),
                ),
        )
        assertEquals("x", base.copy(obfuscated = "x").obfuscated)
    }

    @Test
    fun `FieldEntry has value semantics across every field`() {
        val base = FieldEntry("f", "I", static = true)
        assertValueSemantics(
            base = base,
            identical = FieldEntry("f", "I", static = true),
            variants =
                listOf(
                    base.copy(obfuscated = "g"),
                    base.copy(type = "J"),
                    base.copy(static = false),
                ),
        )
        assertEquals("J", base.copy(type = "J").type)
    }

    @Test
    fun `MapSource has value semantics across every field`() {
        val base =
            MapSource(
                tool = "sigmatcher",
                config = "cfg",
                classes = 3,
                notes = "n",
            )
        assertValueSemantics(
            base = base,
            identical = MapSource("sigmatcher", "cfg", 3, "n"),
            variants =
                listOf(
                    base.copy(tool = "hand-authored"),
                    base.copy(config = "other"),
                    base.copy(classes = 4),
                    base.copy(notes = "m"),
                ),
        )
        assertEquals(4, base.copy(classes = 4).classes)
    }

    @Test
    fun `ClientHints has value semantics across every field`() {
        val base = ClientHints(fridaMinVersion = "16.0.0", fridaMaxVersion = "17.0.0")
        assertValueSemantics(
            base = base,
            identical = ClientHints("16.0.0", "17.0.0"),
            variants =
                listOf(
                    base.copy(fridaMinVersion = "15.0.0"),
                    base.copy(fridaMaxVersion = "18.0.0"),
                ),
        )
        assertEquals("15.0.0", base.copy(fridaMinVersion = "15.0.0").fridaMinVersion)
    }

    @Test
    fun `MethodOverloads has value semantics`() {
        val base = MethodOverloads(listOf(MethodEntry("a", "()V"), MethodEntry("b", "(I)V")))
        assertValueSemantics(
            base = base,
            identical = MethodOverloads(listOf(MethodEntry("a", "()V"), MethodEntry("b", "(I)V"))),
            variants = listOf(base.copy(entries = listOf(MethodEntry("a", "()V")))),
        )
        assertEquals(1, base.copy(entries = listOf(MethodEntry("a", "()V"))).entries.size)
    }

    @Test
    fun `ClassEntry has value semantics across every field`() {
        val base =
            ClassEntry(
                obfuscated = "a",
                extends = "com.example.Base",
                kind = ClassKind.CLASS,
                dex = "shard-1",
                methods = mapOf("m" to MethodOverloads(listOf(MethodEntry("a", "()V")))),
                fields = mapOf("f" to FieldEntry("a", "I")),
                source = "sigmatcher",
            )
        assertValueSemantics(
            base = base,
            identical =
                ClassEntry(
                    obfuscated = "a",
                    extends = "com.example.Base",
                    kind = ClassKind.CLASS,
                    dex = "shard-1",
                    methods = mapOf("m" to MethodOverloads(listOf(MethodEntry("a", "()V")))),
                    fields = mapOf("f" to FieldEntry("a", "I")),
                    source = "sigmatcher",
                ),
            variants =
                listOf(
                    base.copy(obfuscated = "b"),
                    base.copy(extends = "com.example.Other"),
                    base.copy(kind = ClassKind.INTERFACE),
                    base.copy(dex = "shard-2"),
                    base.copy(methods = mapOf("n" to MethodOverloads(listOf(MethodEntry("a", "()V"))))),
                    base.copy(fields = mapOf("g" to FieldEntry("a", "I"))),
                    base.copy(source = "hand-authored"),
                ),
        )
        assertEquals("b", base.copy(obfuscated = "b").obfuscated)
    }

    @Test
    fun `RosettaMap has value semantics across every field`() {
        val base =
            RosettaMap(
                schemaVersion = 4,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                capturedAt = "2026-01-01",
                signerSha256s = listOf("deadbeef"),
                clientHints = ClientHints(fridaMinVersion = "16.0.0", fridaMaxVersion = "17.0.0"),
                generatedFrom = GeneratedFrom(signaturesRev = "abc123"),
                status = MapStatus.ACTIVE,
                supersededBy = null,
                sources = listOf(MapSource("sigmatcher")),
                classes = mapOf("com.example.Foo" to ClassEntry("a")),
            )
        assertValueSemantics(
            base = base,
            identical =
                RosettaMap(
                    schemaVersion = 4,
                    app = "com.example.app",
                    version = "1.0.0",
                    versionCode = 100,
                    capturedAt = "2026-01-01",
                    signerSha256s = listOf("deadbeef"),
                    clientHints = ClientHints(fridaMinVersion = "16.0.0", fridaMaxVersion = "17.0.0"),
                    generatedFrom = GeneratedFrom(signaturesRev = "abc123"),
                    status = MapStatus.ACTIVE,
                    supersededBy = null,
                    sources = listOf(MapSource("sigmatcher")),
                    classes = mapOf("com.example.Foo" to ClassEntry("a")),
                ),
            variants =
                listOf(
                    base.copy(schemaVersion = 3),
                    base.copy(app = "com.other.app"),
                    base.copy(version = "2.0.0"),
                    base.copy(versionCode = 200),
                    base.copy(capturedAt = "2026-02-02"),
                    base.copy(signerSha256s = listOf("beefdead")),
                    base.copy(signerSha256s = listOf("deadbeef", "beefdead")),
                    base.copy(clientHints = ClientHints(fridaMinVersion = "15.0.0")),
                    base.copy(clientHints = ClientHints(fridaMaxVersion = "18.0.0")),
                    base.copy(generatedFrom = GeneratedFrom(signaturesRev = "def456")),
                    base.copy(status = MapStatus.SUPERSEDED),
                    base.copy(supersededBy = 200L),
                    base.copy(sources = listOf(MapSource("hand-authored"))),
                    base.copy(classes = mapOf("com.example.Bar" to ClassEntry("b"))),
                ),
        )
        assertEquals(200, base.copy(versionCode = 200).versionCode)
    }

    @Test
    fun `Resolved types have value semantics`() {
        val rc = ResolvedClass("com.example.Foo", "a", extends = "zzzz")
        assertValueSemantics(
            base = rc,
            identical = ResolvedClass("com.example.Foo", "a", extends = "zzzz"),
            variants =
                listOf(
                    rc.copy(realName = "com.example.Bar"),
                    rc.copy(obfName = "b"),
                    rc.copy(extends = "yyyy"),
                ),
        )
        assertEquals("b", rc.copy(obfName = "b").obfName)

        val rm =
            ResolvedMethod(
                realName = "single",
                obfName = "c",
                className = "a",
                signature = "()V",
                static = true,
                synthetic = false,
                isConstructor = false,
                allOverloads = listOf(MethodEntry("c", "()V")),
            )
        assertValueSemantics(
            base = rm,
            identical =
                ResolvedMethod(
                    realName = "single",
                    obfName = "c",
                    className = "a",
                    signature = "()V",
                    static = true,
                    synthetic = false,
                    isConstructor = false,
                    allOverloads = listOf(MethodEntry("c", "()V")),
                ),
            variants =
                listOf(
                    rm.copy(realName = "other"),
                    rm.copy(obfName = "d"),
                    rm.copy(className = "b"),
                    rm.copy(signature = "(I)V"),
                    rm.copy(static = false),
                    // Tri-state flags are distinct values: null != false != true.
                    rm.copy(static = null),
                    rm.copy(synthetic = true),
                    rm.copy(synthetic = null),
                    rm.copy(isConstructor = true),
                    rm.copy(isConstructor = null),
                    rm.copy(allOverloads = listOf(MethodEntry("d", "()V"))),
                ),
        )
        assertEquals("d", rm.copy(obfName = "d").obfName)

        val rf = ResolvedField("id", "a", "c", "Ljava/lang/String;", static = true)
        assertValueSemantics(
            base = rf,
            identical = ResolvedField("id", "a", "c", "Ljava/lang/String;", true),
            variants =
                listOf(
                    rf.copy(realName = "name"),
                    rf.copy(obfName = "b"),
                    rf.copy(className = "d"),
                    rf.copy(type = "I"),
                    rf.copy(static = false),
                ),
        )
        assertEquals("I", rf.copy(type = "I").type)
    }

    @Test
    fun `DiscoveredClass has value semantics and carries only resolver-relevant fields`() {
        val methods = mapOf("single" to MethodOverloads(listOf(MethodEntry("c", "()V"))))
        val fields = mapOf("id" to FieldEntry("f", "Ljava/lang/String;"))
        val dc =
            DiscoveredClass(
                realName = "com.example.Foo",
                obfName = "a",
                extends = "zzzz",
                methods = methods,
                fields = fields,
            )
        assertValueSemantics(
            base = dc,
            identical = DiscoveredClass("com.example.Foo", "a", "zzzz", methods, fields),
            variants =
                listOf(
                    dc.copy(realName = "com.example.Bar"),
                    dc.copy(obfName = "b"),
                    dc.copy(extends = "yyyy"),
                    dc.copy(methods = null),
                    dc.copy(fields = null),
                ),
        )
        assertEquals("a", dc.obfName)
    }

    // ---- write$Self optional-field branch coverage --------------------------

    /**
     * Serializes [allDefaults] (every optional at its default → the skip arm of
     * each `write$Self` guard) and [allSet] (every optional non-default → the
     * encode arm), proving both arms of every generated optional-field branch
     * are exercised. Naming [serializer] also covers the `$Companion.serializer`
     * accessor line.
     */
    private fun <T : Any> assertWriteSelfBranches(
        serializer: KSerializer<T>,
        allDefaults: T,
        allSet: T,
    ) {
        val skipArm = json.encodeToString(serializer, allDefaults)
        val encodeArm = json.encodeToString(serializer, allSet)
        // The encode-arm output names more keys than the skip arm: optional
        // fields appear only when non-default.
        assertTrue(encodeArm.length > skipArm.length)
        // Round-trips back to equal values (defensive — also exercises read).
        assertEquals(allDefaults, json.decodeFromString(serializer, skipArm))
        assertEquals(allSet, json.decodeFromString(serializer, encodeArm))
        // With encodeDefaults = true the `shouldEncodeElementDefault` operand
        // of each optional-field guard is true, so the all-defaults value now
        // emits every optional key — covering the other arm of the guard.
        val forced = jsonEncodeDefaults.encodeToString(serializer, allDefaults)
        assertTrue(forced.length > skipArm.length)
        // Also run the all-set value with encodeDefaults = true: this combines
        // the shouldEncodeElementDefault = true operand with non-default values,
        // covering the remaining short-circuit arm of each guard.
        val forcedSet = jsonEncodeDefaults.encodeToString(serializer, allSet)
        assertTrue(forcedSet.isNotEmpty())
    }

    @Test
    fun `MethodEntry write$Self covers both arms of every optional`() {
        assertWriteSelfBranches(
            MethodEntry.serializer(),
            allDefaults = MethodEntry("m", "()V"),
            allSet = MethodEntry("m", "()V", static = true, synthetic = true, isConstructor = true),
        )
    }

    @Test
    fun `FieldEntry write$Self covers both arms of every optional`() {
        assertWriteSelfBranches(
            FieldEntry.serializer(),
            allDefaults = FieldEntry("f", "I"),
            allSet = FieldEntry("f", "I", static = true),
        )
    }

    @Test
    fun `MapSource write$Self covers both arms of every optional`() {
        assertWriteSelfBranches(
            MapSource.serializer(),
            allDefaults = MapSource("sigmatcher"),
            allSet = MapSource("sigmatcher", config = "cfg", classes = 3, notes = "n"),
        )
    }

    @Test
    fun `ClassEntry write$Self covers both arms of every optional`() {
        assertWriteSelfBranches(
            ClassEntry.serializer(),
            allDefaults = ClassEntry("a"),
            allSet =
                ClassEntry(
                    obfuscated = "a",
                    extends = "com.example.Base",
                    kind = ClassKind.CLASS,
                    dex = "shard-1",
                    methods = mapOf("m" to MethodOverloads(listOf(MethodEntry("a", "()V")))),
                    fields = mapOf("f" to FieldEntry("a", "I")),
                    source = "sigmatcher",
                ),
        )
    }

    @Test
    fun `RosettaMap write$Self covers both arms of every optional`() {
        assertWriteSelfBranches(
            RosettaMap.serializer(),
            allDefaults =
                RosettaMap(
                    schemaVersion = 4,
                    app = "com.example.app",
                    version = "1.0.0",
                    versionCode = 100,
                    classes = mapOf("com.example.Foo" to ClassEntry("a")),
                ),
            allSet =
                RosettaMap(
                    schemaVersion = 4,
                    app = "com.example.app",
                    version = "1.0.0",
                    versionCode = 100,
                    capturedAt = "2026-01-01",
                    signerSha256s = listOf("deadbeef"),
                    clientHints = ClientHints(fridaMinVersion = "16.0.0", fridaMaxVersion = "17.0.0"),
                    generatedFrom = GeneratedFrom(signaturesRev = "abc123"),
                    status = MapStatus.SUPERSEDED,
                    supersededBy = 200L,
                    sources = listOf(MapSource("sigmatcher")),
                    classes = mapOf("com.example.Foo" to ClassEntry("a")),
                ),
        )
    }

    @Test
    fun `ClientHintsSerializer round-trips every field-presence combination`() {
        // ClientHints uses a hand-rolled serializer (the auto-generated all-optional
        // serializer carries a branch no public decoder can reach). Round-trip all
        // four (min, max) presence combos so every per-field emit/skip arm of both
        // serialize and deserialize is exercised; absent keys are omitted on emit.
        val cases =
            mapOf(
                ClientHints() to "{}",
                ClientHints(fridaMinVersion = "16.0.0") to """{"frida_min_version":"16.0.0"}""",
                ClientHints(fridaMaxVersion = "17.0.0") to """{"frida_max_version":"17.0.0"}""",
                ClientHints(fridaMinVersion = "16.0.0", fridaMaxVersion = "17.0.0") to
                    """{"frida_min_version":"16.0.0","frida_max_version":"17.0.0"}""",
            )
        for ((value, expectedJson) in cases) {
            assertEquals(expectedJson, json.encodeToString(ClientHints.serializer(), value))
            assertEquals(value, json.decodeFromString(ClientHints.serializer(), expectedJson))
        }
        // Reordered keys decode the same (the serializer is name-keyed, not
        // positional), exercising both lookups with both keys present.
        assertEquals(
            ClientHints(fridaMinVersion = "16.0.0", fridaMaxVersion = "17.0.0"),
            json.decodeFromString(
                ClientHints.serializer(),
                """{"frida_max_version":"17.0.0","frida_min_version":"16.0.0"}""",
            ),
        )
    }

    @Test
    fun `ClientHintsSerializer rejects an unknown key and a non-object`() {
        // Strict (additionalProperties: false): an unknown key inside client_hints
        // is rejected, matching the canonical schema and the Frida twin.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(ClientHints.serializer(), """{"frida_min_version":"16.0.0","mystery":true}""")
        }
        // A non-object client_hints value is rejected.
        assertFailsWith<IllegalStateException> {
            json.decodeFromString(ClientHints.serializer(), "\"not-an-object\"")
        }
    }

    @Test
    fun `ClientHintsSerializer rejects a non-JSON encoder and decoder`() {
        // The serializer is JSON-only (like MethodOverloadsSerializer); Properties
        // is a non-JSON format, so both the encode and decode guards fire.
        assertFailsWith<IllegalStateException> {
            kotlinx.serialization.properties.Properties.encodeToMap(
                ClientHints.serializer(),
                ClientHints(fridaMinVersion = "16.0.0"),
            )
        }
        assertFailsWith<IllegalStateException> {
            kotlinx.serialization.properties.Properties
                .decodeFromMap(ClientHints.serializer(), emptyMap())
        }
    }

    // ---- Generated deserialization-constructor missing-required-field branch.
    //
    // Each @Serializable DTO also gets a synthetic `<init>(seen, ...)`
    // constructor whose `seen`-bitmask check calls throwMissingFieldException
    // when a REQUIRED field is absent. The round-trips above only ever hit the
    // "all required present" arm; decoding JSON that omits a required field
    // hits the throw arm, closing the last generated branch on each DTO.

    @Test
    fun `decoding a DTO missing a required field throws (deserializer bitmask arm)`() {
        // MethodEntry requires obfuscated + signature.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(MethodEntry.serializer(), """{"obfuscated":"c"}""")
        }
        // FieldEntry requires obfuscated + type.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(FieldEntry.serializer(), """{"obfuscated":"a"}""")
        }
        // MapSource requires tool.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(MapSource.serializer(), """{"config":"cfg"}""")
        }
        // ClassEntry requires obfuscated.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(ClassEntry.serializer(), """{"kind":"class"}""")
        }
        // RosettaMap requires schema_version, app, version, version_code, classes.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(RosettaMap.serializer(), """{"app":"com.example.app"}""")
        }
    }
}
