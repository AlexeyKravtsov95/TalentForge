package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.appLanguage
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * M5.UI.ABOUT.1 PR evidence: Home with its About link, the simplified About
 * screen (top, privacy details open, the licenses row) and the third-party
 * licenses (top, modules expanded, licence text) — desktop-rendered in English
 * and Russian at 393 × 852, plus the narrow 320 × 568 phone at 200 % font
 * scale. Supersedes the M5.RC.2b capture, whose single-page flow no longer
 * exists.
 *
 * Skipped unless the caller opts in (evidence, not a regression assertion —
 * the assertions live in `AboutScreenUiTest` and `LicensesScreenUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5UiAbout1ScreenshotTest*" \
 *     -Dtalentforge.m5uiabout1.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class M5UiAbout1ScreenshotTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun onScreen(
        language: AppLanguage,
        widthDp: Int = 393,
        heightDp: Int = 852,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = (widthDp * DENSITY).toInt(), height = (heightDp * DENSITY).toInt()) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(DENSITY, fontScale),
                        LocalReducedMotion provides true,
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) { content() }
                    }
                }
            }
            block()
        }
    }

    private val about: @Composable () -> Unit = {
        AboutScreen(
            appVersion = AppVersion(name = "0.1.0", code = 1),
            backupStatement = BackupStatement.AndroidBackupDisabled,
            privacyPolicyUrl = privacyPolicyUrlOf(PublishedPrivacyPolicy.Android, appLanguage()),
            onBack = {},
            onOpenLicenses = {},
        )
    }

    private val licenses: @Composable () -> Unit = {
        LicensesScreen(noticesPath = ANDROID_OSS_NOTICES_PATH, onBack = {})
    }

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.scrollTo(text: String) {
        awaitText(text)
        onAllNodesWithText(text).onFirst().performScrollTo()
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    @Test
    fun m5uiAbout1Screens() {
        for (language in AppLanguage.entries) {
            val ru = language == AppLanguage.RUSSIAN
            val tag = if (ru) "ru" else "en"
            val licensesTitle = if (ru) "Лицензии сторонних компонентов" else "Third-party licenses"
            onScreen(language, content = { App(storage = FakeBuildLibraryStorage(), clock = FixedClock(), ids = SequentialIds()) }) {
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithContentDescription(if (ru) "О приложении" else "About").fetchSemanticsNodes().isNotEmpty()
                }
                shoot("$tag-01-home-about-link.png")
            }
            onScreen(language, content = about) {
                awaitText(if (ru) "НЕЗАВИСИМЫЙ ПРОЕКТ" else "INDEPENDENT PROJECT")
                shoot("$tag-02-about.png")
                val details = if (ru) "Подробнее" else "Show details"
                scrollTo(details)
                onAllNodesWithText(details).onFirst().performClick()
                scrollTo(licensesTitle)
                shoot("$tag-03-about-privacy-details.png")
            }
            onScreen(language, content = licenses) {
                awaitText("Guava ListenableFuture")
                shoot("$tag-04-licenses.png")
                scrollTo("AndroidX (Android Jetpack)")
                onAllNodesWithText(if (ru) "Показать модули" else "Show modules").onFirst().performClick()
                shoot("$tag-05-licenses-modules-expanded.png")
                val showText = if (ru) "Показать текст лицензии" else "Show license text"
                scrollTo(showText)
                onAllNodesWithText(showText).onFirst().performClick()
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithText("TERMS AND CONDITIONS", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                shoot("$tag-06-licenses-license-text.png")
            }
            onScreen(language, widthDp = 320, heightDp = 568, fontScale = 2f, content = about) {
                awaitText(if (ru) "НЕЗАВИСИМЫЙ ПРОЕКТ" else "INDEPENDENT PROJECT")
                shoot("$tag-07-about-320x568-font200.png")
                scrollTo(licensesTitle)
                shoot("$tag-08-about-320x568-font200-bottom.png")
            }
            onScreen(language, widthDp = 320, heightDp = 568, fontScale = 2f, content = licenses) {
                awaitText("Kotlin Standard Library")
                shoot("$tag-09-licenses-320x568-font200.png")
            }
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5uiabout1.screenshots"
        const val DENSITY = 2f
    }
}
