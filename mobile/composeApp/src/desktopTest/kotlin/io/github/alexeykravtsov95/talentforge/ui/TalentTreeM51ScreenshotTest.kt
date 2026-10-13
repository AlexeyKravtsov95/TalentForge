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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeBuildScreen
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * Produces the M5.1 visual-evidence screenshots from the REAL app: Home
 * (empty and returning), New Build, All Builds, the reopened saved Warrior
 * build and a non-Warrior editor — real datasets, real persisted builds in a
 * fake injected storage, no fabricated cards.
 *
 * Skipped unless the caller opts in, because it is PR evidence, not a
 * regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeM51ScreenshotTest*" \
 *     -Dtalentforge.m51.screenshots=<output-dir>
 * ```
 */
class TalentTreeM51ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File
        get() = File(System.getProperty("talentforge.m51.screenshots")!!)

    private val storage = FakeBuildLibraryStorage()
    private val clock = FixedClock(startMillis = 1_700_000_000_000L)
    private val ids = SequentialIds()

    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty("talentforge.m51.screenshots") != null)
        outputDir.mkdirs()
    }

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun composeApp() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        App(storage = storage, clock = clock, ids = ids)
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

    private fun documentOf(token: String): TalentDataDocument {
        val outcome = runBlocking {
            datasets.document(DatasetId(GameId("forever"), "1.60.1.70009", ClassToken(token)))
        }
        return assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(outcome).value
    }

    /** Persists a real engine-spent build of one class (empty ranks = fresh draft). */
    private fun persistBuild(token: String, ranks: Map<String, Int>) {
        val document = documentOf(token)
        val engine = TalentAllocationEngine(document)
        var state = engine.emptyState()
        for ((name, count) in ranks) {
            val nodeId = engine.tree.nodes.first { it.name == name }.id
            repeat(count) { state = engine.spend(state, nodeId).state }
        }
        val repository = repository()
        val created = loadedRecord(
            repository.createDraft(
                buildFileOf(
                    state = engine.emptyState(),
                    game = document.game,
                    clientBuild = document.clientBuild,
                    classToken = document.classInfo.token,
                ),
            ),
        )
        if (state.allocations.isNotEmpty()) {
            repository.saveBuild(
                created.localId,
                buildFileOf(
                    state = state,
                    game = document.game,
                    clientBuild = document.clientBuild,
                    classToken = document.classInfo.token,
                ),
            )
        }
        clock.advanceBy(3_600_000L)
    }

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    private fun loadedRecord(outcome: BuildLibraryOutcome<LocalBuildRecord>): LocalBuildRecord =
        loaded(outcome)

    @Test
    fun m51ShellAndWarriorEditor() {
        // Home empty
        composeApp()
        awaitText("No builds yet")
        shoot("m51-home-empty.png")

        // New Build / class selection
        composeRule.onNodeWithText("New build").performClick()
        awaitText("GAME VERSION")
        shoot("m51-new-build.png")

        // back to Home, then a returning user: a spent Warrior build + a Mage draft
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("No builds yet")
        persistBuild("WARRIOR", mapOf("Improved Rend" to 3, "Deflection" to 2))
        persistBuild("MAGE", emptyMap())
        composeApp()
        awaitText("CONTINUE")
        shoot("m51-home-continue-recent.png")

        // All Builds with several drafts
        composeRule.onNodeWithText("All builds 2").performClick()
        awaitText("Builds 2")
        shoot("m51-all-builds.png")

        // Warrior reopened saved build (real ranks restored through validation)
        composeRule.onNodeWithText("Warrior · Forever · 2 h ago").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("5 / 51").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()
        shoot("m51-warrior-reopened-saved-build.png")
    }

    @Test
    fun m51NonWarriorEditor() {
        persistBuild("MAGE", mapOf("Arcane Focus" to 2))
        val warrior = loaded(repository().load()).first { it.build.classToken.value == "MAGE" }

        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        val scope = rememberCoroutineScope()
                        TalentTreeBuildScreen(
                            localId = assertNotNull(warrior).localId,
                            repository = repository(),
                            datasets = datasets,
                            autosave = remember { BuildAutosaveCoordinator(repository(), scope) },
                            onBack = {},
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Mage · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        shoot("m51-mage-editor.png")
    }

    private fun shoot(name: String) {
        val image = composeRule.onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }
}
