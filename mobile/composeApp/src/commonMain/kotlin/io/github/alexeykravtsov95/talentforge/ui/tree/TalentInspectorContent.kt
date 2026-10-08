package io.github.alexeykravtsov95.talentforge.ui.tree

/**
 * Pure, UI-toolkit-free content of the talent inspector (M4.2). Built from a
 * [TalentNodePresentation], the real node names and the tree-level point
 * facts; every string is derived from dataset/engine values, and the builder
 * never invents spell descriptions, effect prose or lore the contracts do not
 * publish. Since M5.V2 the description comes from the build-pinned
 * `talent-tooltips.v1` sidecar ([TalentInspectorDescription]).
 *
 * Wording follows TalentForge_TalentTree_B3.pdf 03–05 where the design freezes
 * it ("Available", "In progress", "Maxed", "Requires <prereq> r/r",
 * "No talent points left · x/y spent", "Available · needed for <origin>").
 * The deviations forced by missing data are documented on the properties they
 * affect.
 */
data class TalentInspectorContent(
    val nodeId: Int,
    /** Real published node name (or the honest id fallback — never invented). */
    val name: String,
    val rankText: String,
    /** B3 state line; may append "· needed for <origin>" while a Show guide is active. */
    val stateLine: String,
    /**
     * "Requires <prerequisite> r/r" when a prerequisite edge is unmet. Kept
     * alongside [tierLine] when both lock reasons are present (B3 07 + M4.2
     * brief: preserve both facts, never collapse engine output).
     */
    val prerequisiteLine: String?,
    /**
     * "Spend <n> more points in this tree" with the real gate deficit.
     * Deviation from B3 04 ("Spend 2 more points in Arms"): the dataset
     * publishes no section/spec names (all Warrior sections are nameless), so
     * no spec name is invented.
     */
    val tierLine: String?,
    /** The one-hop Show action; absent when ineligible (see [ShowAction]). */
    val showAction: ShowAction?,
    /** Real required/spent values of every unmet tier gate (B3 non-interactive meter). */
    val tierMeters: List<TalentTierGateProgress>,
    /**
     * M5.V2: the rendered description and CURRENT / NEXT RANK comparison from
     * the verified `talent-tooltips.v1` sidecar; `null` when the caller
     * provided no descriptions (the inspector then has no description section).
     */
    val description: TalentInspectorDescription? = null,
) {
    /**
     * The read-only prerequisite navigation action (B3 07: one hop, never
     * spends). Offered only when the engine reports *exactly one* unmet
     * prerequisite source: B3 does not define which source Show should pick
     * when several exist, so the presentation fails safe and offers no action
     * rather than implying a semantic preference.
     */
    data class ShowAction(val prerequisiteNodeId: Int, val prerequisiteName: String)
}

/**
 * Real per-node facts the inspector copy needs beyond the presentation:
 * published names and max ranks of the tree's nodes (the engine's
 * SUFFICIENT-prerequisite issues carry no `required` number, so "Requires X
 * r/r" takes the source node's real max rank).
 */
data class TalentTreeNodeFacts(
    val names: Map<Int, String?>,
    val maxRanks: Map<Int, Int>,
) {
    fun nameOf(nodeId: Int): String =
        names[nodeId].orEmpty().ifBlank { "Talent $nodeId" }

    fun maxRankOf(nodeId: Int): Int = maxRanks[nodeId] ?: 0
}

/** Builds the node facts of an engine's tree (one lookup per node). */
fun talentNodeFacts(engine: io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine): TalentTreeNodeFacts =
    TalentTreeNodeFacts(
        names = engine.tree.nodes.associate { it.id to it.name },
        maxRanks = engine.tree.nodes.associate { it.id to it.maxRank },
    )

/**
 * Builds the inspector content of one node.
 *
 * @param facts published names/max ranks of the tree's nodes.
 * @param guideOriginName while a Show guide is active (the user navigated from
 *   this node), the target's state line appends the B3 "needed for" suffix.
 * @param descriptions the tree's verified talent descriptions (M5.V2); the
 *   default [TalentNodeDescriptions.None] leaves the description section out.
 * @param blockedReason the explanation of a disabled rank control (B3 04
 *   state 07: "+ is shown disabled and the state line explains why"; B3 07:
 *   "A blocked refund shows its reason in the state line"), or the transient
 *   reason after a denied mutation call. While present it replaces the state
 *   line entirely.
 */
fun buildTalentInspectorContent(
    presentation: TalentNodePresentation,
    tree: TalentTreePresentation,
    facts: TalentTreeNodeFacts,
    guideOriginName: String? = null,
    blockedReason: String? = null,
    descriptions: TalentNodeDescriptions = TalentNodeDescriptions.None,
): TalentInspectorContent {
    val name = facts.nameOf(presentation.nodeId)

    val baseStateLine = when (presentation.state) {
        TalentNodeState.AVAILABLE -> "Available"
        TalentNodeState.PARTIAL -> "In progress"
        TalentNodeState.MAXED -> "Maxed"
        TalentNodeState.PREREQUISITE_LOCKED,
        TalentNodeState.TIER_LOCKED,
        -> "" // the lock reason lines below are the state (B3 04: "The reason is spelled out in words")

        TalentNodeState.NO_POINTS ->
            "No talent points left · ${tree.spentPoints}/${tree.pointBudget} spent"

        TalentNodeState.UNAVAILABLE -> "Unavailable"
    }
    // B3 05: after Show, the target's state line reads "Available · needed for <origin>".
    // B3 07: a blocked action's reason takes the state line while it shows.
    val stateLine = when {
        blockedReason != null -> blockedReason
        guideOriginName != null && baseStateLine.isNotEmpty() ->
            "$baseStateLine · needed for $guideOriginName"

        else -> baseStateLine
    }

    return TalentInspectorContent(
        nodeId = presentation.nodeId,
        name = name,
        rankText = presentation.rankText,
        stateLine = stateLine,
        prerequisiteLine = prerequisiteLineOf(presentation, facts),
        tierLine = tierLineOf(presentation),
        showAction = presentation.unmetPrerequisiteNodeIds.singleOrNull()?.let { sourceId ->
            TalentInspectorContent.ShowAction(
                prerequisiteNodeId = sourceId,
                prerequisiteName = facts.nameOf(sourceId),
            )
        },
        tierMeters = presentation.tierGates,
        description = buildTalentInspectorDescription(
            descriptions = descriptions,
            nodeId = presentation.nodeId,
            rank = presentation.rank,
            maxRank = presentation.maxRank,
        ),
    )
}

/**
 * "Requires <prerequisite> <maxRank>/<maxRank>" from the real node facts (the
 * unmet source must be fully ranked — EDGE-ACTIVE). With several unmet
 * sources the combination has no frozen wording (and no Show — see
 * [TalentInspectorContent.ShowAction]), so the line reports the count without
 * inventing priority between them.
 */
private fun prerequisiteLineOf(
    presentation: TalentNodePresentation,
    facts: TalentTreeNodeFacts,
): String? {
    val unmet = presentation.unmetPrerequisiteNodeIds
    if (unmet.isEmpty()) return null
    if (unmet.size == 1) {
        val sourceId = unmet.single()
        val required = facts.maxRankOf(sourceId)
        return "Requires ${facts.nameOf(sourceId)} $required/$required"
    }
    return "Requires ${unmet.size} prerequisite talents"
}

/** "Spend <deficit> more points in this tree" from the first unmet gate's real values. */
private fun tierLineOf(presentation: TalentNodePresentation): String? =
    when (presentation.tierGates.size) {
        0 -> null
        1 -> {
            val gate = presentation.tierGates.single()
            val deficit = (gate.requiredPoints - gate.spentPoints).coerceAtLeast(1)
            "Spend $deficit more points in this tree"
        }

        else -> "Spend more points in this tree"
    }
