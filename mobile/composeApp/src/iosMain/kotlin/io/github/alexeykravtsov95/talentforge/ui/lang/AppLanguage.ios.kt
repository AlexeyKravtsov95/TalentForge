package io.github.alexeykravtsov95.talentforge.ui.lang

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * iOS: the first entry of the user's preferred languages (Settings →
 * General → Language & Region), the device's primary language — the
 * `NSLocale` class property Kotlin/Native exports as an extension of its
 * companion object. It is the same list Compose's `Locale.current` (and so
 * the resource environment) reads its first entry from.
 */
@Composable
actual fun platformPrimaryLanguageTag(): String =
    remember { primaryLanguageTagOf(NSLocale.Companion.preferredLanguages.map { it as? String ?: "" }) }
