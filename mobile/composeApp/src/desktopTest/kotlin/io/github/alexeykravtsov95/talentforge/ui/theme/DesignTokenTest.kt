package io.github.alexeykravtsov95.talentforge.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins every colour constant back to the OKLCH values published in
 * TalentForge_UX_UI_Spec_v1.pdf (section G), so the sRGB constants cannot
 * drift from the design source of truth.
 */
class DesignTokenTest {

    private val specTokens: Map<Long, Triple<Double, Double, Double>> = mapOf(
        TalentForgeColors.background.toArgbLong() to Triple(0.16, 0.008, 260.0),
        TalentForgeColors.surface.toArgbLong() to Triple(0.195, 0.009, 260.0),
        TalentForgeColors.surfaceElevated.toArgbLong() to Triple(0.225, 0.011, 260.0),
        TalentForgeColors.surfaceInteractive.toArgbLong() to Triple(0.26, 0.011, 260.0),
        TalentForgeColors.borderDefault.toArgbLong() to Triple(0.28, 0.01, 260.0),
        TalentForgeColors.borderStrong.toArgbLong() to Triple(0.4, 0.01, 260.0),
        TalentForgeColors.textPrimary.toArgbLong() to Triple(0.95, 0.005, 260.0),
        TalentForgeColors.textSecondary.toArgbLong() to Triple(0.74, 0.01, 260.0),
        TalentForgeColors.textMuted.toArgbLong() to Triple(0.58, 0.01, 260.0),
        TalentForgeColors.accentClassWarrior.toArgbLong() to Triple(0.68, 0.15, 32.0),
        TalentForgeColors.focus.toArgbLong() to Triple(0.97, 0.01, 90.0),
        TalentForgeColors.positive.toArgbLong() to Triple(0.8, 0.13, 150.0),
        TalentForgeColors.warning.toArgbLong() to Triple(0.84, 0.12, 75.0),
        TalentForgeColors.danger.toArgbLong() to Triple(0.6, 0.19, 22.0),
        TalentForgeColors.disabled.toArgbLong() to Triple(0.42, 0.01, 260.0),
        // M5.UI.1 B+ additions — source: the supplied class-artwork
        // integration specification, not UX/UI-spec section G.
        TalentForgeColors.primaryAction.toArgbLong() to Triple(0.83, 0.12, 68.0),
    )

    /** Hex constants the B+ asset spec states directly (no OKLCH source). */
    @Test
    fun bPlusClassArtworkConstantsMatchTheAssetIntegrationSpec() {
        assertEquals(0xFF3E424AL, TalentForgeColors.classArtworkHairline.toArgbLong())
        assertEquals(0xFF16181CL, TalentForgeColors.classArtworkGround.toArgbLong())
    }

    @Test
    fun colourConstantsMatchSpecOklchValues() {
        for ((argb, oklch) in specTokens) {
            val (lightness, chroma, hue) = oklch
            val expected = OklchConversions.oklchToArgbHex(lightness, chroma, hue)
            assertEquals(
                expected,
                argb,
                "token with oklch($lightness $chroma ${hue.toInt()}) drifted from the spec",
            )
        }
    }

    @Test
    fun warriorAccentUsesTheSpecAccentClassToken() {
        assertEquals(TalentForgeColors.accentClassWarrior, WarriorClassAccent.color)
    }

    private fun androidx.compose.ui.graphics.Color.toArgbLong(): Long =
        (value shr 32).toLong() and 0xFFFFFFFFL
}
