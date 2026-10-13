package io.github.alexeykravtsov95.talentforge.ui.tree

/**
 * The three presentation layouts of the M4.2 adaptive inspector, derived from
 * the available area:
 *
 * - `TABLET` — UX/UI spec v1 page 13 (section H): at "Tablet · 840+ dp" the
 *   tree "runs full-screen with a persistent 380 dp inspector";
 * - `LANDSCAPE` — B3 05: the 300 dp side panel opens on selection (a phone
 *   held sideways; since M5.UX.2b.1 one section per tab, like portrait);
 * - `PORTRAIT` — B3 03: the compact bottom inspector appears on selection.
 *
 * Pure classifier shared by the screen layout, the canvas metrics and tests.
 */
enum class TalentTreeLayoutClass {
    PORTRAIT,
    LANDSCAPE,
    TABLET,
}

/**
 * Classifies an available area (dp). The tablet width class wins over the
 * aspect-ratio classes because the persistent inspector is a width-driven
 * treatment (840+ dp), independent of orientation — provided the area is also
 * tall enough to be a tablet (M5.UX.2b.1: [TalentInspectorMetrics.TABLET_MIN_HEIGHT_DP]);
 * a wide but short area is a phone in landscape.
 */
fun talentTreeLayoutClass(widthDp: Float, heightDp: Float): TalentTreeLayoutClass = when {
    widthDp >= TalentInspectorMetrics.TABLET_MIN_WIDTH_DP.value &&
        heightDp >= TalentInspectorMetrics.TABLET_MIN_HEIGHT_DP.value -> TalentTreeLayoutClass.TABLET
    widthDp >= heightDp -> TalentTreeLayoutClass.LANDSCAPE
    else -> TalentTreeLayoutClass.PORTRAIT
}

/**
 * M5.UX.2b.1: classifies the editor from its WHOLE workspace (header included),
 * so the header and the canvas region below it always agree on one class: the
 * region is the workspace minus the nominal header height
 * ([TalentTreeHeaderMetrics.HEADER_HEIGHT_DP]).
 */
fun talentTreeWorkspaceLayoutClass(workspaceWidthDp: Float, workspaceHeightDp: Float): TalentTreeLayoutClass =
    talentTreeLayoutClass(
        widthDp = workspaceWidthDp,
        heightDp = workspaceHeightDp - TalentTreeHeaderMetrics.HEADER_HEIGHT_DP.value,
    )

/** Where a landscape phone shows the section tabs (M5.UX.2b.1). */
enum class TalentTreeLandscapeTabsPlacement {
    /** B3 05: "The two portrait rows merge into one 48 dp bar: title, spec tabs centred, undo / redo, 18 / 51." */
    IN_HEADER,

    /** Too narrow for one bar (compact phone, cutout, large text): the portrait tab row below the header. */
    BELOW_HEADER,
}

/** The landscape tab placement and, in the header, the width the tabs get. */
data class TalentTreeLandscapeTabs(
    val placement: TalentTreeLandscapeTabsPlacement,
    val tabsWidthDp: Float,
)

/**
 * Metrics of the merged landscape header (M5.UX.2b.1). B3 05/08 fix the one
 * 48 dp bar; the reserves below are implementation-safe estimates of what the
 * header's other content needs, so the merged bar is chosen only when no
 * control, the counter or a readable title would be squeezed.
 */
object TalentTreeLandscapeHeaderMetrics {
    /** B3 08 "Landscape behaviour: One 48 dp header holding the title, tabs, undo / redo and points." */
    const val HEADER_HEIGHT_DP: Float = 48f

    /** Back, undo, redo and ⋯ (48 dp each, 1A v2 S1) plus the two 4 dp edges. */
    const val CONTROLS_DP: Float = 4f * 48f + 2f * 4f

    /** The `51 / 51` counter (mono 16 sp) at 100 % font; scales with the font. */
    const val POINTS_DP: Float = 72f

    /** The smallest title column kept readable (name + meta, ellipsized) at 100 % font. */
    const val TITLE_MIN_DP: Float = 120f

    /** The title reserve stops growing above this font scale (the title ellipsizes). */
    const val TITLE_MAX_FONT_SCALE: Float = 1.5f

    /** Preferred width of one tab in the header (B3 05 "Arms 15 · Fury 5 · Protection 0" spacing). */
    const val PREFERRED_TAB_DP: Float = 104f

    /** Gap between the tabs and the undo control. */
    const val TABS_GAP_DP: Float = 8f
}

/**
 * M5.UX.2b.1: where a landscape phone of [widthDp] (the safe-area width) at
 * [fontScale] shows [tabCount] section tabs. The merged B3 bar is used when
 * every tab keeps the 1A v2 S8 minimum ([TalentTreeSectionTabsMetrics.TAB_MIN_WIDTH_DP],
 * scaled with the font until the number-only tabs at
 * [TalentTreeSectionTabsMetrics.NUMBER_ONLY_FONT_SCALE]) next to the controls,
 * the counter and a readable title; otherwise the tabs keep their own row
 * below the header, exactly as in portrait. In the header the tabs grow up
 * to [TalentTreeLandscapeHeaderMetrics.PREFERRED_TAB_DP] each.
 */
fun talentTreeLandscapeTabs(widthDp: Float, fontScale: Float, tabCount: Int): TalentTreeLandscapeTabs {
    val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
    val tabScale = if (scale >= TalentTreeSectionTabsMetrics.NUMBER_ONLY_FONT_SCALE) 1f else scale.coerceAtLeast(1f)
    val metrics = TalentTreeLandscapeHeaderMetrics
    val available = widthDp - metrics.CONTROLS_DP - metrics.POINTS_DP * scale - metrics.TABS_GAP_DP -
        metrics.TITLE_MIN_DP * scale.coerceIn(1f, metrics.TITLE_MAX_FONT_SCALE)
    val tabsMin = tabCount * TalentTreeSectionTabsMetrics.TAB_MIN_WIDTH_DP.value * tabScale
    if (tabCount <= 0 || !available.isFinite() || available < tabsMin) {
        return TalentTreeLandscapeTabs(TalentTreeLandscapeTabsPlacement.BELOW_HEADER, 0f)
    }
    val preferred = tabCount * metrics.PREFERRED_TAB_DP * tabScale
    return TalentTreeLandscapeTabs(TalentTreeLandscapeTabsPlacement.IN_HEADER, minOf(available, preferred))
}

/**
 * The active read-only prerequisite guide of a Show navigation (B3 05 "After
 * Show"): the [originNodeId] the user navigated *from* (keeps a dashed ring)
 * and the [targetNodeId] prerequisite now selected (ring + one-shot pulse,
 * bright path between them). Cleared by the next selection, a deselect or a
 * spec change; it never spends and never alters the build.
 */
data class TalentShowGuide(
    val originNodeId: Int,
    val targetNodeId: Int,
)
