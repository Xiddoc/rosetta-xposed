/*
 * ClassEntryCodec tests — the single-entry JSON codec the persistent discovery
 * cache (rosetta-xposed#19) round-trips a discovered [ClassEntry] through.
 *
 * Covers a full-fidelity round-trip (incl. the MethodOverloads object/array
 * form, fields, anchors, kind, and provenance), and the FAIL-SOFT decode: a
 * corrupt or schema-drifted string is a `null` (a cache miss), never a throw.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.ClassEntryCodec
import io.github.xiddoc.rosetta.core.model.ClassKind
import io.github.xiddoc.rosetta.core.model.Confidence
import io.github.xiddoc.rosetta.core.model.FieldEntry
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.MethodOverloads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClassEntryCodecTest {
    @Test
    fun `a minimal entry round-trips`() {
        val entry = ClassEntry(obfuscated = "aaaa")
        val decoded = ClassEntryCodec.decodeOrNull(ClassEntryCodec.encode(entry))
        assertEquals(entry, decoded)
    }

    @Test
    fun `a rich entry round-trips with methods fields anchors kind and provenance`() {
        val entry =
            ClassEntry(
                obfuscated = "aaaa",
                extends = "zzzz",
                kind = ClassKind.AIDL_STUB,
                aidlDescriptor = "Lcom/example/IFoo;",
                anchors = listOf("login_token", "session_id"),
                methods =
                    mapOf(
                        // One overload → object form on the wire.
                        "single" to
                            MethodOverloads(
                                listOf(MethodEntry(obfuscated = "c", signature = "(Ljava/lang/String;)V", aidlTxn = 2)),
                            ),
                        // Two overloads → array form on the wire.
                        "multi" to
                            MethodOverloads(
                                listOf(
                                    MethodEntry(obfuscated = "d", signature = "(I)V"),
                                    MethodEntry(obfuscated = "d", signature = "(J)V"),
                                ),
                            ),
                    ),
                fields = mapOf("id" to FieldEntry(obfuscated = "a", type = "Ljava/lang/String;")),
                source = "rosetta-runtime-discovered",
                confidence = Confidence.LOW,
            )
        val decoded = ClassEntryCodec.decodeOrNull(ClassEntryCodec.encode(entry))
        assertEquals(entry, decoded)
    }

    @Test
    fun `decodeOrNull returns null on a non-JSON string`() {
        assertNull(ClassEntryCodec.decodeOrNull("not json at all"))
    }

    @Test
    fun `decodeOrNull returns null on JSON missing the required obfuscated field`() {
        // Structurally valid JSON, but not a valid ClassEntry (no `obfuscated`).
        assertNull(ClassEntryCodec.decodeOrNull("""{"extends":"zzzz"}"""))
    }

    @Test
    fun `decodeOrNull rejects an unknown key (strict, like the map loader)`() {
        // A stray / typo'd key fails the strict decode → treated as a miss.
        assertNull(ClassEntryCodec.decodeOrNull("""{"obfuscated":"aaaa","bogus":1}"""))
    }
}
