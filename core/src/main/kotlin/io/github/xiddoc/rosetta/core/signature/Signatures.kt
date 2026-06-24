/*
 * Community-signature model — the Kotlin client of the rosetta-maps
 * *sigmatcher* dialect (RFC 0001 Decision 4 / 5).
 *
 * rosetta-maps OWNS two signature dialects (its AGENTS.md, Hard rule 4):
 *
 *   1. the offline / host **sigmatcher** dialect — `signatures/<app>/signatures.yaml`,
 *      regex-over-smali rules that identify a class/method across versions;
 *   2. the on-device **DexKit** dialect — harvested one-time from (1).
 *
 * This file is the framework-neutral, typed twin of dialect (1): a faithful
 * model of the same structure `rosetta-maps`'s `scripts/lint_signatures.py`
 * validates (a top-level list of class rules; each rule carries class-level
 * `signatures` and optional nested `fields` / `methods`, every signature a
 * `{signature, type}` matcher). It is the SOURCE the layer-4 self-healing
 * backend reads to detect obfuscation for a version that has no published map
 * yet — the same role the map model plays for a mapped version.
 *
 * SCOPE — this models only what the runtime READS. The sigmatcher dialect is
 * an *authoring* format with facets that have no on-device meaning (the smali
 * match `count`, free-form provenance comments); those are ignored at load
 * (see [io.github.xiddoc.rosetta.core.signature.SignatureLoader]). The
 * convergence point with dialect (2) is the resolved map, NOT a unified IR —
 * the harvest from these rules into DexKit discovery hints lives in the
 * layer-4 binding (`xposed/SignatureCompiler`), not here.
 *
 * `:core` stays Android-free: this is pure data + kotlinx-serialization, so it
 * builds and unit-tests on any JVM.
 */
package io.github.xiddoc.rosetta.core.signature

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The matcher kind of a single [SignatureRule], mirroring the sigmatcher
 * dialect's `type` field (and the `KNOWN_SIGNATURE_TYPES` the maps linter
 * enforces). Only these three are valid; an unknown `type` fails to load.
 */
@Serializable
public enum class SignatureType {
    /** A regular expression matched over the class/method's smali. */
    @SerialName("regex")
    REGEX,

    /** A plain string literal the class/method references verbatim. */
    @SerialName("string")
    STRING,

    /** A raw smali fragment (structural). Not a string-constant signal. */
    @SerialName("smali")
    SMALI,
}

/**
 * One signature matcher: a pattern plus how to interpret it. The `count`
 * facet from the sigmatcher dialect (how many smali matches are expected
 * offline) carries no on-device meaning and is deliberately NOT modelled —
 * the loader ignores it (and any other authoring-only key).
 *
 * @property signature the pattern (a regex over smali, a string literal, or a
 *   smali fragment, per [type]). Quoting conventions (a `"…"`-wrapped value
 *   denotes a string CONSTANT the code references) are interpreted by the
 *   harvest, not here — this is the verbatim authored pattern.
 * @property type how [signature] is matched.
 */
@Serializable
public data class SignatureRule(
    val signature: String,
    val type: SignatureType,
)

/**
 * Signatures that identify ONE member (a field or a method) within a matched
 * class. The [name] is the member's REAL name; [signatures] are the matchers
 * that pin it.
 *
 * @property name the member's real name.
 * @property signatures the non-empty matcher list for this member.
 */
@Serializable
public data class MemberSignature(
    val name: String,
    val signatures: List<SignatureRule> = emptyList(),
)

/**
 * The signatures that identify ONE class across versions — one top-level
 * entry of a `signatures/<app>/signatures.yaml` file.
 *
 * @property name the class's REAL short name (may be nested, e.g.
 *   `IRemoteService$Stub`).
 * @property pkg the class's REAL package (`package` in the dialect; renamed
 *   here because `package` is a Kotlin hard keyword).
 * @property signatures the class-level matchers (non-empty in a valid file).
 * @property fields per-field signatures (optional).
 * @property methods per-method signatures (optional).
 */
@Serializable
public data class ClassSignature(
    val name: String,
    @SerialName("package") val pkg: String,
    val signatures: List<SignatureRule> = emptyList(),
    val fields: List<MemberSignature> = emptyList(),
    val methods: List<MemberSignature> = emptyList(),
) {
    /**
     * The class's REAL fully-qualified name — `pkg` + `.` + `name` — the key
     * the resolver and the discovery harvest use (e.g.
     * `com.example.app.IRemoteService$Stub`).
     */
    public val realFqn: String
        get() = "$pkg.$name"
}

/**
 * A loaded set of community signatures for an app — the in-memory form of a
 * `signatures/<app>/signatures.yaml` file (whose top level is a list of class
 * rules). A thin, framework-neutral holder produced by
 * [io.github.xiddoc.rosetta.core.signature.SignatureLoader]; the layer-4
 * binding compiles it into on-device discovery hints.
 *
 * @property classes the per-class signature rules, in file order.
 */
public data class SignatureSet(
    val classes: List<ClassSignature>,
) {
    /** The real fully-qualified names this set carries signatures for. */
    public val realNames: List<String>
        get() = classes.map { it.realFqn }
}
