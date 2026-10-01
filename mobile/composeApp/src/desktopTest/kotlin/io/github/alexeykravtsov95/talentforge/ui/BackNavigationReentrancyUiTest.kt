package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * Back navigation is NON-REENTRANT while an autosave flush is in flight
 * (review fix): repeated Back (chevron or Android system back) before the
 * flush completes must not launch a second pop — otherwise two concurrent
 * pops can remove Home and leave an empty stack. One Back in flight at a
 * time; a failed flush keeps the editor visible; Retry then a single Back
 * pops exactly one destination; Home is never removed.
 */
class BackNavigationReentrancyUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private val clock = FixedClock()
    private val ids = SequentialIds()

    private val datasets = io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository(
        io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource(),
    )

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
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

    private fun persistDraft(token: String) {
        assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository(), datasets, ClassToken(token)) },
        )
    }

    private fun persistWarriorDraft() = persistDraft("WARRIOR")

    /** Opens the editor through Home → Continue (no mutation yet). */
    private fun openEditorFromHome() {
        composeApp()
        awaitText("Continue editing")
        composeRule.onNodeWithText("Continue editing").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Spends one rank through the real control (selection never writes). */
    private fun spendOneRank() {
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.onNodeWithText("+").performClick()
    }

    private fun backTwiceImmediately() {
        composeRule.onNodeWithContentDescription("go back").performClick()
        // still on the editor — the flush is held in flight by the write delay
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("go back").performClick()
    }

    private fun storedWarriorRank(): Int {
        val records = assertIs<BuildLibraryOutcome.Loaded<List<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord>>>(
            repository().load(),
        ).value
        return records.first { it.build.classToken.value == "WARRIOR" }
            .build.allocations.single().rank
    }

    // ------------------------------------------------------------------ A

    @Test
    fun doubleBackDuringFlushPopsExactlyOnceAndKeepsHome() {
        persistWarriorDraft()
        openEditorFromHome()

        // the write delay is set BEFORE the mutation, so its save is held in
        // flight when both Back presses land
        storage.writeDelayMillis = 2_500
        spendOneRank()
        backTwiceImmediately()

        // exactly ONE pop: [Home, Editor] -> [Home]; the second Back was a no-op
        awaitText("CONTINUE")
        composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().let {
            assertEquals(0, it.size, "the editor must be gone after the single pop")
        }
        composeRule.onAllNodesWithContentDescription("go back").fetchSemanticsNodes().let {
            assertEquals(0, it.size, "Home is the root — there is no back affordance left to empty the stack")
        }
        assertEquals(1, storedWarriorRank())
    }

    // ------------------------------------------------------------------ B

    @Test
    fun doubleBackDuringFlushFromAllBuildsStopsAtAllBuilds() {
        persistWarriorDraft()
        persistDraft("MAGE") // a second build makes the RECENT/All-builds row appear
        composeApp()
        awaitText("CONTINUE")
        composeRule.onNodeWithText("All builds 2").performClick()
        awaitText("Builds 2")
        composeRule.onNodeWithText("Warrior · Forever · Just now").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        storage.writeDelayMillis = 2_500
        spendOneRank()
        backTwiceImmediately()

        // exactly one pop: [Home, AllBuilds, Editor] -> [Home, AllBuilds]
        awaitText("Builds 2")
        composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        assertEquals(1, storedWarriorRank())

        // a later Back (flush already drained) pops the remaining one
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")
    }

    // ------------------------------------------------------------------ C

    @Test
    fun repeatedBackWhileTheNewestSaveFailsNeverPops() {
        persistWarriorDraft()
        openEditorFromHome()

        storage.failWrites = true
        // a fresh submission while writes fail keeps the newest state unpersisted
        spendOneRank()
        awaitText("Couldn't save your changes on this device")

        repeat(3) {
            composeRule.onNodeWithContentDescription("go back").performClick()
            composeRule.waitForIdle()
        }

        // no pop ever happened: still the editor, failure still visible
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Couldn't save your changes on this device")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("CONTINUE").fetchSemanticsNodes().let {
            assertEquals(0, it.size, "a failed newest save must keep the editor on screen")
        }
    }

    // ------------------------------------------------------------------ D

    @Test
    fun afterRetrySucceedsASingleBackPopsExactlyOneDestination() {
        persistWarriorDraft()
        openEditorFromHome()

        storage.failWrites = true
        spendOneRank()
        awaitText("Couldn't save your changes on this device")
        composeRule.onNodeWithContentDescription("go back").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("CONTINUE").fetchSemanticsNodes().let {
            assertEquals(0, it.size, "Back stays blocked while the newest save failed")
        }

        storage.failWrites = false
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Couldn't save your changes on this device")
                .fetchSemanticsNodes().isEmpty()
        }

        // one Back now pops exactly one destination — and only one
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")
        composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        assertEquals(1, storedWarriorRank(), "the retried newest state (rank 1) is the persisted one")
    }
}
