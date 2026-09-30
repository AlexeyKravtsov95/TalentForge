package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssue
import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine

/**
 * The M4.3 UI-facing **action projection** of one selected node: what the + / −
 * / Max controls may do *right now*, derived independently through
 * `engine.canSpend` / `engine.canRefund` over the current build.
 *
 * `TalentNodeState != action legality` — the visual state and the action
 * legality are deliberately separate projections of the same engine truth
 * (a PARTIAL node may allow both actions, or allow spending while a dependent
 * blocks the refund; a MAXED node denies spending but may or may not allow a
 * refund; a NO_POINTS node still refunds legal ranks). Button enabled state
 * comes from THIS type, never from [TalentNodeState] alone.
 *
 * Gameplay truth stays in the engine: this projection only queries and never
 * mutates. [presentation] is the node's already-built engine-backed
 * presentation (rank/maxRank/visual state are reused, never re-derived).
 */
data class TalentNodeActions(
    val nodeId: Int,
    val rank: Int,
    val maxRank: Int,
    val state: TalentNodeState,
    val canSpend: Boolean,
    val canRefund: Boolean,
    /** The engine's typed spend denial (empty when spending is allowed). */
    val spendIssues: List<AllocationIssue>,
    /** The engine's typed refund denial (empty when refunding is allowed). */
    val refundIssues: List<AllocationIssue>,
) {
    val isMaxed: Boolean get() = rank >= maxRank

    /**
     * Whether the B3 compact/full action row offers rank-changing controls at
     * all. Locked-by-prerequisite keeps `Show`, tier-locked keeps its meter,
     * and fail-closed unsupported nodes get no spend control.
     */
    val offersRankControls: Boolean
        get() = state == TalentNodeState.AVAILABLE ||
            state == TalentNodeState.PARTIAL ||
            state == TalentNodeState.MAXED ||
            state == TalentNodeState.NO_POINTS
}

/** Builds the action projection of one node from the real engine + build. */
fun buildTalentNodeActions(
    engine: TalentAllocationEngine,
    state: BuildState,
    presentation: TalentNodePresentation,
): TalentNodeActions {
    val spend = engine.canSpend(state, presentation.nodeId)
    val refund = engine.canRefund(state, presentation.nodeId)
    return TalentNodeActions(
        nodeId = presentation.nodeId,
        rank = presentation.rank,
        maxRank = presentation.maxRank,
        state = presentation.state,
        canSpend = spend.allowed,
        canRefund = refund.allowed,
        spendIssues = spend.issues,
        refundIssues = refund.issues,
    )
}

/**
 * Short user-facing copy of a denied action (B3 07: "A blocked refund shows
 * its reason in the state line for 2.6 s"). Wording is derived from the typed
 * issue **codes** only — raw engine diagnostic messages are never surfaced.
 * Facts that need names (which dependent blocks the refund) come from the
 * real node facts, never guesses.
 */
fun blockedActionReason(
    issues: List<AllocationIssue>,
    facts: TalentTreeNodeFacts,
): String {
    val issue = issues.firstOrNull() ?: return "Action not available"
    return when (issue.code) {
        AllocationIssueCode.NOT_ALLOCATED -> "No rank to refund"
        AllocationIssueCode.MAX_RANK_REACHED -> "Max rank reached"
        AllocationIssueCode.NO_POINTS_REMAINING,
        AllocationIssueCode.POINT_BUDGET_EXCEEDED,
        -> "No talent points left"

        AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK -> {
            val dependent = issue.nodeId
            if (dependent != null) {
                "Needed by ${facts.nameOf(dependent)}"
            } else {
                "Needed by an allocated talent"
            }
        }

        AllocationIssueCode.TIER_GATE_NOT_MET -> "Locked — spend more points in this tree"
        AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET,
        AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET,
        -> {
            val source = issue.relatedNodeIds.firstOrNull()
            if (source != null) {
                "Requires ${facts.nameOf(source)} first"
            } else {
                "Requires a prerequisite talent"
            }
        }

        else -> "Action not available"
    }
}

/**
 * The steady-state explanation of a **disabled** rank control, derived up
 * front from the same typed engine issues that disabled it — a correctly
 * disabled control can never be clicked to produce a denial, so the reason
 * must not depend on a denied call (B3 04 state 07: "+ is shown disabled and
 * the state line explains why"; UX/UI spec G: a blocked action is "always
 * paired with a reason").
 *
 * `null` when every offered control is enabled — B3 03/04 give actionable
 * nodes their plain state lines, and a false denial reason must never appear
 * next to a working control.
 *
 * Precedence mirrors what the inspector renders: a disabled `+` explains
 * itself first. A maxed node renders the non-interactive ✓ badge instead of a
 * spend button ("Max rank reached" is the badge's own accessibility label),
 * so its blocked `−` explains instead. Locked nodes offer `Show` / the tier
 * meter rather than rank controls and keep their own B3 05/06 reason lines,
 * which is why [TalentNodeActions.offersRankControls] gates the spend branch.
 */
fun TalentNodeActions.disabledActionExplanation(
    facts: TalentTreeNodeFacts,
    spentPoints: Int,
    pointBudget: Int,
): String? = when {
    offersRankControls && !isMaxed && !canSpend ->
        spendExplanation(facts, spentPoints, pointBudget)

    rank > 0 && !canRefund -> blockedActionReason(refundIssues, facts)
    else -> null
}

/**
 * B3 04 state 07 freezes the no-points wording — "No talent points left ·
 * 51/51 spent" — so the exhausted budget carries its live count; every other
 * spend denial keeps its [blockedActionReason] copy.
 */
private fun TalentNodeActions.spendExplanation(
    facts: TalentTreeNodeFacts,
    spentPoints: Int,
    pointBudget: Int,
): String {
    val code = spendIssues.firstOrNull()?.code
    return if (code == AllocationIssueCode.NO_POINTS_REMAINING ||
        code == AllocationIssueCode.POINT_BUDGET_EXCEEDED
    ) {
        "No talent points left · $spentPoints/$pointBudget spent"
    } else {
        blockedActionReason(spendIssues, facts)
    }
}
