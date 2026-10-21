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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.ota.OtaStatus
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * M5.RELEASE.FINAL.1 PR evidence: the About screen of an OTA-enabled build (production OTA is
 * on only in non-debuggable builds, so a `.devcheck` debug install shows the OTA-off screen)
 * with its privacy details open on the corrected talent-data-updates paragraph —
 * desktop-rendered in English and Russian at 393 × 852.
 *
 * Skipped unless the caller opts in (evidence, not a regression assertion — the assertions
 * live in `AboutScreenUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5ReleaseFinal1ScreenshotTest*" \
 *     -Dtalentforge.m5releasefinal1.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class M5ReleaseFinal1ScreenshotTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun onScreen(language: AppLanguage, block: ComposeUiTest.() -> Unit) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = (WIDTH_DP * DENSITY).toInt(), height = (HEIGHT_DP * DENSITY).toInt()) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(DENSITY, 1f), LocalReducedMotion provides true) {
                        Box(Modifier.size(WIDTH_DP.dp, HEIGHT_DP.dp)) { otaAbout(language) }
                    }
                }
            }
            block()
        }
    }

    @Composable
    private fun otaAbout(language: AppLanguage) {
        AboutScreen(
            appVersion = AppVersion(name = "1.0.0", code = 2),
            backupStatement = BackupStatement.AndroidBackupDisabled,
            privacyPolicyUrl = privacyPolicyUrlOf(PublishedPrivacyPolicy.Android, language),
            onBack = {},
            onOpenLicenses = {},
            otaStatus = OtaStatus(productBuild = PRODUCT_BUNDLE.clientBuild, productReleaseId = null),
            onCheckForUpdates = {},
        )
    }

    private fun ComposeUiTest.scrollTo(text: String) {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(text, substring = true).onFirst().performScrollTo()
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    @Test
    fun m5releaseFinal1Screens() {
        for (language in AppLanguage.entries) {
            val ru = language == AppLanguage.RUSSIAN
            val tag = if (ru) "ru" else "en"
            onScreen(language) {
                scrollTo(if (ru) "Проверить обновления" else "Check for updates")
                shoot("$tag-ota-on-01-about.png")
                val details = if (ru) "Подробнее" else "Show details"
                scrollTo(details)
                onAllNodesWithText(details).onFirst().performClick()
                scrollTo(if (ru) "Чтобы данные талантов оставались актуальными" else "To keep talent data current")
                shoot("$tag-ota-on-02-privacy-updates.png")
            }
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5releasefinal1.screenshots"
        const val DENSITY = 2f
        const val WIDTH_DP = 393
        const val HEIGHT_DP = 852
    }
}
