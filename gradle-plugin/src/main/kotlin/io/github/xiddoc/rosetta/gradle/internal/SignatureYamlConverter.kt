/*
 * Build-time YAML→JSON conversion for community signatures.
 *
 * A `signatures/<app>/signatures.yaml` is authored in YAML (the sigmatcher
 * dialect), but the on-device `SignatureLoader` reads JSON — deliberately, so
 * the device runtime never carries a YAML parser (rosetta-xposed#43). This
 * converter does the one-time YAML→JSON translation HERE, on the
 * developer's/CI JVM at build time, using snakeyaml-engine. The result is the
 * exact JSON shape `SignatureLoader.fromJson` accepts (a top-level array of
 * class rules), which the fetcher then validates with the real loader before
 * baking it into resources.
 *
 * snakeyaml correctly handles the dialect's quoting (single-quoted scalars keep
 * backslashes literal, so a regex like `'requestTicket\(…\)'` round-trips
 * verbatim) and the int-or-string `count` (kept as-is; the loader ignores it).
 * Nothing here runs on a device.
 */
package io.github.xiddoc.rosetta.gradle.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** Converts a fetched sigmatcher `signatures.yaml` to the JSON `SignatureLoader` reads. */
internal object SignatureYamlConverter {
    private val json = Json { prettyPrint = false }

    /**
     * Parse [yaml] and re-emit it as JSON. Throws [IllegalStateException] if the
     * YAML is malformed (snakeyaml raises) — the fetcher turns that into a build
     * failure, so a broken upstream file never bakes silently.
     */
    fun toJson(yaml: String): String {
        val loaded =
            try {
                Load(LoadSettings.builder().build()).loadFromString(yaml)
            } catch (ex: org.snakeyaml.engine.v2.exceptions.YamlEngineException) {
                error("signatures.yaml is not valid YAML: ${ex.message}")
            }
        return json.encodeToString(JsonElement.serializer(), toElement(loaded))
    }

    /** Map snakeyaml's plain-Java tree (Map/List/scalars) onto kotlinx JSON. */
    private fun toElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toElement(v) })
            is List<*> -> JsonArray(value.map { toElement(it) })
            is Boolean -> JsonPrimitive(value)
            // snakeyaml decodes YAML integers as Int/Long/BigInteger and the
            // `count: '1-2'` range form as a String — both reach JSON intact.
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            // BigInteger/BigDecimal/timestamps and any other scalar: render as a
            // string so the JSON stays well-formed (these never appear in the
            // fields the loader reads, which are all strings/ints).
            else -> JsonPrimitive(value.toString())
        }
}
