/*
 * JsonInputGuard — the shared cheap pre-parse denial-of-service guard.
 *
 * Both strict-JSON loaders ([MapLoader] for maps, [SignatureLoader] for
 * community signatures) reject abusive raw input BEFORE handing it to
 * kotlinx-serialization's recursive-descent decoder. Two abuse shapes are
 * caught fail-fast: an oversized input (a memory / pathological-parse vector)
 * and excessively-nested input (a stack-overflow vector). The logic is
 * identical for both artifacts, so it lives here once rather than drifting in
 * two copies — each loader supplies its own caps and wraps the returned reason
 * in its own exception type.
 */
package io.github.xiddoc.rosetta.core

/** Shared pre-parse size / nesting guard for the strict-JSON loaders. */
internal object JsonInputGuard {
    /** Highest code point encoded as 1 UTF-8 byte (U+007F). See [utf8ByteLength]. */
    private const val UTF8_1BYTE_MAX = 0x7F

    /** Highest code point encoded as 2 UTF-8 bytes (U+07FF). See [utf8ByteLength]. */
    private const val UTF8_2BYTE_MAX = 0x7FF

    /** UTF-8 byte count for a BMP code unit above U+07FF (incl. each surrogate). See [utf8ByteLength]. */
    private const val UTF8_3BYTE_LEN = 3

    /**
     * Check [text] against [maxBytes] (UTF-8) and [maxDepth] (structural
     * nesting). Returns `null` when the input is within bounds, or a
     * human-readable reason it was rejected (containing the word `bytes` or
     * `depth`, so callers can prefix their own noun). The size check runs
     * first and short-circuits, so an oversized input never reaches the depth
     * scan.
     */
    fun check(
        text: String,
        maxBytes: Int,
        maxDepth: Int,
    ): String? {
        val bytes = utf8ByteLength(text, maxBytes)
        if (bytes > maxBytes) {
            return "input exceeds the $maxBytes-byte limit (over $maxBytes bytes)"
        }
        val depth = maxNestingDepth(text, maxDepth)
        if (depth > maxDepth) {
            return "input nests to depth $depth, over the $maxDepth limit"
        }
        return null
    }

    /**
     * UTF-8 byte length of [text] computed in place (no `toByteArray` copy).
     * Each `char` contributes 1 byte (≤ U+007F), 2 bytes (≤ U+07FF), or 3 bytes
     * (any higher BMP code unit — INCLUDING each half of a surrogate pair).
     *
     * This is a CONSERVATIVE OVERCOUNT for supplementary characters, NOT exact
     * parity with `String.toByteArray(UTF_8)`: a real surrogate pair encodes to
     * 4 UTF-8 bytes, but counting 3 bytes per surrogate charges it 6 — an
     * overcount of 2 bytes per supplementary char. That is intentional and safe
     * for a fail-fast SIZE guard: it can only make the count larger, so it never
     * lets an over-cap input slip through; the worst case is rejecting an input
     * very slightly under the byte cap that happens to be dense in emoji, which
     * is far below any real artifact size. Short-circuits at [maxBytes] + 1:
     * once the running total exceeds the cap the exact size is irrelevant (the
     * input is rejected), so we stop counting.
     */
    private fun utf8ByteLength(
        text: String,
        maxBytes: Int,
    ): Int {
        var bytes = 0
        for (ch in text) {
            val code = ch.code
            bytes +=
                when {
                    code <= UTF8_1BYTE_MAX -> 1
                    code <= UTF8_2BYTE_MAX -> 2
                    else -> UTF8_3BYTE_LEN
                }
            // Bound the work: the moment we pass the cap the exact length no
            // longer matters, so stop (returns a value strictly over the cap).
            if (bytes > maxBytes) return bytes
        }
        return bytes
    }

    /**
     * Single-pass scan of the maximum `{`/`[` nesting depth, skipping over
     * string literals so structural punctuation inside strings is ignored.
     * Returns the deepest level reached, short-circuiting once it passes
     * [maxDepth] (there is nothing deeper to learn).
     */
    private fun maxNestingDepth(
        text: String,
        maxDepth: Int,
    ): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > max) max = depth
                    // Once we've already exceeded the cap there is nothing
                    // deeper to learn — stop scanning early to bound the work.
                    if (max > maxDepth) return max
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return max
    }
}
