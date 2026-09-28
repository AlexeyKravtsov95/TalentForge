package io.github.alexeykravtsov95.talentforge.data.talentdata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

/** One selectable entry (TraitNodeEntry) of a node. */
@Serializable
data class TalentEntry(
    val id: Int,
    val definitionId: Int,
    val spellId: Int,
    val name: String? = null,
    val maxRank: Int,
    val entryType: Int,
    val descriptionTemplate: String? = null,
    val effects: List<TalentEffect> = emptyList(),
)

/** One spell effect attached to an entry, with its per-rank value curve. */
@Serializable
data class TalentEffect(
    val index: Int,
    val perRank: List<RankValue>? = null,
    val perRankStatus: PerRankStatus,
    val basePoints: NumberValue? = null,
    val unresolvedReason: String? = null,
)

@Serializable
enum class PerRankStatus {
    @SerialName("resolved")
    RESOLVED,

    @SerialName("incomplete")
    INCOMPLETE,

    @SerialName("none")
    NONE,

    @SerialName("unresolved")
    UNRESOLVED,
}

/** Value of an effect at one rank. */
@Serializable
data class RankValue(
    val rank: Int,
    val value: NumberValue,
)

/**
 * JSON number that keeps its exact wire form. The schema allows `integer` or
 * `number` (never strings or booleans); the distinction is preserved instead of
 * being coerced to a single floating point type.
 */
@Serializable(with = NumberValueSerializer::class)
sealed interface NumberValue {
    data class Integral(val value: Long) : NumberValue

    data class Decimal(val literal: String) : NumberValue
}

internal object NumberValueSerializer : KSerializer<NumberValue> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("NumberValue")

    override fun deserialize(decoder: Decoder): NumberValue {
        val primitive = decoder.decodeJsonPrimitive()
        if (primitive.isString || primitive.isBooleanLiteral) {
            throw SerializationException("expected a JSON number, got ${primitive.content}")
        }
        val content = primitive.content
        content.toLongOrNull()?.let { return NumberValue.Integral(it) }
        if (content.isStrictDecimalLiteral()) {
            return NumberValue.Decimal(content)
        }
        throw SerializationException("invalid JSON number literal: $content")
    }

    override fun serialize(encoder: Encoder, value: NumberValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("NumberValue supports only JSON")
        val literal = when (value) {
            is NumberValue.Integral -> value.value.toString()
            is NumberValue.Decimal -> value.literal
        }
        jsonEncoder.encodeJsonElement(JsonPrimitive(literal))
    }

    private fun Decoder.decodeJsonPrimitive(): JsonPrimitive {
        val input = this as? JsonDecoder
            ?: throw SerializationException("NumberValue supports only JSON")
        val element = input.decodeJsonElement()
        return element as? JsonPrimitive
            ?: throw SerializationException("expected a JSON number primitive, got $element")
    }

    /** Accepts `1.5`, `-2`, `1e3`; rejects `1.`, `--1`, `1.2.3`, `1e`, `0x1f`. */
    private fun String.isStrictDecimalLiteral(): Boolean {
        if (isEmpty()) return false
        val body = when {
            startsWith("-") -> substring(1)
            startsWith("+") -> return false
            else -> this
        }
        if (body.isEmpty()) return false
        val exponentSplit = body.split('e', 'E')
        if (exponentSplit.size > 2) return false
        val mantissa = exponentSplit[0]
        if (exponentSplit.size == 2 && exponentSplit[1].isEmpty()) return false
        if (exponentSplit.size == 2 && !exponentSplit[1].all { it in '0'..'9' }) return false
        val dotParts = mantissa.split('.')
        if (dotParts.size > 2 || dotParts.any { it.isEmpty() }) return false
        return dotParts.joinToString("").all { it in '0'..'9' }
    }
}

internal object TalentNodeTypeSerializer : KSerializer<TalentNodeType> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TalentNodeType")

    override fun deserialize(decoder: Decoder): TalentNodeType {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("TalentNodeType supports only JSON")
        val element = input.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("expected a string talent node type, got $element")
        if (!primitive.isString) {
            throw SerializationException("talent node type must be a string, got ${primitive.content}")
        }
        return when (val raw = primitive.content) {
            "single" -> TalentNodeType.Single
            "tiered" -> TalentNodeType.Tiered
            "selection" -> TalentNodeType.Selection
            else -> TalentNodeType.Unknown(rawValue = raw)
        }
    }

    override fun serialize(encoder: Encoder, value: TalentNodeType) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("TalentNodeType supports only JSON")
        val raw = when (value) {
            TalentNodeType.Single -> "single"
            TalentNodeType.Tiered -> "tiered"
            TalentNodeType.Selection -> "selection"
            is TalentNodeType.Unknown -> value.rawValue
        }
        jsonEncoder.encodeJsonElement(JsonPrimitive(raw))
    }
}

private val JsonPrimitive.isBooleanLiteral: Boolean
    get() = !isString && (content == "true" || content == "false")
