/*
 * Map loading + validation.
 *
 * The on-disk JSON is parsed into a [RosettaMap] and gated on the schema
 * version. This is the Kotlin analogue of rosetta-frida's
 * `src/validate/schema.ts` + JSON load path:
 *
 *   - Unknown keys are REJECTED (xposed#14 M6): the canonical rosetta-maps
 *     JSON Schema is going `additionalProperties: false` (strict), so a typo'd
 *     or stray key is a hard parse failure here too — a map that loads on one
 *     client loads on all. (`ignoreUnknownKeys = false`.)
 *   - `schema_version` is a hard gate: a map declaring anything other than
 *     the current version is rejected with a structured issue, exactly as
 *     the Frida side rejects non-`2` maps via `z.literal(2)`.
 *
 * Beyond the schema gate, [validate] enforces a set of hardening BOUNDS
 * (entry counts, string lengths, the `app` package-name shape) and rejects
 * JS prototype-pollution-style reserved keys. These bounds mirror the
 * canonical rosetta-maps JSON Schema (the authoritative reference; the
 * frida Zod and this Kotlin client track it). [fromJson] additionally
 * applies a cheap pre-parse denial-of-service guard (max input size, max
 * nesting depth) before kotlinx-serialization's recursive decoder ever runs.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION
import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.RosettaMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Loads and validates Rosetta map artifacts. */
public object MapLoader {
    private val json: Json =
        Json {
            // Strict (xposed#14 M6): reject unknown / typo'd keys, matching the
            // canonical schema's `additionalProperties: false`. All optional
            // schema fields are declared on the model, so a rejected key is a
            // genuinely unknown one, not additive evolution this reader lags.
            ignoreUnknownKeys = false
            isLenient = false
        }

    // ---- Hardening bounds (mirrored across all three Rosetta clients) ----

    /** Maximum number of class entries in a single map. */
    public const val MAX_CLASSES: Int = 50_000

    /** Maximum number of method names per class. */
    public const val MAX_METHODS_PER_CLASS: Int = 5_000

    /** Maximum number of field names per class. */
    public const val MAX_FIELDS_PER_CLASS: Int = 5_000

    /** Maximum number of overloads registered under one method name. */
    public const val MAX_OVERLOADS_PER_METHOD: Int = 200

    /** Maximum number of `anchors` strings per class. */
    public const val MAX_ANCHORS_PER_CLASS: Int = 1_000

    /** Maximum number of `sources` provenance entries. */
    public const val MAX_SOURCES: Int = 100

    /** Maximum length of an obfuscated / short name. */
    public const val MAX_SHORT_NAME_LEN: Int = 512

    /** Maximum length of a method `signature`. */
    public const val MAX_SIGNATURE_LEN: Int = 4_096

    /** Maximum length of the `app` package name. */
    public const val MAX_APP_LEN: Int = 256

    /** Maximum length of the `version` label. */
    public const val MAX_VERSION_LEN: Int = 256

    /**
     * Maximum value of `version_code` — the full Android `longVersionCode`
     * (`(versionCodeMajor shl 32) or versionCode`), capped at 2^53 − 1
     * (`Number.MAX_SAFE_INTEGER`), matching `maximum: 9007199254740991` in
     * the canonical rosetta-maps JSON Schema and rosetta-frida's Zod
     * `MAX_VERSION_CODE`. Apps that set `versionCodeMajor` legitimately
     * exceed the old int32 cap; the value is NOT masked to its low 32 bits
     * (that would alias distinct releases). The bound is 2^53 − 1 (not the
     * full `Long` range) so the three clients accept/reject identical
     * values — the Frida client reads this through a JS `Number`, exact only
     * up to `Number.MAX_SAFE_INTEGER`.
     */
    public const val MAX_VERSION_CODE: Long = 9_007_199_254_740_991L

    /** Maximum length of any other free-form string. */
    public const val MAX_FREE_STRING_LEN: Int = 4_096

    /**
     * Maximum raw input size handed to [fromJson], in bytes (8 MiB). A
     * larger input is rejected fail-fast as a memory / parse-pressure
     * vector before any deserialization runs.
     */
    public const val MAX_INPUT_BYTES: Int = 8 * 1024 * 1024

    /**
     * Maximum JSON structural nesting depth accepted by [fromJson]. Guards
     * against deeply-nested input that could stack-overflow
     * kotlinx-serialization's recursive descent decoder.
     */
    public const val MAX_NESTING_DEPTH: Int = 64

    /**
     * Keys forbidden in any user-keyed map (`classes`, `methods`,
     * `fields`). These are JavaScript prototype-pollution footguns; Kotlin
     * `Map`s are immune, but the keys are rejected here for cross-client
     * parity so a map that loads on the Frida side loads here too.
     */
    private val RESERVED_KEYS: Set<String> = setOf("__proto__", "constructor", "prototype")

    /**
     * The Android package-name shape required of `app`: EACH dotted segment
     * must start with a letter (not a digit), matching the canonical
     * rosetta-maps JSON Schema + the rosetta-frida Zod twin. A digit-leading
     * segment after a dot (e.g. `com.1app`) is rejected.
     */
    private val APP_PATTERN: Regex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Highest code point encoded as 1 UTF-8 byte (U+007F). See [utf8ByteLength]. */
    private const val UTF8_1BYTE_MAX = 0x7F

    /** Highest code point encoded as 2 UTF-8 bytes (U+07FF). See [utf8ByteLength]. */
    private const val UTF8_2BYTE_MAX = 0x7FF

    /** UTF-8 byte count for a BMP code unit above U+07FF (incl. each surrogate). See [utf8ByteLength]. */
    private const val UTF8_3BYTE_LEN = 3

    /**
     * Parse and validate a JSON map.
     *
     * @throws MapInputTooLargeException if the raw text exceeds
     *   [MAX_INPUT_BYTES] or is nested deeper than [MAX_NESTING_DEPTH].
     * @throws MapValidationException if the text is not valid JSON, does
     *   not match the schema, or violates a hardening bound.
     */
    public fun fromJson(text: String): RosettaMap {
        guardInput(text)
        val map =
            try {
                json.decodeFromString(RosettaMap.serializer(), text)
            } catch (ex: SerializationException) {
                // Interpolate the (nullable) message rather than `?: "..."`: a
                // kotlinx-serialization parse error always carries a message,
                // so an elvis fallback would be a permanently-uncovered branch.
                // Interpolation is null-safe (renders "null" in the rare event)
                // and keeps the issue text honest without the dead branch.
                val detail = "${ex.message}"
                throw MapValidationException(
                    "Map failed to parse: $detail",
                    listOf(ValidationIssue("", detail)),
                    ex,
                )
            }
        return validate(map)
    }

    /**
     * Cheap pre-parse denial-of-service guard: reject oversized input by
     * byte length, then scan once for excessive structural nesting (which
     * would risk a stack overflow in the recursive decoder).
     *
     * The byte-length check counts UTF-8 bytes in a SINGLE pass WITHOUT
     * allocating a copy (xposed#14 L4): the previous `toByteArray().size`
     * allocated a full byte[] just to read its length, scanning the input an
     * extra time. [utf8ByteLength] counts in place and short-circuits the moment
     * the count passes [MAX_INPUT_BYTES], so an oversized input is rejected
     * fail-fast before the depth scan ever runs.
     */
    private fun guardInput(text: String) {
        val bytes = utf8ByteLength(text)
        if (bytes > MAX_INPUT_BYTES) {
            throw MapInputTooLargeException(
                "Map input exceeds the $MAX_INPUT_BYTES-byte limit (over $MAX_INPUT_BYTES bytes)",
            )
        }
        val depth = maxNestingDepth(text)
        if (depth > MAX_NESTING_DEPTH) {
            throw MapInputTooLargeException(
                "Map input nests to depth $depth, over the $MAX_NESTING_DEPTH limit",
            )
        }
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
     * is far below any real map size. Short-circuits at [MAX_INPUT_BYTES] + 1:
     * once the running total exceeds the cap the exact size is irrelevant (the
     * input is rejected), so we stop counting.
     */
    private fun utf8ByteLength(text: String): Int {
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
            if (bytes > MAX_INPUT_BYTES) return bytes
        }
        return bytes
    }

    /**
     * Single-pass scan of the maximum `{`/`[` nesting depth, skipping over
     * string literals so structural punctuation inside strings is ignored.
     * Returns the deepest level reached.
     */
    private fun maxNestingDepth(text: String): Int {
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
                    if (max > MAX_NESTING_DEPTH) return max
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return max
    }

    /**
     * Validate an already-deserialized map (schema-version gate plus the
     * structural invariants that the type system can't express).
     *
     * @throws MapValidationException on any failed invariant.
     */
    public fun validate(map: RosettaMap): RosettaMap {
        val issues = BoundsChecker(map).run()
        if (issues.isNotEmpty()) {
            val summary = if (issues.size == 1) "1 issue" else "${issues.size} issues"
            throw MapValidationException("Map failed schema validation ($summary)", issues)
        }
        return map
    }

    /**
     * Accumulates every schema + hardening-bound violation for one [map]
     * into [issues]. Carrying the issue list as state (rather than passing
     * it to every helper) keeps the traversal readable and keeps
     * [MapLoader] itself small. All caps live on [MapLoader] so the three
     * Rosetta clients can be diffed against one set of constants.
     */
    private class BoundsChecker(
        private val map: RosettaMap,
    ) {
        private val issues = mutableListOf<ValidationIssue>()

        fun run(): List<ValidationIssue> {
            if (map.schemaVersion != CURRENT_SCHEMA_VERSION) {
                issues += ValidationIssue("schema_version", "expected $CURRENT_SCHEMA_VERSION, got ${map.schemaVersion}")
            }
            checkApp()
            if (map.version.isBlank()) {
                issues += ValidationIssue("version", "must not be empty")
            } else {
                len("version", map.version, MAX_VERSION_LEN)
            }
            if (map.versionCode < 0) {
                issues += ValidationIssue("version_code", "must be a non-negative integer")
            } else if (map.versionCode > MAX_VERSION_CODE) {
                issues +=
                    ValidationIssue(
                        "version_code",
                        "must be at most $MAX_VERSION_CODE (2^53 - 1, Number.MAX_SAFE_INTEGER)",
                    )
            }
            free("captured_at", map.capturedAt)
            free("client_hints.frida_min_version", map.clientHints?.fridaMinVersion)
            free("client_hints.frida_max_version", map.clientHints?.fridaMaxVersion)
            checkSources()
            checkClasses()
            return issues
        }

        private fun checkApp() {
            if (map.app.isBlank()) {
                issues += ValidationIssue("app", "must not be empty")
                return
            }
            len("app", map.app, MAX_APP_LEN)
            if (!APP_PATTERN.matches(map.app)) {
                issues += ValidationIssue("app", "must be a dotted package name matching ${APP_PATTERN.pattern}")
            }
        }

        private fun checkSources() {
            val sources = map.sources ?: return
            cap("sources", sources.size, MAX_SOURCES, "entries")
            sources.forEachIndexed { i, src ->
                nonEmpty("sources[$i].tool", src.tool)
                free("sources[$i].tool", src.tool)
                free("sources[$i].config", src.config)
                free("sources[$i].notes", src.notes)
            }
        }

        private fun checkClasses() {
            cap("classes", map.classes.size, MAX_CLASSES, "entries")
            for ((realName, entry) in map.classes) {
                reserved("classes", realName)
                checkClassEntry(realName, entry)
            }
        }

        private fun checkClassEntry(
            realName: String,
            entry: ClassEntry,
        ) {
            val path = "classes[$realName]"
            nonEmpty("$path.obfuscated", entry.obfuscated)
            len("$path.obfuscated", entry.obfuscated, MAX_SHORT_NAME_LEN)
            free("$path.extends", entry.extends)
            free("$path.dex", entry.dex)
            free("$path.aidl_descriptor", entry.aidlDescriptor)
            free("$path.source", entry.source)
            entry.anchors?.let { anchors ->
                cap("$path.anchors", anchors.size, MAX_ANCHORS_PER_CLASS, "entries")
                anchors.forEachIndexed { i, a -> free("$path.anchors[$i]", a) }
            }
            entry.methods?.let { methods ->
                cap("$path.methods", methods.size, MAX_METHODS_PER_CLASS, "entries")
                for ((name, overloads) in methods) {
                    reserved("$path.methods", name)
                    val mpath = "$path.methods[$name]"
                    cap(mpath, overloads.entries.size, MAX_OVERLOADS_PER_METHOD, "overloads")
                    overloads.entries.forEachIndexed { i, m ->
                        nonEmpty("$mpath[$i].obfuscated", m.obfuscated)
                        len("$mpath[$i].obfuscated", m.obfuscated, MAX_SHORT_NAME_LEN)
                        nonEmpty("$mpath[$i].signature", m.signature)
                        len("$mpath[$i].signature", m.signature, MAX_SIGNATURE_LEN)
                    }
                }
            }
            entry.fields?.let { fields ->
                cap("$path.fields", fields.size, MAX_FIELDS_PER_CLASS, "entries")
                for ((name, f) in fields) {
                    reserved("$path.fields", name)
                    val fpath = "$path.fields[$name]"
                    nonEmpty("$fpath.obfuscated", f.obfuscated)
                    len("$fpath.obfuscated", f.obfuscated, MAX_SHORT_NAME_LEN)
                    nonEmpty("$fpath.type", f.type)
                    len("$fpath.type", f.type, MAX_SIGNATURE_LEN)
                }
            }
        }

        // ---- Leaf checks (all append to `issues`) ----

        private fun cap(
            path: String,
            count: Int,
            max: Int,
            noun: String,
        ) {
            if (count > max) issues += ValidationIssue(path, "has $count $noun, over the $max limit")
        }

        private fun reserved(
            container: String,
            key: String,
        ) {
            if (key in RESERVED_KEYS) issues += ValidationIssue(container, "reserved key \"$key\" is not allowed")
        }

        /**
         * Non-empty leaf check for a required string. The canonical schema +
         * Frida Zod pin `minLength: 1` on `obfuscated` / `signature` / field
         * `type` / `source.tool`; an empty value is rejected fail-closed
         * (these are non-null in the model, so only the empty case is possible).
         */
        private fun nonEmpty(
            path: String,
            value: String,
        ) {
            if (value.isEmpty()) issues += ValidationIssue(path, "must not be empty")
        }

        /** Length cap for a (possibly null) string; null is a no-op. */
        private fun len(
            path: String,
            value: String?,
            max: Int,
        ) {
            if (value != null && value.length > max) issues += ValidationIssue(path, "exceeds $max characters")
        }

        private fun free(
            path: String,
            value: String?,
        ) = len(path, value, MAX_FREE_STRING_LEN)
    }
}
