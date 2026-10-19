package io.github.alexeykravtsov95.talentforge.ui.ota

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.about.AboutScreen
import io.github.alexeykravtsov95.talentforge.ui.about.AppVersion
import io.github.alexeykravtsov95.talentforge.ui.about.BackupStatement
import io.github.alexeykravtsov95.talentforge.ui.about.PublishedPrivacyPolicy
import io.github.alexeykravtsov95.talentforge.ui.about.privacyPolicyUrlOf
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeBuildScreen
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * M5.OTA.3 PR evidence (desktop-rendered, EN and RU): the editor's on-demand download of a
 * historical build and its typed unavailable state with Retry, in portrait 393 × 852 and
 * landscape 852 × 393; the About "talent data" section; the Home "updated" line. Skipped unless
 * requested (the assertions live in `OtaUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5Ota3ScreenshotTest*" -Dtalentforge.m5ota3.screenshots=<dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class M5Ota3ScreenshotTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun onScreen(language: AppLanguage, widthDp: Int, heightDp: Int, content: @Composable () -> Unit, block: ComposeUiTest.() -> Unit) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = (widthDp * DENSITY).toInt(), height = (heightDp * DENSITY).toInt()) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(DENSITY, 1f), LocalReducedMotion provides true) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) { content() }
                    }
                }
            }
            block()
        }
    }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.shoot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    private fun historicalDevice(): Pair<OtaDevice, String> {
        val device = OtaDevice(bundledBuilds = setOf(B_BUILD))
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = OtaTestRepo.fixture("catalogs/catalog-b-active.json")
        device.check()
        return device to device.saveBuild(A_BUILD, "ROGUE").localId
    }

    private fun editor(device: OtaDevice, localId: String): @Composable () -> Unit = {
        // as App opens an editor: leased on the session current when it opens
        val access = androidx.compose.runtime.remember { EditorDatasetAccess.open(device.runtime, SessionDatasets.of(device.runtime.snapshot.value)) }
        val session = access.session
        TalentTreeBuildScreen(
            localId = localId,
            repository = device.repository,
            datasets = session.datasets,
            autosave = BuildAutosaveCoordinator(device.repository, rememberCoroutineScope()),
            onBack = {},
            icons = session.icons,
            descriptions = session.descriptions,
            localization = session.localization,
            datasetAccess = access,
        )
    }

    @Test
    fun m5ota3Screens() {
        for (language in AppLanguage.entries) {
            val tag = if (language == AppLanguage.RUSSIAN) "ru" else "en"
            for ((orientation, size) in listOf("portrait" to (393 to 852), "landscape" to (852 to 393))) {
                val (width, height) = size
                historicalDevice().let { (device, localId) ->
                    device.test {
                        device.server.networkDown = true
                        onScreen(language, width, height, editor(device, localId)) {
                            awaitText("1.60.1.70374")
                            shoot("$tag-$orientation-01-editor-unavailable-offline.png")
                        }
                    }
                }
                historicalDevice().let { (device, localId) ->
                    device.test {
                        val gate = CompletableDeferred<Unit>()
                        device.server.beforeResponse = { url -> if ("/blobs/" in url && device.server.blobRequests().size > 150) gate.await() }
                        onScreen(language, width, height, editor(device, localId)) {
                            awaitText("396")
                            waitUntil(timeoutMillis = 30_000) { device.server.blobRequests().size > 150 }
                            Thread.sleep(500) // the progress of the files already staged reaches the screen
                            shoot("$tag-$orientation-02-editor-downloading.png")
                            gate.complete(Unit)
                            awaitText("Forever")
                            shoot("$tag-$orientation-03-editor-opened-on-its-own-build.png")
                        }
                    }
                }
            }
            OtaDevice().test { device ->
                device.server.publish(TestRelease.A, TestRelease.B)
                device.server.catalog = OtaTestRepo.fixture("catalogs/catalog-b-active.json")
                onScreen(language, 393, 852, { App(storage = device.library, clock = FixedClock(), ids = SequentialIds(), ota = device.runtime) }) {
                    awaitText("1.60.1.70450")
                    shoot("$tag-portrait-04-home-talent-data-updated.png")
                }
                onScreen(language, 393, 852, {
                    val scope = rememberCoroutineScope()
                    AboutScreen(
                        appVersion = AppVersion("0.1.0", 1),
                        backupStatement = BackupStatement.AndroidBackupDisabled,
                        privacyPolicyUrl = privacyPolicyUrlOf(PublishedPrivacyPolicy.Android, language),
                        onBack = {},
                        onOpenLicenses = {},
                        otaStatus = produceState(device.runtime.status.value) { device.runtime.status.collect { value = it } }.value,
                        onCheckForUpdates = { scope.launch(Dispatchers.Default) { device.runtime.coordinator.checkNow() } },
                    )
                }) {
                    awaitText("1.60.1.70450-r1")
                    onAllNodesWithText("1.60.1.70450-r1", substring = true).onFirst().performScrollTo()
                    shoot("$tag-portrait-05-about-talent-data.png")
                }
            }
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5ota3.screenshots"
        const val DENSITY = 2f
    }
}
