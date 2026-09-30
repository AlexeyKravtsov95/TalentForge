package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The read-only Talent Tree canvas.
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
 * - pinching zooms 0.8–2.2× around the fingers.
 */
@Composable
fun TalentTreeCanvas(
    model: TalentTreeUiModel,
    viewportState: TalentTreeViewportState,
    selectedNodeId: Int?,
    onNodeSelected: (Int) -> Unit,
    onSelectionCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localDensity = LocalDensity.current
    val colors = talentForgeColors()

    LaunchedEffect(model, viewportState.canvasSize) {
        viewportState.initializeOnce(model.bounds)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
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
                    val alpha = if (selectedNodeId != null) {
                        TalentTreeCanvasMetrics.CONNECTOR_SELECTED_ALPHA
                    } else {
                        1f
                    }
                    val strokeWidth =
                        TalentTreeCanvasMetrics.CONNECTOR_WIDTH_DP.dp.toPx() * viewport.scale
                    for (edge in model.edges) {
                        val start = viewport
                            .worldToScreen(Offset(edge.startX, edge.startY))
                            .times(this.density)
                        val end = viewport
                            .worldToScreen(Offset(edge.endX, edge.endY))
                            .times(this.density)
                        drawLine(
                            color = connectorColor.copy(alpha = alpha),
                            start = start,
                            end = end,
                            strokeWidth = strokeWidth,
                        )
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
                                role = Role.Button
                                selected = nodeIsSelected
                                onClick(label = "select talent") {
                                    onNodeSelected(node.id)
                                    true
                                }
                            },
                    ) {
                        TalentNodeFallbackVisual(
                            node = node,
                            selected = nodeIsSelected,
                        )
                    }
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
        // Screen-space target: never below the B3 orientation minimum
        // (56 dp portrait / 48 dp landscape) even when zoomed out.
        val minimumTarget =
            TalentTreeCanvasMetrics.minimumNodeTargetDp(viewportState.isLandscape)
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
                    viewportState.applyTransform(next)
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
