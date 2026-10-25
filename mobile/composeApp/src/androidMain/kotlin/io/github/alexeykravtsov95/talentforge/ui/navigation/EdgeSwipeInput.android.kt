package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable

/** Android: no edge swipe of the app's own; system Back stays with [SystemBackHandler]. */
@Composable
internal actual fun PlatformEdgeSwipeInput(dispatcher: EdgeSwipeBackDispatcher) = Unit
