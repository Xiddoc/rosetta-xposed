/*
 * SafePattern — the ReDoS chokepoint for contributor-supplied discovery
 * patterns (audit H4).
 *
 * THREAT. Dynamic discovery consults contributor data: signature descriptors
 * and stable string anchors that may themselves be regexes. Feeding those to a
 * backtracking engine (`java.util.regex` / `kotlin.text.Regex`) opens a
 * catastrophic-backtracking (ReDoS) hole — a single crafted pattern like
 * `(a+)+$` can hang the host app's JVM for seconds-to-forever against a short
 * input. The dynamic backend runs INSIDE the target app, so a hang is a
 * device-side denial of service.
 *
 * MITIGATION (two layers, fail-closed):
 *
 *   1. BOUNDS BEFORE COMPILE. Enforce the schema caps ([MAX_SIGNATURE_LEN],
 *      [MAX_ANCHORS]) on the raw input BEFORE any compilation, so an
 *      over-bound input never even reaches the regex engine. Over-bounds
 *      throws [DiscoveryException] — never a silent skip.
 *   2. LINEAR-TIME ENGINE ONLY. Compile through `com.google.re2j.Pattern`
 *      (RE2 — a linear-time automaton with NO backtracking), never
 *      `java.util.regex` / `kotlin.text.Regex`. A pathological pattern that
 *      would hang a backtracking engine returns promptly under RE2.
 *
 * This object is the SINGLE place contributor input becomes a [Pattern]; the
 * backend must route every contributor string through here.
 */
package io.github.xiddoc.rosetta.xposed

import com.google.re2j.Pattern

/**
 * Schema-cap-aligned bounds and the RE2-only compiler for contributor input.
 *
 * The constants intentionally mirror the canonical schema caps so the
 * discovery path rejects exactly what the map loader would reject
 * (`MapLoader.MAX_SIGNATURE_LEN` / `MapLoader.MAX_ANCHORS_PER_CLASS`),
 * keeping the runtime-discovered path and the static map under one budget.
 */
public object SafePattern {
    /** Max length of a single contributor pattern / signature string. */
    public const val MAX_SIGNATURE_LEN: Int = 4_096

    /** Max number of anchors (or pattern entries) in one discovery request. */
    public const val MAX_ANCHORS: Int = 1_000

    /**
     * Compile [pattern] (a contributor-supplied string) to a linear-time RE2
     * [Pattern], after enforcing [MAX_SIGNATURE_LEN].
     *
     * @throws DiscoveryException if [pattern] exceeds [MAX_SIGNATURE_LEN]
     *   (checked BEFORE compilation), or if RE2 rejects it as malformed.
     */
    public fun compile(pattern: String): Pattern {
        if (pattern.length > MAX_SIGNATURE_LEN) {
            throw DiscoveryException(
                "rosetta-xposed: discovery pattern is ${pattern.length} chars, over the " +
                    "$MAX_SIGNATURE_LEN-char limit (ReDoS / resource guard) — refusing to compile.",
            )
        }
        return try {
            Pattern.compile(pattern)
        } catch (ex: com.google.re2j.PatternSyntaxException) {
            throw DiscoveryException(
                "rosetta-xposed: discovery pattern is not a valid RE2 expression: ${ex.message}",
                ex,
            )
        }
    }

    /**
     * Validate an [anchors] list against the bounds and RE2-compile each
     * entry, returning the compiled patterns. Enforces [MAX_ANCHORS] on the
     * list size and [MAX_SIGNATURE_LEN] on every element BEFORE compiling.
     *
     * @throws DiscoveryException if the list is over [MAX_ANCHORS] or any
     *   element is over [MAX_SIGNATURE_LEN] / malformed.
     */
    public fun compileAll(anchors: List<String>): List<Pattern> {
        if (anchors.size > MAX_ANCHORS) {
            throw DiscoveryException(
                "rosetta-xposed: discovery request carries ${anchors.size} anchors, over the " +
                    "$MAX_ANCHORS-entry limit (resource guard) — refusing to compile.",
            )
        }
        return anchors.map { compile(it) }
    }

    /**
     * Validate [anchors] against the bounds WITHOUT compiling — used when the
     * anchors are passed verbatim to the index as literals (not regexes) but
     * must still be bounded fail-closed before leaving the chokepoint.
     *
     * @throws DiscoveryException if the list is over [MAX_ANCHORS] or any
     *   element is over [MAX_SIGNATURE_LEN].
     */
    public fun checkBounds(anchors: List<String>) {
        if (anchors.size > MAX_ANCHORS) {
            throw DiscoveryException(
                "rosetta-xposed: discovery request carries ${anchors.size} anchors, over the " +
                    "$MAX_ANCHORS-entry limit (resource guard).",
            )
        }
        anchors.forEach { checkLen(it) }
    }

    /**
     * Enforce [MAX_SIGNATURE_LEN] on a single string used verbatim (e.g. an
     * AIDL descriptor passed straight to the index).
     *
     * @throws DiscoveryException if [value] is over [MAX_SIGNATURE_LEN].
     */
    public fun checkLen(value: String) {
        if (value.length > MAX_SIGNATURE_LEN) {
            throw DiscoveryException(
                "rosetta-xposed: discovery string is ${value.length} chars, over the " +
                    "$MAX_SIGNATURE_LEN-char limit (resource guard).",
            )
        }
    }
}
