package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable

/** Desktop: no edge swipe; Esc stays with [SystemBackHandler]. Tests drive the dispatcher directly. */
@Composable
internal actual fun PlatformEdgeSwipeInput(dispatcher: EdgeSwipeBackDispatcher) = Unit
