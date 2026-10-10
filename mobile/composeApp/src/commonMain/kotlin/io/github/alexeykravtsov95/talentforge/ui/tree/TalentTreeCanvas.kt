package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
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
) {
    val localDensity = LocalDensity.current
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
        animateCamera(viewportState, current, target, TalentInspectorMetrics.SHOW_PAN_MS)
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
            .onSizeChanged { size ->
                viewportState.onCanvasSizeChanged(
                    sizePx = Size(size.width.toFloat(), size.height.toFloat()),
                    density = localDensity.density,
                )
            }
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
                )
            },
    ) {
        val connectorColor = colors.borderStrong

        // Connectors: one draw pass in screen space, reading the transform
        // deferred so pan/zoom redraw without recomposition.
        Box(
            Modifier
                .matchParentSize()
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

            for (node in model.nodes) {
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
 *   the transform finite at all times.
 */
private suspend fun PointerInputScope.talentTreeGestures(
    model: TalentTreeUiModel,
    viewportState: TalentTreeViewportState,
    onNodeTap: (Int) -> Unit,
    onEmptyTap: () -> Unit,
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
                break
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
            }

            val anchor = previousCentroid
            if (passedSlop && !fingerSetChanged && anchor != null) {
                val viewport = viewportState.viewport
                if (viewport != null) {
                    val pan = centroid - anchor
                    var next = viewport.panBy(screenPxToDp(pan))
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
    }
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
