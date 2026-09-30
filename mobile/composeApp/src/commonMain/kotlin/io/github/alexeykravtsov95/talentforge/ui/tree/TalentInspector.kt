package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * The M4.2 adaptive Talent Inspector — three presentations of the same real
 * content ([TalentInspectorContent]), all strictly read-only (no +/−, no
 * purchase/refund, no fake placeholders):
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
 * Content is intentionally sparse: only facts the dataset/engine publish.
 */

/** Portrait: the compact bottom inspector; `content == null` hides it entirely. */
@Composable
fun TalentInspectorCompactBar(
    content: TalentInspectorContent?,
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "expand inspector",
                                onClick = onToggleExpanded,
                            ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                        ) {
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
                        InspectorReasonLines(content = content, compact = true)
                    }
                    content.showAction?.let { action ->
                        ShowButton(action = action, onClick = { onShowPrerequisite(action.prerequisiteNodeId) })
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

/** Portrait: the expanded sheet over the canvas (B3 04/08). */
@Composable
fun TalentInspectorExpandedSheet(
    content: TalentInspectorContent,
    heightCap: Dp,
    onCollapse: () -> Unit,
    onDeselect: () -> Unit,
    onShowPrerequisite: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // UX/UI spec 04: "448 dp is the preferred height … capped at the
            // available height minus 96 dp … and scrolls internally". The M4.2
            // content is sparse by design, so the cap is an upper bound, not
            // an inflated fixed height.
            .heightIn(max = heightCap)
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
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            InspectorDetail(content = content, onShowPrerequisite = onShowPrerequisite)
        }
        if (content.showAction != null) {
            Spacer(Modifier.height(TalentForgeSpacing.inline))
        }
    }
}

/** Landscape: the 300 dp side panel with the full detail (B3 05). */
@Composable
fun TalentInspectorSidePanel(
    content: TalentInspectorContent,
    onDeselect: () -> Unit,
    onShowPrerequisite: (Int) -> Unit,
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
            modifier = Modifier.padding(vertical = TalentForgeSpacing.inline),
        ) {
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
    }
}

/**
 * Tablet: the persistent 380 dp inspector (UX/UI spec 13). With no selection
 * it renders the minimal empty prompt the spec requires; the wording is not
 * frozen by any design source, so it stays one honest implementation line.
 */
@Composable
fun TalentInspectorPersistentPanel(
    content: TalentInspectorContent?,
    onShowPrerequisite: (Int) -> Unit,
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
                modifier = Modifier.padding(vertical = TalentForgeSpacing.inline),
            ) {
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
        }
    }
}

// ------------------------------------------------------------------ shared internals

/** The full-detail facts of one selected node — sparse, real, nothing invented. */
@Composable
private fun InspectorDetail(
    content: TalentInspectorContent,
    onShowPrerequisite: (Int) -> Unit,
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
        content.showAction?.let { action ->
            ShowButton(action = action, onClick = { onShowPrerequisite(action.prerequisiteNodeId) })
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
