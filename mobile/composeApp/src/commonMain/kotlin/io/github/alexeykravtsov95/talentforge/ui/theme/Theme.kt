package io.github.alexeykravtsov95.talentforge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Minimal theme wiring for the M3A smoke host. The full design system
 * (typography, component library) arrives with the production UI milestone —
 * only the frozen colour tokens and a few text styles exist today.
 *
 * Material 3 is deliberately absent: CMP 1.12 publishes material3 only in
 * alpha form, and this milestone accepts no pre-release dependencies.
 */
private val LocalTalentForgeColors = staticCompositionLocalOf { TalentForgeColors }

/** Typography placeholders matching the smoke host's few text roles. */
object TalentForgeTypography {
    val title: TextStyle = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
    )

    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val bodySmall: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val label: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val value: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
}

@Composable
fun TalentForgeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTalentForgeColors provides TalentForgeColors) {
        content()
    }
}

@Composable
fun talentForgeColors(): TalentForgeColors = LocalTalentForgeColors.current
