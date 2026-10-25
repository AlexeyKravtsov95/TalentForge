package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.navigation.BACK_SWIPE_BENEATH_TAG
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.EdgeSwipeBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.LocalEdgeSwipeBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.tree.EditorCopy
import io.github.alexeykravtsov95.talentforge.ui.tree.TALENT_INSPECTOR_COMPACT_TAG
import io.github.alexeykravtsov95.talentforge.ui.tree.TALENT_INSPECTOR_SHEET_TAG
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The iOS left-edge swipe back through the real app. The test plays the iOS
 * host's recognizer: it feeds the app's [EdgeSwipeBackDispatcher] the same
 * started / progressed / completed-or-cancelled events the Compose host
 * derives from the finger. The swipe does what the header chevron does — it
 * pops the screen (never just deselects), only after the autosave flush, at
 * most once per gesture, never past Home — while sheets and dialogs take it
 * first, and a cancelled swipe leaves the screen and its state untouched.
 * Android's system Back order (desktop Esc) is unchanged.
 */
class EdgeSwipeBackAppUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val swipe = EdgeSwipeBackDispatcher()

    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
    }

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun persistDraft(token: String) {
        assertIs<DraftCreation.Created>(runBlocking { createClassDraft(repository(), datasets, ClassToken(token)) })
        clock.advanceBy(60_000)
    }

    private fun composeApp(reducedMotion: Boolean? = null) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalEdgeSwipeBackDispatcher provides swipe,
                    LocalReducedMotion provides reducedMotion,
                ) {
                    Box(Modifier.size(360.dp, 760.dp)) { App(storage = storage, clock = clock, ids = ids) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun awaitText(text: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitGone(text: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun has(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasTag(tag: String) =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun onEditor(): Boolean = has("Warrior · Forever")

    private fun openEditorFromHome() {
        awaitText("Continue editing")
        composeRule.onNodeWithText("Continue editing").performClick()
        composeRule.waitUntil(10_000) { onEditor() }
    }

    private fun selectImprovedRend() {
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()
        assertTrue(hasTag(TALENT_INSPECTOR_COMPACT_TAG), "the talent is selected")
    }

    /** The finger touches the left edge and moves to [progress] of the width; `false` when nothing took it. */
    private fun swipeTo(progress: Float): Boolean {
        var taken = false
        composeRule.runOnIdle {
            taken = swipe.start()
            swipe.progress(progress)
        }
        composeRule.waitForIdle()
        return taken
    }

    private fun release(complete: Boolean) {
        composeRule.runOnIdle { if (complete) swipe.complete() else swipe.cancel() }
        composeRule.waitForIdle()
    }

    private fun storedWarriorRank(): Int {
        val records = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository().load()).value
        return records.first { it.build.classToken.value == "WARRIOR" }.build.allocations.single().rank
    }

    // ------------------------------------------------------------------ Home

    @Test
    fun homeHasNoSwipeBack() {
        persistDraft("WARRIOR")
        composeApp()
        awaitText("CONTINUE")
        composeRule.runOnIdle { assertFalse(swipe.hasEnabledHandler, "the host keeps its recognizer off on Home") }
        assertFalse(swipeTo(0.6f))
        release(complete = true)
        assertTrue(has("CONTINUE"), "Home stays")

        // and after a pop back to Home it is off again
        openEditorFromHome()
        composeRule.runOnIdle { assertTrue(swipe.hasEnabledHandler) }
        swipeTo(0.6f)
        release(complete = true)
        awaitText("CONTINUE")
        composeRule.runOnIdle { assertFalse(swipe.hasEnabledHandler) }
    }

    // ------------------------------------------------------------------ cancel / complete

    @Test
    fun aCancelledSwipeKeepsTheEditorItsSelectionAndItsInspector() {
        persistDraft("WARRIOR")
        composeApp()
        openEditorFromHome()
        selectImprovedRend()
        composeRule.onNodeWithContentDescription(EditorCopy.English.expand).performClick()
        composeRule.waitForIdle()
        assertTrue(hasTag(TALENT_INSPECTOR_SHEET_TAG))

        assertTrue(swipeTo(0.4f))
        composeRule.waitUntil(5_000) { hasTag(BACK_SWIPE_BENEATH_TAG) }
        assertTrue(onEditor(), "the editor follows the finger, still composed")
        assertFalse(has("CONTINUE"), "Home beneath is out of the accessibility tree")

        release(complete = false)
        composeRule.waitUntil(5_000) { !hasTag(BACK_SWIPE_BENEATH_TAG) }
        assertTrue(onEditor(), "a cancelled swipe stays on the editor")
        assertTrue(hasTag(TALENT_INSPECTOR_SHEET_TAG), "… with its selection and expanded inspector")

        // the swipe is available again at once
        assertTrue(swipeTo(0.2f))
        release(complete = false)
        assertTrue(onEditor())
    }

    @Test
    fun aCompletedSwipePopsTheEditorEvenWithASelectionAndTheSpendIsSaved() {
        persistDraft("WARRIOR")
        composeApp()
        openEditorFromHome()
        // the newest save is still in flight when the finger is lifted
        storage.writeDelayMillis = 1_500
        selectImprovedRend()
        composeRule.onNodeWithText("+").performClick()

        assertTrue(swipeTo(0.7f))
        release(complete = true)
        awaitText("CONTINUE")
        assertFalse(onEditor(), "the swipe popped the editor instead of clearing the selection")
        assertFalse(hasTag(BACK_SWIPE_BENEATH_TAG))
        assertEquals(1, storedWarriorRank(), "the spend made before the swipe is on storage")
    }

    @Test
    fun theDesktopSystemBackStillWalksTheInspectorOrder() {
        persistDraft("WARRIOR")
        composeApp()
        openEditorFromHome()
        selectImprovedRend()

        composeRule.runOnIdle { assertTrue(DesktopBackDispatcher.dispatch()) }
        composeRule.waitForIdle()
        assertFalse(hasTag(TALENT_INSPECTOR_COMPACT_TAG), "Esc / Android Back clears the selection first, as before")
        assertTrue(onEditor())
    }

    // ------------------------------------------------------------------ sheets and dialogs

    @Test
    fun theBuildActionsSheetAndTheRenameDialogTakeTheSwipeFirst() {
        persistDraft("WARRIOR")
        composeApp()
        openEditorFromHome()

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        awaitText("Rename")
        assertTrue(swipeTo(0.6f))
        assertFalse(hasTag(BACK_SWIPE_BENEATH_TAG), "the sheet holds the swipe: no screen moves")
        release(complete = true)
        awaitGone("Rename")
        assertTrue(onEditor(), "the swipe closed the sheet only")

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        awaitText("Rename")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        swipeTo(0.6f)
        release(complete = true)
        awaitGone("Rename build")
        assertTrue(onEditor(), "the swipe cancelled the rename only")

        swipeTo(0.6f)
        release(complete = true)
        awaitText("CONTINUE")
    }

    // ------------------------------------------------------------------ repeated gestures

    @Test
    fun rapidRepeatedSwipesPopExactlyOnce() {
        persistDraft("WARRIOR")
        persistDraft("MAGE")
        composeApp()
        awaitText("CONTINUE")
        composeRule.onNodeWithText("All builds 2").performClick()
        awaitText("Builds 2")
        composeRule.onNodeWithText("Warrior · Forever", substring = true).performClick()
        composeRule.waitUntil(10_000) { onEditor() }

        storage.writeDelayMillis = 2_500
        selectImprovedRend()
        composeRule.onNodeWithText("+").performClick()
        // three quick gestures while the first one's flush is still in flight
        repeat(3) {
            composeRule.runOnIdle {
                swipe.start()
                swipe.progress(0.8f)
                swipe.complete()
            }
        }
        composeRule.waitForIdle()

        // exactly one pop: [Home, AllBuilds, Editor] -> [Home, AllBuilds]
        awaitText("Builds 2")
        assertFalse(onEditor())
        assertFalse(has("CONTINUE"), "the further gestures did not pop All Builds")
        assertEquals(1, storedWarriorRank())

        swipeTo(0.8f)
        release(complete = true)
        awaitText("CONTINUE")
        assertFalse(swipeTo(0.8f), "Home is the root")
    }

    @Test
    fun aGestureStartedWhileAnotherSettlesNeverPopsLater() {
        persistDraft("WARRIOR")
        persistDraft("MAGE")
        composeApp()
        awaitText("CONTINUE")
        composeRule.onNodeWithText("All builds 2").performClick()
        awaitText("Builds 2")
        composeRule.onNodeWithText("Warrior · Forever", substring = true).performClick()
        composeRule.waitUntil(10_000) { onEditor() }

        composeRule.runOnIdle {
            swipe.start()
            swipe.progress(0.8f)
            swipe.complete()
            // a second finger-down before the first swipe has settled
            swipe.start()
            swipe.progress(0.8f)
        }
        composeRule.waitForIdle()
        awaitText("Builds 2")
        // released only after the first pop is done: it must not pop All Builds as well
        release(complete = true)
        assertTrue(has("Builds 2"))
        assertFalse(has("CONTINUE"))
    }

    // ------------------------------------------------------------------ save failure

    @Test
    fun aFailedSaveKeepsTheEditorAfterACompletedSwipeUntilRetrySucceeds() {
        persistDraft("WARRIOR")
        composeApp()
        openEditorFromHome()
        storage.failWrites = true
        selectImprovedRend()
        composeRule.onNodeWithText("+").performClick()
        awaitText("Couldn't save your changes on this device")

        assertTrue(swipeTo(0.7f))
        assertFalse(hasTag(BACK_SWIPE_BENEATH_TAG), "nothing is shown beneath while the newest save failed")
        release(complete = true)
        composeRule.waitForIdle()
        assertTrue(onEditor(), "a failed newest save keeps the editor")
        assertTrue(has("Couldn't save your changes on this device"), "… with its retryable notice")
        assertFalse(has("CONTINUE"))

        storage.failWrites = false
        composeRule.onNodeWithText("Retry").performClick()
        awaitGone("Couldn't save your changes on this device")
        swipeTo(0.7f)
        release(complete = true)
        awaitText("CONTINUE")
        assertEquals(1, storedWarriorRank())
    }

    // ------------------------------------------------------------------ motion

    @Test
    fun withReducedMotionACompletedSwipeLeavesWithinAFewFrames() {
        persistDraft("WARRIOR")
        composeApp(reducedMotion = true)
        openEditorFromHome()
        composeRule.mainClock.autoAdvance = false
        swipeTo(0.7f)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnUiThread { swipe.complete() }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        assertFalse(onEditor(), "no settle animation: the editor is gone within a few frames")
        composeRule.mainClock.autoAdvance = true
        awaitText("CONTINUE")
    }

    @Test
    fun withMotionACompletedSwipeSlidesTheEditorOutFirst() {
        persistDraft("WARRIOR")
        composeApp(reducedMotion = false)
        openEditorFromHome()
        composeRule.mainClock.autoAdvance = false
        swipeTo(0.3f)
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.runOnUiThread { swipe.complete() }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        assertTrue(onEditor(), "the editor is still sliding out")
        composeRule.mainClock.autoAdvance = true
        awaitText("CONTINUE")
        assertFalse(onEditor())
    }
}
