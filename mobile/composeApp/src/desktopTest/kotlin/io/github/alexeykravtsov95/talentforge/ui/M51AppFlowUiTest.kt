package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The M5.1 production loop against the REAL app root, real bundled datasets
 * and a fake injected storage: Home → New Build → class tap creates+persists
 * the draft → editor autosaves every mutation → back → Home reflects it →
 * All Builds lists everything → a fresh App over the same storage still sees
 * it. Pan/zoom/selection must never write.
 */
class M51AppFlowUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private lateinit var clock: FixedClock
    private val ids = SequentialIds()

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
        clock = FixedClock()
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

    private fun createDraftViaRepository(token: String): String {
        val created = runBlocking {
            kotlin.test.assertIs<io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation.Created>(
                createClassDraft(repository(), realDatasets(), ClassToken(token)),
            )
        }
        return created.record.localId
    }

    /** The real node id of a Warrior talent, resolved from the bundled document. */
    private fun warriorNodeId(talentName: String): Int {
        val document = runBlocking {
            kotlin.test.assertIs<io.github.alexeykravtsov95.talentforge.data.DatasetOutcome.Loaded<io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument>>(
                realDatasets().document(
                    io.github.alexeykravtsov95.talentforge.data.DatasetId(
                        PRODUCT_BUNDLE.game,
                        PRODUCT_BUNDLE.clientBuild,
                        ClassToken("WARRIOR"),
                    ),
                ),
            ).value
        }
        return document.trees.single().nodes.first { it.name == talentName }.id
    }

    private fun realDatasets() = io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository(
        io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource(),
    )

    private fun storedPayload(): String = storage.payload!!

    // ------------------------------------------------------------------ Home

    @Test
    fun emptyLibraryHomeOffersNewBuildAndNothingFake() {
        composeApp()

        awaitText("+ New build")
        composeRule.onNodeWithText("Plan your first build").assertExists()
        composeRule.onNodeWithText("CONTINUE").assertDoesNotExist()
        composeRule.onNodeWithText("RECENT").assertDoesNotExist()
        composeRule.onNodeWithText("Forever talent data 1.60.1").assertExists()
    }

    @Test
    fun onePersistedDraftAppearsOnHomeAsContinue() {
        createDraftViaRepository("WARRIOR")
        composeApp()

        awaitText("CONTINUE")
        composeRule.onNodeWithText("Continue editing").assertExists()
        composeRule.onNodeWithText("Draft").assertExists()
        composeRule.onNodeWithText("0 / 51").assertExists()
        composeRule.onNodeWithText("Warrior · Forever · Just now").assertExists()
    }

    @Test
    fun continueOpensTheRealSavedBuild() {
        createDraftViaRepository("WARRIOR")
        composeApp()
        awaitText("Continue editing")

        composeRule.onNodeWithText("Continue editing").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("0 / 51").assertExists()
    }

    // ------------------------------------------------------------------ New Build

    @Test
    fun newBuildShowsForeverContextAndTheRealNineClasses() {
        composeApp()
        awaitText("+ New build")

        composeRule.onNodeWithText("+ New build").performClick()
        awaitText("GAME VERSION")

        composeRule.onNodeWithText("New build").assertExists()
        composeRule.onNodeWithText("Forever").assertExists()
        // the exact client build drafts are created on - no "latest" / "up to date" claim
        composeRule.onNodeWithText("Talent data 1.60.1.70338").assertExists()
        composeRule.onNodeWithText("Builds save to this device as you edit.").assertExists()
        for (name in listOf(
            "Warrior", "Paladin", "Hunter", "Rogue", "Priest", "Shaman", "Mage", "Warlock", "Druid",
        )) {
            composeRule.onNodeWithText(name).assertExists()
        }
    }

    @Test
    fun theVersionRowIsPlainContextWhileForeverIsTheOnlyVersion() {
        composeApp()
        awaitText("+ New build")
        composeRule.onNodeWithText("+ New build").performClick()
        awaitText("GAME VERSION")

        composeRule.onNodeWithText("Change").assertDoesNotExist()
    }

    @Test
    fun choosingWarriorPersistsTheDraftBeforeTheEditorOpens() {
        composeApp()
        awaitText("+ New build")
        composeRule.onNodeWithText("+ New build").performClick()
        awaitText("Warrior")

        composeRule.onNodeWithText("Warrior").performClick()

        // the editor is visible AND the draft is already persisted
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        val payload = storedPayload()
        assertTrue(payload.contains("\"class\":\"WARRIOR\""), "a real Warrior BuildFile must be stored")
        assertTrue(payload.contains("\"allocations\":[]"), "a fresh draft starts empty")
    }

    @Test
    fun choosingANonWarriorClassPersistsAndOpensTheSameWay() {
        composeApp()
        awaitText("+ New build")
        composeRule.onNodeWithText("+ New build").performClick()
        awaitText("Mage")

        composeRule.onNodeWithText("Mage").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Mage · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(storedPayload().contains("\"class\":\"MAGE\""))
    }

    @Test
    fun backFromANewDraftGoesHomeAndHomeShowsTheDraft() {
        composeApp()
        awaitText("+ New build")
        composeRule.onNodeWithText("+ New build").performClick()
        awaitText("Warrior")
        composeRule.onNodeWithText("Warrior").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("go back").performClick()

        awaitText("CONTINUE")
        composeRule.onNodeWithText("Draft").assertExists()
    }

    // ------------------------------------------------------------------ All Builds

    @Test
    fun allBuildsListsEveryPersistedBuild() {
        createDraftViaRepository("WARRIOR")
        clock.advanceBy(1_000)
        createDraftViaRepository("MAGE")
        clock.advanceBy(1_000)
        createDraftViaRepository("ROGUE")
        composeApp()
        awaitText("CONTINUE")

        composeRule.onNodeWithText("All builds 3").performClick()
        awaitText("Builds 3")

        // three rows (clickable rows merge their texts; substring match)
        composeRule.onAllNodesWithText("Draft").assertCountEquals(3)
        composeRule.onNodeWithText("Mage · Forever · Just now").assertExists()
    }

    @Test
    fun aFreshAppOverTheSameStorageStillListsTheBuilds() {
        createDraftViaRepository("WARRIOR")
        clock.advanceBy(1_000)
        createDraftViaRepository("MAGE")
        composeApp()
        awaitText("CONTINUE")

        // "relaunch": a brand-new App composition over the same storage
        composeApp()
        awaitText("CONTINUE")
        composeRule.onNodeWithText("All builds 2").performClick()
        awaitText("Builds 2")
        composeRule.onAllNodesWithText("Draft").assertCountEquals(2)
    }

    // ------------------------------------------------------------------ autosave

    @Test
    fun everyMutationAutosavesTheExactResultingBuild() {
        createDraftViaRepository("WARRIOR")
        composeApp()
        awaitText("Continue editing")
        composeRule.onNodeWithText("Continue editing").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }

        // spend one rank of Improved Rend through the real control
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitUntil(10_000) { storedPayload().contains("\"rank\":1") }
        val rendId = warriorNodeId("Improved Rend")
        assertTrue(storedPayload().contains("\"nodeId\":$rendId"))

        // undo is a mutation too: the stored build returns to empty
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitUntil(10_000) { storedPayload().contains("\"allocations\":[]") }

        // reset (already empty is a no-op, so spend again first)
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitUntil(10_000) { storedPayload().contains("\"rank\":1") }
        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitUntil(10_000) { storedPayload().contains("\"allocations\":[]") }
    }

    @Test
    fun selectionAndPanNeverWriteToStorage() {
        createDraftViaRepository("WARRIOR")
        composeApp()
        awaitText("Continue editing")
        composeRule.onNodeWithText("Continue editing").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(10_000) { storage.writeCount >= 2 } // create + open-touch
        val writesBefore = storage.writeCount

        // selection only
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()
        // pan (a real drag on the canvas)
        composeRule.onRoot().performTouchInput { swipe(start = center, end = center - topLeft / 2f) }
        composeRule.waitForIdle()

        assertEquals(writesBefore, storage.writeCount, "selection/pan must not autosave")
        assertTrue(storedPayload().contains("\"allocations\":[]"))
    }
}
