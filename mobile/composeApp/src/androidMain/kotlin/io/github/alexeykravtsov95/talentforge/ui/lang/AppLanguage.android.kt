package io.github.alexeykravtsov95.talentforge.ui.lang

import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Android: the first entry of the user's preferred locale list (Settings →
 * System → Languages), the device's primary language.
 */
@Composable
actual fun platformPrimaryLanguageTag(): String = remember { LocaleList.getDefault().get(0).toLanguageTag() }
