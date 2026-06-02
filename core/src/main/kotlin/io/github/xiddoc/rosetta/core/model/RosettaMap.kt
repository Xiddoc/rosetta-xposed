/*
 * Mapping-file model — the on-disk schema, loaded into memory.
 *
 * This is the Kotlin twin of rosetta-frida's `src/types/map.ts`. The
 * shapes are LOCKED to the same `schema_version: 2` contract so the same
 * JSON artifact deserializes identically on both sides. Do not diverge
 * field names or optionality without a matching change on the Frida side
 * (and a schema bump).
 *
 * The on-disk format is strict JSON (one map per `(app, version_code)`
 * file). Comment-bearing YAML / TS modules are *authoring inputs*
 * converted to JSON upstream — never parsed here.
 */
package io.github.xiddoc.rosetta.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The current map schema version — the single source of truth on the
 * Kotlin side. Kept in lockstep with rosetta-frida's
 * `CURRENT_SCHEMA_VERSION`. The loader hard-gates on this value.
 */
public const val CURRENT_SCHEMA_VERSION: Int = 2

/** Confidence gradient for a map or a subset of entries. */
@Serializable
public enum class Confidence {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW,
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
    /** Confidence default for entries from this source. */
    val confidence: Confidence? = null,
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
    /** Per-entry confidence override. */
    val confidence: Confidence? = null,
)

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
     * Authoritative app-identity key — Android `PackageInfo.versionCode`
     * (or the low 32 bits of `longVersionCode`). The primary, O(1) key
     * the runtime selects maps by. See RFC 0001 Decision 3.
     */
    @SerialName("version_code") val versionCode: Long,
    /** ISO date when the map was captured. */
    @SerialName("captured_at") val capturedAt: String? = null,
    /**
     * Optional authenticity guard — hex SHA-256 of the APK signing
     * certificate (not the APK bytes). Cheap to verify on-device.
     */
    @SerialName("signer_sha256") val signerSha256: String? = null,
    /** Minimum Frida version this map is known to work with (Frida-only hint). */
    @SerialName("frida_min_version") val fridaMinVersion: String? = null,
    /** Maximum Frida version this map is known to work with (Frida-only hint). */
    @SerialName("frida_max_version") val fridaMaxVersion: String? = null,
    /** Provenance — which tools produced which subsets. */
    val sources: List<MapSource>? = null,
    /** The classes themselves, keyed by real fully-qualified name. */
    val classes: Map<String, ClassEntry>,
)
