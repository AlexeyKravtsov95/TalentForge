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
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * M5.RC.2b PR evidence: Home with its About link and the About screen — top,
 * privacy, open-source notices (collapsed and expanded) and the licence text —
 * desktop-rendered in English and Russian at 393 × 852, plus the narrow
 * 320 × 568 phone at 200 % font scale.
 *
 * Skipped unless the caller opts in (evidence, not a regression assertion —
 * the assertions live in `AboutScreenUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5Rc2bScreenshotTest*" \
 *     -Dtalentforge.m5rc2b.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class M5Rc2bScreenshotTest {

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
            noticesPath = ANDROID_OSS_NOTICES_PATH,
            backupStatement = BackupStatement.AndroidBackupDisabled,
            privacyPolicyUrl = LegalLinks.privacyPolicyUrl,
            onBack = {},
        )
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
    fun m5rc2bAboutScreens() {
        for (language in AppLanguage.entries) {
            val ru = language == AppLanguage.RUSSIAN
            val tag = if (ru) "ru" else "en"
            onScreen(language, content = { App(storage = FakeBuildLibraryStorage(), clock = FixedClock(), ids = SequentialIds()) }) {
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithContentDescription(if (ru) "О приложении" else "About").fetchSemanticsNodes().isNotEmpty()
                }
                shoot("$tag-01-home-about-link.png")
            }
            onScreen(language, content = about) {
                awaitText(if (ru) "НЕЗАВИСИМЫЙ ПРОЕКТ" else "INDEPENDENT PROJECT")
                shoot("$tag-02-about-top.png")
                scrollTo(if (ru) "ЛИЦЕНЗИИ ОТКРЫТОГО ПО" else "OPEN-SOURCE LICENSES")
                shoot("$tag-03-about-privacy.png")
                scrollTo("Guava ListenableFuture")
                shoot("$tag-04-about-licenses.png")
                scrollTo("AndroidX (Android Jetpack)")
                onAllNodesWithText(if (ru) "Показать модули" else "Show modules").onFirst().performClick()
                shoot("$tag-05-about-modules-expanded.png")
                val showText = if (ru) "Показать текст лицензии" else "Show license text"
                scrollTo(showText)
                onAllNodesWithText(showText).onFirst().performClick()
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithText("TERMS AND CONDITIONS", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                shoot("$tag-06-about-license-text.png")
            }
            onScreen(language, widthDp = 320, heightDp = 568, fontScale = 2f, content = about) {
                awaitText(if (ru) "НЕЗАВИСИМЫЙ ПРОЕКТ" else "INDEPENDENT PROJECT")
                shoot("$tag-07-about-320x568-font200.png")
                scrollTo("Kotlin Standard Library")
                shoot("$tag-08-about-320x568-font200-licenses.png")
            }
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5rc2b.screenshots"
        const val DENSITY = 2f
    }
}
