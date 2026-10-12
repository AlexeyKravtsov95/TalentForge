package io.github.alexeykravtsov95.talentforge.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** iOS: Settings → Accessibility → Motion → Reduce Motion. */
@Composable
actual fun platformPrefersReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
