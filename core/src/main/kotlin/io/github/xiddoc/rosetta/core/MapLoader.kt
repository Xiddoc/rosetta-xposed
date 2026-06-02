/*
 * Map loading + validation.
 *
 * The on-disk JSON is parsed into a [RosettaMap] and gated on the schema
 * version. This is the Kotlin analogue of rosetta-frida's
 * `src/validate/schema.ts` + JSON load path:
 *
 *   - Unknown keys are accepted and ignored, so additive schema evolution
 *     doesn't break older readers (matches the Zod `.strip()` behaviour).
 *   - `schema_version` is a hard gate: a map declaring anything other than
 *     the current version is rejected with a structured issue, exactly as
 *     the Frida side rejects non-`2` maps via `z.literal(2)`.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION
import io.github.xiddoc.rosetta.core.model.RosettaMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Loads and validates Rosetta map artifacts. */
public object MapLoader {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    /**
     * Parse and validate a JSON map.
     *
     * @throws MapValidationException if the text is not valid JSON, does
     *   not match the schema, or declares a non-current `schema_version`.
     */
    public fun fromJson(text: String): RosettaMap {
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
     * Validate an already-deserialized map (schema-version gate plus the
     * structural invariants that the type system can't express).
     *
     * @throws MapValidationException on any failed invariant.
     */
    public fun validate(map: RosettaMap): RosettaMap {
        val issues = mutableListOf<ValidationIssue>()

        if (map.schemaVersion != CURRENT_SCHEMA_VERSION) {
            issues +=
                ValidationIssue(
                    "schema_version",
                    "expected $CURRENT_SCHEMA_VERSION, got ${map.schemaVersion}",
                )
        }
        if (map.app.isBlank()) {
            issues += ValidationIssue("app", "must not be empty")
        }
        if (map.version.isBlank()) {
            issues += ValidationIssue("version", "must not be empty")
        }
        if (map.versionCode < 0) {
            issues += ValidationIssue("version_code", "must be a non-negative integer")
        }

        if (issues.isNotEmpty()) {
            val summary = if (issues.size == 1) "1 issue" else "${issues.size} issues"
            throw MapValidationException("Map failed schema validation ($summary)", issues)
        }
        return map
    }
}
