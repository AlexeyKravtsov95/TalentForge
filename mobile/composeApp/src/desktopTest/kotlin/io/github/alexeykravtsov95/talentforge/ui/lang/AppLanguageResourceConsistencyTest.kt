package io.github.alexeykravtsov95.talentforge.ui.lang

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_new_build
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule

/**
 * M5.REL.2a: ONE language rule for both copy sources. `AppLanguage`
 * (specialization names) and the Compose resource environment (all shell
 * copy) read the same platform input — the device's PRIMARY language — and
 * must agree on it for every locale: `ru`/`ru-RU`/`ru-KZ`/… → Russian,
 * every other primary language (an English-first list with Russian second
 * included) → English. A `LocalAppLanguage` override that the resource locale
 * contradicts fails loudly instead of rendering a mixed-language UI.
 */
@OptIn(ExperimentalTestApi::class)
class AppLanguageResourceConsistencyTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun onlyARussianPrimaryLanguageSelectsRussian() {
        for (tag in listOf("ru", "ru-RU", "ru-KZ", "ru-BY", "ru-UA", "ru_RU", "RU-ru")) {
            assertEquals(AppLanguage.RUSSIAN, appLanguageOf(tag), tag)
        }
        for (tag in listOf("en", "en-US", "en-GB", "de-DE", "uk-UA", "kk-KZ", "be-BY", "fr", "ja-JP", "", "und", "x")) {
            assertEquals(AppLanguage.ENGLISH, appLanguageOf(tag), tag)
        }
    }

    @Test
    fun onlyTheFirstPreferredLanguageCounts() {
        // English primary, Russian second → English
        assertEquals(AppLanguage.ENGLISH, appLanguageOf(primaryLanguageTagOf(listOf("en-US", "ru-RU"))))
        assertEquals(AppLanguage.ENGLISH, appLanguageOf(primaryLanguageTagOf(listOf("de-DE", "ru-RU", "en-US"))))
        assertEquals(AppLanguage.RUSSIAN, appLanguageOf(primaryLanguageTagOf(listOf("ru-RU", "en-US"))))
        assertEquals(AppLanguage.RUSSIAN, appLanguageOf(primaryLanguageTagOf(listOf("ru-KZ"))))
        // nothing determinable → English
        assertEquals("", primaryLanguageTagOf(emptyList()))
        assertEquals(AppLanguage.ENGLISH, appLanguageOf(primaryLanguageTagOf(emptyList())))
    }

    /**
     * The resource system's automatic choice never contradicts AppLanguage:
     * for each platform locale, the copy Compose resources render is the
     * language `appLanguageOf` derives from the same locale tag, and
     * `appLanguage()` (no override) answers the same.
     */
    @Test
    fun theResourceLocaleAndAppLanguageAgreeForEveryPlatformLocale() {
        val tags = listOf(
            "ru", "ru-RU", "ru-KZ", "ru-BY", "en", "en-US", "en-GB", "de-DE", "uk-UA", "kk-KZ", "be-BY",
            "fr-FR", "ja-JP", "zh-Hans-CN", "und",
        )
        for (tag in tags) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            var resourceLanguage: AppLanguage? = null
            var language: AppLanguage? = null
            var copy = ""
            runComposeUiTest {
                setContent {
                    resourceLanguage = resourceAppLanguage()
                    language = appLanguage()
                    copy = stringResource(Res.string.common_new_build)
                    BasicText(copy)
                }
                waitForIdle()
            }
            val expected = appLanguageOf(Locale.getDefault().toLanguageTag())
            assertEquals(expected, resourceLanguage, "resource language for $tag")
            assertEquals(expected, language, "appLanguage() for $tag")
            assertEquals(if (expected == AppLanguage.RUSSIAN) "Новый билд" else "New build", copy, "copy for $tag")
        }
    }

    @Test
    fun anOverrideMatchingThePlatformLocaleIsHonoured() {
        for (language in AppLanguage.entries) {
            uiLanguage.language = language
            var seen: AppLanguage? = null
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAppLanguage provides language) {
                        seen = appLanguage()
                        BasicText(stringResource(Res.string.common_new_build))
                    }
                }
                onNodeWithText(if (language == AppLanguage.RUSSIAN) "Новый билд" else "New build").assertExists()
            }
            assertEquals(language, seen)
        }
    }

    @Test
    fun anOverrideTheResourceLocaleContradictsFailsInsteadOfMixingLanguages() {
        uiLanguage.language = AppLanguage.ENGLISH
        val failure = assertFailsWith<IllegalStateException> {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.RUSSIAN) {
                        BasicText(appLanguage().name)
                    }
                }
                waitForIdle()
            }
        }
        assertEquals(true, failure.message?.contains("contradicts the resource locale"), failure.message)
    }
}
