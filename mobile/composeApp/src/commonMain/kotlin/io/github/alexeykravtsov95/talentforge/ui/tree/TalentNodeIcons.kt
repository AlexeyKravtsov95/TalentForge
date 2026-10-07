package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decoded talent icons of one opened tree, by node id — a presentation input
 * next to the geometry-only [TalentTreeUiModel], never part of it. Built once
 * when the editor session opens (see `TalentIconsLoader`), so composition and
 * drawing only ever look an already decoded bitmap up.
 *
 * A node that is absent here renders the monogram tile: that is the documented
 * fallback for a missing, unreadable or undecodable icon, and the default for
 * callers that have no visuals at all ([None]).
 */
@Immutable
data class TalentNodeIcons(private val byNodeId: Map<Int, ImageBitmap>) {

    fun iconOf(nodeId: Int): ImageBitmap? = byNodeId[nodeId]

    /** Ids of the nodes that have a real icon. */
    val nodeIds: Set<Int> get() = byNodeId.keys

    companion object {
        val None: TalentNodeIcons = TalentNodeIcons(emptyMap())
    }
}
