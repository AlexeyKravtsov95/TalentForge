package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable

/** iOS host: no system back; the visible header chevron is the back control. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
