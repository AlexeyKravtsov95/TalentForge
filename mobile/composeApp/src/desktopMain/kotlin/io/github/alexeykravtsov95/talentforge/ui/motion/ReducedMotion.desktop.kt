package io.github.alexeykravtsov95.talentforge.ui.motion

import androidx.compose.runtime.Composable

/** Desktop: no system reduced-motion setting is read. */
@Composable
actual fun platformPrefersReducedMotion(): Boolean = false
