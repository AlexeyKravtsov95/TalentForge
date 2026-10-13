package io.github.alexeykravtsov95.talentforge.ui.lang

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * iOS: the first entry of the user's preferred languages (Settings →
 * General → Language & Region), the device's primary language — the
 * `NSLocale` class property Kotlin/Native exports as an extension of its
 * companion object.
 */
@Composable
actual fun platformPrimaryLanguageTag(): String =
    remember { NSLocale.Companion.preferredLanguages.firstOrNull() as? String ?: "" }
