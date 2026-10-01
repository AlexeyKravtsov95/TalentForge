package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Android: the system back button pops the app's destination stack. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
