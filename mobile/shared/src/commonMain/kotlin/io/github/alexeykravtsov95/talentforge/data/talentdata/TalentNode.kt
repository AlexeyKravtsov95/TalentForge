package io.github.alexeykravtsov95.talentforge.data.talentdata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One purchasable node of a tree. */
@Serializable
data class TalentNode(
    val id: Int,
    val sectionIndex: Int?,
    val position: TalentPosition,
    val type: TalentNodeType,
    val flags: Int,
    val maxRank: Int,
    val spellId: Int?,
    val name: String?,
    val prerequisites: List<Prerequisite>,
    val unlockRequirements: List<UnlockRequirement>,
    val unresolvedConditionIds: List<Int>,
    val entries: List<TalentEntry>,
)

/** Raw client coordinates (authoritative) plus the derived grid position. */
@Serializable
data class TalentPosition(
    val x: Int,
    val y: Int,
    val row: Int,
    val column: Int,
)

/**
 * TraitNode.Type. The contract allows three mapped values plus an open
 * `unknown:<n>` form for unmapped raw types — [TalentNodeType.Unknown] preserves
 * that raw value verbatim instead of dropping or approximating it.
 */
@Serializable(with = TalentNodeTypeSerializer::class)
sealed interface TalentNodeType {
    data object Single : TalentNodeType

    data object Tiered : TalentNodeType

    data object Selection : TalentNodeType

    data class Unknown(val rawValue: String) : TalentNodeType
}

/** Edge-based prerequisite of a node. */
@Serializable
data class Prerequisite(
    val nodeId: Int,
    val kind: PrerequisiteKind,
)

@Serializable
enum class PrerequisiteKind {
    @SerialName("sufficient")
    SUFFICIENT,

    @SerialName("required")
    REQUIRED,
}

/**
 * Unlock gate of a node: points spent among [countedNodeIds] must reach
 * [requiredPoints]. The contract currently publishes a single kind
 * (schema-defaulted to `pointsSpent`).
 */
@Serializable
data class UnlockRequirement(
    val requiredPoints: Int,
    val countedNodeIds: List<Int>,
    val kind: UnlockRequirementKind = UnlockRequirementKind.POINTS_SPENT,
)

@Serializable
enum class UnlockRequirementKind {
    @SerialName("pointsSpent")
    POINTS_SPENT,
}
