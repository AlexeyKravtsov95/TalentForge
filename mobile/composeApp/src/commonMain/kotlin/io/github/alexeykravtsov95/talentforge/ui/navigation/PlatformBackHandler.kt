package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable

/**
 * The app's one back dispatcher: the visible back affordances and the Android
 * system back button must agree (UX/UI spec 02 §1: "native system back
 * everywhere").
 *
 * - Android: the real `BackHandler` of activity-compose;
 * - iOS/desktop: there is no system back in these hosts, so the actual is a
 *   no-op and the frozen visible back affordances (header chevron) carry
 *   navigation.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
