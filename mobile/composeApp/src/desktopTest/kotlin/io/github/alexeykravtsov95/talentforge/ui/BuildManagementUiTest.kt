package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * M5.2a build management against the REAL app root, real bundled datasets and
 * a fake injected storage: the ⋯ row sheet (open, never opens the editor),
 * the rename dialog (save/cancel/disabled/validation/retry), duplicate
 * (success notice + retryable failure) and delete (confirmation, cancel,
 * retryable failure, empty state) — plus the screen-consistency contracts:
 * the list/count reload from the repository and Home reflects the results on
 * return (a deleted Continue promotes the next build).
 *
 * Every scenario seeds TWO builds — a named "Spare" plus the unnamed target
 * (the newest) — because Home offers its "All builds" link only once a RECENT
 * build exists, and because distinct titles keep every row's ⋯ button
 * addressable unambiguously.
 */
class BuildManagementUiTest {

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

    private fun awaitGone(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun createDraftViaRepository(token: String): String {
        val created = runBlocking {
            kotlin.test.assertIs<DraftCreation.Created>(
                createClassDraft(repository(), realDatasets(), ClassToken(token)),
            )
        }
        return created.record.localId
    }

    private fun realDatasets() = io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository(
        io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource(),
    )

    /**
     * Seeds the standard scenario: a named "Spare" build plus the unnamed
     * TARGET (the newest — Home's Continue). Returns nothing; the target row
     * is always addressed by its title "Draft".
     */
    private fun seedSpareAndTarget() {
        val spare = createDraftViaRepository("WARRIOR")
        runBlocking { repository().renameBuild(spare, "Spare") }
        clock.advanceBy(1_000)
        createDraftViaRepository("MAGE")
    }

    /** Home → All Builds; waits for the list title "Builds N". */
    private fun openAllBuilds(expectedCount: Int) {
        awaitText("All builds $expectedCount")
        composeRule.onNodeWithText("All builds $expectedCount").performClick()
        awaitText("Builds $expectedCount")
    }

    private fun openRowActions(rowTitle: String) {
        composeRule.onNodeWithContentDescription("build actions for $rowTitle").performClick()
        awaitText("Duplicate")
    }

    // ------------------------------------------------------------------ the ⋯ sheet

    @Test
    fun theRowMenuOpensWithRenameDuplicateAndDelete() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)

        openRowActions("Draft")

        composeRule.onNodeWithText("Rename").assertExists()
        composeRule.onNodeWithText("Duplicate").assertExists()
        composeRule.onNodeWithText("Delete").assertExists()
    }

    @Test
    fun openingTheRowMenuNeverOpensTheEditor() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)

        openRowActions("Draft")

        // still on All Builds, and no editor is composed behind the sheet
        composeRule.onNodeWithText("Builds 2").assertExists()
        composeRule.onNodeWithContentDescription("open build actions").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ rename

    @Test
    fun renamingADraftRenamesTheRowAndHomeReflectsItOnReturn() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")

        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        // a Draft starts with an EMPTY field — never a guessed name
        composeRule.onNodeWithText("Save").assertIsNotEnabled()

        composeRule.onNodeWithContentDescription("build name").performTextInput("My raid build")
        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithText("Save").performClick()

        awaitGone("Rename build")
        awaitText("My raid build")
        composeRule.onNodeWithText("Builds 2").assertExists()
        assertTrue(storage.payload!!.contains("My raid build"), "the rename must reach storage")

        // back to Home: Continue reflects the new name
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")
        composeRule.onNodeWithText("My raid build").assertExists()
    }

    @Test
    fun cancellingRenameWritesNothing() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        val writesBefore = storage.writeCount

        composeRule.onNodeWithContentDescription("build name").performTextInput("Discarded")
        composeRule.onNodeWithText("Cancel").performClick()

        awaitGone("Rename build")
        assertEquals(writesBefore, storage.writeCount, "Cancel must not write")
        composeRule.onNodeWithText("Discarded").assertDoesNotExist()
    }

    @Test
    fun saveStaysDisabledForBlankAndOverLimitNames() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        val field = composeRule.onNodeWithContentDescription("build name")

        // blank (also whitespace-only) never saves
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        field.performTextInput("   ")
        composeRule.onNodeWithText("Save").assertIsNotEnabled()

        // one over the limit: the live hint flips and Save stays disabled
        // (validation trims first, so the over-limit text is all real characters)
        field.performTextClearance()
        field.performTextInput("x".repeat(61))
        composeRule.onNodeWithText("61 / 60").assertExists()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aFailedRenameKeepsTheTypedTextAndRetries() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        composeRule.onNodeWithContentDescription("build name").performTextInput("My build")

        storage.failWrites = true
        composeRule.onNodeWithText("Save").performClick()
        awaitText("Couldn't rename the build")
        composeRule.onNodeWithText("My build").assertExists() // the user's text survived

        storage.failWrites = false
        composeRule.onNodeWithText("Save").performClick()
        awaitGone("Rename build")
        awaitText("My build")
        assertTrue(storage.payload!!.contains("My build"))
    }

    // ------------------------------------------------------------------ duplicate

    @Test
    fun duplicatingAddsAnIndependentRowAndShowsTheSuccessNotice() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")

        composeRule.onNodeWithText("Duplicate").performClick()

        awaitText("Build duplicated")
        awaitText("Builds 3")
        awaitText("Draft (copy)")
        // the copy is NOT opened: still on All Builds, no editor behind
        composeRule.onNodeWithContentDescription("open build actions").assertDoesNotExist()
        assertTrue(storage.payload!!.contains("Draft (copy)"))
    }

    @Test
    fun aFailedDuplicateShowsARetryableNoticeAndThenSucceeds() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")

        storage.failWrites = true
        composeRule.onNodeWithText("Duplicate").performClick()
        awaitText("Couldn't duplicate the build")
        composeRule.onNodeWithText("Builds 2").assertExists() // the list never faked a row

        storage.failWrites = false
        composeRule.onNodeWithText("Retry").performClick()
        awaitText("Build duplicated")
        awaitText("Builds 3")
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun deleteAsksForConfirmationAndCancelKeepsTheBuild() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")

        composeRule.onNodeWithText("Delete").performClick()

        awaitText("Delete \"Draft\"?")
        composeRule
            .onNodeWithText("This permanently deletes the build from this device and cannot be undone.")
            .assertExists()

        composeRule.onNodeWithText("Cancel").performClick()
        awaitGone("Delete \"Draft\"?")
        composeRule.onNodeWithText("Builds 2").assertExists()
        composeRule.onNodeWithContentDescription("build actions for Draft").assertExists()
    }

    @Test
    fun confirmingDeleteRemovesEachBuildUntilTheEmptyState() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)

        // delete the target…
        openRowActions("Draft")
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Delete \"Draft\"?")
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Builds 1")
        composeRule.onNodeWithContentDescription("build actions for Draft").assertDoesNotExist()

        // …then the last remaining build
        openRowActions("Spare")
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Delete \"Spare\"?")
        composeRule.onNodeWithText("Delete").performClick()

        awaitText("No saved builds yet.")
        awaitText("Builds 0")
        assertTrue(
            composeRule.onAllNodesWithContentDescription("build actions for Spare")
                .fetchSemanticsNodes().isEmpty(),
        )
        // the library is truly empty on storage
        runBlocking {
            val records = kotlin.test.assertIs<
                io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome.Loaded<
                    List<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord>,
                    >,
                >(repository().load())
            assertTrue(records.value.isEmpty())
        }
    }

    @Test
    fun aFailedDeleteKeepsTheDialogOpenAndRetries() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Delete \"Draft\"?")

        storage.failWrites = true
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Couldn't delete the build")
        composeRule.onNodeWithText("Builds 2").assertExists() // a failed delete never looks successful

        storage.failWrites = false
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Builds 1")
    }

    // ------------------------------------------------------------------ consistency

    @Test
    fun deletingTheContinueBuildPromotesTheNextBuildOnHome() {
        createDraftViaRepository("WARRIOR").also { warriorId ->
            runBlocking { repository().renameBuild(warriorId, "Keeper") }
        }
        clock.advanceBy(1_000)
        createDraftViaRepository("MAGE") // newest → Home's Continue
        composeApp()
        awaitText("CONTINUE")
        composeRule.onNodeWithText("Draft").assertExists() // the Mage draft is Continue

        openAllBuilds(expectedCount = 2)
        openRowActions("Draft") // the Mage row (only unnamed row)
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Delete \"Draft\"?")
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Builds 1")

        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")
        composeRule.onNodeWithText("Keeper").assertExists() // Continue moved to the next build
    }

    @Test
    fun openingABuildFromTheListStillWorksAfterManagement() {
        seedSpareAndTarget()
        composeApp()
        openAllBuilds(expectedCount = 2)
        openRowActions("Draft")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        composeRule.onNodeWithContentDescription("build name").performTextInput("Renamed")
        composeRule.onNodeWithText("Save").performClick()
        awaitText("Renamed")

        composeRule.onNodeWithText("Renamed").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription("open build actions").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("Builds 2")
    }
}
