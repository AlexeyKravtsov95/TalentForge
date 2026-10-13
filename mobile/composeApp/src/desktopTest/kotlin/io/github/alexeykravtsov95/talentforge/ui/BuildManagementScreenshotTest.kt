package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * Produces the M5.2a build-management screenshots from the REAL app root and
 * a real persisted library (fake injected storage): the ⋯ actions sheet, the
 * rename dialog, the delete confirmation and the duplicate success notice.
 *
 * Skipped unless the caller opts in, because it is PR evidence, not a
 * regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*BuildManagementScreenshotTest*" \
 *     -Dtalentforge.m52a.screenshots=<output-dir>
 * ```
 */
class BuildManagementScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File
        get() = File(System.getProperty("talentforge.m52a.screenshots")!!)

    private val storage = FakeBuildLibraryStorage()
    private val clock = FixedClock(startMillis = 1_700_000_000_000L)
    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty("talentforge.m52a.screenshots") != null)
        outputDir.mkdirs()
    }

    private fun repository() = LocalBuildRepository(storage, clock, SequentialIds())

    private fun composeApp() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        App(storage = storage, clock = clock, ids = SequentialIds())
                    }
                }
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitGone(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Persists a real draft of one class; returns its local id. */
    private fun persistDraft(token: String): String {
        val created = runBlocking {
            assertIs<DraftCreation.Created>(createClassDraft(repository(), datasets, ClassToken(token)))
        }
        clock.advanceBy(3_600_000L)
        return created.record.localId
    }

    private fun shoot(name: String) {
        val image = composeRule.onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }

    @Test
    fun m52aManagementSurfaces() {
        // a returning user's library: one named build + one draft
        val named = persistDraft("WARRIOR")
        assertIs<BuildLibraryOutcome.Loaded<*>>(repository().renameBuild(named, "Raid build"))
        persistDraft("MAGE")
        composeApp()
        awaitText("CONTINUE")

        composeRule.onNodeWithText("All builds 2").performClick()
        awaitText("Builds 2")

        // the ⋯ sheet over the list (unnamed Mage draft → display fallback)
        composeRule.onNodeWithContentDescription("build actions for Mage build").performClick()
        awaitText("Duplicate")
        shoot("m52a-actions-sheet.png")

        // the rename dialog (typed text + live limit hint)
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        composeRule.onNodeWithContentDescription("build name").performTextInput("My raid build")
        composeRule.waitForIdle()
        shoot("m52a-rename-dialog.png")
        composeRule.onNodeWithText("Save").performClick()
        awaitGone("Rename build")
        awaitText("My raid build")

        // the duplicate success notice + refreshed list
        composeRule.onNodeWithContentDescription("build actions for My raid build").performClick()
        awaitText("Duplicate")
        composeRule.onNodeWithText("Duplicate").performClick()
        awaitText("Build duplicated")
        awaitText("My raid build (copy)")
        shoot("m52a-duplicate-notice.png")

        // the delete confirmation
        composeRule.onNodeWithContentDescription("build actions for My raid build (copy)").performClick()
        awaitText("Delete")
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Delete \"My raid build (copy)\"?")
        shoot("m52a-delete-confirm.png")
    }
}
