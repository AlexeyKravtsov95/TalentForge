package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable

/** iOS host: no system Back button; its system back is the edge swipe ([PlatformEdgeSwipeInput]). */
@Composable
internal actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
