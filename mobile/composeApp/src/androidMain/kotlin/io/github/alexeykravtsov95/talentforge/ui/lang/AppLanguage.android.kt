package io.github.alexeykravtsov95.talentforge.ui.lang

import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Android: the first entry of the user's preferred locale list (Settings →
 * System → Languages), the device's primary language — the same
 * `LocaleList.getDefault()[0]` Compose's `Locale.current` (and so the
 * resource environment) reads.
 */
@Composable
actual fun platformPrimaryLanguageTag(): String =
    remember { primaryLanguageTagOf(LocaleList.getDefault().toLanguageTags().split(',')) }
