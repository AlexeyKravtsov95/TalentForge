package io.github.alexeykravtsov95.talentforge.ui.tree

/**
 * Canvas metrics of the Talent Tree, in dp unless stated otherwise.
 *
 * Every value that the design sources freeze is cited in place:
 * - `TalentForge_TalentTree_B3.pdf` pages 05–08 (selection ring, touch slop,
 *   connector treatment);
 * - `TalentForge_UX_UI_Spec_v1.pdf` section G (radius/spacing tokens);
 * - the approved M5.UX direction "1A v2 · Specialization Tabs" (M5.UX.1),
 *   sections S1 (sizes and touch targets) and S3 (camera, zoom, FIT). Since
 *   M5.UX.1 its node/pitch/zoom values replace the earlier B3 numbers
 *   (48 dp node on a 76 dp grid, 56 dp target, 0.8–2.2× zoom) — the task
 *   explicitly revises them together with hit testing.
 *
 * The two entries marked *implementation-safe* are not frozen by any spec
 * page; they are the smallest constants that keep the neutral node legible
 * and must not be treated as product semantics.
 */
object TalentTreeCanvasMetrics {

    /**
     * 1A v2 S1: "pitch = 80·s" — one client grid step (`layout.xStep`) in
     * world dp at scale 1. The client layout is square (`xStep == yStep ==
     * 600` in every Forever tree), so the same uniform — non-distorting —
     * factor maps both axes.
     */
    const val GRID_STEP_DP: Float = 80f

    /** 1A v2 S1: "size = 52·s" — the node visual side in world dp at scale 1. */
    const val NODE_VISUAL_DP: Float = 52f

    /**
     * 1A v2 S1: "hit = min(pitch − 2, max(48, size + 8))" — the screen-space
     * touch-target floor (48 dp, the B3 landscape/usable minimum).
     */
    const val NODE_TARGET_MIN_DP: Float = 48f

    /** 1A v2 S1: "size + 8" — the target grows 4 dp beyond the node on every side. */
    const val NODE_TARGET_PADDING_DP: Float = 8f

    /**
     * 1A v2 S1: "pitch − 2" — the target never reaches a neighbour: two
     * targets one pitch apart keep at least this gap, so they never overlap.
     */
    const val NODE_TARGET_PITCH_GAP_DP: Float = 2f

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

    /**
     * 1A v2 S3: "pinch around the focus, 0.62–1.6×, pitch ≥ 49.6 dp" — the lower
     * bound keeps the pitch at 80 × 0.62 = 49.6 dp, above the 48 dp target
     * floor plus its 2 dp gap.
     */
    const val ZOOM_MIN: Float = 0.62f

    /** 1A v2 S3: "pinch around the focus, 0.62–1.6×". */
    const val ZOOM_MAX: Float = 1.6f

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

    /** B3 06/08: the portrait reference renders the tree at 1× (phone cap, 1A v2 S3). */
    const val INITIAL_SCALE_PORTRAIT: Float = 1f

    /**
     * 1A v2 S3/05: "landscape 0.75 (node 39 dp, target 48)" — the landscape
     * scale cap, used as the initial landscape scale of the whole-tree canvas
     * (B3 05's 38 dp landscape node, within a dp).
     */
    const val INITIAL_SCALE_LANDSCAPE: Float = 0.75f

    /**
     * 1A v2 S3: "s = clamp(Wvis/354, 0.62, cap)" — the visible width at which
     * one section is shown at scale 1 when it is first opened.
     */
    const val SECTION_FIT_REFERENCE_WIDTH_DP: Float = 354f

    /**
     * 1A v2 S3 bounds: "the content never goes more than 12 dp past the
     * visible area" — the pan/zoom clamp keeps the content within this field of
     * the visible edges, so the tree can never be lost in empty canvas.
     */
    const val CAMERA_FIELD_DP: Float = 12f

    /** 1A v2 S3: FIT animates over 280 ms. */
    const val FIT_ANIMATION_MS: Int = 280

    /**
     * Implementation-safe: two cameras closer than this (dp of offset, or
     * ×0.001 of scale) are "at the fit" — FIT is hidden (1A v2 S3: shown only
     * when the camera differs from the fit).
     */
    const val CAMERA_EPSILON_DP: Float = 0.5f
}
