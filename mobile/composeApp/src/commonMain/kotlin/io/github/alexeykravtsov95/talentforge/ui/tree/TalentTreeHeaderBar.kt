package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_go_back
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_build_actions_open
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_redo_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_undo_action
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.jetbrains.compose.resources.stringResource

/**
 * The M4.3/M5.1 header bar: `back · name / meta · undo · redo · x / 51 · ⋯`
 * (UX/UI spec 03 §5). M5.UX.2a makes it the adaptive 1A v2 S1 header: 52 dp
 * minimum height that grows with the font, 48 × 48 dp controls, name 15 sp /
 * meta 12 sp / counter mono 16 sp, the title column takes all the free width,
 * and on narrow screens or large text the counter leads the meta line
 * ([talentTreeHeaderLayout]). Since M5.1 the title is the two-line ADAPT 1 form —
 * line 1 the build name (or "Draft"), line 2 `class · game` — and the back
 * control appears when the screen is not the root (M4 kept the single-line
 * class title because no navigation existed). There is still no spec row of
 * the header's own: the specialization names live in the section tabs
 * (M5.REL.1 resolves them from the approved class-token × section-index
 * table, `TalentSpecializationNames`), which merge into this bar only in
 * the landscape layout.
 *
 * Undo/redo are enabled iff the corresponding history is non-empty, stay
 * visible while disabled, and affect ONLY the BuildState — never selection,
 * pan, zoom or inspector layout. ⋯ opens the build-actions sheet
 * (UX/UI spec ADAPT 2: "It is the only new header control").
 */
object TalentTreeHeaderMetrics {

    /**
     * Minimum editor header height. M5.UX.2a: 1A v2 S1 "Высота 52 dp" (B3 06
     * CHROME froze 44); the header grows with the system font so the
     * two-line title never clips.
     */
    val HEADER_HEIGHT_DP = 52.dp

    /** B3 08: "All buttons at least 44 × 44 dp" — the default back chevron of the other screens. */
    val CONTROL_SIZE_DP = 44.dp

    /** 1A v2 S1: "Back, Undo, Redo и меню по 48×48" — the editor header's touch targets. */
    val EDITOR_CONTROL_DP = 48.dp

    /**
     * Implementation-safe (M5.UX.2a): horizontal edge padding next to a 48 dp
     * control — its glyph then sits about 19 dp from the screen edge, in line
     * with the 16 dp screen margin, while the target reaches the edge.
     */
    val EDGE_DP = 4.dp

    /** 1A v2 S1: "При W < 360 dp … переносится в мета-строку". */
    val POINTS_INLINE_MIN_WIDTH_DP = 360.dp

    /** 1A v2 S1: "… или шрифте > 115% на W < 400" — the large-text width bound. */
    val POINTS_INLINE_LARGE_TEXT_MIN_WIDTH_DP = 400.dp

    /** 1A v2 S1: the font scale above which the large-text bound applies. */
    const val POINTS_LARGE_TEXT_FONT_SCALE: Float = 1.15f

    /**
     * Implementation-safe guard beyond 1A v2: also move the counter when the
     * width per font scale drops below 300 dp (e.g. 200 % text on a 412 dp
     * phone), so the title column never collapses.
     */
    val POINTS_INLINE_MIN_SCALED_WIDTH_DP = 300.dp
}

/** Where the header shows the global point counter (M5.UX.2a). */
enum class TalentTreeHeaderLayout {
    /** `back · title / meta · undo · redo · x / 51 · ⋯` (UX/UI spec 03 §5). */
    POINTS_INLINE,

    /** Narrow / large text (1A v2 03 3.1): `back · title / (x / 51 · meta) · undo · redo · ⋯`. */
    POINTS_IN_META,
}

/**
 * Pure choice of the header arrangement (1A v2 S1): the counter moves into
 * the meta line when `W < 360 dp`, or when the font is above 115 % and
 * `W < 400 dp` (plus the implementation-safe `W ÷ fontScale < 300 dp` guard).
 */
fun talentTreeHeaderLayout(width: Dp, fontScale: Float): TalentTreeHeaderLayout {
    val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
    val inMeta = width < TalentTreeHeaderMetrics.POINTS_INLINE_MIN_WIDTH_DP ||
        (scale > TalentTreeHeaderMetrics.POINTS_LARGE_TEXT_FONT_SCALE &&
            width < TalentTreeHeaderMetrics.POINTS_INLINE_LARGE_TEXT_MIN_WIDTH_DP) ||
        width / scale < TalentTreeHeaderMetrics.POINTS_INLINE_MIN_SCALED_WIDTH_DP
    return if (inMeta) TalentTreeHeaderLayout.POINTS_IN_META else TalentTreeHeaderLayout.POINTS_INLINE
}

/** Test tag of the editor header's title column (M5.UX.2a). */
const val TALENT_TREE_HEADER_TITLE_TAG: String = "talent-tree-header-title"

/** Test tag of the global point counter, wherever the header places it (M5.UX.2a). */
const val TALENT_TREE_HEADER_POINTS_TAG: String = "talent-tree-header-points"

@Composable
fun TalentTreeHeaderBar(
    title: String,
    meta: String?,
    pointsText: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenBuildActions: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * M5.UX.2b.1 landscape (B3 05): the section tabs merged into this bar,
     * between the title and undo, [tabsWidth] wide; the bar is then the B3
     * 48 dp one. `null`: the portrait header (tabs, if any, in their own row).
     */
    tabs: (@Composable () -> Unit)? = null,
    tabsWidth: Dp = 0.dp,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = talentTreeHeaderLayout(maxWidth, LocalDensity.current.fontScale)
        val pointsInMeta = layout == TalentTreeHeaderLayout.POINTS_IN_META && pointsText != null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(
                    min = if (tabs != null) {
                        TalentTreeLandscapeHeaderMetrics.HEADER_HEIGHT_DP.dp
                    } else {
                        TalentTreeHeaderMetrics.HEADER_HEIGHT_DP
                    },
                )
                .padding(
                    start = if (onBack != null) TalentTreeHeaderMetrics.EDGE_DP else TalentForgeSpacing.screenX,
                    end = TalentTreeHeaderMetrics.EDGE_DP,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                BackChevronButton(onClick = onBack, size = TalentTreeHeaderMetrics.EDITOR_CONTROL_DP)
            }
            // The title column takes ALL the free width (M5.UX.2a: no second
            // weighted spacer halving it); the controls keep their targets.
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = TalentForgeSpacing.hairline)
                    .testTag(TALENT_TREE_HEADER_TITLE_TAG),
            ) {
                BasicText(
                    text = title,
                    style = HeaderTitleStyle.copy(color = talentForgeColors().textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pointsInMeta) {
                    // 1A v2 03 (3.1): "21/51 · Druid · Forev…" — the counter leads the
                    // meta line and never ellipsizes; the class/game text gives way.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            text = pointsText.orEmpty(),
                            style = HeaderMetaPointsStyle.copy(color = talentForgeColors().textPrimary),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag(TALENT_TREE_HEADER_POINTS_TAG),
                        )
                        if (meta != null) {
                            BasicText(
                                text = " · $meta",
                                style = HeaderMetaStyle.copy(color = talentForgeColors().textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (meta != null) {
                    BasicText(
                        text = meta,
                        style = HeaderMetaStyle.copy(color = talentForgeColors().textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tabs != null) {
                Box(
                    Modifier
                        .padding(end = TalentTreeLandscapeHeaderMetrics.TABS_GAP_DP.dp)
                        .width(tabsWidth),
                ) {
                    tabs()
                }
            }
            // Undo = ↺ (counterclockwise), redo = ↻: the arc is symmetric, so the
            // flag only flips the arrowhead's direction.
            HistoryIconButton(
                label = stringResource(Res.string.editor_undo_action),
                enabled = canUndo,
                mirrored = true,
                onClick = onUndo,
            )
            HistoryIconButton(
                label = stringResource(Res.string.editor_redo_action),
                enabled = canRedo,
                mirrored = false,
                onClick = onRedo,
            )
            if (pointsText != null && !pointsInMeta) {
                BasicText(
                    text = pointsText,
                    style = HeaderPointsStyle.copy(color = talentForgeColors().textPrimary),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .padding(horizontal = TalentForgeSpacing.hairline)
                        .testTag(TALENT_TREE_HEADER_POINTS_TAG),
                )
            }
            OverflowButton(onClick = onOpenBuildActions)
        }
    }
}

/**
 * The visible back affordance of any non-root screen (UX/UI spec 02 §1:
 * "native system back everywhere" — on iOS/desktop hosts this chevron IS the
 * frozen back behaviour; on Android it agrees with the system button). 44 × 44
 * dp target by default (the editor header passes its 48 dp, M5.UX.2a), drawn
 * glyph so it renders identically on every platform. Its accessible label is
 * localized (M5.REL.2a: shared by All Builds, Class Selection and the editor).
 */
@Composable
fun BackChevronButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = TalentTreeHeaderMetrics.CONTROL_SIZE_DP,
) {
    val color = talentForgeColors().textPrimary
    val label = stringResource(Res.string.common_go_back)
    Box(
        modifier = modifier
            .size(size)
            .clickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 14.dp, height = 22.dp)
                .drawBehind { drawBackChevron(color) },
        )
    }
}

/** A ‹ chevron: two strokes meeting in a left point, vertically centred. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackChevron(
    color: androidx.compose.ui.graphics.Color,
) {
    val stroke = 2.4.dp.toPx()
    val cap = androidx.compose.ui.graphics.StrokeCap.Round
    val top = Offset(size.width * 0.72f, size.height * 0.18f)
    val bottom = Offset(size.width * 0.72f, size.height * 0.82f)
    val point = Offset(size.width * 0.28f, size.height * 0.5f)
    drawLine(color = color, start = point, end = top, strokeWidth = stroke, cap = cap)
    drawLine(color = color, start = point, end = bottom, strokeWidth = stroke, cap = cap)
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
            .size(TalentTreeHeaderMetrics.EDITOR_CONTROL_DP)
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
    val label = stringResource(Res.string.editor_build_actions_open)
    Box(
        modifier = modifier
            .size(TalentTreeHeaderMetrics.EDITOR_CONTROL_DP)
            .clickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
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

/** 1A v2 S1: "Имя 15 sp/600". */
private val HeaderTitleStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

/** ADAPT 1 line 2 (`Warrior · Forever`) — 1A v2 S1: "мета 12 sp". */
private val HeaderMetaStyle = TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/** 1A v2 S1: "Бюджет · Mono 16 sp справа" (UX/UI spec G: mono · 600 · tabular). */
private val HeaderPointsStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)

/** M5.UX.2a: the counter in the meta line — the numeric mono register at the meta size. */
private val HeaderMetaPointsStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.SemiBold,
)

private val HeaderOverflowStyle = TextStyle(
    fontSize = 20.sp,
    lineHeight = 24.sp,
)
