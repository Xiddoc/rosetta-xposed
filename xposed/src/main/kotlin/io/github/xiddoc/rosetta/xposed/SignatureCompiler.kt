/*
 * SignatureCompiler — harvest community signatures into DexKit discovery hints
 * (RFC 0001 Decision 5: the on-device "harvest" of the offline sigmatcher
 * dialect into the runtime DexKit dialect).
 *
 * This is the bridge that makes the self-healing backend run on Rosetta's
 * community knowledge base: it turns a [SignatureSet] (the typed form of a
 * `signatures/<app>/signatures.yaml`, loaded by `core/SignatureLoader`) into
 * the `Map<realFqn, DiscoveryHints>` the [DynamicResolutionBackend] searches
 * by — so a module that has signatures but no published map for the running
 * version can still resolve obfuscated names live.
 *
 * WHAT IT HARVESTS — string-constant signals, which is what DexKit can match.
 * A signature is a string-constant signal in exactly these cases:
 *
 *   - `type: string`                      → a literal the code references.
 *   - `type: regex`, QUOTED (`"…"`)       → a string CONSTANT; its inner text
 *                                            is a literal (exact anchor) when it
 *                                            has no unescaped regex
 *                                            metacharacters, else a genuine
 *                                            regex anchor (RE2 / SimilarRegex).
 *   - `type: regex`, UNQUOTED bare word    → a referenced token (e.g. a field /
 *     (`[A-Za-z0-9_/-]+`)                    key name like `sessionId`); an
 *                                            exact anchor.
 *
 * Everything else — `type: smali`, and an UNQUOTED pattern carrying regex /
 * descriptor punctuation (e.g. `requestTicket\(Landroid/os/Bundle;\)`) — is a
 * STRUCTURAL match over smali, not a string the live code references, so it is
 * NOT harvested (DexKit cannot match it by string). This is the deliberate,
 * documented boundary of a *mechanical* harvest: it extracts the
 * rotation-stable string evidence and skips structural patterns that the
 * one-time human harvest (RFC 0001 Decision 5) would translate by hand. The
 * real community corpus is overwhelmingly quoted string constants, so the
 * mechanical harvest covers it.
 *
 * FAIL-CLOSED. Class-locating anchors flow through the [SafePattern] chokepoint
 * (bounds for literals; bounds + RE2 linear-time compile for regex anchors), so
 * an over-bound or pathological signature is rejected at COMPILE time rather
 * than inside a hook — surfaced as a
 * [io.github.xiddoc.rosetta.core.SignatureValidationException] (a malformed
 * *signature*, distinct from a runtime discovery miss). A class whose signatures
 * yield NO usable class-locating anchor is OMITTED from the result (and reported
 * as un-locatable) — never fabricated. Field signatures are ignored (field
 * discovery is not in the strategy set); method signatures contribute only
 * their literal string constants as `usingStrings`. Use [SignatureCompiler.report]
 * to see every dropped class/signature and why.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.SignatureValidationException
import io.github.xiddoc.rosetta.core.ValidationIssue
import io.github.xiddoc.rosetta.core.signature.ClassSignature
import io.github.xiddoc.rosetta.core.signature.SignatureRule
import io.github.xiddoc.rosetta.core.signature.SignatureSet
import io.github.xiddoc.rosetta.core.signature.SignatureType

/**
 * One signature the harvest could NOT turn into a discovery signal, with why.
 * A diagnostic (not an error): the signature was well-formed but carries no
 * string constant DexKit can match (a `type: smali`/structural pattern, a
 * `type` newer than this client, or a blank value). Surfacing it turns a
 * "my class won't resolve and I don't know why" into a readable reason.
 *
 * @property realName the class the signature belongs to (its real FQN).
 * @property signature the verbatim authored signature that was skipped.
 * @property reason a human-readable explanation of why it was not harvested.
 */
public data class SkippedSignature(
    val realName: String,
    val signature: String,
    val reason: String,
)

/**
 * The full outcome of harvesting a [SignatureSet] — the usable [hints] plus the
 * diagnostics a developer needs to understand what was dropped and why. The
 * convenience [SignatureCompiler.compile] returns only [hints]; call
 * [SignatureCompiler.report] when a class unexpectedly won't resolve.
 *
 * @property hints the per-real-class discovery hints (the harvest result).
 * @property skippedSignatures individual signatures that yielded no signal.
 * @property unlocatableClasses real names whose signatures produced NO usable
 *   class-locating anchor, so they are absent from [hints] (fail-closed —
 *   never fabricated). These are the classes that would silently "vanish".
 * @property duplicateRealNames real names that appeared in more than one rule;
 *   the LAST rule's hints win (the earlier ones are overwritten), so this flags
 *   the silent data loss for the contributor to resolve.
 */
public data class SignatureCompilationReport(
    val hints: Map<String, DiscoveryHints>,
    val skippedSignatures: List<SkippedSignature>,
    val unlocatableClasses: List<String>,
    val duplicateRealNames: List<String>,
)

/**
 * Compiles community [SignatureSet]s into [DiscoveryHints]. Stateless; use
 * [compile] for the hints alone, or [report] when you also need to know what
 * was dropped.
 */
public object SignatureCompiler {
    /**
     * Harvest [signatures] into per-real-class discovery hints — the hints
     * alone. Equivalent to `report(signatures).hints`. Only classes with at
     * least one usable class-locating anchor appear; an un-locatable class is
     * omitted (fail-closed). To learn WHICH classes/signatures were dropped and
     * why, call [report] instead.
     *
     * @throws SignatureValidationException if a harvested anchor is over the
     *   [SafePattern] bounds or (for a regex anchor) is not a valid RE2
     *   expression — a malformed *signature*, distinct from a runtime discovery
     *   miss, so it is NOT a swallowable [XposedBindingFailure].
     */
    public fun compile(signatures: SignatureSet): Map<String, DiscoveryHints> = report(signatures).hints

    /**
     * Harvest [signatures] into hints AND the diagnostics ([SkippedSignature]s,
     * un-locatable classes, duplicate real names) — so a class that quietly
     * fails to resolve can be traced to a skipped/structural signature instead
     * of vanishing without a trace.
     *
     * @throws SignatureValidationException as [compile].
     */
    public fun report(signatures: SignatureSet): SignatureCompilationReport {
        val out = linkedMapOf<String, DiscoveryHints>()
        val skipped = mutableListOf<SkippedSignature>()
        val unlocatable = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        for (rule in signatures.classes) {
            val hints = compileClass(rule, skipped)
            if (!hints.canLocateClass) {
                unlocatable += rule.realFqn
                continue
            }
            // out.put returns the prior value: non-null ⇒ a second rule for the
            // same real name overwrote the first (last-wins) — flag the loss.
            if (out.put(rule.realFqn, hints) != null) duplicates += rule.realFqn
        }
        return SignatureCompilationReport(out, skipped, unlocatable, duplicates)
    }

    /**
     * Build the discovery hints for one class rule (may be un-locatable),
     * appending any un-harvestable signatures to [skipped]. A bounds / RE2
     * failure from [SafePattern] is a malformed-signature error, re-thrown as a
     * [SignatureValidationException] pinned to this rule (not the runtime
     * [DiscoveryException] the chokepoint raises).
     */
    private fun compileClass(
        rule: ClassSignature,
        skipped: MutableList<SkippedSignature>,
    ): DiscoveryHints {
        val literals = mutableListOf<String>()
        val patterns = mutableListOf<String>()
        for (sig in rule.signatures) {
            val anchor = AnchorClassifier.classify(sig)
            if (anchor == null) {
                skipped += SkippedSignature(rule.realFqn, sig.signature, skipReason(sig))
            } else if (anchor.isRegex) {
                patterns += anchor.value
            } else {
                literals += anchor.value
            }
        }
        return try {
            // Fail-closed bounds at compile time: literals are bounded, regex
            // anchors are bounded AND RE2-compiled (SafePattern rejects a
            // pathological or malformed pattern before it can reach a hook).
            SafePattern.checkBounds(literals)
            patterns.forEach { SafePattern.compile(it) }
            SafePattern.checkBounds(patterns)
            DiscoveryHints(
                anchors = literals.distinct(),
                regexAnchors = patterns.distinct(),
                methods = compileMethods(rule, skipped),
            )
        } catch (ex: DiscoveryException) {
            // Interpolate (not `?: ...`): a DiscoveryException always carries a
            // message, so an elvis fallback would be a permanently-dead branch.
            throw SignatureValidationException(
                "Signatures for '${rule.realFqn}' could not be compiled: ${ex.message}",
                listOf(ValidationIssue(rule.realFqn, "${ex.message}")),
                ex,
            )
        }
    }

    /**
     * Harvest each method rule's LITERAL string constants into a
     * [MethodDiscoveryHint.usingStrings] facet (DexKit method matching uses
     * exact-string `usingStrings`, so regex / structural method signatures are
     * skipped and recorded in [skipped]). A method with no literal string
     * constant yields no hint — the class still discovers; that method is
     * simply not pre-hinted.
     */
    private fun compileMethods(
        rule: ClassSignature,
        skipped: MutableList<SkippedSignature>,
    ): List<MethodDiscoveryHint> {
        val hints = mutableListOf<MethodDiscoveryHint>()
        for (member in rule.methods) {
            val using = mutableListOf<String>()
            for (sig in member.signatures) {
                val anchor = AnchorClassifier.classify(sig)
                // Only EXACT literal constants drive a method's usingStrings
                // facet (DexKit method matching is exact); regex / structural
                // method signatures carry no usable runtime signal.
                if (anchor != null && !anchor.isRegex) {
                    using += anchor.value
                } else {
                    skipped += SkippedSignature(rule.realFqn, sig.signature, methodSkipReason(sig, anchor))
                }
            }
            val distinct = using.distinct()
            if (distinct.isNotEmpty()) {
                SafePattern.checkBounds(distinct)
                hints += MethodDiscoveryHint(realName = member.name, usingStrings = distinct)
            }
        }
        return hints
    }

    /** Why a class-level signature was not harvested (for the report). */
    private fun skipReason(sig: SignatureRule): String =
        when (sig.type) {
            SignatureType.SMALI -> "type 'smali' is a structural pattern, not a string constant DexKit can match"
            SignatureType.UNKNOWN -> "unknown signature type (a matcher kind newer than this client)"
            SignatureType.STRING, SignatureType.REGEX ->
                "not a harvestable string constant (blank, or a structural / descriptor pattern)"
        }

    /**
     * Why a method signature was not harvested as a usingStrings facet, given
     * the [anchor] already classified for it. A non-null [anchor] here is
     * necessarily a REGEX one (an exact anchor would have become a hint, not a
     * skip), so it maps to the exact-only reason; a null anchor falls back to
     * the shared [skipReason].
     */
    private fun methodSkipReason(
        sig: SignatureRule,
        anchor: Anchor?,
    ): String =
        if (anchor != null) {
            "regex string constant — a method's usingStrings facet is exact-only"
        } else {
            skipReason(sig)
        }
}

/**
 * A harvested class-locating anchor: a string constant plus how it is matched —
 * [isRegex] = true → `regexAnchors` (RE2 / SimilarRegex), false → `anchors`
 * (exact / Equals). A flag rather than a sealed `Literal`/`Pattern` hierarchy
 * because the downstream [DiscoveryHints] models the two modes as two separate
 * lists today; when match-mode becomes a first-class value threaded end-to-end
 * through the seam (a planned refactor), this flag folds into that type.
 */
internal class Anchor(
    val value: String,
    val isRegex: Boolean,
)

/**
 * Interprets ONE sigmatcher signature into a harvestable string [Anchor] (or
 * null when it carries no string constant DexKit can match). Split from
 * [SignatureCompiler] so the dialect-interpretation rules — quoting, escaped-
 * literal reduction, bare-word vs structural — are a cohesive unit, directly
 * unit-testable in isolation rather than only through a full `compile`. The
 * compiler keeps the *assembly* concern (bounds, methods, report); this keeps
 * the *interpretation* concern.
 */
internal object AnchorClassifier {
    /** The regex metacharacters that make a pattern more than a literal. */
    private val META: Set<Char> = ".^$*+?()[]{}|".toSet()

    /** An UNQUOTED `type: regex` value is a referenced token only if it is a bare word. */
    private val BARE_WORD: Regex = Regex("^[A-Za-z0-9_/-]+$")

    /** Classify one signature into a harvested anchor, or null to skip it. */
    fun classify(sig: SignatureRule): Anchor? =
        when (sig.type) {
            // A declared string literal: take it verbatim (de-quoted).
            SignatureType.STRING -> literalOrNull(dequote(sig.signature))
            // A smali fragment is structural, and an unknown type is a matcher
            // kind newer than this client — neither is a referenced string.
            SignatureType.SMALI, SignatureType.UNKNOWN -> null
            SignatureType.REGEX -> classifyRegex(sig.signature)
        }

    /**
     * Classify a `type: regex` value. A QUOTED value denotes a string
     * constant: literal inner → exact anchor, genuine-regex inner → regex
     * anchor. An UNQUOTED value is a string only if it is a bare word;
     * otherwise it is a structural / descriptor pattern and is skipped.
     */
    private fun classifyRegex(raw: String): Anchor? {
        val quoted = isQuoted(raw)
        val inner = if (quoted) dequote(raw) else raw
        val literal = toLiteralOrNull(inner)
        return when {
            literal != null && literal.isNotBlank() && (quoted || BARE_WORD.matches(literal)) -> Anchor(literal, isRegex = false)
            quoted && inner.isNotBlank() -> Anchor(inner, isRegex = true)
            else -> null
        }
    }

    /** Wrap a non-blank de-quoted literal as an exact anchor (used by `type: string`). */
    private fun literalOrNull(value: String): Anchor? = if (value.isNotBlank()) Anchor(value, isRegex = false) else null

    /** True when [s] is wrapped in a single pair of double quotes. */
    private fun isQuoted(s: String): Boolean = s.length >= 2 && s.first() == '"' && s.last() == '"'

    /** Strip one surrounding pair of double quotes, if present. */
    private fun dequote(s: String): String = if (isQuoted(s)) s.substring(1, s.length - 1) else s

    /**
     * Reduce [s] to its literal form, or null if it is a genuine regex. Drops
     * the backslash before an escaped character (so `\.` becomes a literal
     * `.`) and returns null on any UNESCAPED regex metacharacter or a dangling
     * trailing backslash — those make [s] a pattern, not a literal.
     */
    private fun toLiteralOrNull(s: String): String? {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= s.length) return null
                    sb.append(s[i + 1])
                    i += 2
                }
                c in META -> return null
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
