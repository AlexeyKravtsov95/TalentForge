package io.github.alexeykravtsov95.talentforge.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * PR #19 motion accessibility: `true` when the platform asks for reduced
 * motion — Android: animations removed (`ANIMATOR_DURATION_SCALE` = 0, also
 * set by Accessibility → Remove animations); iOS: Reduce Motion. Desktop has
 * no such setting (`false`).
 */
@Composable
expect fun platformPrefersReducedMotion(): Boolean

/** An override of [platformPrefersReducedMotion] (tests, previews); `null` = follow the platform. */
val LocalReducedMotion = staticCompositionLocalOf<Boolean?> { null }

/**
 * Whether the editor's transitions should be instant. With reduced motion the
 * section pages and the inspector sheet jump straight to their end state;
 * every action behaves exactly as with motion (the transitions are visual only).
 */
@Composable
fun prefersReducedMotion(): Boolean = LocalReducedMotion.current ?: platformPrefersReducedMotion()
