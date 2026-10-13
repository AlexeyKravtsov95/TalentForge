package io.github.alexeykravtsov95.talentforge.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens frozen by TalentForge_UX_UI_Spec_v1.pdf, section G.
 *
 * The spec defines them in OKLCH; the constants below are their exact sRGB
 * conversions (see [OklchConversions] for the shared math and the test that
 * pins every constant back to its spec value). Do not tweak these by eye —
 * change the spec first.
 */
object TalentForgeColors {

    /** oklch(0.16 0.008 260) */
    val background = Color(0xFF0B0D11)

    /** oklch(0.195 0.009 260) */
    val surface = Color(0xFF131519)

    /** oklch(0.225 0.011 260) */
    val surfaceElevated = Color(0xFF191C21)

    /** oklch(0.26 0.011 260) */
    val surfaceInteractive = Color(0xFF212429)

    /** oklch(0.28 0.01 260) */
    val borderDefault = Color(0xFF26292E)

    /** oklch(0.4 0.01 260) */
    val borderStrong = Color(0xFF45484D)

    /** oklch(0.95 0.005 260) */
    val textPrimary = Color(0xFFECEFF2)

    /** oklch(0.74 0.01 260) */
    val textSecondary = Color(0xFFA7ABB1)

    /** oklch(0.58 0.01 260) */
    val textMuted = Color(0xFF777A80)

    /**
     * The spec's single fixed class accent token: oklch(0.68 0.15 32) — the
     * Warrior accent. Per-class final numeric values for the remaining classes
     * are not yet frozen in a machine-readable source and are deliberately
     * not invented here.
     */
    val accentClassWarrior = Color(0xFFE6715B)

    /** oklch(0.97 0.01 90) — neutral white focus. */
    val focus = Color(0xFFF8F5EE)

    /** oklch(0.8 0.13 150) */
    val positive = Color(0xFF7CD591)

    /** oklch(0.84 0.12 75) */
    val warning = Color(0xFFF8BF6C)

    /** oklch(0.6 0.19 22) */
    val danger = Color(0xFFDA4149)

    /** oklch(0.42 0.01 260) */
    val disabled = Color(0xFF4A4D53)

    /** rgba(5, 6, 10, 0.55) */
    val scrim = Color(red = 5f / 255f, green = 6f / 255f, blue = 10f / 255f, alpha = 0.55f)

    // ------------------------------------------------------- M5.UI.1 (B+) additions
    //
    // The three constants below are NOT UX/UI-spec section G tokens. They are
    // frozen by the supplied M5.UI.1 asset integration specification
    // (`TalentForge_Class_Icons_Approved/INTEGRATION.md`), which is this
    // milestone's committed design source for the B+ class-artwork tiles, and
    // are pinned by `DesignTokenTest` exactly like the section G set.

    /**
     * oklch(0.83 0.12 68) — the B+ primary-action amber. Same value the B+
     * class-artwork spec uses for its accent spinner; distinct hue from
     * [warning] (75) so warning semantics stay unambiguous. Shell primary
     * actions only (`PrimaryActionButton`); never the editor.
     */
    val primaryAction = Color(0xFFFBB86D)

    /** `#3E424A` (≈ oklch 0.36 0.01 260) — the 1 dp inner hairline of a class-artwork tile. */
    val classArtworkHairline = Color(0xFF3E424A)

    /** `#16181C` — the reserved ground of a class-artwork tile. */
    val classArtworkGround = Color(0xFF16181C)
}
