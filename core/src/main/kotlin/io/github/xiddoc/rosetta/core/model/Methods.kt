/*
 * Method-map value handling.
 *
 * In the schema, a class's `methods` is keyed by real method name and the
 * value is EITHER a single method object (the common case — one overload)
 * OR an array of them (the multi-overload form). rosetta-frida models this
 * as `MethodEntry | MethodEntry[]`; on the Kotlin side we normalize both
 * input shapes to a non-empty list ([MethodOverloads]) at parse time, and
 * re-emit the single-object form when there is exactly one overload so the
 * JSON round-trips faithfully.
 */
package io.github.xiddoc.rosetta.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder

/** Methods keyed by real name; each value is one-or-more overloads. */
public typealias Methods = Map<String, MethodOverloads>

/**
 * The overloads registered under a single real method name. Always holds
 * at least one [MethodEntry]; an empty overload list is semantically
 * meaningless and rejected at parse time.
 */
@Serializable(with = MethodOverloadsSerializer::class)
public data class MethodOverloads(
    val entries: List<MethodEntry>,
) {
    init {
        require(entries.isNotEmpty()) { "a method must declare at least one overload" }
    }

    /** Convenience: the single overload, or `null` when there are several. */
    public val singleOrNull: MethodEntry?
        get() = entries.singleOrNull()
}

/**
 * Accepts both on-disk shapes (object or array); emits the object form for
 * a lone overload and the array form otherwise.
 */
public object MethodOverloadsSerializer : KSerializer<MethodOverloads> {
    private val listSerializer = ListSerializer(MethodEntry.serializer())

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("MethodOverloads")

    override fun deserialize(decoder: Decoder): MethodOverloads {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: error("MethodOverloads can only be read from JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray ->
                MethodOverloads(jsonDecoder.json.decodeFromJsonElement(listSerializer, element))
            else ->
                MethodOverloads(
                    listOf(jsonDecoder.json.decodeFromJsonElement(MethodEntry.serializer(), element)),
                )
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: MethodOverloads,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: error("MethodOverloads can only be written to JSON")
        val single = value.singleOrNull
        if (single != null) {
            jsonEncoder.encodeSerializableValue(MethodEntry.serializer(), single)
        } else {
            jsonEncoder.encodeSerializableValue(listSerializer, value.entries)
        }
    }
}
