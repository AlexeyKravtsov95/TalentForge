package io.github.alexeykravtsov95.talentforge.ui.lang

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The display languages TalentForge ships (M5.REL.1): Russian when the
 * device's primary language is Russian, English for every other primary
 * language. There is no in-app language selector. The next milestone widens
 * this from the specialization names to all visible UI copy, which is why
 * the choice lives here and not inside the tree UI.
 */
enum class AppLanguage {
    ENGLISH,
    RUSSIAN,
}

/**
 * The display language of a device primary-language tag (BCP-47 style, e.g.
 * `ru-RU`, `ru-KZ`, `en-GB`): only a Russian PRIMARY language selects
 * [AppLanguage.RUSSIAN]; every other tag — including an empty or malformed
 * one — reads as [AppLanguage.ENGLISH]. The tag's first subtag decides; the
 * region never does.
 */
fun appLanguageOf(primaryLanguageTag: String): AppLanguage {
    val primary = primaryLanguageTag.substringBefore('-').substringBefore('_').lowercase()
    return if (primary == "ru") AppLanguage.RUSSIAN else AppLanguage.ENGLISH
}

/** An override of the device language (tests, previews); `null` = follow the device. */
val LocalAppLanguage = staticCompositionLocalOf<AppLanguage?> { null }

/** The device's primary language tag, as the platform reports it. */
@Composable
expect fun platformPrimaryLanguageTag(): String

/**
 * The language the UI presents in: the explicit [LocalAppLanguage] override
 * when one is provided (tests, previews), otherwise derived from the
 * device's primary language through [appLanguageOf].
 */
@Composable
fun appLanguage(): AppLanguage = LocalAppLanguage.current ?: appLanguageOf(platformPrimaryLanguageTag())
