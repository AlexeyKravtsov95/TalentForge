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
     * Zooms so that [targetScale] (clamped to the 1A v2 S3 0.62–1.6 bounds) is
     * applied around [focal] (screen dp): the world point under the focal
     * point stays under it. Non-finite targets are ignored, so a degenerate
     * pinch span can never poison the transform.
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

        /**
         * M5.UX.1 (1A v2 S3 "first open"): the camera of ONE section
         * when it is first opened — `s = clamp(Wvis / 354, 0.62, cap)` with
         * the layout-class cap (phone 1.0, landscape 0.75, tablet 1.1), so a
         * standard four-column section (3 pitches + one node = 292 dp at
         * s = 1, plus 31 dp each side) fills the visible width without
         * shrinking nodes below the S1 sizes.
         *
         * The section is horizontally centred when it fits the width (with
         * the [TalentTreeCanvasMetrics.CAMERA_FIELD_DP] field), otherwise
         * start-aligned at the screen margin; it is always top-aligned at
         * the margin, so a tall section scrolls down to its lower tiers
         * instead of shrinking. Start-alignment is what keeps the real
         * section visible when the dataset places a node far outside the
         * grid (the documented OFF_GRID_NODE duplicates of Hunter/Priest):
         * those nodes stay reachable by pan, never hidden, never moved.
         *
         * Deterministic and finite for any non-degenerate canvas; a canvas
         * without area falls back to scale 1 at the margin.
         */
        fun sectionFitFor(
            bounds: TalentTreeBounds,
            canvasSize: Size,
            layoutClass: TalentTreeLayoutClass,
        ): TalentTreeViewport {
            val margin = TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP
            val width = canvasSize.width
            if (!width.isFinite() || width <= 0f) {
                return TalentTreeViewport(1f, Offset(margin - bounds.minX, margin - bounds.minY))
            }
            val cap = sectionFitCap(layoutClass)
            val scale = (width / TalentTreeCanvasMetrics.SECTION_FIT_REFERENCE_WIDTH_DP)
                .coerceIn(TalentTreeCanvasMetrics.ZOOM_MIN, cap)
            val field = TalentTreeCanvasMetrics.CAMERA_FIELD_DP
            val contentWidth = bounds.width * scale
            val offsetX = if (contentWidth + 2f * field <= width) {
                (width - contentWidth) / 2f - bounds.minX * scale
            } else {
                margin - bounds.minX * scale
            }
            val offsetY = margin - bounds.minY * scale
            return TalentTreeViewport(scale, Offset(offsetX, offsetY))
        }

        /** 1A v2 S3: "cap: phone 1.0, landscape 0.75, tablet 1.1". */
        fun sectionFitCap(layoutClass: TalentTreeLayoutClass): Float = when (layoutClass) {
            TalentTreeLayoutClass.PORTRAIT -> TalentTreeCanvasMetrics.INITIAL_SCALE_PORTRAIT
            TalentTreeLayoutClass.LANDSCAPE -> TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE
            TalentTreeLayoutClass.TABLET -> TABLET_SECTION_FIT_CAP
        }

        /** 1A v2 S3: the tablet scale cap. */
        const val TABLET_SECTION_FIT_CAP: Float = 1.1f
    }

    /**
     * True when [other] is the same camera within
     * [TalentTreeCanvasMetrics.CAMERA_EPSILON_DP] — used to hide FIT when the
     * camera already is the fit (1A v2 S3).
     */
    fun isCloseTo(other: TalentTreeViewport): Boolean {
        val epsilon = TalentTreeCanvasMetrics.CAMERA_EPSILON_DP
        return abs(scale - other.scale) <= epsilon / 500f &&
            abs(offset.x - other.offset.x) <= epsilon &&
            abs(offset.y - other.offset.y) <= epsilon
    }

    /**
     * 1A v2 S3 bounds: the camera may not move [content] (world-dp bounds)
     * further than [TalentTreeCanvasMetrics.CAMERA_FIELD_DP] into the
     * visible area — the user can never lose the tree in empty canvas.
     *
     * Per axis: content that fits the visible extent (plus the field on both
     * sides) stays fully inside it, keeping the field; larger content may not
     * open a gap wider than the field at either edge. The vertical extent
     * excludes [coveredBottomDp] (the region an overlaid inspector covers),
     * so a bottom node can always be pulled above the inspector; likewise the
     * horizontal extent excludes [coveredLeftDp] / [coveredRightDp] (M5.UX.2b.1:
     * the landscape side panel overlaid on one side), so a node can always be
     * pulled out from under the panel.
     *
     * [reference] (the camera before this change) relaxes every bound to
     * where the reference already sat: a camera that is outside the bounds
     * (after the visible extent changed, or the initial margin) is never
     * pulled in with a jump and never pushed further out — it can only move
     * back towards them. Works across scale changes because the comparison is
     * made on screen-space edge gaps, not offsets.
     *
     * Only the offset changes; the scale is never touched and the result is
     * always finite. Degenerate canvases return `this`.
     */
    fun clampedTo(
        content: TalentTreeBounds,
        canvasSize: Size,
        coveredBottomDp: Float = 0f,
        reference: TalentTreeViewport? = null,
        coveredLeftDp: Float = 0f,
        coveredRightDp: Float = 0f,
    ): TalentTreeViewport {
        fun cover(value: Float) = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
        val covered = cover(coveredBottomDp)
        val left = cover(coveredLeftDp)
        val extentX = canvasSize.width - left - cover(coveredRightDp)
        val extentY = canvasSize.height - covered
        if (!extentX.isFinite() || !extentY.isFinite() || extentX <= 0f || extentY <= 0f) return this
        // the visible region starts `left` dp into the canvas: clamp in its own frame
        val x = left + clampAxis(
            offset = offset.x - left,
            min = content.minX,
            max = content.maxX,
            extent = extentX,
            referenceScale = reference?.scale,
            referenceOffset = reference?.offset?.x?.minus(left),
        )
        val y = clampAxis(
            offset = offset.y,
            min = content.minY,
            max = content.maxY,
            extent = extentY,
            referenceScale = reference?.scale,
            referenceOffset = reference?.offset?.y,
        )
        if (x == offset.x && y == offset.y) return this
        return TalentTreeViewport(scale, Offset(x, y))
    }

    private fun clampAxis(
        offset: Float,
        min: Float,
        max: Float,
        extent: Float,
        referenceScale: Float?,
        referenceOffset: Float?,
    ): Float {
        val field = TalentTreeCanvasMetrics.CAMERA_FIELD_DP
        // screen-space gaps of the reference camera (leading = content start
        // to the visible start, trailing = content end to the visible end)
        val referenceLead = if (referenceScale != null && referenceOffset != null) {
            min * referenceScale + referenceOffset
        } else {
            null
        }
        val referenceTrail = if (referenceScale != null && referenceOffset != null) {
            extent - (max * referenceScale + referenceOffset)
        } else {
            null
        }
        val size = (max - min) * scale
        val low: Float
        val high: Float
        if (size + 2f * field <= extent) {
            // fits: both gaps at least the field (or as small as they already were)
            val leadMin = minOf(field, referenceLead ?: field)
            val trailMin = minOf(field, referenceTrail ?: field)
            low = leadMin - min * scale
            high = extent - trailMin - max * scale
        } else {
            // larger: neither gap wider than the field (or than it already was)
            val leadMax = maxOf(field, referenceLead ?: field)
            val trailMax = maxOf(field, referenceTrail ?: field)
            low = extent - trailMax - max * scale
            high = leadMax - min * scale
        }
        if (!low.isFinite() || !high.isFinite()) return offset
        if (low > high) return (low + high) / 2f
        return offset.coerceIn(low, high)
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
 * Effective touch-target side in **screen dp** for [viewport] — 1A v2 S1:
 * `hit = min(pitch − 2, max(48, size + 8))` with `size = 52·s` and
 * `pitch = 80·s` on screen, where 48 is the layout's floor
 * ([minimumTargetDp]).
 *
 * The node's own visual plus 4 dp on every side, never below the floor so
 * zooming out keeps a usable target — but never closer than 2 dp to the next
 * grid position, so the targets of two neighbouring talents can never
 * overlap at any zoom (the cap wins over the floor; at the 0.62 minimum zoom
 * the pitch is 49.6 dp and the target 47.6 dp). Node *visuals* are untouched
 * by this — only the interaction target is defined here.
 */
fun effectiveNodeTargetDp(viewport: TalentTreeViewport, minimumTargetDp: Float): Float {
    val size = TalentTreeCanvasMetrics.NODE_VISUAL_DP * viewport.scale
    val pitch = TalentTreeCanvasMetrics.GRID_STEP_DP * viewport.scale
    return minOf(
        pitch - TalentTreeCanvasMetrics.NODE_TARGET_PITCH_GAP_DP,
        maxOf(minimumTargetDp, size + TalentTreeCanvasMetrics.NODE_TARGET_PADDING_DP),
    )
}

/**
 * Screen-space floor of the usable touch target for the layout class: the
 * 1A v2 S1 48 dp on phones (portrait and landscape); the UX/UI spec 13 68 dp
 * tablet target is kept unchanged (M5.UX.2 owns the tablet redesign).
 */
fun minimumNodeTargetDp(layoutClass: TalentTreeLayoutClass): Float = when (layoutClass) {
    TalentTreeLayoutClass.PORTRAIT,
    TalentTreeLayoutClass.LANDSCAPE,
    -> TalentTreeCanvasMetrics.NODE_TARGET_MIN_DP
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
