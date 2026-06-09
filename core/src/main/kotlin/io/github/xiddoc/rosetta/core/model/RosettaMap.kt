/*
 * Mapping-file model — the on-disk schema, loaded into memory.
 *
 * This is the Kotlin twin of rosetta-frida's `src/types/map.ts`. The
 * shapes are LOCKED to the same `schema_version: 3` contract so the same
 * JSON artifact deserializes identically on both sides. Do not diverge
 * field names or optionality without a matching change on the Frida side
 * (and a schema bump).
 *
 * The on-disk format is strict JSON (one map per `(app, version_code)`
 * file). Comment-bearing YAML / TS modules are *authoring inputs*
 * converted to JSON upstream — never parsed here.
 */
package io.github.xiddoc.rosetta.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The current map schema version — the single source of truth on the
 * Kotlin side. Kept in lockstep with rosetta-frida's
 * `CURRENT_SCHEMA_VERSION`. The loader hard-gates on this value.
 */
public const val CURRENT_SCHEMA_VERSION: Int = 3

/**
 * Lifecycle status of a published map (schema 3, maps#40). A map with no
 * `status` is [ACTIVE]. A [RETRACTED] map is REFUSED at load time
 * ([io.github.xiddoc.rosetta.core.MapLoader.fromJson] throws), and a
 * [SUPERSEDED] map produces a warning at health-check time. Kept in lockstep
 * with the canonical schema's `active|superseded|retracted` enum.
 */
@Serializable
public enum class MapStatus {
    /** The default: the map is current and consumable. Absent `status` ⇒ this. */
    @SerialName("active")
    ACTIVE,

    /** A newer map exists (see [RosettaMap.supersededBy]); usable but warned. */
    @SerialName("superseded")
    SUPERSEDED,

    /** The map was withdrawn (e.g. found wrong); refused fail-closed at load. */
    @SerialName("retracted")
    RETRACTED,
}

/** What kind of class an entry describes. */
@Serializable
public enum class ClassKind {
    @SerialName("class")
    CLASS,

    @SerialName("interface")
    INTERFACE,

    @SerialName("enum")
    ENUM,

    @SerialName("aidl_stub")
    AIDL_STUB,

    @SerialName("aidl_callback")
    AIDL_CALLBACK,

    @SerialName("synthetic")
    SYNTHETIC,

    @SerialName("anonymous")
    ANONYMOUS,
}

/** Provenance source for a map (or subset of entries). */
@Serializable
public data class MapSource(
    /** Tool name: `sigmatcher` | `hand-authored` | `rosetta-runtime-discovered` | other. */
    val tool: String,
    /** Optional config / config-path that produced these entries. */
    val config: String? = null,
    /** Optional count of classes attributed to this source. */
    val classes: Int? = null,
    /** Free-form notes (e.g. "verified via runtime trace"). */
    val notes: String? = null,
)

/** One method overload. */
@Serializable
public data class MethodEntry(
    /** Obfuscated method name (e.g. "c", "f"). */
    val obfuscated: String,
    /**
     * JVM descriptor signature with obfuscated names for class refs,
     * e.g. `(Landroid/os/Bundle;Lbbbb;)V`.
     */
    val signature: String,
    /** AIDL transaction code, if this is a binder dispatch target. */
    @SerialName("aidl_txn") val aidlTxn: Int? = null,
    /** Whether the method is static. */
    val static: Boolean? = null,
    /** Whether the method is synthetic (compiler-generated). */
    val synthetic: Boolean? = null,
    /** Whether this is a constructor (`<init>`). */
    @SerialName("is_constructor") val isConstructor: Boolean? = null,
)

/** One field on a class. */
@Serializable
public data class FieldEntry(
    /** Obfuscated field name (e.g. "a", "b"). */
    val obfuscated: String,
    /** JVM descriptor type. Class refs use the obfuscated name (e.g. `Lbbbb;`). */
    val type: String,
    /** Whether the field is static. */
    val static: Boolean? = null,
)

/**
 * One class entry, keyed in [RosettaMap.classes] by its real
 * fully-qualified name.
 */
@Serializable
public data class ClassEntry(
    /** Obfuscated short name (e.g. "aaaa"). */
    val obfuscated: String,
    /**
     * Parent class — a real name (also a key in `classes`) or an
     * obfuscated name for parents we have no real-name mapping for.
     */
    val extends: String? = null,
    /** What kind of class this is. */
    val kind: ClassKind? = null,
    /** DEX shard (optional debugging metadata). */
    val dex: String? = null,
    /** AIDL interface descriptor — the stable cross-version anchor. */
    @SerialName("aidl_descriptor") val aidlDescriptor: String? = null,
    /** Stable string literals contained in this class, for self-healing discovery. */
    val anchors: List<String>? = null,
    /** Methods keyed by real name (see [Methods]). */
    val methods: Methods? = null,
    /** Fields keyed by real name. */
    val fields: Map<String, FieldEntry>? = null,
    /** Which source contributed this entry (cross-reference into [RosettaMap.sources]). */
    val source: String? = null,
)

/**
 * Optional, client-specific hints nested under a map's `client_hints`
 * sub-object. The canonical schema groups per-client metadata here (with its
 * own `additionalProperties: false`) rather than at the top level, so an
 * unknown hint key fails loudly on both clients. The Kotlin twin of
 * rosetta-frida's `ClientHints`. Frida reads the `frida_min_version` /
 * `frida_max_version` range; other clients (this one) ignore it.
 */
@Serializable(with = ClientHintsSerializer::class)
public data class ClientHints(
    /** Minimum Frida version this map is known to work with (Frida-only hint). */
    val fridaMinVersion: String? = null,
    /** Maximum Frida version this map is known to work with (Frida-only hint). */
    val fridaMaxVersion: String? = null,
)

/**
 * Hand-rolled JSON serializer for [ClientHints], mirroring the project's
 * [MethodOverloadsSerializer] pattern. An auto-generated `@Serializable` on an
 * all-optional data class emits a defensive generated branch that no public
 * decoder can reach (a known kotlinx-serialization / coverage interaction), so
 * we serialize the two optional hint fields by hand instead — every branch here
 * is our own code and reachable by tests.
 *
 * STRICT: an unknown key inside `client_hints` is rejected (the canonical
 * schema's `additionalProperties: false`), keeping a map that loads on one
 * client loading on all.
 */
public object ClientHintsSerializer : KSerializer<ClientHints> {
    private const val MIN_KEY = "frida_min_version"
    private const val MAX_KEY = "frida_max_version"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ClientHints")

    override fun deserialize(decoder: Decoder): ClientHints {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: error("ClientHints can only be read from JSON")
        val obj =
            jsonDecoder.decodeJsonElement() as? JsonObject
                ?: error("client_hints must be a JSON object")
        for (key in obj.keys) {
            if (key != MIN_KEY && key != MAX_KEY) {
                throw kotlinx.serialization.SerializationException("Unknown key in client_hints: '$key'")
            }
        }
        return ClientHints(
            fridaMinVersion = obj[MIN_KEY]?.let { it.jsonPrimitive.content },
            fridaMaxVersion = obj[MAX_KEY]?.let { it.jsonPrimitive.content },
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: ClientHints,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: error("ClientHints can only be written to JSON")
        val element =
            buildJsonObject {
                value.fridaMinVersion?.let { put(MIN_KEY, JsonPrimitive(it)) }
                value.fridaMaxVersion?.let { put(MAX_KEY, JsonPrimitive(it)) }
            }
        jsonEncoder.encodeJsonElement(element)
    }
}

/**
 * Provenance pointer (schema 3, maps#36) tying a map back to the exact
 * signature revision it was generated from, so a published map is reproducible.
 * The Kotlin twin of rosetta-frida's `GeneratedFrom`.
 */
@Serializable
public data class GeneratedFrom(
    /** The signatures-repo revision (e.g. a git SHA) the map was generated from. */
    @SerialName("signatures_rev") val signaturesRev: String,
)

/**
 * Hand-rolled serializer for the map's `signer_sha256` (schema 3, maps#38/#32):
 * the canonical schema accepts EITHER a single bare lowercase 64-hex string OR a
 * JSON array of them (match-any). Both shapes decode to a `List<String>` here so
 * the rest of the code (and [io.github.xiddoc.rosetta.core.MapLoader]) sees one
 * uniform list; a single-string input becomes a one-element list. The values are
 * carried THROUGH UNCHANGED (no normalization here) so [MapLoader] can length-
 * and format-bound the canonical bare-hex value, and the layer-4 `SignerGuard`
 * normalizes the APP-presented hashes (which may be uppercase / colon-separated)
 * at comparison time — keeping a guard-accepted map value schema-valid.
 *
 * STRICT: a non-string array element, or a non-string/non-array value, is a hard
 * decode failure, matching the canonical schema's `oneOf[string, array<string>]`.
 */
public object SignerSha256Serializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("signer_sha256")

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: error("signer_sha256 can only be read from JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive ->
                if (element.isString) {
                    listOf(element.content)
                } else {
                    throw kotlinx.serialization.SerializationException(
                        "signer_sha256 must be a hex string or an array of hex strings",
                    )
                }
            is JsonArray ->
                element.map {
                    val p =
                        it as? JsonPrimitive
                            ?: throw kotlinx.serialization.SerializationException(
                                "signer_sha256 array entries must be strings",
                            )
                    if (!p.isString) {
                        throw kotlinx.serialization.SerializationException(
                            "signer_sha256 array entries must be strings",
                        )
                    }
                    p.content
                }
            else ->
                throw kotlinx.serialization.SerializationException(
                    "signer_sha256 must be a hex string or an array of hex strings",
                )
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: List<String>,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: error("signer_sha256 can only be written to JSON")
        // Round-trip the single-hash case back to a bare string (the common,
        // canonical authoring shape); emit an array only for the multi-hash case.
        val element =
            if (value.size == 1) {
                JsonPrimitive(value[0])
            } else {
                buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
            }
        jsonEncoder.encodeJsonElement(element)
    }
}

/** The top-level mapping file — one per `(app, version_code)`. */
@Serializable
public data class RosettaMap(
    /** Mandatory. Bumped on breaking schema changes; gated to [CURRENT_SCHEMA_VERSION]. */
    @SerialName("schema_version") val schemaVersion: Int,
    /** Android package name (e.g. "com.example.app"). */
    val app: String,
    /**
     * Human-readable version label (e.g. "3.4.5"). NOT authoritative for
     * selection — a display label that can repeat across builds.
     */
    val version: String,
    /**
     * Authoritative app-identity key — the full Android `longVersionCode`
     * (`(versionCodeMajor shl 32) or versionCode`), never masked to its low
     * 32 bits. The primary, O(1) key the runtime selects maps by. Bounded to
     * `[0, MapLoader.MAX_VERSION_CODE]` (2^53 − 1). See RFC 0001 Decision 3.
     */
    @SerialName("version_code") val versionCode: Long,
    /**
     * ISO `date` (`YYYY-MM-DD`) when the map was captured (schema 3, maps#39).
     * Kept as a [String] — it is data, not a parsed instant; [MapLoader] applies
     * a lightweight shape check consistent with the schema's `format: date`.
     */
    @SerialName("captured_at") val capturedAt: String? = null,
    /**
     * Optional authenticity guard — hex SHA-256(s) of the APK signing
     * certificate (not the APK bytes). Schema 3 accepts EITHER a single bare
     * lowercase 64-hex string OR an array of them (match-any); both shapes
     * decode to this list via [SignerSha256Serializer]. Cheap to verify
     * on-device. `null` when the map declares no signer guard.
     */
    @SerialName("signer_sha256")
    @Serializable(with = SignerSha256Serializer::class)
    val signerSha256s: List<String>? = null,
    /**
     * Optional, advisory per-client hints (e.g. Frida version range). NOT used
     * by the core resolver; nested under `client_hints` to keep the top-level
     * identity keys clean. Matches the canonical schema + rosetta-frida twin.
     */
    @SerialName("client_hints") val clientHints: ClientHints? = null,
    /**
     * Optional provenance pointer to the signature revision the map was
     * generated from (schema 3, maps#36).
     */
    @SerialName("generated_from") val generatedFrom: GeneratedFrom? = null,
    /**
     * Lifecycle status (schema 3, maps#40). Absent ⇒ [MapStatus.ACTIVE]. A
     * [MapStatus.RETRACTED] map is refused at load; a [MapStatus.SUPERSEDED] one
     * warns at health-check time.
     */
    val status: MapStatus = MapStatus.ACTIVE,
    /**
     * When [status] is [MapStatus.SUPERSEDED], the `version_code` of the map
     * that replaces this one (schema 3, maps#40). Advisory.
     */
    @SerialName("superseded_by") val supersededBy: Long? = null,
    /** Provenance — which tools produced which subsets. */
    val sources: List<MapSource>? = null,
    /** The classes themselves, keyed by real fully-qualified name. */
    val classes: Map<String, ClassEntry>,
)
