package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.section_fit_tree
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.section_fit_tree_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.section_points_spent
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.section_return_action
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Metrics of the M5.UX.1 section tabs and their canvas controls, frozen by the
 * approved 1A v2 direction (S1 sizes, S4 motion, S8 edge cases). Values
 * marked implementation-safe are not in the direction document.
 */
object TalentTreeSectionTabsMetrics {

    /** 1A v2 S1 tabs: 48 dp tall. */
    val TAB_HEIGHT_DP: Dp = 48.dp

    /** 1A v2 S8: up to five tabs share the width while each keeps ≥ 72 dp; otherwise the row scrolls. */
    val TAB_MIN_WIDTH_DP: Dp = 72.dp

    /** Implementation-safe: the width of one tab in the scrollable row. */
    val SCROLLING_TAB_WIDTH_DP: Dp = 96.dp

    /** 1A v2 S1: tab icon 20 dp. */
    val TAB_ICON_DP: Dp = 20.dp

    /** 1A v2 S1: indicator 2 dp, 64 % of the tab width. */
    val INDICATOR_HEIGHT_DP: Dp = 2.dp

    /** 1A v2 S1: indicator 2 dp, 64 % of the tab width. */
    const val INDICATOR_WIDTH_FRACTION: Float = 0.64f

    /** 1A v2 S4: indicator 200 ms, cubic(.2, .8, .2, 1). */
    const val INDICATOR_MS: Int = 200

    // 1A v2 S4's tab-change entry (180 ms fade + 24 dp shift of the new section)
    // is replaced by the PR #19 page transition: see TalentMotionMetrics.

    /** 1A v2 03/S1: below 360 dp of width the tab icons are hidden. */
    val ICONS_MIN_WIDTH_DP: Dp = 360.dp

    /** 1A v2 03/S1: above a 115 % font scale the tab icons are hidden. */
    const val ICONS_MAX_FONT_SCALE: Float = 1.15f

    /**
     * 1A v2 S8 "Шрифт 200%: … вкладки только «N» и очки": from this font scale
     * on (M5.UX.2a) a tab shows its position number instead of its label; the
     * full label stays its accessibility description.
     */
    const val NUMBER_ONLY_FONT_SCALE: Float = 1.75f

    /** 1A v2 S8: an undo/redo that changes another tab highlights that tab for 600 ms. */
    const val CHANGE_HIGHLIGHT_MS: Int = 600

    /** 1A v2 S1 FIT: visual 40 dp. */
    val FIT_VISUAL_DP: Dp = 40.dp

    /** 1A v2 S1 FIT: target 48 dp. */
    val FIT_TARGET_DP: Dp = 48.dp

    /** 1A v2 S1 FIT: 10 dp from the canvas edges. */
    val FIT_INSET_DP: Dp = 10.dp

    /** Implementation-safe: the Back chip's visual height (its touch target is 48 dp). */
    val RETURN_CHIP_HEIGHT_DP: Dp = 32.dp

    /** 1A v2 S4 indicator easing `cubic(.2, .8, .2, 1)`. */
    val INDICATOR_EASING = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

/** Test tag of a tab's talent icon (distinct from the canvas node icon tag). */
const val TALENT_SECTION_TAB_ICON_TAG = "talent-section-tab-icon"

/** Test tag of the tab indicator (its bounds follow the pages, PR #19 motion). */
const val TALENT_SECTION_INDICATOR_TAG = "talent-section-indicator"

/** One tab: the section, its display label, its live spent points and its icon node. */
data class TalentSectionTab(
    val index: Int,
    val label: String,
    val points: Int,
    /** The node whose real icon represents the section, or null (no icon shown). */
    val iconNodeId: Int?,
)

/**
 * Builds the tabs of [sections] from live per-section [points] and the
 * available [icons]. M5.REL.1: each tab's [TalentSectionTab.label] is the
 * resolved display label of ([classToken], the section's index) in
 * [language] — the approved specialization name ("Arms" / "Оружие" …), the
 * dataset's own published section name when it has one, and the localized
 * generic "Tree N" / "Ветка N" only for a pair the approved table does not
 * name (see [talentSectionDisplayLabel]).
 */
fun talentSectionTabs(
    sections: TalentTreeSectionsResult.Sectioned,
    points: Map<Int, Int>,
    icons: TalentNodeIcons,
    classToken: ClassToken,
    language: AppLanguage,
): List<TalentSectionTab> = sections.sections.map { section ->
    TalentSectionTab(
        index = section.index,
        label = talentSectionDisplayLabel(
            datasetName = section.datasetName,
            classToken = classToken,
            sectionIndex = section.index,
            ordinal = section.ordinal,
            language = language,
        ),
        points = points[section.index] ?: 0,
        iconNodeId = section.readingOrderNodeIds.firstOrNull { icons.iconOf(it) != null },
    )
}

/**
 * The M5.UX.1 section tab row (1A v2 01): one tab per section — the
 * section's first talent icon, its resolved display label (M5.REL.1: the
 * approved specialization name, e.g. "Arms" / "Оружие") and the points
 * spent in it. One tap switches the section; a tap on the active tab asks
 * for FIT. Any number of sections is supported: they share the width while
 * each tab keeps 72 dp, otherwise the row scrolls.
 *
 * Accessibility: each tab is a selectable `Tab` whose merged text carries the
 * label and the count; the count is also its state description.
 */
@Composable
fun TalentTreeSectionTabs(
    tabs: List<TalentSectionTab>,
    selectedIndex: Int,
    accent: Color,
    icons: TalentNodeIcons,
    highlightedSections: Set<Int>,
    highlightToken: Int,
    onSelectSection: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * PR #19 motion: the indicator's live position (fractional tab ordinal,
     * the pager position, see [TalentSectionPager]) read at layout time — it travels
     * with the pages instead of animating on its own, and the label emphasis
     * follows the tab the screen mostly shows. `selected` (accessibility)
     * stays the logical section. `null`: the 1A v2 S4 200 ms animation.
     */
    indicatorPosition: (() -> Float)? = null,
) {
    val colors = talentForgeColors()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TalentTreeSectionTabsMetrics.TAB_HEIGHT_DP)
            .drawBehind {
                // hairline separating the tabs from the canvas
                drawLine(
                    color = colors.borderDefault,
                    start = Offset(0f, size.height - 0.5f),
                    end = Offset(size.width, size.height - 0.5f),
                    strokeWidth = 1f,
                )
            },
    ) {
        if (tabs.isEmpty()) return@BoxWithConstraints
        val shared = maxWidth / tabs.size
        val scrolls = shared < TalentTreeSectionTabsMetrics.TAB_MIN_WIDTH_DP
        val tabWidth = if (scrolls) TalentTreeSectionTabsMetrics.SCROLLING_TAB_WIDTH_DP else shared
        val fontScale = LocalDensity.current.fontScale
        val showIcons = maxWidth >= TalentTreeSectionTabsMetrics.ICONS_MIN_WIDTH_DP &&
            fontScale <= TalentTreeSectionTabsMetrics.ICONS_MAX_FONT_SCALE
        val numberOnly = fontScale >= TalentTreeSectionTabsMetrics.NUMBER_ONLY_FONT_SCALE
        val selectedOrdinal = tabs.indexOfFirst { it.index == selectedIndex }.coerceAtLeast(0)
        val indicatorWidth = tabWidth * TalentTreeSectionTabsMetrics.INDICATOR_WIDTH_FRACTION
        val indicatorX by animateDpAsState(
            targetValue = tabWidth * selectedOrdinal + (tabWidth - indicatorWidth) / 2,
            animationSpec = tween(
                durationMillis = TalentTreeSectionTabsMetrics.INDICATOR_MS,
                easing = TalentTreeSectionTabsMetrics.INDICATOR_EASING,
            ),
            label = "sectionTabIndicator",
        )
        val livePosition by rememberUpdatedState(indicatorPosition)
        // recomposes only when the mostly shown tab changes, never per frame
        val emphasisedOrdinal by remember {
            derivedStateOf { livePosition?.invoke()?.let { kotlin.math.round(it).toInt() } }
        }

        Box(
            Modifier
                .fillMaxHeight()
                .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        ) {
            Row(Modifier.fillMaxHeight()) {
                for ((ordinal, tab) in tabs.withIndex()) {
                    SectionTab(
                        tab = tab,
                        shortLabel = if (numberOnly) "${ordinal + 1}" else null,
                        selected = tab.index == selectedIndex,
                        emphasised = (emphasisedOrdinal ?: selectedOrdinal) == ordinal,
                        accent = accent,
                        icon = if (showIcons) tab.iconNodeId?.let { icons.iconOf(it) } else null,
                        highlightToken = if (tab.index in highlightedSections) highlightToken else 0,
                        onClick = { onSelectSection(tab.index) },
                        modifier = Modifier.width(tabWidth),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset {
                        val live = livePosition
                        val x = if (live != null) {
                            tabWidth * live() + (tabWidth - indicatorWidth) / 2
                        } else {
                            indicatorX
                        }
                        IntOffset(x.roundToPx(), 0)
                    }
                    .testTag(TALENT_SECTION_INDICATOR_TAG)
                    .width(indicatorWidth)
                    .height(TalentTreeSectionTabsMetrics.INDICATOR_HEIGHT_DP)
                    .background(accent),
            )
        }
    }
}

@Composable
private fun SectionTab(
    tab: TalentSectionTab,
    shortLabel: String?,
    selected: Boolean,
    /** The label's emphasis: the tab the screen mostly shows (follows the pages, PR #19). */
    emphasised: Boolean,
    accent: Color,
    icon: ImageBitmap?,
    highlightToken: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val pointsSpent = pluralStringResource(Res.plurals.section_points_spent, tab.points, tab.points)
    // 1A v2 S8: a tab changed by an undo/redo made elsewhere flashes for 600 ms
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(highlightToken) {
        if (highlightToken > 0) {
            highlight.snapTo(1f)
            highlight.animateTo(0f, tween(TalentTreeSectionTabsMetrics.CHANGE_HIGHLIGHT_MS))
        } else {
            highlight.snapTo(0f)
        }
    }
    Row(
        modifier = modifier
            .fillMaxHeight()
            .drawBehind {
                if (highlight.value > 0f) drawRect(accent.copy(alpha = 0.18f * highlight.value))
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                stateDescription = pointsSpent
                if (shortLabel != null) contentDescription = tab.label
            }
            .padding(horizontal = TalentForgeSpacing.hairline),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(TalentTreeSectionTabsMetrics.TAB_ICON_DP)
                    .clip(RoundedCornerShape(4.dp))
                    .testTag(TALENT_SECTION_TAB_ICON_TAG),
            )
            Spacer(Modifier.width(6.dp))
        }
        BasicText(
            text = shortLabel ?: tab.label,
            style = TabLabelStyle.copy(
                color = if (emphasised) colors.textPrimary else colors.textSecondary,
                fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        BasicText(
            text = tab.points.toString(),
            style = TabPointsStyle.copy(
                // UX/UI spec G: the class accent marks invested points only
                color = if (selected && tab.points > 0) accent else colors.textMuted,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The 1A v2 FIT control: shown only while [viewportState] was moved by the
 * user away from its fit (S3); a tap animates the camera back. Reads the
 * camera through a derived state, so pan frames never recompose callers.
 * 40 dp visual inside a 48 dp target (S1).
 */
@Composable
fun TalentTreeFitButton(
    viewportState: TalentTreeViewportState,
    modifier: Modifier = Modifier,
) {
    val away by remember(viewportState) { derivedStateOf { viewportState.isAwayFromFit } }
    if (!away) return
    val colors = talentForgeColors()
    val shape = RoundedCornerShape(TalentForgeRadius.medium)
    val label = stringResource(Res.string.section_fit_tree)
    Box(
        modifier = modifier
            .size(TalentTreeSectionTabsMetrics.FIT_TARGET_DP)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(Res.string.section_fit_tree_action),
                onClick = viewportState::requestFit,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(TalentTreeSectionTabsMetrics.FIT_VISUAL_DP)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.borderStrong, shape)
                .drawBehind { drawFitGlyph(colors.textPrimary) },
        )
    }
}

/** Four corner brackets — the "fit to screen" glyph, drawn so every platform renders it identically. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFitGlyph(color: Color) {
    val stroke = 1.8.dp.toPx()
    val inset = size.minDimension * 0.3f
    val arm = size.minDimension * 0.14f
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(x, y), Offset(x, y + dy), strokeWidth = stroke, cap = StrokeCap.Round)
    }
    corner(left, top, arm, arm)
    corner(right, top, -arm, arm)
    corner(left, bottom, arm, -arm)
    corner(right, bottom, -arm, -arm)
}

/**
 * The 1A v2 04 Back chip after a cross-section Show: "‹ Back to <origin> ·
 * <origin tab>". It returns to the origin node and its untouched camera;
 * it never spends and never touches the build.
 */
@Composable
fun TalentSectionReturnChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val shape = RoundedCornerShape(TalentTreeSectionTabsMetrics.RETURN_CHIP_HEIGHT_DP / 2)
    Box(
        modifier = modifier
            .heightIn(min = TalentTreeSectionTabsMetrics.FIT_TARGET_DP)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(Res.string.section_return_action),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(TalentTreeSectionTabsMetrics.RETURN_CHIP_HEIGHT_DP)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.warning.copy(alpha = 0.6f), shape)
                .padding(horizontal = TalentForgeSpacing.rowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "‹",
                style = ChipTextStyle.copy(color = colors.warning, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = text,
                style = ChipTextStyle.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------ typography (1A v2 S1/S2)

/** 1A v2 S1: tab label 13.5 sp. */
private val TabLabelStyle = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp)

/** 1A v2 S1/S2: tab points 12.5 sp mono (Geist Mono in the mock; the platform mono here). */
private val TabPointsStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.SemiBold,
)

/** UX/UI spec G Label role (12 / 16 · 500) for the Back chip. */
private val ChipTextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
