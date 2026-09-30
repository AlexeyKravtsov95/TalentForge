package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssue
import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine

/**
 * The UI-facing, read-only allocation-state projection of one talent tree
 * (M4.2). This is a *presentation* of the M3B engine output, never a second
 * allocation model: every fact comes from [TalentAllocationEngine.canSpend],
 * [BuildState] and the real dataset, and the mapper never calls
 * `spend`/`refund`.
 *
 * The engine can report several reasons for one node at the same time (a node
 * may fail its tier gate *and* its prerequisite edge). Those reasons are never
 * collapsed: [TalentNodePresentation.issues] keeps the engine's full ordered
 * issue set, and [state] is only the deterministic *visual* precedence —
 * B3 does not freeze a precedence for combined locks, so the rule below is
 * UI-only and documented as such (see [TalentNodeState]).
 */
data class TalentTreePresentation(
    val nodes: Map<Int, TalentNodePresentation>,
    val spentPoints: Int,
    val remainingPoints: Int,
    val pointBudget: Int,
)

/**
 * One node's presentation facts. No gameplay rules live here — availability
 * and lock reasons are the engine's issues, structured for the visuals and the
 * inspector.
 */
data class TalentNodePresentation(
    val nodeId: Int,
    val rank: Int,
    val maxRank: Int,
    val state: TalentNodeState,
    val issues: List<AllocationIssue>,
    /** Sorted distinct prerequisite source ids the engine reports as unmet. */
    val unmetPrerequisiteNodeIds: List<Int>,
    /** `required`/`actual` of every TIER_GATE_NOT_MET issue, in engine order. */
    val tierGates: List<TalentTierGateProgress>,
) {
    val isAllocated: Boolean get() = rank > 0

    val isMaxed: Boolean get() = rank >= maxRank

    /** Rank chip text ("2/5"), always derived from real state — never hardcoded. */
    val rankText: String get() = "$rank/$maxRank"
}

/** Tier-gate progress from a real TIER_GATE_NOT_MET issue (structured values). */
data class TalentTierGateProgress(
    val requiredPoints: Int,
    val spentPoints: Int,
)

/**
 * Primary visual state of a node, using the B3 node-state language
 * (TalentForge_TalentTree_B3.pdf 02/07). The categories are presentation
 * facts; allocation truth stays in [TalentNodePresentation.issues].
 *
 * UI-only precedence (B3 freezes no combined-lock rule): fail-closed
 * unsupported/unresolved first (never shown as purchasable), then
 * partial/maxed from the purchased rank, then prerequisite lock (the
 * node-specific, Show-navigable reason) ahead of tier lock, then no-points.
 */
enum class TalentNodeState {
    /** Reachable, nothing spent — solid outline, empty chip. */
    AVAILABLE,

    /** Some ranks spent — accent tint, filled accent chip. */
    PARTIAL,

    /** All ranks spent — solid fill, double rim, light chip. */
    MAXED,

    /** A prerequisite edge is not active — dashed border, ↑ chip. */
    PREREQUISITE_LOCKED,

    /** A tier gate is not met — dashed border, lock chip. */
    TIER_LOCKED,

    /** Reachable but the point budget is exhausted — dimmed solid border. */
    NO_POINTS,

    /** Fail-closed: unsupported/unresolved/invalid — conservative unavailable. */
    UNAVAILABLE,
}

/** Issue codes that make a node unsafe to present as purchasable, whatever else is true. */
private val FAIL_CLOSED_CODES: Set<AllocationIssueCode> = setOf(
    AllocationIssueCode.UNSUPPORTED_NODE_TYPE,
    AllocationIssueCode.UNSUPPORTED_ENTRY_TYPE,
    AllocationIssueCode.UNSUPPORTED_NODE_FLAGS,
    AllocationIssueCode.UNSUPPORTED_EDGE_COMBINATION,
    AllocationIssueCode.UNRESOLVED_CONDITION,
    AllocationIssueCode.NODE_NEVER_PURCHASABLE,
    // the state itself is not valid / the node is unknown to the engine:
    // present nothing actionable rather than guessing.
    AllocationIssueCode.UNKNOWN_NODE,
    AllocationIssueCode.UNKNOWN_TREE,
    AllocationIssueCode.INVALID_BUILD_STATE,
    AllocationIssueCode.RANK_OUT_OF_BOUNDS,
    AllocationIssueCode.DUPLICATE_ALLOCATION,
)

private val PREREQUISITE_CODES: Set<AllocationIssueCode> = setOf(
    AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET,
    AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET,
)

/**
 * Projects every node of the engine's tree through the read-only engine query
 * surface. Deterministic and side-effect free: the same engine + state always
 * produce the same presentation, and [state] is never mutated (M4.2 is
 * read-only; M4.3 will re-run this mapper when ranks actually change).
 */
fun buildTalentTreePresentation(
    engine: TalentAllocationEngine,
    state: BuildState,
): TalentTreePresentation {
    val nodes = HashMap<Int, TalentNodePresentation>(engine.tree.nodes.size)
    for (node in engine.tree.nodes) {
        val decision = engine.canSpend(state, node.id)
        nodes[node.id] = nodePresentation(node.id, node.maxRank, state.rankOf(node.id), decision.issues)
    }
    return TalentTreePresentation(
        nodes = nodes,
        spentPoints = engine.spentPoints(state),
        remainingPoints = engine.remainingPoints(state),
        pointBudget = engine.pointBudget,
    )
}

/** Per-node projection shared by the tree mapper and focused inspector tests. */
internal fun nodePresentation(
    nodeId: Int,
    maxRank: Int,
    rank: Int,
    issues: List<AllocationIssue>,
): TalentNodePresentation {
    val unmetPrerequisiteNodeIds = issues
        .filter { it.code in PREREQUISITE_CODES }
        .flatMap { it.relatedNodeIds }
        .distinct()
        .sorted()
    val tierGates = issues
        .filter { it.code == AllocationIssueCode.TIER_GATE_NOT_MET }
        .map { TalentTierGateProgress(requiredPoints = it.required ?: 0, spentPoints = it.actual ?: 0) }

    val state = when {
        issues.any { it.code in FAIL_CLOSED_CODES } -> TalentNodeState.UNAVAILABLE
        rank > 0 && rank >= maxRank -> TalentNodeState.MAXED
        rank > 0 -> TalentNodeState.PARTIAL
        unmetPrerequisiteNodeIds.isNotEmpty() -> TalentNodeState.PREREQUISITE_LOCKED
        tierGates.isNotEmpty() -> TalentNodeState.TIER_LOCKED
        issues.any {
            it.code == AllocationIssueCode.NO_POINTS_REMAINING ||
                it.code == AllocationIssueCode.POINT_BUDGET_EXCEEDED
        } -> TalentNodeState.NO_POINTS

        else -> TalentNodeState.AVAILABLE
    }
    return TalentNodePresentation(
        nodeId = nodeId,
        rank = rank,
        maxRank = maxRank,
        state = state,
        issues = issues,
        unmetPrerequisiteNodeIds = unmetPrerequisiteNodeIds,
        tierGates = tierGates,
    )
}

/** Short accessibility label of the primary visual state (B3 02 wording). */
fun TalentNodeState.accessibilityLabel(): String = when (this) {
    TalentNodeState.AVAILABLE -> "available"
    TalentNodeState.PARTIAL -> "in progress"
    TalentNodeState.MAXED -> "maxed"
    TalentNodeState.PREREQUISITE_LOCKED -> "locked by prerequisite"
    TalentNodeState.TIER_LOCKED -> "locked by tier"
    TalentNodeState.NO_POINTS -> "no points remaining"
    TalentNodeState.UNAVAILABLE -> "unavailable"
}

/** Spoken form of a node's rank + state ("2/5, in progress"). */
fun TalentNodePresentation.accessibilityStateDescription(): String =
    "$rankText, ${state.accessibilityLabel()}"
