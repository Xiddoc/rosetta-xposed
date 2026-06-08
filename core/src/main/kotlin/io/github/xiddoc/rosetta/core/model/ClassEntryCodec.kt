/*
 * Single-entry JSON codec for a [ClassEntry].
 *
 * A persistent on-device discovery cache (rosetta-xposed#19) needs to write a
 * discovered [ClassEntry] to durable storage and read it back across process
 * restarts. The cache itself lives in the layer-4 modules (:xposed seam,
 * :android-runtime persistent impl), but the kotlinx-serialization runtime and
 * the generated `ClassEntry.serializer()` live HERE — `:core` is the only
 * module that applies the serialization plugin and depends on
 * `kotlinx-serialization-json`. Exposing a tiny codec here keeps that
 * dependency confined to `:core` (the layer-4 modules stay free of a direct
 * serialization dependency) while reusing the exact same `schema_version: 2`
 * field shapes (incl. the [MethodOverloads] object/array form) the map loader
 * uses, so a cached entry round-trips identically to one read from a map file.
 *
 * This is NOT a map loader: it encodes/decodes a LONE class entry (the cache's
 * value type), not a whole [RosettaMap], and applies no bounds validation — a
 * cached obfuscated FQN is still routed through the same C1 target guard as a
 * static one when the binding realises it, so a tampered cache value cannot
 * widen the trust surface (see DynamicResolutionBackend / RosettaXposed).
 */
package io.github.xiddoc.rosetta.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Encodes / decodes a single [ClassEntry] to and from the canonical JSON form. */
public object ClassEntryCodec {
    private val json: Json =
        Json {
            // Strict, mirroring MapLoader: an unknown / typo'd key is rejected so
            // a tampered or stale cache value fails the decode (the caller then
            // treats it as a miss and re-discovers) rather than loading silently.
            ignoreUnknownKeys = false
            isLenient = false
        }

    /** Serialize [entry] to a compact JSON string. */
    public fun encode(entry: ClassEntry): String = json.encodeToString(ClassEntry.serializer(), entry)

    /**
     * Decode a [ClassEntry] from [text], or `null` if [text] is not a valid
     * encoding of one. A cache read must be FAIL-SOFT: a corrupt or
     * schema-drifted stored value is a cache miss (re-discover), never a thrown
     * exception that aborts resolution. Confining the `catch` here also keeps
     * the serialization dependency — and its [SerializationException] type —
     * inside `:core`, so the layer-4 cache can stay serialization-free.
     */
    public fun decodeOrNull(text: String): ClassEntry? =
        try {
            json.decodeFromString(ClassEntry.serializer(), text)
        } catch (ignored: SerializationException) {
            // Deliberately turned into a miss (not rethrown): an undecodable value
            // is a stale/corrupt cache entry, so the caller re-discovers. ONLY a
            // serialization failure is caught — any other throwable still propagates.
            null
        }
}
