package io.github.alexeykravtsov95.talentforge.ui.lang

import java.util.Locale
import org.junit.rules.ExternalResource

/**
 * The explicit UI language of a desktop UI test (M5.REL.2a). Compose
 * resources read the desktop platform locale (`Locale.getDefault()`), so a
 * test pins it — independent of the machine's own locale — and restores the
 * previous default afterwards. [language] may change mid-test; the test then
 * also flips its [LocalAppLanguage] provider (or re-keys its content) so the
 * composition re-reads the locale. An override that disagrees with this
 * rule fails in `appLanguage()` instead of rendering a mixed-language UI.
 */
class AppLanguageRule(initial: AppLanguage = AppLanguage.ENGLISH) : ExternalResource() {

    private var saved: Locale? = null
    private var active = false

    var language: AppLanguage = initial
        set(value) {
            field = value
            if (active) Locale.setDefault(localeOf(value))
        }

    override fun before() {
        saved = Locale.getDefault()
        active = true
        Locale.setDefault(localeOf(language))
    }

    override fun after() {
        active = false
        saved?.let(Locale::setDefault)
    }

    companion object {
        /** The platform locale a test uses for [language]. */
        fun localeOf(language: AppLanguage): Locale = when (language) {
            AppLanguage.ENGLISH -> Locale.forLanguageTag("en-US")
            AppLanguage.RUSSIAN -> Locale.forLanguageTag("ru-RU")
        }
    }
}
