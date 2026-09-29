package io.github.alexeykravtsov95.talentforge.ui.tree

/**
 * Canvas metrics of the read-only Talent Tree, in dp unless stated otherwise.
 *
 * Every value that the design sources freeze is cited in place:
 * - `TalentForge_TalentTree_B3.pdf` pages 05–08 (node/target/grid sizes,
 *   selection ring, touch slop, zoom bounds, connector treatment);
 * - `TalentForge_UX_UI_Spec_v1.pdf` section G (radius/spacing tokens).
 *
 * The two entries marked *implementation-safe* are not frozen by any spec
 * page; they are the smallest constants that keep the neutral read-only node
 * legible and must not be treated as product semantics.
 */
object TalentTreeCanvasMetrics {

    /**
     * B3 06: portrait grid "76 dp columns, 80 dp tiers". The client layout is
     * square (Warrior: `xStep == yStep == 600`), so a uniform — non-distorting —
     * mapping must choose a single pitch. The column pitch (76) is used for both
     * axes: it matches B3 horizontally exactly and still leaves 20 dp between
     * 56 dp touch targets vertically, which is B3's binding non-overlap rule.
     */
    const val GRID_STEP_DP: Float = 76f

    /** B3 05 landscape grid "62 × 56 dp"; only the ratio to the portrait grid matters here. */
    const val LANDSCAPE_GRID_STEP_DP: Float = 62f

    /** B3 06: "NODE 48 dp visual." */
    const val NODE_VISUAL_DP: Float = 48f

    /** B3 06: "TARGET 56 × 56 dp, centred on the node." */
    const val NODE_TARGET_DP: Float = 56f

    /** UX/UI spec section G: radius medium 12 — "buttons, fields, nodes, rows". */
    const val NODE_CORNER_RADIUS_DP: Float = 12f

    /**
     * Implementation-safe: neutral outline width of the read-only node. B3
     * freezes connector widths but no outline width for the unstyled node.
     */
    const val NODE_BORDER_DP: Float = 1.5f

    /** B3 06: "SELECTION RING 3 dp gap + 2.5 dp ring". */
    const val SELECTION_RING_GAP_DP: Float = 3f

    /** B3 06: "SELECTION RING 3 dp gap + 2.5 dp ring". */
    const val SELECTION_RING_WIDTH_DP: Float = 2.5f

    /** B3 06: "+ 6% scale". */
    const val SELECTION_SCALE: Float = 1.06f

    /** B3 07: "A 7 dp slop keeps taps as taps." */
    const val TOUCH_SLOP_DP: Float = 7f

    /** B3 07: "Pinch: zoom 0.8–2.2× around the fingers." */
    const val ZOOM_MIN: Float = 0.8f

    /** B3 07: "Pinch: zoom 0.8–2.2× around the fingers." */
    const val ZOOM_MAX: Float = 2.2f

    /**
     * B3 06: "CONNECTIONS … 2 dp solid when the prerequisite is met". The
     * read-only tree has no allocation state, so every connector uses this one
     * neutral width; no active-path (3 dp) or dashed treatment is invented.
     */
    const val CONNECTOR_WIDTH_DP: Float = 2f

    /** B3 06: "Lines fade to 30% with a selection." */
    const val CONNECTOR_SELECTED_ALPHA: Float = 0.3f

    /** UX/UI spec section G spacing: 16 dp screen margin. */
    const val CANVAS_START_MARGIN_DP: Float = 16f

    /** B3 06/08: the portrait reference renders the tree at 1×. */
    const val INITIAL_SCALE_PORTRAIT: Float = 1f

    /**
     * Landscape initial zoom derived from B3 05 ("nodes are 38 dp … on a
     * 62 × 56 dp grid"): 62 / 76 ≈ 0.816 renders the 48 dp world node at
     * ≈ 39 dp on screen and keeps the initial scale inside the 0.8–2.2 zoom
     * bounds.
     */
    val INITIAL_SCALE_LANDSCAPE: Float = LANDSCAPE_GRID_STEP_DP / GRID_STEP_DP
}
