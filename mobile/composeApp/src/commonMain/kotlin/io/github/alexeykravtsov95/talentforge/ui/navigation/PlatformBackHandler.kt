package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable

/**
 * The app's one back dispatcher: the visible back affordances and the system
 * back must agree (UX/UI spec 02 §1: "native system back everywhere").
 *
 * - Android: the real `BackHandler` of activity-compose ([SystemBackHandler]);
 * - desktop: Esc through the same registration rules ([SystemBackHandler]);
 * - iOS: there is no system Back button; the system back is the left-edge
 *   swipe ([EdgeSwipeBackDispatcher]), which must match the visible chevron.
 *
 * [edgeSwipe] says whether this handler also takes the iOS edge swipe — by
 * default whenever it is [enabled]. A handler whose Back is not what the
 * screen's visible back control does passes `false` for that state (the
 * editor's inspector-collapse and deselect steps: on iOS the swipe pops the
 * editor like its chevron). The app's own page pops take the swipe through
 * [BackSwipeStack], so they pass `false` here as well.
 */
@Composable
fun PlatformBackHandler(enabled: Boolean, edgeSwipe: Boolean = enabled, onBack: () -> Unit) {
    SystemBackHandler(enabled = enabled, onBack = onBack)
    EdgeSwipeBackHandler(enabled = edgeSwipe, onCompleted = onBack)
}

/** The host's own system Back: Android's back button, desktop's Esc; none on iOS. */
@Composable
internal expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
