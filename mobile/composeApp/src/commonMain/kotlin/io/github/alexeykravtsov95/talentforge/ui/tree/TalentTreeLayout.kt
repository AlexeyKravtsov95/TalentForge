package io.github.alexeykravtsov95.talentforge.ui.tree

/**
 * The three presentation layouts of the M4.2 adaptive inspector, derived from
 * the available area:
 *
 * - `TABLET` — UX/UI spec v1 page 13 (section H): at "Tablet · 840+ dp" the
 *   tree "runs full-screen with a persistent 380 dp inspector";
 * - `LANDSCAPE` — B3 05: the 300 dp side panel opens on selection;
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
 * treatment (840+ dp), independent of orientation.
 */
fun talentTreeLayoutClass(widthDp: Float, heightDp: Float): TalentTreeLayoutClass = when {
    widthDp >= TalentInspectorMetrics.TABLET_MIN_WIDTH_DP.value -> TalentTreeLayoutClass.TABLET
    widthDp >= heightDp -> TalentTreeLayoutClass.LANDSCAPE
    else -> TalentTreeLayoutClass.PORTRAIT
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
