package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeMotion

/**
 * Inspector and node-state metrics of the M4.2 adaptive Talent Inspector, in
 * dp unless stated otherwise.
 *
 * Every value the design sources freeze is cited in place:
 * - `TalentForge_TalentTree_B3.pdf` pages 03–08 (inspector chrome, rank chip,
 *   connection treatments, Show motion);
 * - `TalentForge_UX_UI_Spec_v1.pdf` pages 03–04 and 13 (adaptive inspector
 *   sizes/caps, the 840 dp tablet class, expanded-sheet cap);
 * - the approved M5.UX direction "1A v2 · Specialization Tabs"
 *   (`Talent_tree_direction_comparison.pdf`, pages 02–03 and S1/S3/S8) for the
 *   portrait compact strip and the content-sized sheet since M5.UX.2a — it
 *   revises the B3 compact card and the 448 dp sheet for portrait phones.
 *
 * Entries marked *implementation-safe* are not frozen by any spec page; they
 * are the smallest constants that keep the read-only presentation legible and
 * must not be treated as product semantics.
 */
object TalentInspectorMetrics {

    /**
     * 1A v2 S1 / 02: "Полоса 76 dp плюс safe area: строка 64 dp" — the row of
     * the compact strip (B3 06 froze the same 64 dp).
     */
    val COMPACT_HEIGHT_DP = 64.dp

    /** 1A v2 S1: "12 dp ручка + 64 dp строка" — the grab handle above the row. */
    val COMPACT_HANDLE_DP = 12.dp

    /**
     * 1A v2 02: the whole docked strip, handle + row = 76 dp (the system
     * safe area below it is applied by the screen root). M5.UX.2a replaces
     * the floating B3 card (64 dp + 2 × 14 dp inset).
     */
    val COMPACT_STRIP_DP = 76.dp

    /** Implementation-safe: the strip's horizontal padding (1A v2 draws ≈ 12 dp). */
    val COMPACT_PADDING_X_DP = 12.dp

    /** 1A v2 S1: "Иконка 44" — the talent icon of the compact strip and the sheet. */
    val COMPACT_ICON_DP = 44.dp

    /**
     * Implementation-safe (M5.V1): the talent icon in the side and tablet
     * panel headers. 40 dp shows the 64 px client texture below its native
     * size on every density bucket ≥ 1.6× (those panels are M5.UX.2b).
     */
    val DETAIL_ICON_DP = 40.dp

    /**
     * 1A v2 02: "Развёрнутый лист подстраивается под контент: минимум 260 dp"
     * (S9: `heightIn(min = 260.dp, max = …)`). See [expandedSheetLimits].
     */
    val EXPANDED_SHEET_MIN_DP = 260.dp

    /** 1A v2 02: "максимум min(66% H, H − top − 104)" — the 66 % of the screen height. */
    const val EXPANDED_SHEET_MAX_SCREEN_FRACTION: Float = 0.66f

    /**
     * 1A v2 02: "… H − top − 104" — the canvas height that always stays
     * visible above the sheet (the row of the selected talent, 1A v2 03 3.2).
     */
    val EXPANDED_SHEET_CANVAS_RESERVE_DP = 104.dp

    /** 1A v2 S1: "Развёрнутый · Шапка 48" — the sheet's top bar (⌄ · handle · ×). */
    val SHEET_HEADER_DP = 48.dp

    /** 1A v2 S1: "поля 18 dp" — the sheet's horizontal padding. */
    val SHEET_PADDING_X_DP = 18.dp

    /** 1A v2 S1: Back, Undo, Redo, menu and the sheet's ⌄ / × are 48 × 48 dp targets. */
    val SHEET_CONTROL_DP = 48.dp

    /**
     * Implementation-safe (M5.UX.2a): the compact strip keeps the 1A v2 02
     * single row `[icon] name rank / state [actions]` while its text column
     * keeps at least this width per font scale (`width ÷ fontScale`) next to
     * the widest action cluster — 1A v2 03 keeps one row at 320 dp and 130 %.
     * Below it, and at very large text ([COMPACT_STACK_FONT_SCALE]), the row
     * grows to two lines (1A v2 S8 "Шрифт 200%: компактная строка до 2 строк").
     */
    val COMPACT_TEXT_MIN_DP = 88.dp

    /** 1A v2 S8: from this font scale on the compact row always uses its two-line form. */
    const val COMPACT_STACK_FONT_SCALE: Float = 1.75f

    /**
     * The fixed part of the single compact row: icon 44 + gap 8 + the widest
     * cluster (− 48 + 4 + + 56) + gap 8.
     */
    val COMPACT_FIXED_ROW_DP = 168.dp

    /** B3 05 / UX/UI spec 03: "Landscape: 300 dp side panel." */
    val LANDSCAPE_PANEL_WIDTH_DP = 300.dp

    /** UX/UI spec 03/13: "Tablet: persistent 380 dp inspector." */
    val TABLET_PANEL_WIDTH_DP = 380.dp

    /**
     * UX/UI spec 13 (section H): "Tablet · 840+ dp" — the width at which the
     * persistent-inspector layout applies.
     */
    val TABLET_MIN_WIDTH_DP = 840.dp

    /**
     * M5.UX.2b.1, implementation-safe (Material's compact-height window class
     * ends at 480 dp; the design sources freeze no value): a canvas region
     * shorter than this is a phone held sideways — the spec's separate "Phone
     * landscape" column — even when it is 840+ dp wide, as most modern phones
     * are in landscape (e.g. 915 × 412, 932 × 430).
     */
    val TABLET_MIN_HEIGHT_DP = 480.dp

    /** B3 08: "All buttons at least 44 × 44 dp" (the read-only Show action included). */
    val ACTION_MIN_DP = 44.dp

    /** 1A v2 S1: the compact strip's action height (− 48 × 48, + 56 × 48, Show, tier chip). */
    val COMPACT_ACTION_DP = 48.dp

    /**
     * B3 06 RANK CHIP: "15 dp tall, 10.5 sp mono, overlapping the node's bottom
     * edge by 8 dp. Connections start below the chip."
     */
    val RANK_CHIP_HEIGHT_DP = 15.dp

    /** B3 06 RANK CHIP (see [RANK_CHIP_HEIGHT_DP]). */
    val RANK_CHIP_OVERLAP_DP = 8.dp

    /** B3 06 RANK CHIP text size: "10.5 sp mono". */
    const val RANK_CHIP_FONT_SP: Float = 10.5f

    /**
     * TF-RC1-BADGE-200: the font scale at which the chip text stops growing,
     * so its line stays inside the fixed 15 dp chip. The line is the em box
     * where the platform honours it (Android), but at least the font's own
     * height (≈ 1.16 em on desktop/Skia): 10.5 sp × 1.2 × 1.16 ≈ 14.6 dp.
     * Above it the text is held instead of being cut; the B3 geometry (chip
     * height, connections starting below it) stays fixed.
     */
    const val RANK_CHIP_MAX_FONT_SCALE: Float = 1.2f

    /** B3 08: "Minimum text: … 12.5 sp for state lines." */
    const val STATE_LINE_SP: Float = 12.5f

    /** UX/UI spec G motion: "Show pan + pulse 300 · 900 ms. From B3." */
    const val SHOW_PAN_MS: Int = TalentForgeMotion.SHOW_PAN_MS

    /**
     * 1A v2 S3: "При выделении и смене высоты листа узел подтягивается в
     * видимую область (260 мс)" — the inspector-driven reveal pan.
     */
    const val REVEAL_PAN_MS: Int = 260

    /** UX/UI spec G motion: "Show pan + pulse 300 · 900 ms. From B3." */
    const val SHOW_PULSE_MS: Int = TalentForgeMotion.SHOW_PULSE_MS

    /** B3 07: the Show pan eases out over 300 ms. */
    const val SHOW_PAN_EASING = "ease-out"

    /**
     * B3 07 (prerequisite navigation): "The canvas stays still if the target
     * is fully visible above the inspector; otherwise the target is centred."
     * Implementation-safe clearance kept around the focused node: the node
     * half plus the selection-ring outset (3 + 2.5 dp) and touch slop.
     */
    val FOCUS_CLEARANCE_DP: Float =
        TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f +
            TalentTreeCanvasMetrics.SELECTION_RING_GAP_DP +
            TalentTreeCanvasMetrics.SELECTION_RING_WIDTH_DP +
            TalentTreeCanvasMetrics.TOUCH_SLOP_DP

    /** B3 06 CONNECTIONS: "3 dp solid for the active path". */
    const val CONNECTOR_ACTIVE_WIDTH_DP: Float = 3f

    /** B3 06 CONNECTIONS: "2 dp dashed when inactive". */
    const val CONNECTOR_INACTIVE_WIDTH_DP: Float = 2f

    /** B3 06 CONNECTIONS: "Lines fade … to 18% during Show." */
    const val CONNECTOR_SHOW_FADE_ALPHA: Float = 0.18f

    /** B3 06: dashed connector/border stroke pattern (dash, gap). */
    const val DASH_STROKE_DP: Float = 5f

    /** B3 06: dashed connector/border stroke pattern (dash, gap). */
    const val DASH_GAP_DP: Float = 4f

    /** UX/UI spec 13 (section H): tablet nodes are "56 dp with 68 dp targets". */
    const val TABLET_NODE_VISUAL_DP: Float = 56f

    /** UX/UI spec 13 (section H): tablet targets are 68 dp. */
    const val TABLET_NODE_TARGET_DP: Float = 68f

    /**
     * Initial canvas scale of the tablet layout, derived from the UX/UI spec
     * 13 node size (56 dp on screen / 52 dp world node ≈ 1.077) — inside the
     * 1A v2 S3 0.62–1.6 zoom bounds and below its 1.1 tablet cap.
     */
    val INITIAL_SCALE_TABLET: Float = TABLET_NODE_VISUAL_DP / TalentTreeCanvasMetrics.NODE_VISUAL_DP
}

/**
 * M5.UX.2a height limits of the content-sized expanded sheet. The sheet wraps
 * its content between [min] and [max]; content taller than [max] scrolls
 * inside the sheet while the actions stay pinned.
 */
data class TalentInspectorSheetLimits(val min: Dp, val max: Dp)

/**
 * 1A v2 02: `max = min(66 % H, H − top − 104)` and `min = 260 dp`, where
 * [screenHeight] is H (the editor's height inside the system safe area) and
 * [areaHeight] is `H − top` (the canvas region below the header and tabs the
 * sheet overlays). `min` never exceeds `max` and neither is negative, so a
 * very short screen degrades to a smaller, still scrolling sheet.
 */
fun expandedSheetLimits(screenHeight: Dp, areaHeight: Dp): TalentInspectorSheetLimits {
    val max = minOf(
        screenHeight * TalentInspectorMetrics.EXPANDED_SHEET_MAX_SCREEN_FRACTION,
        areaHeight - TalentInspectorMetrics.EXPANDED_SHEET_CANVAS_RESERVE_DP,
    ).coerceAtLeast(0.dp)
    return TalentInspectorSheetLimits(min = minOf(TalentInspectorMetrics.EXPANDED_SHEET_MIN_DP, max), max = max)
}

/** The two arrangements of the compact strip (M5.UX.2a). */
enum class TalentInspectorCompactLayout {
    /** 1A v2 02: `[icon] name rank / state [actions]` in one 64 dp row. */
    SINGLE_ROW,

    /** 1A v2 S8 large text / very narrow: `[icon] name rank` over `state [actions]`. */
    TWO_LINE,
}

/**
 * Picks the compact arrangement from the strip's width and the font scale
 * (see [TalentInspectorMetrics.COMPACT_TEXT_MIN_DP]). Pure: the same inputs
 * always give the same layout, whichever talent is selected.
 */
fun talentInspectorCompactLayout(stripWidth: Dp, fontScale: Float): TalentInspectorCompactLayout {
    val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
    val textColumn = stripWidth - TalentInspectorMetrics.COMPACT_PADDING_X_DP * 2 - TalentInspectorMetrics.COMPACT_FIXED_ROW_DP
    return if (scale >= TalentInspectorMetrics.COMPACT_STACK_FONT_SCALE ||
        textColumn / scale < TalentInspectorMetrics.COMPACT_TEXT_MIN_DP
    ) {
        TalentInspectorCompactLayout.TWO_LINE
    } else {
        TalentInspectorCompactLayout.SINGLE_ROW
    }
}

/**
 * The font scale the rank chip text uses for a system [fontScale]: the system
 * scale up to [TalentInspectorMetrics.RANK_CHIP_MAX_FONT_SCALE], so the text
 * grows with large text but never past the chip it sits on (TF-RC1-BADGE-200).
 * The full rank stays readable at any text size in the inspector, and the
 * canvas itself zooms.
 */
fun rankChipFontScale(fontScale: Float): Float {
    val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
    return minOf(scale, TalentInspectorMetrics.RANK_CHIP_MAX_FONT_SCALE)
}
