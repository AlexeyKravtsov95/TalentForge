package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

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
         * [TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE]); tablet starts at
         * the UX/UI spec 13 node ratio (56 dp nodes — see
         * [TalentInspectorMetrics.INITIAL_SCALE_TABLET]).
         */
        fun initialFor(
            bounds: TalentTreeBounds,
            canvasSize: Size,
            landscape: Boolean,
        ): TalentTreeViewport = initialFor(bounds, canvasSize,
            if (landscape) TalentTreeLayoutClass.LANDSCAPE else TalentTreeLayoutClass.PORTRAIT)

        /** Layout-class-aware variant of [initialFor] (M4.2). */
        fun initialFor(
            bounds: TalentTreeBounds,
            canvasSize: Size,
            layoutClass: TalentTreeLayoutClass,
        ): TalentTreeViewport {
            val scale = when (layoutClass) {
                TalentTreeLayoutClass.PORTRAIT -> TalentTreeCanvasMetrics.INITIAL_SCALE_PORTRAIT
                TalentTreeLayoutClass.LANDSCAPE -> TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE
                TalentTreeLayoutClass.TABLET -> TalentInspectorMetrics.INITIAL_SCALE_TABLET
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

    /**
     * B3 07 (prerequisite navigation): the transform that makes [world] fully
     * visible inside [visibleSize] — the canvas area **not** covered by the
     * inspector. Returns `this` unchanged when the node is already fully
     * visible ("The canvas stays still if the target is fully visible above
     * the inspector"); otherwise the node is centred in the visible region
     * with the scale untouched ("otherwise the target is centred").
     *
     * Pure: the result is always finite (constructor-checked) and normal
     * pan/zoom continues from it.
     */
    fun focusedOn(world: Offset, visibleSize: Size): TalentTreeViewport {
        if (!world.x.isFinite() || !world.y.isFinite()) return this
        if (visibleSize.width.isFinite() && visibleSize.height.isFinite() &&
            (visibleSize.width <= 0f || visibleSize.height <= 0f)
        ) {
            return this
        }
        val clearance = TalentInspectorMetrics.FOCUS_CLEARANCE_DP
        val screen = worldToScreen(world)
        val fullyVisible =
            screen.x >= clearance &&
                screen.x <= visibleSize.width - clearance &&
                screen.y >= clearance &&
                screen.y <= visibleSize.height - clearance
        if (fullyVisible) return this
        return TalentTreeViewport(
            scale = scale,
            offset = Offset(
                visibleSize.width / 2f - world.x * scale,
                visibleSize.height / 2f - world.y * scale,
            ),
        )
    }

    /**
     * B3 08 (inspector layout): "pans the canvas the minimum amount needed,
     * and only when the node would be covered" — the smallest offset change
     * that brings [world] fully inside [visibleSize] with the Show clearance
     * (each axis moves only when it is out of bounds, unlike the centring
     * [focusedOn] used by Show). Returns `this` unchanged when already fully
     * visible; the scale is never touched and the result is finite.
     */
    fun panToReveal(world: Offset, visibleSize: Size): TalentTreeViewport {
        if (!world.x.isFinite() || !world.y.isFinite()) return this
        if (!visibleSize.width.isFinite() || !visibleSize.height.isFinite() ||
            visibleSize.width <= 0f || visibleSize.height <= 0f
        ) {
            return this
        }
        val clearance = TalentInspectorMetrics.FOCUS_CLEARANCE_DP
        // A region smaller than twice the clearance cannot satisfy both
        // bounds; clamp so the target ends up on the (clamped) nearer bound.
        val maxVisibleX = maxOf(clearance, visibleSize.width - clearance)
        val maxVisibleY = maxOf(clearance, visibleSize.height - clearance)
        val screen = worldToScreen(world)
        val dx = when {
            screen.x < clearance -> clearance - screen.x
            screen.x > maxVisibleX -> maxVisibleX - screen.x
            else -> 0f
        }
        val dy = when {
            screen.y < clearance -> clearance - screen.y
            screen.y > maxVisibleY -> maxVisibleY - screen.y
            else -> 0f
        }
        if (dx == 0f && dy == 0f) return this
        return TalentTreeViewport(scale = scale, offset = Offset(offset.x + dx, offset.y + dy))
    }
}

/**
 * Effective touch-target side in **screen dp** for [viewport], never smaller
 * than the orientation's B3 minimum ([minimumTargetDp]).
 *
 * Hit testing happens in screen space: the world-space 56 dp target scales
 * with zoom-in (it may grow), but zooming out must not shrink the physically
 * tappable area below the applicable minimum (56 dp portrait / 48 dp
 * landscape, B3 05–06; 68 dp tablet, UX/UI spec 13). Node *visuals* are
 * untouched by this — only the interaction target is guaranteed.
 */
fun effectiveNodeTargetDp(viewport: TalentTreeViewport, minimumTargetDp: Float): Float =
    maxOf(TalentTreeCanvasMetrics.NODE_TARGET_DP * viewport.scale, minimumTargetDp)

/** Screen-space floor of the usable touch target for the layout class. */
fun minimumNodeTargetDp(layoutClass: TalentTreeLayoutClass): Float = when (layoutClass) {
    TalentTreeLayoutClass.PORTRAIT -> TalentTreeCanvasMetrics.NODE_TARGET_MIN_PORTRAIT_DP
    TalentTreeLayoutClass.LANDSCAPE -> TalentTreeCanvasMetrics.NODE_TARGET_MIN_LANDSCAPE_DP
    TalentTreeLayoutClass.TABLET -> TalentInspectorMetrics.TABLET_NODE_TARGET_DP
}

/**
 * Hit-tests a screen position (dp) against the node touch targets in screen
 * space, using [effectiveNodeTargetDp] with the orientation's minimum. The
 * closest node whose effective target contains [screen] wins (deterministic);
 * null means empty canvas.
 */
fun TalentTreeUiModel.nodeAtScreen(
    screen: Offset,
    viewport: TalentTreeViewport,
    minimumTargetDp: Float,
): Int? {
    if (!screen.x.isFinite() || !screen.y.isFinite()) return null
    val halfTarget = effectiveNodeTargetDp(viewport, minimumTargetDp) / 2f
    var bestId: Int? = null
    var bestDistance = Float.MAX_VALUE
    for (node in nodes) {
        val nodeScreen = viewport.worldToScreen(Offset(node.centerX, node.centerY))
        val dx = abs(screen.x - nodeScreen.x)
        val dy = abs(screen.y - nodeScreen.y)
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
