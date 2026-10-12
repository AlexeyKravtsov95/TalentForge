package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/**
 * The M4.3 explicit rank-action controls (B3 03/04/06/07, UX/UI spec 03):
 * spending and refunding happen ONLY through these visible buttons — a node
 * tap never spends.
 *
 * - compact inspector (B3 07): Available `[+]`, Partial `[− +]`, Maxed `[− ✓]`
 *   (✓ is a badge, not a button), No points `[− if ranked] [＋ disabled]`;
 *   "There is no Max in the compact bar";
 * - expanded sheet / landscape side panel / tablet persistent panel (full
 *   detail): the `− + Rank Max` action row (UX/UI spec 03: "Max in the
 *   expanded sheet"), actions pinned at the bottom.
 *
 * Enabled state comes from the engine-backed [TalentNodeActions]
 * (`canSpend`/`canRefund`), never from the visual node state alone; a click is
 * still re-checked against the engine result by the screen (authoritative).
 */
object TalentInspectorActionMetrics {

    /** B3 06 CHROME: "+ is 48 × 44". */
    val SPEND_WIDTH_DP = 48.dp

    /** B3 08: "All buttons at least 44 × 44 dp". */
    val ACTION_SIZE_DP = 44.dp

    /** 1A v2 S1 compact strip: "− 48×48". */
    val COMPACT_REFUND_DP = 48.dp

    /** 1A v2 S1 compact strip: "+ 56×48". */
    val COMPACT_SPEND_WIDTH_DP = 56.dp

    /** 1A v2 S1 compact strip: the action height (48 dp). */
    val COMPACT_HEIGHT_DP = 48.dp

    /** 1A v2 S1 expanded sheet: "− 56×52". */
    val SHEET_REFUND_WIDTH_DP = 56.dp

    /** 1A v2 S1 expanded sheet: "основная flex×52, Max 68×52" — the row height. */
    val SHEET_HEIGHT_DP = 52.dp

    /** 1A v2 S1 expanded sheet: "Max 68×52". */
    val SHEET_MAX_WIDTH_DP = 68.dp
}

/**
 * The B3 compact action cluster of the selected node: `− ?` then `+`/`✓`.
 * Rendered only when the state offers rank controls (locked
 * tiers/prerequisites keep their meter/`Show`; unsupported nodes get nothing).
 */
@Composable
fun TalentInspectorCompactActions(
    talentName: String,
    actions: TalentNodeActions,
    accent: androidx.compose.ui.graphics.Color,
    onSpend: (Int) -> Unit,
    onRefund: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline),
    ) {
        // B3 07: "No points [− if ranked, + disabled]" — the refund control
        // stays whenever ranks exist (PARTIAL/MAXED; NO_POINTS always has
        // rank 0 by the state precedence, so it never shows −).
        val height = TalentInspectorActionMetrics.COMPACT_HEIGHT_DP
        if (actions.rank > 0) {
            RefundButton(
                talentName = talentName,
                enabled = actions.canRefund,
                onClick = { onRefund(actions.nodeId) },
                width = TalentInspectorActionMetrics.COMPACT_REFUND_DP,
                height = height,
            )
        }
        if (actions.isMaxed) {
            // B3 03: "+ becomes a check badge (not a button)".
            MaxedBadge(width = TalentInspectorActionMetrics.COMPACT_REFUND_DP, height = height)
        } else {
            SpendButton(
                talentName = talentName,
                enabled = actions.canSpend,
                accent = accent,
                onClick = { onSpend(actions.nodeId) },
                width = TalentInspectorActionMetrics.COMPACT_SPEND_WIDTH_DP,
                height = height,
            )
        }
    }
}

/**
 * The full-detail action row `− + Rank Max` (B3 04/08 sheet, landscape side
 * panel, tablet persistent panel). `Rank` captions the − / + pair; `Max`
 * (UX/UI spec 03) is one atomic spend-to-max step. For a maxed talent the +
 * becomes the non-interactive ✓ badge and Max disables.
 */
@Composable
fun TalentInspectorRankActions(
    talentName: String,
    actions: TalentNodeActions,
    accent: androidx.compose.ui.graphics.Color,
    onSpend: (Int) -> Unit,
    onRefund: (Int) -> Unit,
    onMax: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        RefundButton(
            talentName = talentName,
            enabled = actions.canRefund,
            onClick = { onRefund(actions.nodeId) },
        )
        if (actions.isMaxed) {
            MaxedBadge()
        } else {
            SpendButton(
                talentName = talentName,
                enabled = actions.canSpend,
                accent = accent,
                onClick = { onSpend(actions.nodeId) },
            )
        }
        BasicText(
            text = "Rank",
            style = InspectorActionCaptionStyle.copy(color = talentForgeColors().textMuted),
            modifier = Modifier.padding(horizontal = TalentForgeSpacing.hairline),
        )
        MaxButton(
            talentName = talentName,
            enabled = actions.canSpend && !actions.isMaxed,
            onClick = { onMax(actions.nodeId) },
        )
    }
}

/**
 * The expanded sheet's pinned action row (1A v2 02 / S1): `−` 56 × 52, the
 * primary `+ Rank` taking the remaining width × 52 (the ✓ badge once maxed)
 * and `Max` 68 × 52. Same engine-backed enabled states and accessibility
 * labels as every other rank control; the side and tablet panels keep
 * [TalentInspectorRankActions] until M5.UX.2b.
 */
@Composable
fun TalentInspectorSheetRankActions(
    talentName: String,
    actions: TalentNodeActions,
    accent: androidx.compose.ui.graphics.Color,
    onSpend: (Int) -> Unit,
    onRefund: (Int) -> Unit,
    onMax: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val height = TalentInspectorActionMetrics.SHEET_HEIGHT_DP
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        RefundButton(
            talentName = talentName,
            enabled = actions.canRefund,
            onClick = { onRefund(actions.nodeId) },
            width = TalentInspectorActionMetrics.SHEET_REFUND_WIDTH_DP,
            height = height,
        )
        if (actions.isMaxed) {
            MaxedBadge(width = null, height = height, modifier = Modifier.weight(1f))
        } else {
            SpendButton(
                talentName = talentName,
                enabled = actions.canSpend,
                accent = accent,
                onClick = { onSpend(actions.nodeId) },
                width = null,
                height = height,
                // 1A v2 S8 very large text: the glyph alone (the label still names the action)
                caption = if (LocalDensity.current.fontScale >= TalentInspectorMetrics.COMPACT_STACK_FONT_SCALE) null else "Rank",
                modifier = Modifier.weight(1f),
            )
        }
        MaxButton(
            talentName = talentName,
            enabled = actions.canSpend && !actions.isMaxed,
            onClick = { onMax(actions.nodeId) },
            // 68 × 52 at 100 % text; grows with a larger font instead of clipping "Max"
            modifier = Modifier
                .height(height)
                .widthIn(min = TalentInspectorActionMetrics.SHEET_MAX_WIDTH_DP),
        )
    }
}

// ------------------------------------------------------------------ buttons

/**
 * The one spend control: `+`, 48 × 44 dp, class accent (B3 03: "One action: +
 * (48 dp, class accent)"). Visible but disabled when spending is denied
 * (e.g. no points left — B3 04 state 07).
 */
@Composable
fun SpendButton(
    talentName: String,
    enabled: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = TalentInspectorActionMetrics.SPEND_WIDTH_DP,
    height: Dp = TalentInspectorActionMetrics.ACTION_SIZE_DP,
    caption: String? = null,
) {
    val colors = talentForgeColors()
    val background = if (enabled) accent else colors.surfaceInteractive
    val glyphColor = if (enabled) colors.background else colors.disabled
    Box(
        modifier = modifier
            .actionSize(width, height)
            .background(background, RoundedCornerShape(TalentForgeRadius.medium))
            .rankActionSemantics(
                label = "Add rank to $talentName",
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(text = "+", style = InspectorGlyphStyle.copy(color = glyphColor))
            // 1A v2 02: the sheet's primary action reads "+ Rank"
            if (caption != null) {
                BasicText(
                    text = caption,
                    style = InspectorActionTextStyle.copy(color = glyphColor),
                    maxLines = 1,
                    modifier = Modifier.padding(start = TalentForgeSpacing.hairline),
                )
            }
        }
    }
}

/**
 * The one refund control: `−`, ≥ 44 dp, neutral surface (no spec page freezes
 * its fill; neutral keeps the accent reserved for invested points — UX/UI
 * spec G). Enabled iff `engine.canRefund(...).allowed` (a blocked dependent
 * disables it instead of cascading).
 */
@Composable
fun RefundButton(
    talentName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TalentInspectorActionMetrics.ACTION_SIZE_DP,
    height: Dp = TalentInspectorActionMetrics.ACTION_SIZE_DP,
) {
    val colors = talentForgeColors()
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .background(colors.surfaceInteractive, RoundedCornerShape(TalentForgeRadius.medium))
            .rankActionSemantics(
                label = "Remove rank from $talentName",
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "−",
            style = InspectorGlyphStyle.copy(
                color = if (enabled) colors.textPrimary else colors.disabled,
            ),
        )
    }
}

/**
 * `Max` of the full-detail row: spends every rank the engine still allows as
 * ONE atomic history step. Disabled once maxed or when spending is denied.
 */
@Composable
fun MaxButton(
    talentName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    Box(
        modifier = modifier
            .heightIn(min = TalentInspectorActionMetrics.ACTION_SIZE_DP)
            .background(colors.surfaceInteractive, RoundedCornerShape(TalentForgeRadius.medium))
            .rankActionSemantics(
                label = "Max out $talentName",
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = TalentForgeSpacing.rowGap),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "Max",
            style = InspectorActionTextStyle.copy(
                color = if (enabled) colors.textPrimary else colors.disabled,
            ),
        )
    }
}

/**
 * B3 03/04: at max rank "+ becomes a check badge (not a button)" — a plain
 * non-interactive completion mark layered into the action row.
 */
@Composable
fun MaxedBadge(
    modifier: Modifier = Modifier,
    width: Dp? = TalentInspectorActionMetrics.ACTION_SIZE_DP,
    height: Dp = TalentInspectorActionMetrics.ACTION_SIZE_DP,
) {
    val colors = talentForgeColors()
    Box(
        modifier = modifier
            .actionSize(width, height)
            .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.medium))
            .semantics(mergeDescendants = true) { contentDescription = "Max rank reached" },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = "✓", style = InspectorGlyphStyle.copy(color = colors.positive))
    }
}

/** A fixed [width] × [height] action, or — `width == null` — a flexible one sized by its parent (1A v2 "flex"). */
private fun Modifier.actionSize(width: Dp?, height: Dp): Modifier =
    if (width != null) size(width = width, height = height) else height(height)

/** Shared semantics of a rank-action button: labelled, roled, disabled-aware. */
private fun Modifier.rankActionSemantics(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = if (enabled) {
    clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }
} else {
    semantics(mergeDescendants = true) {
        role = Role.Button
        disabled()
        contentDescription = label
    }
}

// ------------------------------------------------------------------ typography

/** Glyph register of the + / − / ✓ controls. */
private val InspectorGlyphStyle = TextStyle(
    fontSize = 20.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.SemiBold,
)

/** Text register of the `Max` button. */
private val InspectorActionTextStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

private val InspectorActionCaptionStyle = TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
)
