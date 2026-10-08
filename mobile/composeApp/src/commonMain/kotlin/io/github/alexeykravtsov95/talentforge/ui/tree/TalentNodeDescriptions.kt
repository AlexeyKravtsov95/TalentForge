package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.runtime.Immutable
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank

/**
 * The verified talent descriptions of one opened tree, by node id (M5.V2) — a
 * presentation input next to [TalentTreeUiModel] and [TalentNodeIcons], never
 * part of allocation. Built once when the editor session opens (see
 * `TalentDescriptionsLoader`); composition only looks ranks up.
 */
@Immutable
sealed interface TalentNodeDescriptions {
    /** No descriptions were provided (lower-level callers and tests): no description section. */
    data object None : TalentNodeDescriptions

    /**
     * The class's `talent-tooltips.v1` could not be loaded or verified: every
     * inspector shows the neutral "unavailable" line, nothing is guessed.
     */
    data object Unavailable : TalentNodeDescriptions

    /**
     * Ranks `1..maxRank` by node id. A node is absent when it has no single
     * entry (the dataset defines no representative entry of a multi-entry
     * node), which the inspector shows as unavailable.
     */
    data class Loaded(private val byNodeId: Map<Int, List<TalentTooltipRank>>) : TalentNodeDescriptions {
        fun ranksOf(nodeId: Int): List<TalentTooltipRank>? = byNodeId[nodeId]

        val nodeIds: Set<Int> get() = byNodeId.keys
    }
}
