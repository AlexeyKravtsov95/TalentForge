package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The M4.3 header bar: `title · undo · redo · x / 51 · ⋯` (UX/UI spec 03 §5:
 * "Header: back · name / meta · undo · redo · x / 51 · ⋯" — there is no back
 * control before navigation exists, and no spec row: the published Warrior
 * sections are all nameless, so no Arms/Fury/Protection labels are invented).
 *
 * Undo/redo are enabled iff the corresponding history is non-empty, stay
 * visible while disabled, and affect ONLY the BuildState — never selection,
 * pan, zoom or inspector layout. ⋯ opens the build-actions sheet
 * (UX/UI spec ADAPT 2: "It is the only new header control").
 */
object TalentTreeHeaderMetrics {

    /** B3 06 CHROME: "Header 44". */
    val HEADER_HEIGHT_DP = 44.dp

    /** B3 08: "All buttons at least 44 × 44 dp". */
    val CONTROL_SIZE_DP = 44.dp
}

@Composable
fun TalentTreeHeaderBar(
    title: String,
    pointsText: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenBuildActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TalentTreeHeaderMetrics.HEADER_HEIGHT_DP)
            .padding(horizontal = TalentForgeSpacing.screenX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = HeaderTitleStyle.copy(color = talentForgeColors().textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        // Undo = ↺ (counterclockwise), redo = ↻: the arc is symmetric, so the
        // flag only flips the arrowhead's direction.
        HistoryIconButton(
            label = "Undo talent change",
            enabled = canUndo,
            mirrored = true,
            onClick = onUndo,
        )
        HistoryIconButton(
            label = "Redo talent change",
            enabled = canRedo,
            mirrored = false,
            onClick = onRedo,
        )
        if (pointsText != null) {
            BasicText(
                text = pointsText,
                style = HeaderPointsStyle.copy(color = talentForgeColors().textPrimary),
                maxLines = 1,
            )
        }
        OverflowButton(onClick = onOpenBuildActions)
    }
}

// ------------------------------------------------------------------ controls

/** Undo/redo glyph button; the glyph is drawn (no icon set is bundled). */
@Composable
private fun HistoryIconButton(
    label: String,
    enabled: Boolean,
    mirrored: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (enabled) talentForgeColors().textSecondary else talentForgeColors().disabled
    Box(
        modifier = modifier
            .size(TalentTreeHeaderMetrics.CONTROL_SIZE_DP)
            .then(
                if (enabled) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                        .semantics { contentDescription = label }
                } else {
                    Modifier.semantics(mergeDescendants = true) {
                        role = Role.Button
                        disabled()
                        contentDescription = label
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 18.dp, height = 18.dp)
                .drawBehind { drawHistoryArrow(color, mirrored) },
        )
    }
}

/** The ⋯ build-actions opener (UX/UI spec ADAPT 2). */
@Composable
private fun OverflowButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(TalentTreeHeaderMetrics.CONTROL_SIZE_DP)
            .clickable(
                role = Role.Button,
                onClickLabel = "open build actions",
                onClick = onClick,
            )
            .semantics { contentDescription = "open build actions" },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "⋯",
            style = HeaderOverflowStyle.copy(color = talentForgeColors().textSecondary),
        )
    }
}

/**
 * A circular arrow glyph: an almost-full arc with a small arrowhead at its
 * start, mirrored for redo. Pure geometry so it renders identically on every
 * target (no bundled font guaranteed to carry ↺ / ↻).
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistoryArrow(
    color: androidx.compose.ui.graphics.Color,
    mirrored: Boolean,
) {
    val stroke = 2.dp.toPx()
    val radius = size.minDimension / 2f - stroke
    if (radius <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)

    fun pointAt(angleDegrees: Float, distance: Float): Offset {
        val radians = angleDegrees * (PI / 180.0)
        val x = center.x + (distance * cos(radians)).toFloat()
        val y = center.y + (distance * sin(radians)).toFloat()
        return if (mirrored) Offset(size.width - x, y) else Offset(x, y)
    }

    // The arc spans −45°…225° (everything but a top gap) — symmetric about
    // the vertical axis, so mirroring only affects the arrowhead below.
    val startAngle = -45f
    val sweep = 270f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )

    // Arrowhead at the arc start, pointing along the sweep direction.
    val tip = pointAt(startAngle, radius)
    val tipInner = pointAt(startAngle, radius - stroke * 1.4f)
    val wingClockwise = pointAt(startAngle - 34f, radius + stroke * 1.4f)
    val wingCounter = pointAt(startAngle + 34f, radius + stroke * 1.4f)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(wingClockwise.x, wingClockwise.y)
        lineTo(tipInner.x, tipInner.y)
        lineTo(wingCounter.x, wingCounter.y)
        close()
    }
    drawPath(path, color = color)
}

// ------------------------------------------------------------------ typography

private val HeaderTitleStyle = TextStyle(
    fontSize = 15.5.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

/** UX/UI spec G: "Numeric 18 / 51 — Mono 17 · 600 · tabular". */
private val HeaderPointsStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)

private val HeaderOverflowStyle = TextStyle(
    fontSize = 20.sp,
    lineHeight = 24.sp,
)
