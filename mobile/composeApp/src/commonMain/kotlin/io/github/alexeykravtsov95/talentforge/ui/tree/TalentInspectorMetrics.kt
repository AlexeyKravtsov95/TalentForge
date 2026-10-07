package io.github.alexeykravtsov95.talentforge.ui.tree

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
 *   sizes/caps, the 840 dp tablet class, expanded-sheet cap).
 *
 * Entries marked *implementation-safe* are not frozen by any spec page; they
 * are the smallest constants that keep the read-only presentation legible and
 * must not be treated as product semantics.
 */
object TalentInspectorMetrics {

    /** B3 06 CHROME: "Compact inspector 64 + 14 dp inset." */
    val COMPACT_HEIGHT_DP = 64.dp

    /** B3 06 CHROME: "Compact inspector 64 + 14 dp inset." */
    val COMPACT_INSET_DP = 14.dp

    /**
     * Implementation-safe (M5.V1): the talent icon in the compact bar. Not
     * frozen by a spec page; 36 dp fits the 64 dp bar with its padding and
     * leaves the bar's height unchanged.
     */
    val COMPACT_ICON_DP = 36.dp

    /**
     * Implementation-safe (M5.V1): the talent icon in the full-detail headers
     * (expanded sheet, side panel, tablet panel). 40 dp shows the 64 px client
     * texture below its native size on every density bucket ≥ 1.6×, and
     * modestly scaled on lower ones.
     */
    val DETAIL_ICON_DP = 40.dp

    /**
     * B3 06 CHROME / UX/UI spec 03: "Expanded sheet 448 dp" — the preferred
     * height, capped by the available height (see [SHEET_BOTTOM_CLEARANCE_DP]).
     */
    val EXPANDED_SHEET_PREFERRED_DP = 448.dp

    /**
     * UX/UI spec 04: the expanded sheet "is capped at the available height
     * minus 96 dp so the selection stays visible, and scrolls internally".
     */
    val SHEET_BOTTOM_CLEARANCE_DP = 96.dp

    /** B3 05 / UX/UI spec 03: "Landscape: 300 dp side panel." */
    val LANDSCAPE_PANEL_WIDTH_DP = 300.dp

    /** UX/UI spec 03/13: "Tablet: persistent 380 dp inspector." */
    val TABLET_PANEL_WIDTH_DP = 380.dp

    /**
     * UX/UI spec 13 (section H): "Tablet · 840+ dp" — the width at which the
     * persistent-inspector layout applies.
     */
    val TABLET_MIN_WIDTH_DP = 840.dp

    /** B3 08: "All buttons at least 44 × 44 dp" (the read-only Show action included). */
    val ACTION_MIN_DP = 44.dp

    /**
     * B3 06 RANK CHIP: "15 dp tall, 10.5 sp mono, overlapping the node's bottom
     * edge by 8 dp. Connections start below the chip."
     */
    val RANK_CHIP_HEIGHT_DP = 15.dp

    /** B3 06 RANK CHIP (see [RANK_CHIP_HEIGHT_DP]). */
    val RANK_CHIP_OVERLAP_DP = 8.dp

    /** B3 08: "Minimum text: … 12.5 sp for state lines." */
    const val STATE_LINE_SP: Float = 12.5f

    /** UX/UI spec G motion: "Show pan + pulse 300 · 900 ms. From B3." */
    const val SHOW_PAN_MS: Int = TalentForgeMotion.SHOW_PAN_MS

    /** UX/UI spec G motion: "Show pan + pulse 300 · 900 ms. From B3." */
    const val SHOW_PULSE_MS: Int = TalentForgeMotion.SHOW_PULSE_MS

    /** B3 07: the Show pan eases out over 300 ms. */
    const val SHOW_PAN_EASING = "ease-out"

    /**
     * B3 07 (prerequisite navigation): "The canvas stays still if the target
     * is fully visible above the inspector; otherwise the target is centred."
     * Implementation-safe clearance kept around the focused node: the 48 dp
     * node half plus the selection-ring outset (3 + 2.5 dp) and touch slop.
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
     * 13 node size (56 dp on screen / 48 dp world node ≈ 1.167) — inside the
     * B3 0.8–2.2 zoom bounds.
     */
    val INITIAL_SCALE_TABLET: Float = TABLET_NODE_VISUAL_DP / TalentTreeCanvasMetrics.NODE_VISUAL_DP
}
