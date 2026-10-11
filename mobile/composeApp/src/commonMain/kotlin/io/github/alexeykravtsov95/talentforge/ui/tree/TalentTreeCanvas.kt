package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The read-only Talent Tree canvas (M4.1 gestures/geometry + the M4.2
 * allocation-state presentation).
 *
 * One coherent world/canvas coordinate system (see [TalentTreeViewport]) drives
 * connectors, nodes, hit testing, pan, zoom and selection. Connectors render
 * behind nodes inside the same transform; nodes are real composables (stable
 * key = node id) so the real names stay available to accessibility semantics.
 *
 * Interaction contract (B3, frozen):
 * - tap a node selects it — never spends anything (there is no spend API here);
 * - tapping the same node keeps it selected;
 * - tapping empty canvas deselects;
 * - dragging pans — including drags that start on a node, once movement passes
 *   the 7 dp touch slop (no node-origin pan dead zones);
 * - pinching zooms 0.62–1.6× around the fingers (1A v2 S3);
 * - gestures are clamped to the content (12 dp field, 1A v2 S3) and
 *   interrupt any running camera animation (1A v2 S4).
 *
 * M4.2 presentation (read-only, engine-backed via [presentation]):
 * - prerequisite connectors draw "2 dp solid when the prerequisite is met,
 *   2 dp dashed when inactive" (B3 06) — active = the edge's source node is
 *   fully ranked (the EDGE-ACTIVE reference rule, applied here as a *visual*
 *   treatment of published rank facts only);
 * - visual-only edges carry no requirement, so they stay neutral solid;
 * - lines fade to 30% with a selection (B3) and to 18% while a Show guide is
 *   active, except the origin→target path, which turns bright at 3 dp (B3 07);
 * - nodes layer the B3 state treatment under the neutral selection ring;
 * - [showGuide]'s target pulses once (900 ms) and its origin keeps a dashed
 *   ring (see [TalentNodeVisual]);
 * - M5.V1: a node with a real icon in [icons] renders it inside the same
 *   48 dp rounded square, under the unchanged state/selection layers; geometry,
 *   hit targets and gestures do not depend on icons at all;
 * - a [TalentTreeViewportState.requestFocus] is animated as the B3 300 ms
 *   ease-out Show pan (scale untouched, transform always finite);
 * - M5.UX.1: a [TalentTreeViewportState.requestFit] is animated back to the
 *   fit camera (1A v2 S3 FIT, 280 ms ease-out). [model] may be the whole tree
 *   or one section's view ([TalentTreeSectionUiModel.model]) — the canvas is
 *   the same either way.
 */
@Composable
fun TalentTreeCanvas(
    model: TalentTreeUiModel,
    presentation: TalentTreePresentation,
    viewportState: TalentTreeViewportState,
    layoutClass: TalentTreeLayoutClass,
    accent: androidx.compose.ui.graphics.Color,
    selectedNodeId: Int?,
    showGuide: TalentShowGuide?,
    guideToken: Int,
    purchaseNodeId: Int? = null,
    purchaseToken: Int = 0,
    icons: TalentNodeIcons = TalentNodeIcons.None,
    onNodeSelected: (Int) -> Unit,
    onSelectionCleared: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * PR #19: a confirmed single-finger section swipe — at FIT a horizontal
     * swipe anywhere in the canvas ([talentSectionFitSwipeDirection]), when
     * zoomed an overscroll past the content's horizontal bound
     * ([talentSectionSwipeDirection]) — `+1` next section, `-1` previous
     * ([sectionSwipeDirectionOf]), `0` none — with the drag the release left
     * (dp), so the pages can finish (or ease back) from where the finger let
     * go, and the finger's horizontal velocity there (dp/s), so the motion continues
     * without a break in speed. Called for every release that moved the pages, including `0`.
     * `null` (landscape/tablet whole tree) disables it.
     */
    onSectionSwipe: ((direction: Int, releaseDragDp: Float, releaseVelocityDpPerSecond: Float) -> Unit)? = null,
    /**
     * PR #19 motion: `false` for a page that is only shown (the section
     * leaving, or the neighbour peeking in): no pointer input and no node
     * semantics, so only ONE canvas is ever interactive.
     */
    interactive: Boolean = true,
    /** The page's horizontal offset (px), applied after the pointer input (`null`: the rubber band only). */
    pageOffsetPx: (() -> Float)? = null,
    /** Test tag of the moving page layer (its bounds follow [pageOffsetPx]). */
    pageTag: String? = null,
    /**
     * PR #19 round 7: how many of [model]'s nodes to compose, in model order. A page being
     * composed ahead off screen grows a few nodes per frame instead of all at once; every
     * page on screen composes all of them (the default).
     */
    composedNodeCount: Int = Int.MAX_VALUE,
) {
    val localDensity = LocalDensity.current
    val sectionSwipe by rememberUpdatedState(onSectionSwipe)
    // Android owns edge swipes (system Back): a swipe that STARTS in the
    // system gesture zone never switches sections (0 on desktop/iOS hosts).
    val gestureInsets = WindowInsets.systemGestures
    val layoutDirection = LocalLayoutDirection.current
    val rtl by rememberUpdatedState(layoutDirection == LayoutDirection.Rtl)
    val windowFrame = remember { CanvasWindowFrame() }
    val inSystemGestureZone: (Offset) -> Boolean = { local ->
        val x = windowFrame.leftPx + local.x
        x < gestureInsets.getLeft(localDensity, layoutDirection) ||
            x > windowFrame.rootWidthPx - gestureInsets.getRight(localDensity, layoutDirection)
    }
    val colors = talentForgeColors()

    LaunchedEffect(model, viewportState.canvasSize, layoutClass) {
        viewportState.layoutClass = layoutClass
        viewportState.initializeOnce(model.bounds)
    }

    // B3 07: "otherwise the target is centred (300 ms ease-out)"; B3 08 reveal
    // requests pan the minimum amount needed. Both keep the scale and always
    // produce a finite transform. Keyed on initialization too: a request made
    // for a camera that is not laid out yet (a cross-section Show) runs as
    // soon as the camera exists. A gesture stops the animation (1A v2 S4).
    // derived: the canvas recomposes when the camera becomes available, never per pan frame
    val viewportReady by remember(viewportState) { derivedStateOf { viewportState.viewport != null } }
    LaunchedEffect(viewportState.focusRequest, viewportReady) {
        val request = viewportState.focusRequest ?: return@LaunchedEffect
        val current = viewportState.viewport ?: return@LaunchedEffect
        // each request runs once, even if this canvas re-enters composition
        if (!viewportState.consumeFocusRequest(request)) return@LaunchedEffect
        val target = when (request.mode) {
            // 1A v2 04: the Show target is centred within the content bounds
            TalentTreeFocusMode.CENTRE ->
                viewportState.clampTarget(current.focusedOn(request.world, request.visibleSize))
            TalentTreeFocusMode.REVEAL -> current.panToReveal(request.world, request.visibleSize)
        }
        if (target == current) return@LaunchedEffect
        // B3 07 Show: 300 ms; 1A v2 S3 inspector reveal: 260 ms
        val duration = when (request.mode) {
            TalentTreeFocusMode.CENTRE -> TalentInspectorMetrics.SHOW_PAN_MS
            TalentTreeFocusMode.REVEAL -> TalentInspectorMetrics.REVEAL_PAN_MS
        }
        animateCamera(viewportState, current, target, duration)
    }

    // 1A v2 S3 FIT: back to the fit camera in 280 ms; a gesture interrupts it
    // (the camera then stays user-moved where the gesture left it).
    LaunchedEffect(viewportState.fitRequest, viewportReady) {
        val current = viewportState.viewport ?: return@LaunchedEffect
        val fit = viewportState.fitViewport() ?: return@LaunchedEffect
        if (!viewportState.consumeFitRequest()) return@LaunchedEffect
        val finished = current.isCloseTo(fit) ||
            animateCamera(viewportState, current, fit, TalentTreeCanvasMetrics.FIT_ANIMATION_MS)
        if (finished) viewportState.completeFit(fit)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // A real viewport: everything the canvas draws — connectors, the
            // transformed node layer, rings and pulses — stays inside its own
            // bounds and can never paint into the header, the inspector or a
            // neighbouring panel. Pointer input was already bounds-restricted.
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                windowFrame.leftPx = coordinates.positionInWindow().x
                windowFrame.rootWidthPx = coordinates.findRootCoordinates().size.width.toFloat()
            }
            .onSizeChanged { size ->
                viewportState.onCanvasSizeChanged(
                    sizePx = Size(size.width.toFloat(), size.height.toFloat()),
                    density = localDensity.density,
                )
            }
            .then(
                if (interactive) {
                    Modifier
                        .semantics {
                            if (selectedNodeId != null) {
                                onClick(label = "clear selection") {
                                    onSelectionCleared()
                                    true
                                }
                            }
                        }
                        .pointerInput(model, viewportState) {
                            talentTreeGestures(
                                model = model,
                                viewportState = viewportState,
                                onNodeTap = onNodeSelected,
                                onEmptyTap = onSelectionCleared,
                                sectionSwipe = { sectionSwipe },
                                inSystemGestureZone = inSystemGestureZone,
                                isRtl = { rtl },
                            )
                        }
                } else {
                    Modifier
                },
            )
            // PR #19 page offset / rubber band: AFTER the pointer input in the
            // chain, so the moving drawing never moves the gesture's own coordinates
            .graphicsLayer {
                translationX = pageOffsetPx?.invoke()
                    ?: (talentSectionSwipeFeedbackDp(viewportState.sectionOverscrollDp) * density)
            },
    ) {
        val connectorColor = colors.borderStrong

        // Connectors: one draw pass in screen space, reading the transform
        // deferred so pan/zoom redraw without recomposition.
        Box(
            Modifier
                .matchParentSize()
                .then(if (pageTag != null) Modifier.testTag(pageTag) else Modifier)
                .drawBehind {
                    val viewport = viewportState.viewport ?: return@drawBehind
                    val guideEdge = showGuide?.let { guide ->
                        model.edges.firstOrNull { edge ->
                            edge.kind == TalentTreeEdgeKind.PREREQUISITE &&
                                (
                                    edge.fromNodeId == guide.targetNodeId &&
                                        edge.toNodeId == guide.originNodeId ||
                                        edge.fromNodeId == guide.originNodeId &&
                                        edge.toNodeId == guide.targetNodeId
                                    )
                        }
                    }
                    val baseAlpha = when {
                        showGuide != null -> TalentInspectorMetrics.CONNECTOR_SHOW_FADE_ALPHA
                        selectedNodeId != null -> TalentTreeCanvasMetrics.CONNECTOR_SELECTED_ALPHA
                        else -> 1f
                    }
                    for (edge in model.edges) {
                        val start = viewport
                            .worldToScreen(Offset(edge.startX, edge.startY))
                            .times(this.density)
                        val end = viewport
                            .worldToScreen(Offset(edge.endX, edge.endY))
                            .times(this.density)
                        if (edge === guideEdge) {
                            // B3 07: the guided path "turns bright neutral at 3 dp".
                            drawLine(
                                color = colors.focus,
                                start = start,
                                end = end,
                                strokeWidth =
                                TalentInspectorMetrics.CONNECTOR_ACTIVE_WIDTH_DP.dp.toPx() *
                                    viewport.scale,
                            )
                            continue
                        }
                        val strokeWidth =
                            TalentTreeCanvasMetrics.CONNECTOR_WIDTH_DP.dp.toPx() * viewport.scale
                        when {
                            edge.kind == TalentTreeEdgeKind.VISUAL_ONLY ->
                                drawLine(
                                    color = connectorColor.copy(alpha = baseAlpha),
                                    start = start,
                                    end = end,
                                    strokeWidth = strokeWidth,
                                )

                            presentation.nodes[edge.fromNodeId]?.isMaxed == true ->
                                // prerequisite met: solid (B3 06)
                                drawLine(
                                    color = connectorColor.copy(alpha = baseAlpha),
                                    start = start,
                                    end = end,
                                    strokeWidth = strokeWidth,
                                )

                            else ->
                                // inactive prerequisite: dashed (B3 06)
                                drawLine(
                                    color = connectorColor.copy(alpha = baseAlpha),
                                    start = start,
                                    end = end,
                                    strokeWidth = strokeWidth,
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(
                                            TalentInspectorMetrics.DASH_STROKE_DP.dp.toPx() *
                                                viewport.scale,
                                            TalentInspectorMetrics.DASH_GAP_DP.dp.toPx() *
                                                viewport.scale,
                                        ),
                                    ),
                                )
                        }
                    }
                },
        )

        // Nodes: laid out once in world dp; the whole layer follows the
        // viewport transform at draw time (no per-node recomposition on pan).
        // Transform origin is the top-left corner so the layer's mapping is
        // exactly the viewport's `screenDp = worldDp * scale + offset`.
        Box(
            Modifier
                .matchParentSize()
                // a shown-only page (PR #19 motion) is a picture: nothing in it reaches semantics
                .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
                .graphicsLayer {
                    val viewport = viewportState.viewport ?: return@graphicsLayer
                    scaleX = viewport.scale
                    scaleY = viewport.scale
                    translationX = viewport.offset.x.dp.toPx()
                    translationY = viewport.offset.y.dp.toPx()
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            val containerHalfSide =
                (TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f +
                    TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP +
                    TalentTreeCanvasMetrics.SELECTION_RING_WIDTH_DP).dp

            val composedNodes = if (composedNodeCount >= model.nodes.size) {
                model.nodes
            } else {
                model.nodes.subList(0, composedNodeCount.coerceAtLeast(0))
            }
            for (node in composedNodes) {
                key(node.id) {
                    val nodeIsSelected = node.id == selectedNodeId
                    val nodePresentation = presentation.nodes[node.id]
                    if (nodePresentation != null) {
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (node.centerX.dp - containerHalfSide).roundToPx(),
                                        (node.centerY.dp - containerHalfSide).roundToPx(),
                                    )
                                }
                                // Always the same modifiers, active page or not: a shown-only page
                                // (PR #19 motion) hides its talents from accessibility with ONE
                                // clearAndSetSemantics on the node layer above, so making a page
                                // active or inactive never rediffs every node's modifier chain
                                // (the switch frames measured 30-55 ms of applyChanges on a phone).
                                .semantics {
                                    contentDescription = node.name ?: "talent ${node.id}"
                                    stateDescription =
                                        nodePresentation.accessibilityStateDescription()
                                    role = Role.Button
                                    selected = nodeIsSelected
                                    onClick(label = "select talent") {
                                        onNodeSelected(node.id)
                                        true
                                    }
                                },
                        ) {
                            TalentNodeVisual(
                                node = node,
                                presentation = nodePresentation,
                                icon = icons.iconOf(node.id),
                                accent = accent,
                                selected = nodeIsSelected,
                                showOrigin = showGuide?.originNodeId == node.id,
                                pulseToken = if (showGuide?.targetNodeId == node.id) guideToken else 0,
                                purchaseToken = if (purchaseNodeId == node.id) purchaseToken else 0,
                            )
                        }
                    }
                    // fail closed: a node without an engine projection is not rendered
                }
            }
        }
    }
}

/**
 * The single gesture handler of the canvas, implementing B3 arbitration:
 *
 * - pointer down (on a node or not) + movement < 7 dp + up → tap: hit-testing
 *   selects the node or clears the selection;
 * - movement ≥ 7 dp at any time → the tap is cancelled and every further move
 *   pans (single finger) or pans + zooms around the centroid (pinch);
 * - a finger joining or leaving rebases the anchors, so the second finger
 *   never causes a jump; a degenerate (zero) pinch span is ignored, keeping
 *   the transform finite at all times;
 * - PR #19 section swipe, part of the SAME handler (no competing detector),
 *   in one of two models decided when the gesture passes the slop
 *   ([TalentSectionSwipeModel]):
 *   - FIT: a mostly horizontal gesture is locked as a section swipe — the
 *     camera does not move, the canvas only follows the finger as a bounded
 *     rubber band, and at the release [talentSectionFitSwipeDirection]
 *     decides (distance or flick); a mostly vertical one scrolls the section
 *     vertically with the sideways movement locked;
 *   - EDGE (zoomed / wider section): one finger pans as always; whatever
 *     horizontal movement the content clamp refuses accumulates as overscroll
 *     ([TalentSectionOverscroll]) and [talentSectionSwipeDirection] decides
 *     on the movement since the edge was reached.
 *   A pinch (a second finger ends a locked swipe and zooms), a tap, a
 *   diagonal or short swipe, a swipe started in the system gesture zone, or a
 *   pull back before the release never switch.
 */
private suspend fun PointerInputScope.talentTreeGestures(
    model: TalentTreeUiModel,
    viewportState: TalentTreeViewportState,
    onNodeTap: (Int) -> Unit,
    onEmptyTap: () -> Unit,
    sectionSwipe: () -> ((Int, Float, Float) -> Unit)?,
    inSystemGestureZone: (Offset) -> Boolean,
    isRtl: () -> Boolean,
) {
    val slopPx = TalentTreeCanvasMetrics.TOUCH_SLOP_DP.dp.toPx()
    val densityScale = this.density

    fun screenPxToDp(position: Offset): Offset =
        Offset(position.x / densityScale, position.y / densityScale)

    fun hitTest(position: Offset): Int? {
        val viewport = viewportState.viewport ?: return null
        // Screen-space target: never below the orientation minimum (68 dp
        // tablet, 56 dp portrait, 48 dp landscape) even when zoomed out.
        val minimumTarget = minimumNodeTargetDp(viewportState.layoutClass)
        return model.nodeAtScreen(screenPxToDp(position), viewport, minimumTarget)
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downPositions = HashMap<PointerId, Offset>()
        downPositions[down.id] = down.position

        var passedSlop = false
        var multiTouchSeen = false
        var pressedIds: Set<PointerId> = setOf(down.id)
        var previousCentroid: Offset? = null
        var previousSpan = 0f
        val overscroll = TalentSectionOverscroll()
        val startedInSystemGestureZone = inSystemGestureZone(down.position)
        var lastSinglePosition = down.position
        // FIT model (decided at the slop): a locked section swipe, or a vertical-only scroll
        var fitSwipe = false
        var fitSwipeFrom = down.position
        var lockSideways = false
        // edge model: where the finger was when the content edge was reached
        var edgeAnchor: Offset? = null
        var swipeReported = false
        // Every touch sample, not one per frame: Android batches the touch samples between two
        // frames into one event's history, so at 60 Hz a frame carries several of them. Compose's
        // own tracking also applies the platform rule that a finger resting before the lift has no speed.
        val velocity = VelocityTracker()
        velocity.addPointerInputChange(down)

        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressedChanges = event.changes.filter { it.pressed }

                val currentPressedIds = pressedChanges.map { it.id }.toSet()
                val fingerSetChanged = currentPressedIds != pressedIds
                for (change in pressedChanges) {
                    if (change.id !in downPositions) {
                        downPositions[change.id] = change.position
                    }
                }
                downPositions.keys.retainAll(currentPressedIds)
                pressedIds = currentPressedIds
                if (currentPressedIds.size > 1) multiTouchSeen = true

                if (pressedChanges.isEmpty()) {
                    // Gesture ended: a clean single-pointer tap only (never a tap
                    // after panning or after extra fingers touched the canvas).
                    if (!passedSlop && !multiTouchSeen && event.changes.size == 1) {
                        val tapped = hitTest(event.changes.first().position)
                        if (tapped != null) onNodeTap(tapped) else onEmptyTap()
                    }
                    // PR #19: the release confirms (or not) a section switch
                    val onSwipe = sectionSwipe()
                    if (onSwipe != null) {
                        // the finger's speed at the release carries into the pages' motion (never after a pinch);
                        // the lift itself is tracked too, so a finger that rested first lets go without speed
                        val releaseVelocity = if (multiTouchSeen) {
                            0f
                        } else {
                            event.changes.firstOrNull { it.id == down.id }?.let { velocity.addPointerInputChange(it) }
                            velocity.calculateVelocity().x / densityScale
                        }
                        val direction = if (fitSwipe) {
                            val total = screenPxToDp(lastSinglePosition - down.position)
                            talentSectionFitSwipeDirection(
                                totalDxDp = total.x,
                                totalDyDp = total.y,
                                velocityXDpPerSecond = releaseVelocity,
                                multiTouch = multiTouchSeen,
                                startedInSystemGestureZone = startedInSystemGestureZone,
                                rtl = isRtl(),
                            )
                        } else {
                            val sinceEdge = screenPxToDp(lastSinglePosition - (edgeAnchor ?: lastSinglePosition))
                            talentSectionSwipeDirection(
                                overscrollDp = overscroll.overscrollDp,
                                totalDxDp = sinceEdge.x,
                                totalDyDp = sinceEdge.y,
                                passedSlop = passedSlop,
                                multiTouch = multiTouchSeen,
                                startedInSystemGestureZone = startedInSystemGestureZone,
                                rtl = isRtl(),
                            )
                        }
                        val releaseDrag = viewportState.sectionOverscrollDp
                        viewportState.sectionOverscrollDp = 0f
                        swipeReported = true
                        // the pages finish the switch, or ease back, from where they were let go
                        if (direction != 0 || releaseDrag != 0f) onSwipe(direction, releaseDrag, releaseVelocity)
                    }
                    break
                }
                if (multiTouchSeen) {
                    // a pinch never switches sections: drop any overscroll at once,
                    // and a locked FIT swipe becomes an ordinary pan + zoom
                    overscroll.reset()
                    edgeAnchor = null
                    fitSwipe = false
                    viewportState.sectionOverscrollDp = 0f
                } else {
                    val single = pressedChanges.first()
                    lastSinglePosition = single.position
                    velocity.addPointerInputChange(single)
                }

                val centroid = centroidOf(pressedChanges)
                val span = spanOf(pressedChanges, centroid)

                if (!passedSlop) {
                    passedSlop = pressedChanges.any { change ->
                        val origin = downPositions[change.id]
                        origin != null && hypot(
                            (change.position.x - origin.x).toDouble(),
                            (change.position.y - origin.y).toDouble(),
                        ).toFloat() > slopPx
                    }
                    // PR #19: the FIT model is chosen once, as the gesture stops being a tap
                    if (passedSlop && !multiTouchSeen && sectionSwipe() != null &&
                        viewportState.sectionSwipeModel() == TalentSectionSwipeModel.FIT_SWIPE
                    ) {
                        val moved = screenPxToDp(lastSinglePosition - down.position)
                        if (talentSectionFitSwipeLocks(moved.x, moved.y)) {
                            fitSwipe = true
                            // the pages follow from where the swipe locked: no jump by the slop
                            fitSwipeFrom = lastSinglePosition
                        } else {
                            lockSideways = true
                        }
                    }
                }

                val anchor = previousCentroid
                if (fitSwipe && passedSlop) {
                    // FIT: the camera stays where it is; the canvas follows the finger (bounded)
                    viewportState.sectionOverscrollDp = screenPxToDp(lastSinglePosition - fitSwipeFrom).x
                    for (change in pressedChanges) {
                        if (change.positionChanged()) change.consume()
                    }
                } else if (passedSlop && !fingerSetChanged && anchor != null) {
                    val viewport = viewportState.viewport
                    if (viewport != null) {
                        val rawPan = screenPxToDp(centroid - anchor)
                        // FIT model, vertical gesture: scrolls a tall section, never sideways
                        val pan = if (lockSideways && !multiTouchSeen) Offset(0f, rawPan.y) else rawPan
                        val trackOverscroll = sectionSwipe() != null && !multiTouchSeen && !lockSideways
                        val overscrollBefore = overscroll.overscrollDp
                        // single finger: a move back towards the content first unwinds the overscroll
                        val givenX = if (trackOverscroll) overscroll.beforePan(pan.x) else pan.x
                        var next = viewport.panBy(Offset(givenX, pan.y))
                        val spanRatio = if (previousSpan > 0f && span > 0f) span / previousSpan else 1f
                        if (spanRatio.isFinite() && spanRatio != 1f) {
                            next = next.zoomAt(
                                focal = screenPxToDp(centroid),
                                targetScale = viewport.scale * spanRatio,
                            )
                        }
                        // clamped to the content, marks the camera user-moved and
                        // stops any running camera animation (1A v2 S3/S4)
                        viewportState.applyUserTransform(next)
                        if (trackOverscroll) {
                            val movedX = (viewportState.viewport?.offset?.x ?: viewport.offset.x) - viewport.offset.x
                            overscroll.afterPan(givenDxDp = givenX, movedDxDp = movedX)
                            viewportState.sectionOverscrollDp = overscroll.overscrollDp
                            // the switch is judged on the movement since the edge was reached
                            if (overscroll.overscrollDp == 0f) {
                                edgeAnchor = null
                            } else if (overscrollBefore == 0f) {
                                edgeAnchor = anchor
                            }
                        }
                    }
                    for (change in pressedChanges) {
                        if (change.positionChanged()) change.consume()
                    }
                }

                // Anchors always end at the current finger state; the transform
                // for a finger-set-change event is skipped above (no jump).
                previousCentroid = centroid
                previousSpan = span
            }
        } finally {
            // a cancelled gesture (the system took the pointer) never leaves a
            // page half-way: it eases back like a released, unconfirmed swipe
            val leftOver = viewportState.sectionOverscrollDp
            viewportState.sectionOverscrollDp = 0f
            if (!swipeReported && leftOver != 0f) sectionSwipe()?.invoke(0, leftOver, 0f)
        }
    }
}

/** PR #19: the swipe model of this camera right now (no camera yet → the edge model). */
private fun TalentTreeViewportState.sectionSwipeModel(): TalentSectionSwipeModel {
    val current = viewport ?: return TalentSectionSwipeModel.EDGE_OVERSCROLL
    val bounds = contentBounds ?: return TalentSectionSwipeModel.EDGE_OVERSCROLL
    val fit = fitViewport() ?: return TalentSectionSwipeModel.EDGE_OVERSCROLL
    return talentSectionSwipeModel(current.scale, fit.scale, bounds.width, canvasSize.width)
}

/** Where the canvas sits in its window (px), for the system-gesture-zone test. */
private class CanvasWindowFrame {
    var leftPx: Float = 0f
    var rootWidthPx: Float = Float.POSITIVE_INFINITY
}

/**
 * Animates the camera from [from] to [to] (scale and offset together, ease-out
 * over [durationMillis]). Returns `false` when a user gesture interrupted it —
 * the animation then stops at once and leaves the camera where the gesture
 * put it (1A v2 S4: "the gesture interrupts the animation").
 */
private suspend fun animateCamera(
    viewportState: TalentTreeViewportState,
    from: TalentTreeViewport,
    to: TalentTreeViewport,
    durationMillis: Int,
): Boolean {
    val epoch = viewportState.gestureEpoch
    var interrupted = false
    AnimationState(0f).animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = durationMillis, easing = LinearOutSlowInEasing),
    ) {
        if (viewportState.gestureEpoch != epoch) {
            interrupted = true
            cancelAnimation()
            return@animateTo
        }
        viewportState.applyTransform(
            TalentTreeViewport(
                scale = from.scale + (to.scale - from.scale) * value,
                offset = Offset(
                    from.offset.x + (to.offset.x - from.offset.x) * value,
                    from.offset.y + (to.offset.y - from.offset.y) * value,
                ),
            ),
        )
    }
    return !interrupted
}

private fun centroidOf(changes: List<PointerInputChange>): Offset {
    var sumX = 0f
    var sumY = 0f
    for (change in changes) {
        sumX += change.position.x
        sumY += change.position.y
    }
    val count = changes.size.toFloat()
    return Offset(sumX / count, sumY / count)
}

private fun spanOf(changes: List<PointerInputChange>, centroid: Offset): Float {
    if (changes.size < 2) return 0f
    var maxDistance = 0f
    for (index in changes.indices) {
        for (other in index + 1 until changes.size) {
            val dx = changes[index].position.x - changes[other].position.x
            val dy = changes[index].position.y - changes[other].position.y
            maxDistance = maxOf(maxDistance, hypot(dx.toDouble(), dy.toDouble()).toFloat())
        }
    }
    return maxDistance
}
