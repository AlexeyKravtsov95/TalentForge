package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipGapClassification
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipRankStatus
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipSegmentKind

/**
 * Pure, UI-toolkit-free description block of the talent inspector (M5.V2),
 * built only from verified `talent-tooltips.v1` ranks — never from the raw
 * `descriptionTemplate`, so template markup can never reach the screen.
 *
 * Layout follows B3 04/08 and UX/UI spec H: the description of the current
 * rank, then a CURRENT / NEXT RANK comparison of the value that changes.
 *  - rank 0: the rank-1 description is shown as the prospective effect; the
 *    comparison reads CURRENT "—" / NEXT RANK "<rank-1 value>" (UX/UI spec H,
 *    "Deflection 0/5 … — · 1%");
 *  - partial rank: current description, CURRENT / NEXT RANK of the one value
 *    that changes; when several values change, the next rank's full text with
 *    the changed values marked (B3 freezes the two-column layout for a single
 *    value only — a column of unlabelled numbers would not be readable);
 *  - max rank: no next-rank section.
 */
data class TalentInspectorDescription(
    val body: DescriptionBody,
    /** The body shows rank 1 of an unspent talent (what the first point would give). */
    val prospective: Boolean,
    val comparison: RankComparison?,
)

/** The description text of one rank, or the neutral unavailable line. */
sealed interface DescriptionBody {
    data class Text(
        val spans: List<DescriptionSpan>,
        /** Explains the `—` marks of a partial description; `null` when there are none. */
        val missingNote: String?,
    ) : DescriptionBody {
        /** Plain rendering (for accessibility and tests). */
        val plainText: String get() = spans.joinToString("") { it.text }
    }

    data object Unavailable : DescriptionBody {
        const val TEXT: String = "Talent description unavailable for this client data."
    }
}

sealed interface DescriptionSpan {
    val text: String

    data class Plain(override val text: String) : DescriptionSpan

    /** An evaluated value; [changed] marks a value that differs from the compared rank. */
    data class Value(override val text: String, val changed: Boolean = false) : DescriptionSpan

    /** A construct that could not be evaluated honestly. */
    data object Missing : DescriptionSpan {
        override val text: String = MISSING_MARK
    }
}

sealed interface RankComparison {
    /** CURRENT / NEXT RANK of the value that changes; `current == null` renders as "—". */
    data class Value(val current: String?, val next: String) : RankComparison

    /** The next rank's full description (several values change), changed values marked. */
    data class NextRankText(val body: DescriptionBody.Text) : RankComparison
}

/** Visible stand-in for a value that is not shown. */
const val MISSING_MARK: String = "—"

const val MISSING_NOTE_CHARACTER: String =
    "Values marked $MISSING_MARK depend on your character and are not shown."

const val MISSING_NOTE_DATA: String =
    "Values marked $MISSING_MARK are not available in this client data."

/**
 * The description block of one selected node at its current [rank].
 * `null` when no descriptions were provided at all ([TalentNodeDescriptions.None]).
 */
fun buildTalentInspectorDescription(
    descriptions: TalentNodeDescriptions,
    nodeId: Int,
    rank: Int,
    maxRank: Int,
): TalentInspectorDescription? {
    val ranks = when (descriptions) {
        TalentNodeDescriptions.None -> return null
        TalentNodeDescriptions.Unavailable -> null
        is TalentNodeDescriptions.Loaded -> descriptions.ranksOf(nodeId)
    }
    if (ranks.isNullOrEmpty() || ranks.size != maxRank) {
        return TalentInspectorDescription(DescriptionBody.Unavailable, prospective = rank == 0, comparison = null)
    }
    val shownRank = rank.coerceIn(1, maxRank)
    val shown = ranks[shownRank - 1]
    val comparison = when {
        rank >= maxRank -> null
        rank == 0 -> prospectiveComparison(ranks)
        else -> nextRankComparison(shown, ranks[rank])
    }
    return TalentInspectorDescription(
        body = bodyOf(shown, changedValues = emptySet()),
        prospective = rank == 0,
        comparison = comparison,
    )
}

private fun bodyOf(rank: TalentTooltipRank, changedValues: Set<Int>): DescriptionBody {
    if (rank.status == TooltipRankStatus.UNRESOLVED || rank.segments.isEmpty()) {
        return DescriptionBody.Unavailable
    }
    val spans = rank.segments.mapIndexed { index, segment ->
        when (segment.kind) {
            TooltipSegmentKind.TEXT -> DescriptionSpan.Plain(segment.text)
            TooltipSegmentKind.VALUE -> DescriptionSpan.Value(segment.text, changed = index in changedValues)
            TooltipSegmentKind.GAP -> DescriptionSpan.Missing
        }
    }
    val note = when {
        rank.gaps.isEmpty() -> null
        rank.gaps.all { it.classification == TooltipGapClassification.CONTEXT_DEPENDENT } -> MISSING_NOTE_CHARACTER
        else -> MISSING_NOTE_DATA
    }
    return DescriptionBody.Text(spans, note)
}

/**
 * Indices of the value segments that differ between two ranks, or `null` when
 * the two descriptions do not share one structure (then values cannot be
 * paired honestly).
 */
private fun changedValueIndices(a: TalentTooltipRank, b: TalentTooltipRank): List<Int>? {
    if (a.status == TooltipRankStatus.UNRESOLVED || b.status == TooltipRankStatus.UNRESOLVED) return null
    if (a.segments.size != b.segments.size) return null
    val changed = ArrayList<Int>()
    for (index in a.segments.indices) {
        val left = a.segments[index]
        val right = b.segments[index]
        if (left.kind != right.kind) return null
        when (left.kind) {
            TooltipSegmentKind.TEXT -> if (left.text != right.text) return null
            TooltipSegmentKind.VALUE -> if (left.text != right.text) changed += index
            TooltipSegmentKind.GAP -> Unit
        }
    }
    return changed
}

private fun nextRankComparison(current: TalentTooltipRank, next: TalentTooltipRank): RankComparison? {
    val changed = changedValueIndices(current, next)
    val nextBody = bodyOf(next, changedValues = changed?.toSet().orEmpty())
    return when {
        nextBody !is DescriptionBody.Text -> null
        changed == null -> RankComparison.NextRankText(nextBody)
        changed.isEmpty() -> null
        changed.size == 1 -> RankComparison.Value(
            current = current.segments[changed.single()].text,
            next = next.segments[changed.single()].text,
        )

        else -> RankComparison.NextRankText(nextBody)
    }
}

/** Rank 0 → rank 1: "—" / the one rank-dependent value of rank 1 (when exactly one varies). */
private fun prospectiveComparison(ranks: List<TalentTooltipRank>): RankComparison? {
    if (ranks.size < 2) return null
    val varying = changedValueIndices(ranks[0], ranks[1])?.singleOrNull() ?: return null
    return RankComparison.Value(current = null, next = ranks[0].segments[varying].text)
}
