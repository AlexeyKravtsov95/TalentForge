package io.github.alexeykravtsov95.talentforge.ui.lang

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.resource_language
import org.jetbrains.compose.resources.stringResource

/**
 * The display languages TalentForge ships (M5.REL.1): Russian when the
 * device's primary language is Russian, English for every other primary
 * language. There is no in-app language selector. Since M5.REL.2a the shell
 * screens' copy (Home, All Builds, Class Selection) — and since M5.REL.2b the
 * talent editor's — comes from Compose resources (`values/` English default,
 * `values-ru/` Russian) under the SAME rule; the specialization names keep
 * their M5.REL.1 table.
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

/**
 * The PRIMARY language tag of a platform's ordered preferred-language list:
 * its first entry, or `""` (→ English) for an empty list. Only the first
 * entry counts — `[en-US, ru-RU]` is an English device even though Russian
 * is preferred second.
 */
fun primaryLanguageTagOf(preferredLanguageTags: List<String>): String =
    preferredLanguageTags.firstOrNull()?.trim().orEmpty()

/**
 * An override of the device language (tests, previews); `null` = follow the
 * device. Since M5.REL.2a the shell copy comes from Compose resources, whose
 * locale is the platform's — so an override must name the language the
 * platform locale already selects (see [appLanguage]); tests switch the
 * platform locale and this override together.
 */
val LocalAppLanguage = staticCompositionLocalOf<AppLanguage?> { null }

/** The device's primary language tag, as the platform reports it. */
@Composable
expect fun platformPrimaryLanguageTag(): String

/**
 * The language the UI presents in: the explicit [LocalAppLanguage] override
 * when one is provided (tests, previews), otherwise derived from the
 * device's primary language through [appLanguageOf].
 *
 * One input, one rule (M5.REL.2a): Compose resources pick `values-ru` exactly
 * when `Locale.current.language` is `ru`, and `Locale.current` reads the SAME
 * platform value [platformPrimaryLanguageTag] does (Android
 * `LocaleList.getDefault()[0]`, iOS `NSLocale.preferredLanguages[0]`, desktop
 * `Locale.getDefault()`), so this language and the resource copy agree on
 * every platform. The resource API offers no public locale override, so an
 * override the resource locale contradicts would render a mixed-language UI —
 * it fails here, loudly, instead.
 */
@Composable
fun appLanguage(): AppLanguage {
    val override = LocalAppLanguage.current ?: return appLanguageOf(platformPrimaryLanguageTag())
    val resourceLanguage = resourceAppLanguage()
    check(override == resourceLanguage) {
        "LocalAppLanguage override $override contradicts the resource locale ($resourceLanguage): " +
            "switch the platform locale together with the override"
    }
    return override
}

/** The language the Compose resource environment selected, from the resource set's own `resource_language`. */
@Composable
fun resourceAppLanguage(): AppLanguage = appLanguageOf(stringResource(Res.string.resource_language))
