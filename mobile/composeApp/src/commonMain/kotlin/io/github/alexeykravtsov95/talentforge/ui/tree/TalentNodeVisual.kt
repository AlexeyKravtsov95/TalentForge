package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/**
 * ICON FALLBACK — isolated on purpose.
 *
 * `talent-data.v1` carries no icon/texture identifier, so M4.2 keeps rendering
 * every node as the B3 implementation-safe rounded square with the
 * deterministic text monogram derived from the real node name (see
 * [nodeMonogram]). No networking, no scraped art, no invented icon id; a real
 * icon renderer replaces this file's internals without touching canvas
 * geometry, hit testing or gestures.
 *
 * M4.2 layers the B3 node-state language (B3 02/07) over the fallback shape,
 * driven only by the engine-backed [TalentNodePresentation]:
 *
 * - available — solid outline, empty (outline) chip;
 * - partial — accent tint, filled accent chip;
 * - maxed — solid accent fill, double rim, light chip;
 * - tier locked — dashed border, padlock chip;
 * - prerequisite locked — dashed border, ↑ chip;
 * - no points — dimmed solid border, dimmed outline chip;
 * - unsupported/unresolved — dashed border, dimmed monogram, no chip;
 * - selection — the neutral white focus ring + 6% scale, layered ABOVE any
 *   state (never replacing it);
 * - Show origin — a dashed ring while the prerequisite guide is active (B3 05);
 * - Show target — a one-shot 900 ms pulse ring (B3 07).
 *
 * State is never carried by colour alone: border style (solid/dashed/dimmed),
 * chip treatment (outline/filled/light/glyph) and fill all differ together
 * with the tint.
 */
@Composable
fun TalentNodeFallbackVisual(
    node: TalentTreeNodeUiModel,
    presentation: TalentNodePresentation,
    selected: Boolean,
    showOrigin: Boolean,
    pulseToken: Int,
    purchaseToken: Int,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val ringOutset =
        (TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP + TalentTreeCanvasMetrics.SELECTION_RING_WIDTH_DP)
    val containerSide =
        (TalentTreeCanvasMetrics.NODE_VISUAL_DP + 2f * ringOutset).dp
    val nodeSide = TalentTreeCanvasMetrics.NODE_VISUAL_DP.dp
    val nodeShape = RoundedCornerShape(TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP.dp)
    val ringShape = RoundedCornerShape(
        (TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP + TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP).dp,
    )
    val state = presentation.state

    // B3 06: selected = white ring + 3 dp gap + 6% scale, layered on any state.
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) TalentTreeCanvasMetrics.SELECTION_SCALE else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "nodeSelectionScale",
    )

    // B3 07: "The target gets a ring and a 900 ms pulse" — one-shot per token.
    val pulseProgress = remember { Animatable(1f) }
    LaunchedEffect(pulseToken) {
        if (pulseToken > 0) {
            pulseProgress.snapTo(0f)
            pulseProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = TalentInspectorMetrics.SHOW_PULSE_MS,
                    easing = LinearOutSlowInEasing,
                ),
            )
        }
    }

    // UX/UI spec G motion (from B3 08): "Talent purchase 260 · 180 ms —
    // accent glow 260 ms; rank chip pops to 1.2× (180 ms spring)". One-shot
    // per purchase token on the node that just gained a rank.
    val glowProgress = remember { Animatable(1f) }
    LaunchedEffect(purchaseToken) {
        if (purchaseToken > 0) {
            glowProgress.snapTo(0f)
            glowProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = TalentForgeMotion.TALENT_PURCHASE_GLOW_MS),
            )
        }
    }
    val chipPop = remember { Animatable(1f) }
    LaunchedEffect(purchaseToken) {
        if (purchaseToken > 0) {
            chipPop.snapTo(1f)
            chipPop.animateTo(
                targetValue = 1.2f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
            chipPop.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = TalentForgeMotion.TALENT_PURCHASE_RANK_POP_MS),
            )
        }
    }

    val accent = colors.accentClassWarrior
    val bodyColor = colors.background
    // B3 01: "Border = can I reach it" — solid (reachable), dashed (locked),
    // dimmed solid (reachable but no points left). Colour stays neutral: the
    // class accent appears only on invested points (fill + chip).
    val borderColor = if (state == TalentNodeState.NO_POINTS) colors.disabled else colors.borderStrong
    val borderDashed = state == TalentNodeState.PREREQUISITE_LOCKED ||
        state == TalentNodeState.TIER_LOCKED ||
        state == TalentNodeState.UNAVAILABLE
    val nodeBackground = when (state) {
        TalentNodeState.PARTIAL -> accent.copy(alpha = 0.22f)
        TalentNodeState.MAXED -> accent
        else -> colors.surfaceElevated
    }
    val monogramColor = when (state) {
        TalentNodeState.MAXED -> bodyColor
        TalentNodeState.PREREQUISITE_LOCKED,
        TalentNodeState.TIER_LOCKED,
        TalentNodeState.UNAVAILABLE,
        TalentNodeState.NO_POINTS -> colors.textMuted

        else -> colors.textSecondary
    }

    Box(
        modifier = modifier
            .size(containerSide)
            .graphicsLayer {
                scaleX = selectionScale
                scaleY = selectionScale
                transformOrigin = TransformOrigin.Center
            },
        contentAlignment = Alignment.Center,
    ) {
        // One-shot Show pulse ring, behind everything else.
        val pulse = pulseProgress.value
        if (pulseToken > 0 && pulse < 1f) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawShowPulse(
                            progress = pulse,
                            color = colors.focus,
                        )
                    },
            )
        }

        // One-shot purchase glow (UX/UI spec G): a 260 ms accent halo on the
        // node that just gained a rank. The class accent is *invested points*
        // semantics, which a fresh rank literally is.
        val glow = glowProgress.value
        if (purchaseToken > 0 && glow < 1f) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawPurchaseGlow(progress = glow, color = accent)
                    },
            )
        }

        // Selection: neutral white focus ring on top of any state.
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        width = TalentTreeCanvasMetrics.SELECTION_RING_WIDTH_DP.dp,
                        color = colors.focus,
                        shape = ringShape,
                    ),
            )
        }

        // B3 05: after Show, the origin "keeps a dashed target ring".
        if (showOrigin && !selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawDashedRoundedRectStroke(
                            color = colors.focus,
                            strokeWidthPx = TalentTreeCanvasMetrics.SELECTION_RING_WIDTH_DP.dp.toPx(),
                            dashPx = TalentInspectorMetrics.DASH_STROKE_DP.dp.toPx(),
                            gapPx = TalentInspectorMetrics.DASH_GAP_DP.dp.toPx(),
                            cornerRadiusPx =
                                (TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP +
                                    TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP).dp.toPx(),
                        )
                    },
            )
        }

        // The node body: 48 dp rounded square, state background + border.
        Box(
            Modifier
                .size(nodeSide)
                .background(nodeBackground, nodeShape)
                .then(
                    if (borderDashed) {
                        Modifier.drawBehind {
                            drawDashedRoundedRectStroke(
                                color = borderColor,
                                strokeWidthPx = TalentTreeCanvasMetrics.NODE_BORDER_DP.dp.toPx(),
                                dashPx = TalentInspectorMetrics.DASH_STROKE_DP.dp.toPx(),
                                gapPx = TalentInspectorMetrics.DASH_GAP_DP.dp.toPx(),
                                cornerRadiusPx = TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP.dp.toPx(),
                            )
                        }
                    } else {
                        Modifier.border(
                            width = TalentTreeCanvasMetrics.NODE_BORDER_DP.dp,
                            color = borderColor,
                            shape = nodeShape,
                        )
                    },
                )
                // Maxed: a second, light rim inside the solid fill (B3 "double rim").
                .then(
                    if (state == TalentNodeState.MAXED) {
                        Modifier.drawBehind {
                            val inset = 3.dp.toPx()
                            drawRoundRect(
                                color = colors.focus.copy(alpha = 0.55f),
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                                cornerRadius = CornerRadius(
                                    (TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP - 3f).dp.toPx(),
                                ),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = node.monogram,
                style = TalentNodeMonogramStyle.copy(color = monogramColor),
            )
        }

        // Rank chip (B3 06): 15 dp tall, overlapping the node's bottom edge by
        // 8 dp; content and treatment come from the real rank/max-rank and
        // lock state — never hardcoded. On purchase it pops to 1.2× (UX/UI
        // spec G, 180 ms spring).
        RankChip(
            state = state,
            rankText = presentation.rankText,
            accent = accent,
            popScale = chipPop.value,
        )
    }
}

/** Rank / lock chip of the node; glyph chips replace the rank while locked (B3 01). */
@Composable
private fun BoxScope.RankChip(
    state: TalentNodeState,
    rankText: String,
    accent: Color,
    popScale: Float = 1f,
) {
    if (state == TalentNodeState.UNAVAILABLE) return
    val colors = talentForgeColors()
    // 15 dp chip, bottom edge overlapping the node's bottom by 8 dp:
    // offset from the container's bottom = chipHeight − overlap − ring inset.
    val bottomOffset =
        TalentInspectorMetrics.RANK_CHIP_HEIGHT_DP -
            TalentInspectorMetrics.RANK_CHIP_OVERLAP_DP -
            (TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP +
                TalentTreeCanvasMetrics.SELECTION_RING_WIDTH_DP).dp

    val chipShape = RoundedCornerShape(TalentForgeRadius.small)
    val (chipBackground, chipTextColor) = when (state) {
        TalentNodeState.PARTIAL -> accent to colors.background
        TalentNodeState.MAXED -> colors.focus to colors.background
        else -> Color.Transparent to colors.textSecondary
    }

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .offset(y = bottomOffset)
            .graphicsLayer {
                scaleX = popScale
                scaleY = popScale
                transformOrigin = TransformOrigin.Center
            }
            .height(TalentInspectorMetrics.RANK_CHIP_HEIGHT_DP)
            .then(
                if (chipBackground == Color.Transparent) {
                    Modifier.border(
                        width = 1.dp,
                        color = if (state == TalentNodeState.NO_POINTS) {
                            colors.disabled
                        } else {
                            colors.borderStrong
                        },
                        shape = chipShape,
                    )
                } else {
                    Modifier.background(chipBackground, chipShape)
                },
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            TalentNodeState.TIER_LOCKED -> PadlockGlyph(color = colors.textSecondary)
            TalentNodeState.PREREQUISITE_LOCKED ->
                BasicText(
                    text = "↑",
                    style = TalentRankChipStyle.copy(color = colors.textSecondary),
                )

            else ->
                BasicText(
                    text = rankText,
                    style = TalentRankChipStyle.copy(
                        color = if (state == TalentNodeState.NO_POINTS) {
                            colors.textMuted
                        } else {
                            chipTextColor
                        },
                    ),
                )
        }
    }
}

/** Minimal padlock indicator of the tier-locked chip (no icon set is bundled). */
@Composable
private fun PadlockGlyph(color: Color) {
    Box(
        Modifier
            .height(TalentInspectorMetrics.RANK_CHIP_HEIGHT_DP)
            .drawBehind {
                val stroke = 1.1.dp.toPx()
                val bodyWidth = 7.dp.toPx()
                val bodyHeight = 4.5.dp.toPx()
                val bodyLeft = (size.width - bodyWidth) / 2f
                val bodyTop = size.height - 2.dp.toPx() - bodyHeight
                drawRoundRect(
                    color = color,
                    topLeft = Offset(bodyLeft, bodyTop),
                    size = Size(bodyWidth, bodyHeight),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                drawArc(
                    color = color,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(bodyLeft + stroke, bodyTop - 2.6.dp.toPx()),
                    size = Size(bodyWidth - 2 * stroke, 5.dp.toPx()),
                    style = Stroke(width = stroke),
                )
            }
            .padding(horizontal = 6.dp),
    )
}

/** Expanding, fading ring of the one-shot Show pulse. */
private fun DrawScope.drawShowPulse(progress: Float, color: Color) {
    val half = size.minDimension / 2f
    val expand = 6.dp.toPx() * progress
    val alpha = (1f - progress).coerceIn(0f, 1f)
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = half - 1.dp.toPx() + expand,
        center = Offset(size.width / 2f, size.height / 2f),
        style = Stroke(width = TalentTreeCanvasMetrics.SELECTION_RING_WIDTH_DP.dp.toPx()),
    )
}

/**
 * One-shot purchase glow (UX/UI spec G: "accent glow 260 ms"): a soft accent
 * border that fades as the purchase lands.
 */
private fun DrawScope.drawPurchaseGlow(progress: Float, color: Color) {
    val alpha = (1f - progress).coerceIn(0f, 1f) * 0.85f
    if (alpha <= 0f) return
    val strokeWidth = 3.dp.toPx() + 2.dp.toPx() * progress
    val inset = ringStrokeInset(strokeWidth)
    drawRoundRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height - 2 * inset),
        cornerRadius = CornerRadius(
            (TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP +
                TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP).dp.toPx(),
        ),
        style = Stroke(width = strokeWidth),
    )
}

/** Inset keeping a stroke of [width] inside the node container's bounds. */
private fun ringStrokeInset(width: Float): Float = width / 2f

/** Dashed rounded-rectangle stroke (Compose has no dashed border modifier). */
private fun DrawScope.drawDashedRoundedRectStroke(
    color: Color,
    strokeWidthPx: Float,
    dashPx: Float,
    gapPx: Float,
    cornerRadiusPx: Float,
) {
    val half = strokeWidthPx / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(size.width - strokeWidthPx, size.height - strokeWidthPx),
        cornerRadius = CornerRadius(cornerRadiusPx - half.coerceAtMost(cornerRadiusPx / 2f)),
        style = Stroke(
            width = strokeWidthPx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), phase = dashPx / 2f),
        ),
    )
}

/**
 * Typography of the fallback monogram: the spec's mono/600 register (section G
 * uses 10.5 sp mono for rank chips and Geist Mono for numerics; Geist faces
 * are not bundled, so the platform monospace is the honest offline fallback).
 */
private val TalentNodeMonogramStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.SemiBold,
)

/** B3 06: "RANK CHIP 15 dp tall, 10.5 sp mono". */
private val TalentRankChipStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.5.sp,
    lineHeight = 15.sp,
    fontWeight = FontWeight.SemiBold,
)
