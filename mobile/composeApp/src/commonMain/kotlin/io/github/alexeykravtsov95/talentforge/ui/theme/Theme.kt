package io.github.alexeykravtsov95.talentforge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Theme wiring for the production UI. The design system is the frozen token
 * set from TalentForge_UX_UI_Spec_v1.pdf section G: colours, spacing, radius,
 * motion and the typography roles below.
 *
 * Material 3 is deliberately absent: CMP publishes material3 only in
 * pre-release form, and this project accepts no pre-release dependencies.
 */
private val LocalTalentForgeColors = staticCompositionLocalOf { TalentForgeColors }

/** Typography roles frozen by TalentForge_UX_UI_Spec_v1.pdf, section G. */
object TalentForgeTypography {
    /** ScreenTitle: 24 / 30 · 600. */
    val title: TextStyle = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** ComponentTitle: 15.5 / 20 · 600. */
    val componentTitle: TextStyle = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** Body: 15 / 22 · 400. */
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)

    /** BodySecondary: 13.5 / 20 · 400. */
    val bodySmall: TextStyle = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)

    /** Label: 12 / 16 · 500. */
    val label: TextStyle = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    )

    /** Meta: 12.5 / 16 · 400 (spec G "Meta Warrior · Forever · 2 h ago"). */
    val meta: TextStyle = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
    )

    /** Numeric: mono 17 · 600 (tabular). */
    val value: TextStyle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun TalentForgeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTalentForgeColors provides TalentForgeColors) {
        content()
    }
}

@Composable
fun talentForgeColors(): TalentForgeColors = LocalTalentForgeColors.current
