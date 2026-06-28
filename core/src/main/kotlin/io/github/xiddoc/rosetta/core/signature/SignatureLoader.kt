/*
 * Community-signature loading + validation.
 *
 * The on-disk `signatures/<app>/signatures.yaml` (rosetta-maps' sigmatcher
 * dialect) is authored in YAML, but a YAML file is structurally a JSON
 * document — a top-level array of class rules. This loader parses that JSON
 * form into a [SignatureSet], the framework-neutral input the layer-4
 * self-healing backend harvests into DexKit discovery hints. It is the
 * signature sibling of [io.github.xiddoc.rosetta.core.MapLoader].
 *
 * STRICTNESS — lenient about EXTRA keys, strict about SHAPE. Unlike the map
 * loader (which is a CONSUMED contract with cross-client parity, so it rejects
 * unknown keys), signatures are an AUTHORING format owned upstream and this is
 * a PARTIAL consumer: it reads the string-constant signals and ignores
 * authoring-only facets (the smali match `count`, provenance comments) and any
 * future field it does not need. So `ignoreUnknownKeys = true` — but every
 * field it DOES model is shape- and bounds-checked fail-closed, and the
 * required `signature` / `type` keys still fail the load when absent. A
 * pre-parse [JsonInputGuard] bounds input size + nesting before the recursive
 * decoder ever runs.
 *
 * FORWARD-COMPATIBILITY. The dialect has NO version field today, so there is no
 * version handshake — this client TRACKS the structure that rosetta-maps'
 * `scripts/lint_signatures.py` validates, by hand. The chosen posture survives
 * additive evolution without a gate: an unknown KEY is ignored, and an unknown
 * `type` VALUE degrades per-rule to [SignatureType.UNKNOWN] (skipped at harvest)
 * rather than failing the whole file (see [SignatureType] /
 * [SignatureTypeSerializer]) — so a file authored against a NEWER maps revision
 * still loads and the client harvests every class whose matchers it understands.
 * If the dialect ever gains a BREAKING change, it should also gain a version /
 * capability signal so clients can gate on it; until then, lenient-without-a-gate
 * is a deliberate trade, not an oversight.
 *
 * INTENTIONAL ASYMMETRIES vs `lint_signatures.py` (decisions, not accidents):
 *  - `count` is validated by the linter but IGNORED here (the runtime never
 *    reads it), so a `count: 0` file the linter rejects still loads.
 *  - `package` must be a DOTTED name here ([PACKAGE_PATTERN]); the linter only
 *    requires a non-empty string, so a single-segment package the linter accepts
 *    is rejected here (it can't form a real FQN).
 *  - an empty top-level `[]` is a valid empty set here; the linter rejects it.
 * These are safe (the linter remains the source-of-truth gate for contributions);
 * they are documented so the two stay diff-able as the dialect evolves.
 */
package io.github.xiddoc.rosetta.core.signature

import io.github.xiddoc.rosetta.core.JsonInputGuard
import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.SignatureInputTooLargeException
import io.github.xiddoc.rosetta.core.SignatureValidationException
import io.github.xiddoc.rosetta.core.ValidationIssue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Loads and validates community-signature artifacts (the sigmatcher dialect). */
public object SignatureLoader {
    private val json: Json =
        Json {
            // Tolerate authoring-only keys (`count`, comments, future fields)
            // the runtime does not read; the fields we DO model are still
            // shape/bounds-checked below, and missing required `signature` /
            // `type` keys still fail the parse.
            ignoreUnknownKeys = true
            isLenient = false
        }

    // ---- Hardening bounds (signature-specific; lengths reuse the map caps) ----

    /** Maximum number of class rules in a single signatures file. */
    public const val MAX_RULES: Int = MapLoader.MAX_CLASSES

    /** Maximum number of signature matchers in one `signatures` list. */
    public const val MAX_SIGNATURES_PER_LIST: Int = 1_000

    /** Maximum number of method rules in one class. */
    public const val MAX_METHODS_PER_CLASS: Int = MapLoader.MAX_METHODS_PER_CLASS

    /** Maximum number of field rules in one class. */
    public const val MAX_FIELDS_PER_CLASS: Int = MapLoader.MAX_FIELDS_PER_CLASS

    /** Maximum length of a single signature pattern. */
    public const val MAX_SIGNATURE_LEN: Int = MapLoader.MAX_SIGNATURE_LEN

    /** Maximum length of a real class / member name. */
    public const val MAX_NAME_LEN: Int = MapLoader.MAX_SHORT_NAME_LEN

    /** Maximum length of a class's package name. */
    public const val MAX_PACKAGE_LEN: Int = MapLoader.MAX_APP_LEN

    /**
     * The dotted package-name shape required of a rule's `package`: each dotted
     * segment must start with a letter, mirroring the canonical map schema's
     * `app` pattern so a signatures file and its map agree on the namespace.
     */
    private val PACKAGE_PATTERN: Regex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val listSerializer = ListSerializer(ClassSignature.serializer())

    /**
     * Parse and validate the JSON form of a `signatures/<app>/signatures.yaml`
     * file — a top-level array of class rules.
     *
     * @throws SignatureInputTooLargeException if the raw text exceeds the
     *   shared size / nesting caps ([MapLoader.MAX_INPUT_BYTES] /
     *   [MapLoader.MAX_NESTING_DEPTH]) — checked before any deserialization.
     * @throws SignatureValidationException if the text is not valid JSON, does
     *   not match the rule shape, or violates a hardening bound.
     */
    public fun fromJson(text: String): SignatureSet {
        JsonInputGuard.check(text, MapLoader.MAX_INPUT_BYTES, MapLoader.MAX_NESTING_DEPTH)?.let { reason ->
            throw SignatureInputTooLargeException("Signatures $reason")
        }
        val rules =
            try {
                json.decodeFromString(listSerializer, text)
            } catch (ex: SerializationException) {
                val detail = "${ex.message}"
                throw SignatureValidationException(
                    "Signatures failed to parse: $detail",
                    listOf(ValidationIssue("", detail)),
                    ex,
                )
            }
        return validate(SignatureSet(rules))
    }

    /**
     * Validate an already-deserialized [SignatureSet] (the structural
     * invariants the type system can't express). Public so a programmatically
     * built set can be checked the same way a parsed one is.
     *
     * @throws SignatureValidationException on any failed invariant.
     */
    public fun validate(set: SignatureSet): SignatureSet {
        val issues = BoundsChecker(set).run()
        if (issues.isNotEmpty()) {
            val summary = if (issues.size == 1) "1 issue" else "${issues.size} issues"
            throw SignatureValidationException("Signatures failed validation ($summary)", issues)
        }
        return set
    }

    /**
     * Accumulates every bound / shape violation for one [set] into [issues].
     * Mirrors [MapLoader]'s `BoundsChecker` so the two loaders read alike.
     */
    private class BoundsChecker(
        private val set: SignatureSet,
    ) {
        private val issues = mutableListOf<ValidationIssue>()

        fun run(): List<ValidationIssue> {
            cap("signatures", set.classes.size, MAX_RULES, "rules")
            set.classes.forEachIndexed { i, rule -> checkRule(i, rule) }
            return issues
        }

        private fun checkRule(
            index: Int,
            rule: ClassSignature,
        ) {
            val path = "signatures[$index]"
            nonEmpty("$path.name", rule.name)
            len("$path.name", rule.name, MAX_NAME_LEN)
            checkPackage(path, rule.pkg)
            checkSignatureList("$path.signatures", rule.signatures)
            cap("$path.methods", rule.methods.size, MAX_METHODS_PER_CLASS, "rules")
            rule.methods.forEachIndexed { i, m -> checkMember("$path.methods[$i]", m) }
            cap("$path.fields", rule.fields.size, MAX_FIELDS_PER_CLASS, "rules")
            rule.fields.forEachIndexed { i, f -> checkMember("$path.fields[$i]", f) }
        }

        private fun checkMember(
            path: String,
            member: MemberSignature,
        ) {
            nonEmpty("$path.name", member.name)
            len("$path.name", member.name, MAX_NAME_LEN)
            checkSignatureList("$path.signatures", member.signatures)
        }

        private fun checkSignatureList(
            path: String,
            sigs: List<SignatureRule>,
        ) {
            if (sigs.isEmpty()) {
                issues += ValidationIssue(path, "must be a non-empty list")
            }
            cap(path, sigs.size, MAX_SIGNATURES_PER_LIST, "entries")
            sigs.forEachIndexed { i, sig ->
                nonEmpty("$path[$i].signature", sig.signature)
                len("$path[$i].signature", sig.signature, MAX_SIGNATURE_LEN)
            }
        }

        private fun checkPackage(
            path: String,
            pkg: String,
        ) {
            if (pkg.isBlank()) {
                issues += ValidationIssue("$path.package", "must not be empty")
                return
            }
            len("$path.package", pkg, MAX_PACKAGE_LEN)
            if (!PACKAGE_PATTERN.matches(pkg)) {
                issues += ValidationIssue("$path.package", "must be a dotted package name matching ${PACKAGE_PATTERN.pattern}")
            }
        }

        private fun cap(
            path: String,
            count: Int,
            max: Int,
            noun: String,
        ) {
            if (count > max) issues += ValidationIssue(path, "has $count $noun, over the $max limit")
        }

        private fun nonEmpty(
            path: String,
            value: String,
        ) {
            if (value.isBlank()) issues += ValidationIssue(path, "must not be empty")
        }

        private fun len(
            path: String,
            value: String,
            max: Int,
        ) {
            if (value.length > max) issues += ValidationIssue(path, "exceeds $max characters")
        }
    }
}
