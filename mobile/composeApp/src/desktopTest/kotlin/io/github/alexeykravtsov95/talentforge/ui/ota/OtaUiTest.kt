package io.github.alexeykravtsov95.talentforge.ui.ota

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.about.AboutScreen
import io.github.alexeykravtsov95.talentforge.ui.about.AppVersion
import io.github.alexeykravtsov95.talentforge.ui.about.BackupStatement
import io.github.alexeykravtsov95.talentforge.ui.about.privacyPolicyUrlOf
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeBuildScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Rule

/**
 * The minimal OTA UI (brief item 12, OD-5): the editor's typed "unavailable" state with Retry
 * and its download progress for a historical build, the About "talent data" section with
 * "check for updates", and the one-time Home line after an activation — and the app's first
 * screen never waits for the network.
 */
@OptIn(ExperimentalTestApi::class)
class OtaUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private fun ComposeUiTest.awaitText(text: String, substring: Boolean = false) = try {
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        throw AssertionError("'$text' not shown; screen:\n" + onRoot(useUnmergedTree = true).printToString(maxDepth = 40), e)
    }

    /** B is the bundled product; the catalog lists A, which a saved build needs and the device lacks. */
    private fun historicalDevice(): Pair<OtaDevice, String> {
        val device = OtaDevice(bundledBuilds = setOf(B_BUILD))
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = OtaTestRepo.fixture("catalogs/catalog-b-active.json")
        device.check()
        return device to device.saveBuild(A_BUILD, "ROGUE").localId
    }

    private fun ComposeUiTest.editor(device: OtaDevice, localId: String) = setContent {
        TalentForgeTheme {
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
    }

    @Test
    fun anUnavailableHistoricalBuildShowsItsReasonAndRetryDownloadsIt() {
        val (device, localId) = historicalDevice()
        device.test {
            val library = device.library.payload
            device.server.networkDown = true
            runDesktopComposeUiTest(width = 393 * 2, height = 852 * 2) {
                editor(device, localId)
                awaitText("Talent data 1.60.1.70374 is not available")
                awaitText("No connection. Connect to the internet and try again. The build stays saved.")
                assertEquals(library, device.library.payload, "the record is untouched")
                device.server.networkDown = false
                onAllNodesWithText("Retry").onFirst().performClick()
                awaitText("Rogue · Forever")
            }
            assertEquals(B_ID, device.runtime.activation.currentState().active, "downloaded, never activated")
        }
    }

    @Test
    fun aHistoricalDownloadShowsItsProgress() {
        val (device, localId) = historicalDevice()
        device.test {
            val gate = CompletableDeferred<Unit>()
            device.server.beforeResponse = { url -> if ("/blobs/" in url && device.server.blobRequests().size > 20) gate.await() }
            runDesktopComposeUiTest(width = 393 * 2, height = 852 * 2) {
                editor(device, localId)
                awaitText("Downloading talent data 1.60.1.70374…")
                awaitText(" files", substring = true)
                gate.complete(Unit)
                awaitText("Rogue · Forever")
            }
        }
    }

    @Test
    fun aboutShowsTheDataInUseAndChecksOnDemand() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = OtaTestRepo.fixture("catalogs/catalog-a-first-publication.json")
        runDesktopComposeUiTest(width = 393 * 2, height = 852 * 2) {
            setContent {
                TalentForgeTheme {
                    val status = device.runtime.status.value
                    val scope = rememberCoroutineScope()
                    AboutScreen(
                        appVersion = AppVersion("0.1.0", 1),
                        backupStatement = BackupStatement.AndroidBackupDisabled,
                        privacyPolicyUrl = privacyPolicyUrlOf(AppLanguage.ENGLISH),
                        onBack = {},
                        onOpenLicenses = {},
                        otaStatus = androidx.compose.runtime.produceState(status) {
                            device.runtime.status.collect { value = it }
                        }.value,
                        onCheckForUpdates = { scope.launchCheck(device) },
                    )
                }
            }
            awaitText("TALENT DATA")
            awaitText("Game build 1.60.1.70374")
            awaitText("Included with the app")
            onAllNodesWithText("Check for updates").onFirst().performScrollTo().performClick()
            awaitText("Downloaded update 1.60.1.70374-r1")
            awaitText("Talent data updated to 1.60.1 (1.60.1.70374)", substring = true)
        }
    }

    @Test
    fun homeShowsTheUpdatedLineOnceAndNeverWaitsForTheNetwork() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = OtaTestRepo.fixture("catalogs/catalog-b-active.json")
        val gate = CompletableDeferred<Unit>()
        device.server.beforeResponse = { gate.await() }
        runDesktopComposeUiTest(width = 393 * 2, height = 852 * 2) {
            setContent { App(storage = device.library, clock = FixedClock(), ids = SequentialIds(), ota = device.runtime) }
            awaitText("No builds yet") // the first screen while the check is still waiting for the network
            gate.complete(Unit)
            awaitText("Talent data updated to 1.60.1 (1.60.1.70450)", substring = true)
            onAllNodesWithText("OK").onFirst().performClick()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Talent data updated to", substring = true).fetchSemanticsNodes().isEmpty()
            }
        }
    }
}

private fun CoroutineScope.launchCheck(device: OtaDevice) {
    launch(Dispatchers.Default) { device.runtime.coordinator.checkNow() }
}
