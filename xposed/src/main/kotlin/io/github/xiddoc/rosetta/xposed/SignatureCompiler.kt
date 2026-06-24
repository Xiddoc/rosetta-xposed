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
 * an over-bound or pathological signature throws [DiscoveryException] at COMPILE
 * time rather than inside a hook. A class whose signatures yield NO usable
 * class-locating anchor is OMITTED from the result (the backend then reports it
 * unresolvable) — never fabricated. Field signatures are ignored (field
 * discovery is not in the strategy set); method signatures contribute only
 * their literal string constants as `usingStrings`.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.signature.ClassSignature
import io.github.xiddoc.rosetta.core.signature.MemberSignature
import io.github.xiddoc.rosetta.core.signature.SignatureRule
import io.github.xiddoc.rosetta.core.signature.SignatureSet
import io.github.xiddoc.rosetta.core.signature.SignatureType

/**
 * Compiles community [SignatureSet]s into [DiscoveryHints]. Stateless; the
 * single entry point is [compile].
 */
public object SignatureCompiler {
    /** The regex metacharacters that make a pattern more than a literal. */
    private val META: Set<Char> = ".^$*+?()[]{}|".toSet()

    /** An UNQUOTED `type: regex` value is a referenced token only if it is a bare word. */
    private val BARE_WORD: Regex = Regex("^[A-Za-z0-9_/-]+$")

    /**
     * A harvested class-locating anchor: a string constant plus whether it is
     * matched as an RE2 regex ([isRegex] = true → `regexAnchors` / SimilarRegex)
     * or as an exact literal ([isRegex] = false → `anchors` / Equals). A single
     * flagged class rather than a 2-subtype sealed hierarchy on purpose — the
     * latter's second `is` check carries a branch that can never be false.
     */
    private class Anchor(
        val value: String,
        val isRegex: Boolean,
    )

    /**
     * Harvest [signatures] into per-real-class discovery hints. Only classes
     * with at least one usable class-locating anchor appear in the result; a
     * class whose signatures are all structural (un-harvestable) is omitted.
     *
     * @throws DiscoveryException if a harvested anchor is over the [SafePattern]
     *   bounds or (for a regex anchor) is not a valid RE2 expression.
     */
    public fun compile(signatures: SignatureSet): Map<String, DiscoveryHints> {
        val out = linkedMapOf<String, DiscoveryHints>()
        for (rule in signatures.classes) {
            val hints = compileClass(rule)
            if (hints.canLocateClass) out[rule.realFqn] = hints
        }
        return out
    }

    /** Build the discovery hints for one class rule (may be un-locatable). */
    private fun compileClass(rule: ClassSignature): DiscoveryHints {
        val literals = mutableListOf<String>()
        val patterns = mutableListOf<String>()
        for (sig in rule.signatures) {
            val anchor = classify(sig) ?: continue // structural / un-harvestable: skip.
            if (anchor.isRegex) patterns += anchor.value else literals += anchor.value
        }
        // Fail-closed bounds at compile time: literals are bounded, regex
        // anchors are bounded AND RE2-compiled (SafePattern.compile rejects a
        // pathological or malformed pattern before it can reach a hook).
        SafePattern.checkBounds(literals)
        patterns.forEach { SafePattern.compile(it) }
        SafePattern.checkBounds(patterns)
        return DiscoveryHints(
            anchors = literals.distinct(),
            regexAnchors = patterns.distinct(),
            methods = compileMethods(rule.methods),
        )
    }

    /**
     * Harvest each method rule's LITERAL string constants into a
     * [MethodDiscoveryHint.usingStrings] facet (DexKit method matching uses
     * exact-string `usingStrings`, so regex / structural method signatures are
     * skipped). A method with no literal string constant yields no hint — the
     * class still discovers; that method is simply not pre-hinted.
     */
    private fun compileMethods(methods: List<MemberSignature>): List<MethodDiscoveryHint> {
        val hints = mutableListOf<MethodDiscoveryHint>()
        for (member in methods) {
            // Only EXACT literal constants drive a method's usingStrings facet
            // (DexKit method matching is exact); regex / structural method
            // signatures carry no usable runtime signal and are dropped.
            val using = member.signatures.mapNotNull { classify(it)?.takeUnless(Anchor::isRegex)?.value }.distinct()
            if (using.isNotEmpty()) {
                SafePattern.checkBounds(using)
                hints += MethodDiscoveryHint(realName = member.name, usingStrings = using)
            }
        }
        return hints
    }

    /** Classify one signature into a harvested anchor, or null to skip it. */
    private fun classify(sig: SignatureRule): Anchor? =
        when (sig.type) {
            // A declared string literal: take it verbatim (de-quoted).
            SignatureType.STRING -> literalOrNull(dequote(sig.signature))
            // A raw smali fragment is structural, never a referenced string.
            SignatureType.SMALI -> null
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
