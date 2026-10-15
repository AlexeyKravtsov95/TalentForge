package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.description_missing_character
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.description_missing_data
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_blocked_needed_by
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_blocked_needed_by_allocated
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_blocked_no_points
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_blocked_no_rank_to_refund
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_blocked_requires
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_blocked_requires_unnamed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_blocked_tier
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_blocked_unavailable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_max_rank_reached
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_needed_for
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_requires_prerequisite
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_requires_prerequisites
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_spend_more_points
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_spend_more_points_unspecified
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_state_available
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_state_in_progress
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_state_maxed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_state_no_points
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.inspector_state_unavailable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_fallback_name
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_select_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_state_available
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_state_in_progress
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_state_maxed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_state_no_points
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_state_prerequisite_locked
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_state_tier_locked
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.talent_state_unavailable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * M5.REL.2b: the talent editor's generated copy as structured presentation
 * descriptors, resolved to English or Russian only at the composable
 * boundary (`text()`). The pure projections (buildTalentInspectorContent,
 * blockedActionReason, disabledActionExplanation) return these instead of
 * pre-formatted English, so:
 *
 * - each message keeps its identity, the engine's typed AllocationIssueCode
 *   where one produced it, its numeric parameters and the node ids it names;
 * - the allocation engine, BuildState and persistence never see UI copy, and
 *   the same allocation state yields the same descriptor in every language;
 * - counts go through plural resources and names through positional
 *   arguments, so Russian word order and plural forms belong to the resource.
 *
 * Talent names are the client's own text (TalentName.published): the dataset's
 * English name, or — in Russian, since M5.REL.3b.2 — the verified overlay name
 * where the client translates it. Nothing is translated here.
 */

/**
 * A talent's display name: the client's own published name — the dataset's
 * English name, or its verified Russian overlay name in Russian (M5.REL.3b.2)
 * — or, only when there is none, the localized `Talent <id>` / `Талант <id>`
 * fallback. Never invented, never persisted.
 */
data class TalentName(
    val nodeId: Int,
    /** The non-blank display name, or `null` when the client publishes none. */
    val published: String?,
)

/** The editor's generated inspector copy: state lines, lock lines and blocked-action reasons. */
sealed interface InspectorText {

    // ----------------------------------------------------------- state lines (B3 03/04)

    data object Available : InspectorText

    data object InProgress : InspectorText

    data object Maxed : InspectorText

    /** Fail-closed unsupported/unresolved node. */
    data object Unavailable : InspectorText

    /** B3 04 state 07: "No talent points left · 51/51 spent". */
    data class NoPointsLeft(val spentPoints: Int, val pointBudget: Int) : InspectorText

    /** B3 05: after Show, the target's state line reads "<state> · needed for <origin>". */
    data class NeededFor(val state: InspectorText, val origin: TalentName) : InspectorText

    // ----------------------------------------------------------- lock lines (B3 04/05)

    /** "Requires <prerequisite> r/r" — one unmet prerequisite source and its real max rank. */
    data class RequiresPrerequisite(val source: TalentName, val requiredRank: Int) : InspectorText

    /** Several unmet prerequisite sources: their count, no invented priority between them. */
    data class RequiresPrerequisites(val count: Int) : InspectorText

    /** "Spend <deficit> more points in this tree" from the gate's real values. */
    data class SpendMorePoints(val deficit: Int) : InspectorText

    /** Several unmet tier gates: no single deficit is honest to show. */
    data object SpendMorePointsInTree : InspectorText

    // ----------------------------------------------------------- blocked actions (B3 07)

    /**
     * The reason of a denied or disabled rank action, from the engine's first
     * typed issue [code] (`null` only when the engine reported none).
     */
    sealed interface Blocked : InspectorText {
        val code: AllocationIssueCode?
    }

    /** NOT_ALLOCATED. */
    data class NoRankToRefund(override val code: AllocationIssueCode) : Blocked

    /** MAX_RANK_REACHED. */
    data class MaxRankReached(override val code: AllocationIssueCode) : Blocked

    /** NO_POINTS_REMAINING / POINT_BUDGET_EXCEEDED. */
    data class NoTalentPoints(override val code: AllocationIssueCode) : Blocked

    /** DEPENDENT_NODE_WOULD_BREAK; [dependent] is `null` when the engine named no node. */
    data class NeededBy(override val code: AllocationIssueCode, val dependent: TalentName?) : Blocked

    /** TIER_GATE_NOT_MET. */
    data class TierLocked(override val code: AllocationIssueCode) : Blocked

    /** REQUIRED_/SUFFICIENT_PREREQUISITE_NOT_MET; [source] is `null` when the engine named no node. */
    data class RequiresFirst(override val code: AllocationIssueCode, val source: TalentName?) : Blocked

    /** Any other code (or none): the action is simply not available. */
    data class ActionNotAvailable(override val code: AllocationIssueCode?) : Blocked
}

/** Why some values of a talent description show the [MISSING_MARK]. */
enum class MissingValuesNote {
    /** Every gap depends on the player's character. */
    CHARACTER_DEPENDENT,

    /** At least one gap is not published in this client data. */
    NOT_IN_CLIENT_DATA,
}

// ------------------------------------------------------------------ resolution (presentation boundary)

/** The localized display text of this name: the published name verbatim, else the localized fallback. */
@Composable
fun TalentName.text(): String = published ?: stringResource(Res.string.talent_fallback_name, nodeId)

/** The localized text of this descriptor in the current UI language. */
@Composable
fun InspectorText.text(): String = when (this) {
    InspectorText.Available -> stringResource(Res.string.inspector_state_available)
    InspectorText.InProgress -> stringResource(Res.string.inspector_state_in_progress)
    InspectorText.Maxed -> stringResource(Res.string.inspector_state_maxed)
    InspectorText.Unavailable -> stringResource(Res.string.inspector_state_unavailable)
    is InspectorText.NoPointsLeft -> stringResource(Res.string.inspector_state_no_points, spentPoints, pointBudget)
    is InspectorText.NeededFor -> stringResource(Res.string.inspector_needed_for, state.text(), origin.text())
    is InspectorText.RequiresPrerequisite ->
        stringResource(Res.string.inspector_requires_prerequisite, source.text(), requiredRank)

    is InspectorText.RequiresPrerequisites ->
        pluralStringResource(Res.plurals.inspector_requires_prerequisites, count, count)

    is InspectorText.SpendMorePoints -> pluralStringResource(Res.plurals.inspector_spend_more_points, deficit, deficit)
    InspectorText.SpendMorePointsInTree -> stringResource(Res.string.inspector_spend_more_points_unspecified)
    is InspectorText.NoRankToRefund -> stringResource(Res.string.inspector_blocked_no_rank_to_refund)
    is InspectorText.MaxRankReached -> stringResource(Res.string.inspector_max_rank_reached)
    is InspectorText.NoTalentPoints -> stringResource(Res.string.inspector_blocked_no_points)
    is InspectorText.NeededBy -> {
        val name = dependent
        if (name == null) {
            stringResource(Res.string.inspector_blocked_needed_by_allocated)
        } else {
            stringResource(Res.string.inspector_blocked_needed_by, name.text())
        }
    }

    is InspectorText.TierLocked -> stringResource(Res.string.inspector_blocked_tier)
    is InspectorText.RequiresFirst -> {
        val name = source
        if (name == null) {
            stringResource(Res.string.inspector_blocked_requires_unnamed)
        } else {
            stringResource(Res.string.inspector_blocked_requires, name.text())
        }
    }

    is InspectorText.ActionNotAvailable -> stringResource(Res.string.inspector_blocked_unavailable)
}

/** The localized explanation of the description's missing-value marks. */
@Composable
fun MissingValuesNote.text(): String = when (this) {
    MissingValuesNote.CHARACTER_DEPENDENT -> stringResource(Res.string.description_missing_character)
    MissingValuesNote.NOT_IN_CLIENT_DATA -> stringResource(Res.string.description_missing_data)
}

/**
 * The canvas nodes' accessibility copy, resolved ONCE per node layer and
 * handed to every node, so a node stays a skippable scope (PR #19 round 7)
 * and no node resolves resources itself. Equal copies compare equal.
 */
@Immutable
data class TalentNodeAccessibilityCopy(
    private val stateLabels: Map<TalentNodeState, String>,
    /** The node's click action label ("select talent"). */
    val selectAction: String,
) {
    /** Spoken form of a node's rank + state ("2/5, in progress" / "2/5, частично изучен"). */
    fun stateDescriptionOf(node: TalentNodePresentation): String =
        "${node.rankText}, ${stateLabels.getValue(node.state)}"
}

/** The [TalentNodeAccessibilityCopy] of the current UI language. */
@Composable
fun talentNodeAccessibilityCopy(): TalentNodeAccessibilityCopy {
    val available = stringResource(Res.string.talent_state_available)
    val inProgress = stringResource(Res.string.talent_state_in_progress)
    val maxed = stringResource(Res.string.talent_state_maxed)
    val prerequisiteLocked = stringResource(Res.string.talent_state_prerequisite_locked)
    val tierLocked = stringResource(Res.string.talent_state_tier_locked)
    val noPoints = stringResource(Res.string.talent_state_no_points)
    val unavailable = stringResource(Res.string.talent_state_unavailable)
    val selectAction = stringResource(Res.string.talent_select_action)
    return TalentNodeAccessibilityCopy(
        stateLabels = mapOf(
            TalentNodeState.AVAILABLE to available,
            TalentNodeState.PARTIAL to inProgress,
            TalentNodeState.MAXED to maxed,
            TalentNodeState.PREREQUISITE_LOCKED to prerequisiteLocked,
            TalentNodeState.TIER_LOCKED to tierLocked,
            TalentNodeState.NO_POINTS to noPoints,
            TalentNodeState.UNAVAILABLE to unavailable,
        ),
        selectAction = selectAction,
    )
}
