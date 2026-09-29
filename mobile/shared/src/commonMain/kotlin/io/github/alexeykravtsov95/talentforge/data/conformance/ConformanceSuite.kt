package io.github.alexeykravtsov95.talentforge.data.conformance

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import io.github.alexeykravtsov95.talentforge.data.talentdata.PrerequisiteKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Root of an `allocation-conformance.v1` suite
 * (docs/schema/allocation-conformance.v1.schema.json): versioned scenarios the
 * allocation engine must reproduce.
 *
 * M3A implements loading and structural validation only. Executing scenarios
 * (spend/refund/validate semantics) is deliberately out of scope until M3B.
 */
@Serializable
data class ConformanceSuite(
    val schemaVersion: Int = 1,
    val game: GameId,
    val clientBuild: String,
    val datasets: List<ConformanceDatasetRef>,
    val scenarios: List<ConformanceScenario>,
) {
    /** Total number of steps across all scenarios. */
    val totalSteps: Int get() = scenarios.sumOf { it.steps.size }
}

/** Dataset a scenario suite is bound to, pinned by content hash. */
@Serializable
data class ConformanceDatasetRef(
    @SerialName("class") val classToken: ClassToken,
    val file: String,
    val contentHash: ContentHash,
)

@Serializable
data class ConformanceScenario(
    val id: String,
    @SerialName("class") val classToken: ClassToken,
    val description: String,
    val rules: List<String>,
    val synthetic: Boolean,
    val mutations: List<ConformanceMutation> = emptyList(),
    val initial: List<NodeAllocation> = emptyList(),
    val pointLimit: Int? = null,
    val steps: List<ConformanceStep>,
)

/**
 * A synthetic, explicit change applied to the real dataset before a scenario
 * runs (see the Mutation description in the schema).
 */
@Serializable
data class ConformanceMutation(
    val op: ConformanceMutationOp,
    val nodeId: Int,
    val kind: PrerequisiteKind? = null,
    val prerequisiteNodeId: Int? = null,
    val value: MutationValue? = null,
)

@Serializable
enum class ConformanceMutationOp {
    @SerialName("addPrerequisite")
    ADD_PREREQUISITE,

    @SerialName("setNodeType")
    SET_NODE_TYPE,

    @SerialName("setNodeFlags")
    SET_NODE_FLAGS,

    @SerialName("setEntryType")
    SET_ENTRY_TYPE,

    @SerialName("addUnresolvedCondition")
    ADD_UNRESOLVED_CONDITION,
}

/** Mutation operand: the schema allows an integer, a string or null. */
@Serializable(with = MutationValueSerializer::class)
sealed interface MutationValue {
    data class IntegralValue(val value: Long) : MutationValue

    data class TextValue(val value: String) : MutationValue
}

internal object MutationValueSerializer : KSerializer<MutationValue> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MutationValue")

    override fun deserialize(decoder: Decoder): MutationValue {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("MutationValue supports only JSON")
        val element = input.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("expected an integer or string mutation value, got $element")
        return when {
            primitive.isString -> MutationValue.TextValue(primitive.content)
            primitive.isBooleanLiteral -> throw SerializationException(
                "mutation value must be an integer or string, got ${primitive.content}",
            )

            else -> primitive.content.toLongOrNull()?.let { MutationValue.IntegralValue(it) }
                ?: throw SerializationException("mutation value must be an integer, got ${primitive.content}")
        }
    }

    override fun serialize(encoder: Encoder, value: MutationValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("MutationValue supports only JSON")
        val primitive = when (value) {
            is MutationValue.IntegralValue -> JsonPrimitive(value.value)
            is MutationValue.TextValue -> JsonPrimitive(value.value)
        }
        jsonEncoder.encodeJsonElement(primitive)
    }
}

private val JsonPrimitive.isBooleanLiteral: Boolean
    get() = !isString && (content == "true" || content == "false")

/** One action applied to the allocation state, with its expected outcome. */
@Serializable
data class ConformanceStep(
    val op: ConformanceStepOp,
    val nodeId: Int? = null,
    val entryId: Int? = null,
    val expect: Expectation,
)

@Serializable
enum class ConformanceStepOp {
    @SerialName("spend")
    SPEND,

    @SerialName("refund")
    REFUND,

    @SerialName("validate")
    VALIDATE,
}

/** Expected engine outcome after a step. */
@Serializable
data class Expectation(
    val ok: Boolean,
    val reasonCodes: List<String>,
    val issues: List<ExpectedIssue>,
    val spentPoints: Int,
    val remainingPoints: Int,
    val nodeRank: Int? = null,
)


/** Expected issue in canonical order, without messages. */
@Serializable
data class ExpectedIssue(
    val code: String,
    val nodeId: Int? = null,
    val entryId: Int? = null,
    val required: Int? = null,
    val actual: Int? = null,
    val conditionId: Int? = null,
    val relatedNodeIds: List<Int> = emptyList(),
    val cause: String? = null,
)
