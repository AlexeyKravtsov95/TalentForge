package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/**
 * The M4.2/M4.3 adaptive Talent Inspector — three presentations of the same
 * real content ([TalentInspectorContent]), driven by the engine-backed action
 * projection ([TalentNodeActions]):
 *
 * - portrait compact bar (B3 03/06: 64 dp + 14 dp inset, grows at large text),
 *   expanded by tapping the name or the chevron into the sheet (B3 04/08:
 *   448 dp preferred, capped by the available height minus 96 dp, scrolls
 *   internally);
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
 * header is exactly the M4 one. Talent descriptions are still not rendered:
 * the dataset's `descriptionTemplate` is raw client tooltip markup, and an
 * unevaluated template is never shown (M5.V2).
 */

/** Test tag of the inspector's talent icon. */
const val TALENT_INSPECTOR_ICON_TAG: String = "talent-inspector-icon"

/** Portrait: the compact bottom inspector; `content == null` hides it entirely. */
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
    AnimatedVisibility(
        visible = content != null,
        enter = slideInVertically(tween(TalentForgeMotion.SHEET_IN_MS)) { it } +
            fadeIn(tween(TalentForgeMotion.SHEET_IN_MS)),
        exit = slideOutVertically(tween(TalentForgeMotion.SHEET_OUT_MS)) { it } +
            fadeOut(tween(TalentForgeMotion.SHEET_OUT_MS)),
        modifier = modifier,
    ) {
        if (content != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = TalentInspectorMetrics.COMPACT_INSET_DP,
                        vertical = TalentInspectorMetrics.COMPACT_INSET_DP,
                    )
                    .heightIn(min = TalentInspectorMetrics.COMPACT_HEIGHT_DP)
                    .background(
                        talentForgeColors().surfaceElevated,
                        RoundedCornerShape(TalentForgeRadius.large),
                    )
                    .padding(horizontal = TalentForgeSpacing.inline, vertical = TalentForgeSpacing.hairline),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                ) {
                    InspectorTalentIcon(icon = icon, size = TalentInspectorMetrics.COMPACT_ICON_DP)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "expand inspector",
                                onClick = onToggleExpanded,
                            ),
                    ) {
                        // M5.V1: `[icon] Talent Name` on the first line, rank + state on
                        // the second. With the icon in the bar, name and rank no longer
                        // fit side by side next to − / + on a 360 dp screen (the rank
                        // wrapped one character per line on a real device), so the rank
                        // moved down to the state line and can never wrap.
                        BasicText(
                            text = content.name,
                            style = InspectorNameStyle.copy(color = talentForgeColors().textPrimary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                        ) {
                            BasicText(
                                text = content.rankText,
                                style = InspectorRankStyle.copy(color = talentForgeColors().textSecondary),
                                maxLines = 1,
                                softWrap = false,
                            )
                            InspectorReasonLines(content = content, compact = true)
                        }
                    }
                    // B3 07 compact action set: prerequisite lock keeps Show
                    // as the only action; rank states show −/+ (or −/✓).
                    if (content.showAction != null && actions?.offersRankControls != true) {
                        content.showAction?.let { action ->
                            ShowButton(action = action, onClick = { onShowPrerequisite(action.prerequisiteNodeId) })
                        }
                    } else if (actions != null && actions.offersRankControls) {
                        TalentInspectorCompactActions(
                            talentName = content.name,
                            actions = actions,
                            accent = accent,
                            onSpend = onSpendRank,
                            onRefund = onRefundRank,
                        )
                    }
                    ChevronButton(expanded = false, onToggle = onToggleExpanded)
                }
                // B3 04: the tier-locked compact state shows a non-interactive meter.
                if (content.tierMeters.isNotEmpty() && content.showAction == null) {
                    Spacer(Modifier.height(TalentForgeSpacing.hairline))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                    ) {
                        TierMeter(gate = content.tierMeters.first(), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Portrait: the expanded sheet over the canvas (B3 04/06/08, UX/UI spec 03–04).
 *
 * The sheet **occupies** its preferred height — min(448 dp, available − 96 dp)
 * — rather than wrapping its (deliberately sparse) content: B3 freezes the
 * sheet as a 448 dp surface, the detail scrolls inside it at large text sizes,
 * and the actions pin to the bottom (UX/UI spec 04). Because [height] is the
 * sheet's actual geometry, the caller uses exactly this value as the covered
 * canvas region for the expand focus — covered height can never diverge from
 * the real one.
 */
@Composable
fun TalentInspectorExpandedSheet(
    accent: androidx.compose.ui.graphics.Color,
    content: TalentInspectorContent,
    actions: TalentNodeActions? = null,
    icon: ImageBitmap? = null,
    height: Dp,
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
            .height(height)
            .background(
                talentForgeColors().surfaceElevated,
                RoundedCornerShape(topStart = TalentForgeRadius.sheet, topEnd = TalentForgeRadius.sheet),
            )
            .padding(horizontal = TalentForgeSpacing.sheetX),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = content.rankText,
                    style = InspectorRankStyle.copy(color = talentForgeColors().textSecondary),
                )
            }
            ChevronButton(expanded = true, onToggle = onCollapse)
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
        // UX/UI spec 04: the sheet's actions are pinned at the bottom — the
        // full-detail `− + Rank Max` row (B3 04/08) and, when the node is
        // prerequisite-locked, its single Show action.
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
                ShowButton(
                    action = action,
                    onClick = { onShowPrerequisite(action.prerequisiteNodeId) },
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
            InspectorDetail(content = content, onShowPrerequisite = onShowPrerequisite)
        }
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
                InspectorDetail(content = content, onShowPrerequisite = onShowPrerequisite)
            }
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
        BasicText(
            text = content.rankText,
            style = InspectorRankLargeStyle.copy(color = talentForgeColors().textPrimary),
        )
        if (content.stateLine.isNotEmpty()) {
            BasicText(
                text = content.stateLine,
                style = InspectorStateLineStyle.copy(color = talentForgeColors().textSecondary),
            )
        }
        content.prerequisiteLine?.let { line ->
            BasicText(
                text = line,
                style = InspectorStateLineStyle.copy(color = talentForgeColors().textSecondary),
            )
        }
        content.tierLine?.let { line ->
            BasicText(
                text = line,
                style = InspectorStateLineStyle.copy(color = talentForgeColors().textSecondary),
            )
        }
        for (gate in content.tierMeters) {
            TierMeter(gate = gate, modifier = Modifier.fillMaxWidth())
        }
        if (showActionInline) {
            content.showAction?.let { action ->
                ShowButton(action = action, onClick = { onShowPrerequisite(action.prerequisiteNodeId) })
            }
        }
    }
}

/** Compact/sheet lock reason line: the prerequisite reason when present, else the tier reason. */
@Composable
private fun InspectorReasonLines(content: TalentInspectorContent, compact: Boolean) {
    val line = content.prerequisiteLine ?: content.tierLine ?: content.stateLine
    BasicText(
        text = line,
        style = InspectorStateLineStyle.copy(color = talentForgeColors().textSecondary),
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
    )
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
) {
    val colors = talentForgeColors()
    Box(
        modifier = modifier
            .heightIn(min = TalentInspectorMetrics.ACTION_MIN_DP)
            .background(colors.focus, RoundedCornerShape(TalentForgeRadius.medium))
            .clickable(
                role = Role.Button,
                onClickLabel = "show prerequisite ${action.prerequisiteName}",
                onClick = onClick,
            )
            .padding(horizontal = TalentForgeSpacing.rowGap, vertical = TalentForgeSpacing.inline),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "Show",
            style = InspectorActionStyle.copy(color = colors.background),
        )
    }
}

/** B3 03: "Tap the name or the chevron tab to expand." */
@Composable
private fun ChevronButton(expanded: Boolean, onToggle: () -> Unit) {
    val color = talentForgeColors().textSecondary
    Box(
        modifier = Modifier
            .size(TalentInspectorMetrics.ACTION_MIN_DP)
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "collapse inspector" else "expand inspector",
                onClick = onToggle,
            ),
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
private fun CloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TalentInspectorMetrics.ACTION_MIN_DP)
            .clickable(role = Role.Button, onClickLabel = "clear selection", onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "×",
            style = InspectorCloseStyle.copy(color = talentForgeColors().textSecondary),
        )
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
