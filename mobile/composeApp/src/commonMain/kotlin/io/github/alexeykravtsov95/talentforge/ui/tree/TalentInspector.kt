package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.motion.prefersReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/**
 * The M4.2/M4.3 adaptive Talent Inspector — three presentations of the same
 * real content ([TalentInspectorContent]), driven by the engine-backed action
 * projection ([TalentNodeActions]):
 *
 * - portrait compact bar (B3 03/06: 64 dp + 14 dp inset, grows at large text;
 *   since M5.UX.2a it stacks its actions under the name on narrow screens and
 *   at large text — [talentInspectorCompactLayout]), expanded by tapping the
 *   name or the chevron into the sheet (M5.UX.2a: sized by its content between
 *   160 dp and min(448 dp, available − 96 dp), scrolls internally — see
 *   [expandedSheetLimits]);
 * - landscape side panel (B3 05 / UX/UI spec 03: 300 dp, full detail, no
 *   compact state; × deselects);
 * - tablet persistent panel (UX/UI spec 03/13: 380 dp, always open, minimal
 *   empty prompt until a selection exists).
 *
 * M4.3 adds the explicit rank actions to every variant (B3 07: compact
 * "Available [+], Partial [− +], Maxed [− ✓], Prerequisite [Show], Tier [lock
 * meter], No points [− if ranked, + disabled]"; full detail adds the
 * `− + Rank Max` row). Spending/refunding happens only here — a node tap
 * never spends.
 *
 * M5.V1 adds the selected talent's real icon in front of its name in every
 * variant (`[icon] Talent Name`), for visual identification only. The icon is
 * the already decoded node icon; when there is none (monogram fallback) the
 * header is exactly the M4 one.
 *
 * M5.V2 adds the talent description to every full-detail variant (expanded
 * sheet, side panel, tablet panel): the current rank's text (rank 1 as the
 * prospective effect while unspent) and the B3 CURRENT / NEXT RANK comparison,
 * from the verified `talent-tooltips.v1` sidecar only — the raw
 * `descriptionTemplate` is never shown. The description sits below the rank,
 * state and lock lines and scrolls with the detail, while the rank actions and
 * Show are pinned to the bottom of every variant, so a long description can
 * never push a control or a blocked-action reason out of reach. The compact
 * bar has no description (B3 03: name, rank, state and actions only).
 */

/** Test tag of the inspector's talent icon. */
const val TALENT_INSPECTOR_ICON_TAG: String = "talent-inspector-icon"

/** Test tag of the compact bar's surface (M5.UX.2a). */
const val TALENT_INSPECTOR_COMPACT_TAG: String = "talent-inspector-compact"

/** Test tag of the compact bar's state / lock reason line (M5.UX.2a). */
const val TALENT_INSPECTOR_REASON_TAG: String = "talent-inspector-reason"

/** Test tag of the expanded sheet's surface (M5.UX.2a). */
const val TALENT_INSPECTOR_SHEET_TAG: String = "talent-inspector-sheet"

/** Test tag of the expanded sheet's scrolling detail region (M5.UX.2a). */
const val TALENT_INSPECTOR_DETAIL_SCROLL_TAG: String = "talent-inspector-detail-scroll"

/** Test tag of the expanded sheet's header — its swipe-down drag area (PR #19). */
const val TALENT_INSPECTOR_SHEET_HEADER_TAG: String = "talent-inspector-sheet-header"

/** Test tag of the expanded sheet's pinned action area (M5.UX.2a). */
const val TALENT_INSPECTOR_SHEET_ACTIONS_TAG: String = "talent-inspector-sheet-actions"

/** Accessibility label of the compact strip's expand handle (M5.UX.2a). */
const val INSPECTOR_EXPAND_LABEL: String = "Expand inspector"

/** Accessibility label of the sheet's ⌄ collapse control. */
const val INSPECTOR_COLLAPSE_LABEL: String = "Collapse inspector"

/**
 * Portrait: the compact strip docked to the bottom (1A v2 02 / S1, M5.UX.2a);
 * `content == null` hides it entirely.
 *
 * `[handle 12 dp]` over one 64 dp row `[icon 44] name … rank / state [actions]`
 * — 76 dp in all (the system safe area below it is applied by the screen
 * root). Tapping the handle or the name expands (1A v2 02); the handle
 * replaces the B3 chevron button, which is what keeps a readable name column
 * next to `− 48×48 + 56×48` on a 320 dp phone. At very large text or on a
 * narrower strip the row grows to its two-line form ([talentInspectorCompactLayout]).
 * The state line carries the engine-derived reason in words and the 1A v2
 * S2 tone ([talentInspectorStateTone]) — never colour alone.
 */
@Composable
fun TalentInspectorCompactBar(
    accent: androidx.compose.ui.graphics.Color,
    content: TalentInspectorContent?,
    actions: TalentNodeActions? = null,
    icon: ImageBitmap? = null,
    onSpendRank: (Int) -> Unit = {},
    onRefundRank: (Int) -> Unit = {},
    onToggleExpanded: () -> Unit,
    onShowPrerequisite: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // PR #19 motion accessibility: with reduced motion the strip simply appears
    val reducedMotion = prefersReducedMotion()
    AnimatedVisibility(
        visible = content != null,
        enter = if (reducedMotion) {
            EnterTransition.None
        } else {
            slideInVertically(tween(TalentForgeMotion.SHEET_IN_MS)) { it } +
                fadeIn(tween(TalentForgeMotion.SHEET_IN_MS))
        },
        exit = if (reducedMotion) {
            ExitTransition.None
        } else {
            slideOutVertically(tween(TalentForgeMotion.SHEET_OUT_MS)) { it } +
                fadeOut(tween(TalentForgeMotion.SHEET_OUT_MS))
        },
        modifier = modifier,
    ) {
        if (content != null) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fontScale = LocalDensity.current.fontScale
                val twoLine = talentInspectorCompactLayout(maxWidth, fontScale) ==
                    TalentInspectorCompactLayout.TWO_LINE
                val stateColor = talentInspectorStateTone(content, actions).color(accent)
                val cluster: @Composable () -> Unit = {
                    CompactActionCluster(
                        content = content,
                        actions = actions,
                        accent = accent,
                        onSpendRank = onSpendRank,
                        onRefundRank = onRefundRank,
                        onShowPrerequisite = onShowPrerequisite,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            talentForgeColors().surfaceElevated,
                            RoundedCornerShape(topStart = TalentForgeRadius.large, topEnd = TalentForgeRadius.large),
                        )
                        // PR #19: the WHOLE 76 dp strip is the swipe area (not only the
                        // 12 dp handle) — a swipe up expands; taps still reach the
                        // handle, the name and the buttons
                        .inspectorVerticalSwipe { swipe -> if (swipe == TalentInspectorSwipe.UP) onToggleExpanded() }
                        .padding(horizontal = TalentInspectorMetrics.COMPACT_PADDING_X_DP)
                        .testTag(TALENT_INSPECTOR_COMPACT_TAG),
                ) {
                    InspectorHandle(onClick = onToggleExpanded)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TalentInspectorMetrics.COMPACT_HEIGHT_DP),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                    ) {
                        InspectorTalentIcon(icon = icon, size = TalentInspectorMetrics.COMPACT_ICON_DP)
                        // 1A v2 02: "Тап по имени или ручке разворачивает инспектор."
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = TalentForgeSpacing.hairline)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "expand inspector",
                                    onClick = onToggleExpanded,
                                ),
                        ) {
                            // `Name … 0/3`: the rank never wraps and sits at the end;
                            // the name ellipsizes first. Two-line form: the name gets the
                            // whole width (two lines, words never split by a rank beside
                            // them), the rank goes under it, and the state line keeps the
                            // width next to the actions.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(
                                    text = content.name,
                                    style = InspectorNameStyle.copy(color = talentForgeColors().textPrimary),
                                    maxLines = if (twoLine) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!twoLine) CompactRank(content.rankText)
                            }
                            if (twoLine) CompactRank(content.rankText, start = 0.dp)
                            if (!twoLine) {
                                InspectorReasonLines(content = content, color = stateColor, maxLines = 2)
                            }
                        }
                        if (!twoLine) cluster()
                    }
                    if (twoLine) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = TalentInspectorMetrics.COMPACT_ACTION_DP)
                                .padding(bottom = TalentForgeSpacing.inline),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                        ) {
                            InspectorReasonLines(
                                content = content,
                                color = stateColor,
                                maxLines = 4,
                                modifier = Modifier.weight(1f),
                            )
                            cluster()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Portrait: the expanded sheet over the canvas (1A v2 02 / S1, M5.UX.2a;
 * B3 04/08 content).
 *
 * Sized by its content within [limits] ([expandedSheetLimits]: 260 dp to
 * min(66 % H, H − top − 104)) — a one-line description no longer leaves an
 * empty 448 dp surface. Top bar 48 dp (`⌄ · handle · ×`), 18 dp side padding,
 * identity row, then the detail, which scrolls inside the sheet
 * ([TALENT_INSPECTOR_DETAIL_SCROLL_TAG]) once the content is taller than the
 * maximum; the actions (`− 56×52`, `+ Rank` flex × 52, `Max 68×52`, or the
 * Show action) are pinned to the bottom, so no description length can push a
 * control out of reach. The caller measures the sheet's real height for the
 * covered canvas region — never an estimate.
 */
@Composable
fun TalentInspectorExpandedSheet(
    accent: androidx.compose.ui.graphics.Color,
    content: TalentInspectorContent,
    actions: TalentNodeActions? = null,
    icon: ImageBitmap? = null,
    limits: TalentInspectorSheetLimits,
    onCollapse: () -> Unit,
    onDeselect: () -> Unit,
    onShowPrerequisite: (Int) -> Unit,
    onSpendRank: (Int) -> Unit = {},
    onRefundRank: (Int) -> Unit = {},
    onMaxRank: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = limits.min, max = limits.max)
            .background(
                talentForgeColors().surfaceElevated,
                RoundedCornerShape(topStart = TalentForgeRadius.sheet, topEnd = TalentForgeRadius.sheet),
            )
            .padding(horizontal = TalentInspectorMetrics.SHEET_PADDING_X_DP)
            .testTag(TALENT_INSPECTOR_SHEET_TAG),
        // the slack of the 260 dp floor sits between the detail and the actions,
        // so the actions stay pinned to the bottom edge in every case
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            // PR #19: the sheet's header (⌄ · handle · × bar + identity row) is its
            // drag handle — a swipe down collapses. The scrolling description below
            // is outside it, so reading a long text never collapses the sheet.
            Column(
                Modifier
                    .inspectorVerticalSwipe { swipe -> if (swipe == TalentInspectorSwipe.DOWN) onCollapse() }
                    .testTag(TALENT_INSPECTOR_SHEET_HEADER_TAG),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TalentInspectorMetrics.SHEET_HEADER_DP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChevronButton(expanded = true, onToggle = onCollapse, targetSize = TalentInspectorMetrics.SHEET_CONTROL_DP)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HandleBar() }
                    CloseButton(onClose = onDeselect, targetSize = TalentInspectorMetrics.SHEET_CONTROL_DP)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
                    modifier = Modifier.padding(bottom = TalentForgeSpacing.inline),
                ) {
                    InspectorTalentIcon(icon = icon, size = TalentInspectorMetrics.COMPACT_ICON_DP)
                    // very large text (1A v2 S8): the rank goes under the name, so the
                    // name keeps the row's width and a long word is never split
                    val rankBelow = LocalDensity.current.fontScale >= TalentInspectorMetrics.COMPACT_STACK_FONT_SCALE
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            text = content.name,
                            style = InspectorNameStyle.copy(color = talentForgeColors().textPrimary),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (rankBelow) SheetRank(content.rankText)
                    }
                    if (!rankBelow) SheetRank(content.rankText)
                }
            }
            // wraps a short detail; a long one takes the remaining height and scrolls
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .testTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG)
                    .padding(bottom = TalentForgeSpacing.inline),
            ) {
                InspectorDetail(
                    content = content,
                    onShowPrerequisite = onShowPrerequisite,
                    showActionInline = false,
                    showRank = false,
                    stateColor = talentInspectorStateTone(content, actions).color(accent),
                )
            }
        }
        // 1A v2 02 / UX/UI spec 04: the actions are pinned at the bottom.
        Column(
            Modifier
                .testTag(TALENT_INSPECTOR_SHEET_ACTIONS_TAG)
                .padding(vertical = TalentForgeSpacing.rowGap),
        ) {
            if (actions != null && actions.offersRankControls) {
                TalentInspectorSheetRankActions(
                    talentName = content.name,
                    actions = actions,
                    accent = accent,
                    onSpend = onSpendRank,
                    onRefund = onRefundRank,
                    onMax = onMaxRank,
                )
            }
            content.showAction?.let { action ->
                ShowButton(
                    action = action,
                    onClick = { onShowPrerequisite(action.prerequisiteNodeId) },
                    portrait = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TalentInspectorActionMetrics.SHEET_HEIGHT_DP),
                )
            }
        }
    }
}

/** Landscape: the 300 dp side panel with the full detail (B3 05). */
@Composable
fun TalentInspectorSidePanel(
    accent: androidx.compose.ui.graphics.Color,
    content: TalentInspectorContent,
    actions: TalentNodeActions? = null,
    icon: ImageBitmap? = null,
    onDeselect: () -> Unit,
    onShowPrerequisite: (Int) -> Unit,
    onSpendRank: (Int) -> Unit = {},
    onRefundRank: (Int) -> Unit = {},
    onMaxRank: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP)
            .fillMaxHeight()
            .background(talentForgeColors().surface)
            .padding(horizontal = TalentForgeSpacing.screenX),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
            modifier = Modifier.padding(vertical = TalentForgeSpacing.inline),
        ) {
            InspectorTalentIcon(icon = icon, size = TalentInspectorMetrics.DETAIL_ICON_DP)
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = content.name,
                    style = InspectorNameStyle.copy(color = talentForgeColors().textPrimary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CloseButton(onClose = onDeselect)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            InspectorDetail(
                content = content,
                onShowPrerequisite = onShowPrerequisite,
                showActionInline = false,
            )
        }
        InspectorPinnedActions(
            content = content,
            actions = actions,
            accent = accent,
            onShowPrerequisite = onShowPrerequisite,
            onSpendRank = onSpendRank,
            onRefundRank = onRefundRank,
            onMaxRank = onMaxRank,
        )
    }
}

/**
 * Tablet: the persistent 380 dp inspector (UX/UI spec 13). With no selection
 * it renders the minimal empty prompt the spec requires; the wording is not
 * frozen by any design source, so it stays one honest implementation line.
 */
@Composable
fun TalentInspectorPersistentPanel(
    accent: androidx.compose.ui.graphics.Color,
    content: TalentInspectorContent?,
    actions: TalentNodeActions? = null,
    icon: ImageBitmap? = null,
    onShowPrerequisite: (Int) -> Unit,
    onSpendRank: (Int) -> Unit = {},
    onRefundRank: (Int) -> Unit = {},
    onMaxRank: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(TalentInspectorMetrics.TABLET_PANEL_WIDTH_DP)
            .fillMaxHeight()
            .background(talentForgeColors().surface)
            .padding(horizontal = TalentForgeSpacing.screenX),
    ) {
        if (content == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicText(
                    text = "Select a talent to inspect it",
                    style = InspectorStateLineStyle.copy(color = talentForgeColors().textMuted),
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                modifier = Modifier.padding(vertical = TalentForgeSpacing.inline),
            ) {
                InspectorTalentIcon(icon = icon, size = TalentInspectorMetrics.DETAIL_ICON_DP)
                BasicText(
                    text = content.name,
                    style = InspectorNameStyle.copy(color = talentForgeColors().textPrimary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                InspectorDetail(
                    content = content,
                    onShowPrerequisite = onShowPrerequisite,
                    showActionInline = false,
                )
            }
            InspectorPinnedActions(
                content = content,
                actions = actions,
                accent = accent,
                onShowPrerequisite = onShowPrerequisite,
                onSpendRank = onSpendRank,
                onRefundRank = onRefundRank,
                onMaxRank = onMaxRank,
            )
        }
    }
}

/**
 * The bottom-pinned actions of the side and tablet panels: the full-detail
 * `− + Rank Max` row and, for a prerequisite-locked node, its single Show
 * action — outside the scrolling detail, so they stay reachable however long
 * the description is (same placement as the expanded sheet, UX/UI spec 04).
 */
@Composable
private fun InspectorPinnedActions(
    content: TalentInspectorContent,
    actions: TalentNodeActions?,
    accent: androidx.compose.ui.graphics.Color,
    onShowPrerequisite: (Int) -> Unit,
    onSpendRank: (Int) -> Unit,
    onRefundRank: (Int) -> Unit,
    onMaxRank: (Int) -> Unit,
) {
    if (actions != null && actions.offersRankControls) {
        TalentInspectorRankActions(
            talentName = content.name,
            actions = actions,
            accent = accent,
            onSpend = onSpendRank,
            onRefund = onRefundRank,
            onMax = onMaxRank,
            modifier = Modifier.padding(vertical = TalentForgeSpacing.inline),
        )
    }
    content.showAction?.let { action ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = TalentForgeSpacing.inline),
        ) {
            ShowButton(action = action, onClick = { onShowPrerequisite(action.prerequisiteNodeId) })
        }
    }
}

// ------------------------------------------------------------------ shared internals

/**
 * The selected talent's real icon in front of its name. Renders nothing when
 * the node has no icon (monogram fallback) — the header then is the M4 one,
 * and no placeholder art is invented. Decorative for accessibility: the name
 * next to it is the label.
 */
@Composable
private fun InspectorTalentIcon(icon: ImageBitmap?, size: Dp) {
    if (icon == null) return
    val shape = RoundedCornerShape(TalentForgeRadius.small)
    Image(
        bitmap = icon,
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .clip(shape)
            .border(width = 1.dp, color = talentForgeColors().borderStrong, shape = shape)
            .testTag(TALENT_INSPECTOR_ICON_TAG),
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.Medium,
    )
}

/** The full-detail facts of one selected node — sparse, real, nothing invented. */
@Composable
private fun InspectorDetail(
    content: TalentInspectorContent,
    onShowPrerequisite: (Int) -> Unit,
    showActionInline: Boolean = true,
    showRank: Boolean = true,
    stateColor: androidx.compose.ui.graphics.Color? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
        // the expanded sheet shows the rank in its identity row (1A v2 02)
        if (showRank) {
            BasicText(
                text = content.rankText,
                style = InspectorRankLargeStyle.copy(color = talentForgeColors().textPrimary),
            )
        }
        if (content.stateLine.isNotEmpty()) {
            BasicText(
                text = content.stateLine,
                style = InspectorStateLineStyle.copy(color = stateColor ?: talentForgeColors().textSecondary),
            )
        }
        content.prerequisiteLine?.let { line ->
            BasicText(
                text = line,
                style = InspectorStateLineStyle.copy(color = stateColor ?: talentForgeColors().textSecondary),
            )
        }
        content.tierLine?.let { line ->
            BasicText(
                text = line,
                style = InspectorStateLineStyle.copy(color = stateColor ?: talentForgeColors().textSecondary),
            )
        }
        for (gate in content.tierMeters) {
            TierMeter(gate = gate, modifier = Modifier.fillMaxWidth())
        }
        content.description?.let { InspectorDescriptionBlock(it) }
        if (showActionInline) {
            content.showAction?.let { action ->
                ShowButton(action = action, onClick = { onShowPrerequisite(action.prerequisiteNodeId) })
            }
        }
    }
}

/**
 * Compact lock reason line: the prerequisite reason when present, else the
 * tier reason, else the state line (which carries a disabled control's
 * engine-derived explanation). M5.UX.2a: wraps up to [maxLines] instead of
 * cutting the reason after a few characters.
 */
@Composable
private fun InspectorReasonLines(
    content: TalentInspectorContent,
    color: androidx.compose.ui.graphics.Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val line = content.prerequisiteLine ?: content.tierLine ?: content.stateLine
    if (line.isEmpty()) return
    BasicText(
        text = line,
        style = InspectorStateLineStyle.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag(TALENT_INSPECTOR_REASON_TAG),
    )
}

/**
 * B3 07 / 1A v2 02 compact action set: a prerequisite lock keeps Show as the
 * only action; rank states show − / + (or − / ✓); a tier lock shows its
 * non-interactive gate counter (`13/15`) in place of + (1A v2 2.2).
 */
@Composable
private fun CompactActionCluster(
    content: TalentInspectorContent,
    actions: TalentNodeActions?,
    accent: androidx.compose.ui.graphics.Color,
    onSpendRank: (Int) -> Unit,
    onRefundRank: (Int) -> Unit,
    onShowPrerequisite: (Int) -> Unit,
) {
    val show = content.showAction
    val gate = content.tierMeters.firstOrNull()
    if (show != null && actions?.offersRankControls != true) {
        ShowButton(
            action = show,
            onClick = { onShowPrerequisite(show.prerequisiteNodeId) },
            portrait = true,
            modifier = Modifier.height(TalentInspectorMetrics.COMPACT_ACTION_DP),
        )
    } else if (gate != null && actions?.offersRankControls != true) {
        TierChip(gate)
    } else if (actions != null && actions.offersRankControls) {
        TalentInspectorCompactActions(
            talentName = content.name,
            actions = actions,
            accent = accent,
            onSpend = onSpendRank,
            onRefund = onRefundRank,
        )
    }
}

/**
 * The B3 non-interactive tier meter: real required/spent values from the
 * engine's TIER_GATE_NOT_MET issue. Neutral fill — the class accent is
 * reserved for invested points (UX/UI spec 03 colour semantics).
 */
@Composable
private fun TierMeter(gate: TalentTierGateProgress, modifier: Modifier = Modifier) {
    val colors = talentForgeColors()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .drawBehind {
                    val fraction = if (gate.requiredPoints > 0) {
                        (gate.spentPoints.toFloat() / gate.requiredPoints).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    drawRoundRect(
                        color = colors.surfaceInteractive,
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    drawRoundRect(
                        color = colors.borderStrong,
                        size = Size(size.width * fraction, size.height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                },
        )
        BasicText(
            text = "${gate.spentPoints}/${gate.requiredPoints}",
            style = InspectorRankStyle.copy(color = colors.textSecondary),
        )
    }
}

/**
 * The one read-only inspector action (B3 04: "Show as the only action").
 * Neutral light fill (UX/UI spec G: "The primary button is neutral light"),
 * ≥ 44 dp, and its accessibility label names the prerequisite (M4.2 brief).
 */
@Composable
private fun ShowButton(
    action: TalentInspectorContent.ShowAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
) {
    val colors = talentForgeColors()
    val shape = RoundedCornerShape(TalentForgeRadius.medium)
    Box(
        modifier = modifier
            .heightIn(min = TalentInspectorMetrics.ACTION_MIN_DP)
            .then(
                // 1A v2 02 (portrait): an outlined amber "↑ Show" — the lock colour,
                // never the class accent; side/tablet panels keep the neutral light fill
                if (portrait) {
                    Modifier.border(1.dp, colors.warning, shape)
                } else {
                    Modifier.background(colors.focus, shape)
                },
            )
            .clickable(
                role = Role.Button,
                onClickLabel = "show prerequisite ${action.prerequisiteName}",
                onClick = onClick,
            )
            .padding(horizontal = TalentForgeSpacing.rowGap, vertical = TalentForgeSpacing.inline),
        contentAlignment = Alignment.Center,
    ) {
        if (portrait) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(text = "↑", style = InspectorActionStyle.copy(color = colors.warning))
                BasicText(
                    text = "Show",
                    style = InspectorActionStyle.copy(color = colors.warning),
                    maxLines = 1,
                    modifier = Modifier.padding(start = TalentForgeSpacing.hairline),
                )
            }
        } else {
            BasicText(
                text = "Show",
                style = InspectorActionStyle.copy(color = colors.background),
            )
        }
    }
}

/**
 * 1A v2 2.2: the tier-locked compact state shows the real gate counter
 * (`spent/required` from the engine's TIER_GATE_NOT_MET issue) in place of +.
 * Not a button; amber like every lock (1A v2 S2).
 */
@Composable
private fun TierChip(gate: TalentTierGateProgress) {
    val colors = talentForgeColors()
    Box(
        modifier = Modifier
            .heightIn(min = TalentInspectorMetrics.COMPACT_ACTION_DP)
            .border(1.dp, colors.warning, RoundedCornerShape(TalentForgeRadius.medium))
            .semantics(mergeDescendants = true) {
                contentDescription = "Tier points ${gate.spentPoints} of ${gate.requiredPoints}"
            }
            .padding(horizontal = TalentForgeSpacing.rowGap),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "${gate.spentPoints}/${gate.requiredPoints}",
            style = InspectorRankStyle.copy(color = colors.warning),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The compact strip's rank (`0/3`, mono, never wraps). */
@Composable
private fun CompactRank(rankText: String, start: Dp = TalentForgeSpacing.inline) {
    BasicText(
        text = rankText,
        style = InspectorRankStyle.copy(color = talentForgeColors().textSecondary),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(start = start),
    )
}

/** The sheet's identity-row rank (1A v2 02: large mono `1/3`). */
@Composable
private fun SheetRank(rankText: String) {
    BasicText(
        text = rankText,
        style = InspectorRankLargeStyle.copy(color = talentForgeColors().textPrimary),
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * PR #19: the vertical swipe of the bottom inspector, observed AFTER the
 * children (Main pass, bubbling up), so taps on the handle, the name and the
 * − / + / Max / Show buttons work as before. Once the finger moved more than
 * [TalentTreeGestureMetrics.SHEET_DRAG_SLOP_DP] mostly vertically the drag is
 * taken: its moves are consumed, which cancels the press of any button under
 * it (a drag never spends, refunds, maxes or shows). At the release
 * [talentInspectorSwipe] decides UP / DOWN / NONE. The canvas below never sees
 * these pointers (the inspector is the topmost hit).
 */
private fun Modifier.inspectorVerticalSwipe(onSwipe: (TalentInspectorSwipe) -> Unit): Modifier =
    pointerInput(Unit) {
        val slop = TalentTreeGestureMetrics.SHEET_DRAG_SLOP_DP.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragging = false
            var multiTouch = false
            var last = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.count { it.pressed } > 1) multiTouch = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                last = change.position
                val total = last - down.position
                if (!dragging && !multiTouch && abs(total.y) > slop &&
                    abs(total.x) <= abs(total.y) * TalentTreeGestureMetrics.SHEET_SWIPE_MAX_SLOPE
                ) {
                    dragging = true
                }
                if (dragging) change.consume()
                if (!change.pressed) break
            }
            if (dragging && !multiTouch) {
                val total = last - down.position
                val swipe = talentInspectorSwipe(totalDxDp = total.x / density, totalDyDp = total.y / density)
                if (swipe != TalentInspectorSwipe.NONE) onSwipe(swipe)
            }
        }
    }

/** The 36 × 4 dp grab handle mark (1A v2 02). */
@Composable
private fun HandleBar() {
    Box(
        Modifier
            .size(width = 36.dp, height = 4.dp)
            .background(talentForgeColors().borderStrong, RoundedCornerShape(2.dp)),
    )
}

/**
 * The compact strip's 12 dp handle (1A v2 S1): tapping it expands, like the
 * name; its tap area spans the strip's whole width (PR #19), and a swipe up
 * anywhere on the strip expands too ([inspectorVerticalSwipe]).
 */
@Composable
private fun InspectorHandle(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TalentInspectorMetrics.COMPACT_HANDLE_DP),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TalentInspectorMetrics.COMPACT_HANDLE_DP)
                .clickable(role = Role.Button, onClickLabel = "expand inspector", onClick = onClick)
                .semantics { contentDescription = INSPECTOR_EXPAND_LABEL },
            contentAlignment = Alignment.Center,
        ) {
            HandleBar()
        }
    }
}

/** B3 03: "Tap the name or the chevron tab to expand." */
@Composable
private fun ChevronButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    targetSize: Dp = TalentInspectorMetrics.ACTION_MIN_DP,
) {
    val color = talentForgeColors().textSecondary
    val label = if (expanded) INSPECTOR_COLLAPSE_LABEL else INSPECTOR_EXPAND_LABEL
    Box(
        modifier = Modifier
            .size(targetSize)
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "collapse inspector" else "expand inspector",
                onClick = onToggle,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 16.dp, height = 10.dp)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    val dir = if (expanded) -1f else 1f // collapse: chevron up …
                    drawLine(
                        color = color,
                        start = Offset(0f, size.height / 2f + dir * size.height / 4f),
                        end = Offset(size.width / 2f, size.height / 2f - dir * size.height / 4f),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width / 2f, size.height / 2f - dir * size.height / 4f),
                        end = Offset(size.width, size.height / 2f + dir * size.height / 4f),
                        strokeWidth = stroke,
                    )
                },
        )
    }
}

/** × dismiss of the sheet/side panel (B3 05: "× or tapping empty canvas deselects"). */
@Composable
private fun CloseButton(onClose: () -> Unit, targetSize: Dp = TalentInspectorMetrics.ACTION_MIN_DP) {
    Box(
        modifier = Modifier
            .size(targetSize)
            .clickable(role = Role.Button, onClickLabel = "clear selection", onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "×",
            style = InspectorCloseStyle.copy(color = talentForgeColors().textSecondary),
        )
    }
}

// ------------------------------------------------------------------ state tone

/**
 * 1A v2 S2 colour semantics of the state line: green for Available, the
 * class accent for invested ranks ("Акцент класса только на потраченных
 * очках"), amber for every lock and blocked action. The words of the line
 * always carry the meaning; the tone only reinforces it.
 */
enum class TalentInspectorStateTone { AVAILABLE, INVESTED, BLOCKED, NEUTRAL }

/**
 * The tone of a selected talent's state line, from the same engine-backed
 * projections the controls use — the lock lines of [content] and the
 * `canSpend` / `canRefund` of [actions] (mirrors
 * [disabledActionExplanation]); never a re-derivation of the rules.
 */
fun talentInspectorStateTone(
    content: TalentInspectorContent,
    actions: TalentNodeActions?,
): TalentInspectorStateTone = when {
    content.prerequisiteLine != null || content.tierLine != null -> TalentInspectorStateTone.BLOCKED
    actions == null -> TalentInspectorStateTone.NEUTRAL
    actions.state == TalentNodeState.NO_POINTS -> TalentInspectorStateTone.BLOCKED
    actions.offersRankControls &&
        ((!actions.isMaxed && !actions.canSpend) || (actions.rank > 0 && !actions.canRefund)) ->
        TalentInspectorStateTone.BLOCKED

    actions.state == TalentNodeState.AVAILABLE -> TalentInspectorStateTone.AVAILABLE
    actions.state == TalentNodeState.PARTIAL || actions.state == TalentNodeState.MAXED ->
        TalentInspectorStateTone.INVESTED

    else -> TalentInspectorStateTone.NEUTRAL
}

@Composable
private fun TalentInspectorStateTone.color(accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val colors = talentForgeColors()
    return when (this) {
        TalentInspectorStateTone.AVAILABLE -> colors.positive
        TalentInspectorStateTone.INVESTED -> accent
        TalentInspectorStateTone.BLOCKED -> colors.warning
        TalentInspectorStateTone.NEUTRAL -> colors.textSecondary
    }
}

// ------------------------------------------------------------------ typography

/** B3 08: "Minimum text: … 12.5 sp for state lines." */
private val InspectorStateLineStyle = TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
)

private val InspectorNameStyle = TextStyle(
    fontSize = 15.5.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

/** B3 06: rank chip register — 10.5 sp mono/600. */
private val InspectorRankStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.5.sp,
    lineHeight = 15.sp,
    fontWeight = FontWeight.SemiBold,
)

private val InspectorRankLargeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)

private val InspectorActionStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

private val InspectorCloseStyle = TextStyle(
    fontSize = 20.sp,
    lineHeight = 24.sp,
)
