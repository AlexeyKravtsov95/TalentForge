package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/**
 * ICON FALLBACK — isolated on purpose.
 *
 * `talent-data.v1` carries no icon/texture identifier, so M4.1 renders every
 * node as the B3 implementation-safe rounded square with a deterministic text
 * monogram derived from the real node name (see [nodeMonogram]). There is no
 * networking, no scraped art and no invented icon id.
 *
 * This composable owns ONLY the node's inner content. When a real icon
 * renderer arrives it replaces this file's internals (monogram → icon) without
 * touching canvas geometry, hit testing or gestures. The container size is the
 * B3 node visual (48 dp) plus the selection-ring outset (3 dp gap + 2.5 dp
 * ring on each side), so selection never clips.
 */
@Composable
fun TalentNodeFallbackVisual(
    node: TalentTreeNodeUiModel,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val ringOutset =
        (TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP + TalentTreeCanvasMetrics.SELECTION_RING_WIDTH_DP)
    val containerSide =
        (TalentTreeCanvasMetrics.NODE_VISUAL_DP + 2f * ringOutset).dp
    val nodeShape = RoundedCornerShape(TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP.dp)
    val ringShape = RoundedCornerShape(
        (TalentTreeCanvasMetrics.NODE_CORNER_RADIUS_DP + TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP).dp,
    )

    // B3 06: selected = white ring + 3 dp gap + 6% scale, layered on any state.
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) TalentTreeCanvasMetrics.SELECTION_SCALE else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "nodeSelectionScale",
    )

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
        Box(
            Modifier
                .size(TalentTreeCanvasMetrics.NODE_VISUAL_DP.dp)
                .background(colors.surfaceElevated, nodeShape)
                .border(
                    width = TalentTreeCanvasMetrics.NODE_BORDER_DP.dp,
                    color = colors.borderStrong,
                    shape = nodeShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = node.monogram,
                style = TalentNodeMonogramStyle.copy(color = colors.textSecondary),
            )
        }
    }
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
