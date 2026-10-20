package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/** Test tag of a node body that shows a real talent icon. */
const val TALENT_NODE_ICON_TAG: String = "talent-node-icon"

/** Test tag of a node body that shows the monogram fallback. */
const val TALENT_NODE_MONOGRAM_TAG: String = "talent-node-monogram"

/** Test tag of a node's rank / lock chip (B3 06). */
const val TALENT_RANK_CHIP_TAG: String = "talent-rank-chip"

/**
 * One talent node: the B3 implementation-safe 48 dp rounded square with its
 * state, selection and feedback layers.
 *
 * NODE CONTENT (M5.V1). The body shows the talent's real, build-pinned WoW
 * icon ([icon], decoded once when the editor session opened — never loaded or
 * decoded here). The deterministic text monogram derived from the real node
 * name (see [nodeMonogram]) is kept as the **exceptional fallback**: it
 * renders only when [icon] is `null` (visuals unavailable, or this icon
 * missing/corrupt), exactly as in M4. Geometry, hit testing and gestures do
 * not depend on which of the two is shown.
 *
 * With an icon, the B3 state language is layered over/around the artwork
 * (nothing is baked into the image file):
 *
 * - available — full-colour icon, solid outline, outline chip;
 * - partial — full-colour icon + class-accent inner frame, filled accent chip;
 * - maxed — full-colour icon + accent frame + light inner rim (the B3 double
 *   rim), light chip;
 * - tier / prerequisite locked, unavailable — desaturated and dimmed icon,
 *   dashed border, padlock / ↑ chip;
 * - no points — dimmed (not desaturated) icon, dimmed solid border and chip.
 *
 * B3 01 "Fill = what I spent" cannot fill a body that shows artwork, so the
 * accent moves from the fill to a frame on the same body; border style and
 * chip treatment are unchanged, so no state is carried by colour alone. Chips
 * sit on an opaque plate over an icon, so rank text never reads against art.
 *
 * Without an icon the M4.2 treatment applies unchanged. It layers the B3
 * node-state language (B3 02/07) over the fallback shape,
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
fun TalentNodeVisual(
    node: TalentTreeNodeUiModel,
    presentation: TalentNodePresentation,
    icon: ImageBitmap?,
    accent: Color,
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

    // The class accent is an explicit input since M5.1 (Warrior keeps its
    // spec-frozen accent; other classes receive the documented neutral
    // fallback) — never a theme lookup: the Warrior token is Warrior-only.
    val bodyColor = colors.background
    // B3 01: "Border = can I reach it" — solid (reachable), dashed (locked),
    // dimmed solid (reachable but no points left). Colour stays neutral: the
    // class accent appears only on invested points (fill + chip).
    val borderColor = if (state == TalentNodeState.NO_POINTS) colors.disabled else colors.borderStrong
    val borderDashed = state == TalentNodeState.PREREQUISITE_LOCKED ||
        state == TalentNodeState.TIER_LOCKED ||
        state == TalentNodeState.UNAVAILABLE
    val nodeBackground = when {
        // Under an icon the body stays neutral: invested points show as the
        // accent frame drawn over the artwork (see the icon layer below).
        icon != null -> colors.surfaceElevated
        state == TalentNodeState.PARTIAL -> accent.copy(alpha = 0.22f)
        state == TalentNodeState.MAXED -> accent
        else -> colors.surfaceElevated
    }
    val iconLocked = state == TalentNodeState.PREREQUISITE_LOCKED ||
        state == TalentNodeState.TIER_LOCKED ||
        state == TalentNodeState.UNAVAILABLE
    val iconAlpha = when {
        iconLocked -> ICON_LOCKED_ALPHA
        state == TalentNodeState.NO_POINTS -> ICON_NO_POINTS_ALPHA
        else -> 1f
    }
    val iconColorFilter = if (iconLocked) DesaturateFilter else null
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
            if (icon != null) {
                // The real icon fills the existing rounded square. FillBounds
                // is exact for the square client textures and never distorts
                // them (the body is square too).
                Image(
                    bitmap = icon,
                    contentDescription = null, // the node's semantics already name the talent
                    modifier = Modifier
                        .matchParentSize()
                        .clip(nodeShape)
                        .testTag(TALENT_NODE_ICON_TAG),
                    contentScale = ContentScale.FillBounds,
                    alpha = iconAlpha,
                    colorFilter = iconColorFilter,
                    filterQuality = FilterQuality.Medium,
                )
                // State layers ABOVE the artwork: the body's own border/rim
                // modifiers draw behind their content, which the icon covers.
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            val borderWidth = TalentTreeCanvasMetrics.NODE_BORDER_DP.dp.toPx()
                            val cornerRadius = TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP.dp.toPx()
                            if (borderDashed) {
                                drawDashedRoundedRectStroke(
                                    color = borderColor,
                                    strokeWidthPx = borderWidth,
                                    dashPx = TalentInspectorMetrics.DASH_STROKE_DP.dp.toPx(),
                                    gapPx = TalentInspectorMetrics.DASH_GAP_DP.dp.toPx(),
                                    cornerRadiusPx = cornerRadius,
                                )
                            } else {
                                drawRoundRect(
                                    color = borderColor,
                                    topLeft = Offset(borderWidth / 2f, borderWidth / 2f),
                                    size = Size(size.width - borderWidth, size.height - borderWidth),
                                    cornerRadius = CornerRadius(cornerRadius - borderWidth / 2f),
                                    style = Stroke(width = borderWidth),
                                )
                            }
                            if (state == TalentNodeState.PARTIAL || state == TalentNodeState.MAXED) {
                                // Invested points: class-accent frame just inside the border.
                                val frameWidth = ICON_ACCENT_FRAME_DP.dp.toPx()
                                val inset = borderWidth + frameWidth / 2f
                                drawRoundRect(
                                    color = accent,
                                    topLeft = Offset(inset, inset),
                                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                                    cornerRadius = CornerRadius(cornerRadius - inset),
                                    style = Stroke(width = frameWidth),
                                )
                            }
                            if (state == TalentNodeState.MAXED) {
                                // B3 "double rim": the light rim inside the accent frame.
                                val inset = borderWidth + ICON_ACCENT_FRAME_DP.dp.toPx() + 0.5.dp.toPx()
                                drawRoundRect(
                                    color = colors.focus.copy(alpha = 0.85f),
                                    topLeft = Offset(inset, inset),
                                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                                    cornerRadius = CornerRadius(cornerRadius - inset),
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                        },
                )
            } else {
                BasicText(
                    text = node.monogram,
                    style = TalentNodeMonogramStyle.copy(color = monogramColor),
                    modifier = Modifier.testTag(TALENT_NODE_MONOGRAM_TAG),
                )
            }
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
            overArtwork = icon != null,
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
    overArtwork: Boolean = false,
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
            .testTag(TALENT_RANK_CHIP_TAG)
            .then(
                if (chipBackground == Color.Transparent) {
                    // Outline chips are see-through over the plain monogram
                    // body; over real artwork they get an opaque plate so the
                    // rank / lock glyph never reads against the icon.
                    (if (overArtwork) Modifier.background(colors.background, chipShape) else Modifier).border(
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
        // TF-RC1-BADGE-200: the chip is 15 dp at every text size (B3 06), so
        // its text follows the system font scale only while it still fits
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, rankChipFontScale(density.fontScale)),
        ) {
            RankChipContent(state = state, rankText = rankText, chipTextColor = chipTextColor)
        }
    }
}

/** The rank text, or the lock glyph that replaces it while locked. */
@Composable
private fun RankChipContent(state: TalentNodeState, rankText: String, chipTextColor: Color) {
    val colors = talentForgeColors()
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

/** Icon dimming of a locked/unavailable node (also desaturated) — implementation-safe. */
private const val ICON_LOCKED_ALPHA: Float = 0.42f

/** Icon dimming of a reachable node with no points left (colour kept) — implementation-safe. */
private const val ICON_NO_POINTS_ALPHA: Float = 0.6f

/** Width of the class-accent "invested" frame over an icon — implementation-safe. */
private const val ICON_ACCENT_FRAME_DP: Float = 2f

/** Greyscale filter of locked icons; state is still carried by border + chip too. */
private val DesaturateFilter: ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

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

/**
 * B3 06: "RANK CHIP 15 dp tall, 10.5 sp mono". The line height is the em box
 * (1 em), not the chip height: the chip centres the line, and at the capped
 * font scale ([rankChipFontScale]) the line still fits the 15 dp chip.
 */
private val TalentRankChipStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = TalentInspectorMetrics.RANK_CHIP_FONT_SP.sp,
    lineHeight = 1.em,
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
    fontWeight = FontWeight.SemiBold,
)
