package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * The single world→screen transform of the Talent Tree canvas.
 *
 * One coherent model drives nodes, connectors, hit testing, pan and zoom:
 * `screenDp = worldDp * scale + offset`. All values are dp at the current
 * density (pointer events are converted to dp at the gesture boundary), so the
 * math here is density-free and unit-testable without any rendering.
 *
 * [scale] is dimensionless and always finite; [offset] is the world-origin
 * position in screen dp. The canvas never sees a partial or non-finite
 * transform: construction rejects non-finite values and every operation either
 * returns `this` unchanged or a new valid instance.
 */
data class TalentTreeViewport(
    val scale: Float,
    val offset: Offset,
) {
    init {
        require(scale.isFinite() && scale > 0f) {
            "viewport scale must be finite and positive, was $scale"
        }
        require(offset.x.isFinite() && offset.y.isFinite()) {
            "viewport offset must be finite, was $offset"
        }
    }

    fun worldToScreen(world: Offset): Offset =
        Offset(world.x * scale + offset.x, world.y * scale + offset.y)

    fun screenToWorld(screen: Offset): Offset =
        Offset((screen.x - offset.x) / scale, (screen.y - offset.y) / scale)

    /** Pans by [delta] screen dp; non-finite input leaves the viewport unchanged. */
    fun panBy(delta: Offset): TalentTreeViewport {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return this
        return copy(offset = Offset(offset.x + delta.x, offset.y + delta.y))
    }

    /**
     * Zooms so that [targetScale] (clamped to the B3 0.8–2.2 bounds) is applied
     * around [focal] (screen dp): the world point under the focal point stays
     * under it. Non-finite targets are ignored, so a degenerate pinch span can
     * never poison the transform.
     */
    fun zoomAt(focal: Offset, targetScale: Float): TalentTreeViewport {
        if (!targetScale.isFinite()) return this
        val newScale = targetScale.coerceIn(
            TalentTreeCanvasMetrics.ZOOM_MIN,
            TalentTreeCanvasMetrics.ZOOM_MAX,
        )
        if (newScale == scale) return this
        val world = screenToWorld(focal)
        return TalentTreeViewport(
            scale = newScale,
            offset = Offset(focal.x - world.x * newScale, focal.y - world.y * newScale),
        )
    }

    companion object {
        /**
         * Deterministic initial transform for [bounds] inside a canvas of
         * [canvasSize] (dp): the tree's left edge starts at the screen margin,
         * and the content is vertically centred when it fits (otherwise
         * top-aligned at the margin). Portrait starts at 1×; landscape starts at
         * the B3 landscape grid ratio (see
         * [TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE]).
         */
        fun initialFor(
            bounds: TalentTreeBounds,
            canvasSize: Size,
            landscape: Boolean,
        ): TalentTreeViewport {
            val scale = if (landscape) {
                TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE
            } else {
                TalentTreeCanvasMetrics.INITIAL_SCALE_PORTRAIT
            }
            val margin = TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP
            val offsetX = margin - bounds.minX * scale
            val contentHeight = bounds.height * scale
            val offsetY = if (contentHeight <= canvasSize.height) {
                canvasSize.height / 2f - bounds.centerY * scale
            } else {
                margin - bounds.minY * scale
            }
            return TalentTreeViewport(scale, Offset(offsetX, offsetY))
        }
    }
}

/**
 * Hit-tests a world position against the node touch targets (B3: 56 × 56 dp
 * centred on the node, non-overlapping on the grid). Returns the closest node
 * id whose target contains [world], or null for empty canvas.
 */
fun TalentTreeUiModel.nodeAt(world: Offset): Int? {
    if (!world.x.isFinite() || !world.y.isFinite()) return null
    val halfTarget = TalentTreeCanvasMetrics.NODE_TARGET_DP / 2f
    var bestId: Int? = null
    var bestDistance = Float.MAX_VALUE
    for (node in nodes) {
        val dx = kotlin.math.abs(world.x - node.centerX)
        val dy = kotlin.math.abs(world.y - node.centerY)
        if (dx <= halfTarget && dy <= halfTarget) {
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestId = node.id
            }
        }
    }
    return bestId
}

/** Screen-dp convenience variant of [nodeAt]. */
fun TalentTreeUiModel.nodeAtScreen(screen: Offset, viewport: TalentTreeViewport): Int? =
    nodeAt(viewport.screenToWorld(screen))
