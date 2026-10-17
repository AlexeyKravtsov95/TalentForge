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
actual fun platformPrimaryLanguageTag(): String = remember { devicePrimaryLanguageTag() }

/*
 * Kept as an androidMain function with a declared `String` type (TF-RC1-LINT):
 * Android lint analyses only the androidMain sources of this KMP module, and its
 * bundled Kotlin analysis cannot read the module's newer Kotlin metadata, so a
 * commonMain call as the last expression of the `remember` lambda has no
 * resolvable type for lint, and its `RememberReturnType` check then reads the
 * `remember` call as returning `Unit` — a false error. Same value, same
 * remember-once behaviour.
 */
private fun devicePrimaryLanguageTag(): String =
    primaryLanguageTagOf(LocaleList.getDefault().toLanguageTags().split(','))
