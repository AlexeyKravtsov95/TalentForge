package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage

/**
 * M4.3 interactive-allocation UI behaviour against the real Warrior screen:
 * explicit + / − / Max controls mutate through the engine-backed editing
 * session, the presentation updates live, undo/redo/reset work through one
 * history, and the frozen M4.1/M4.2 invariants (tap never spends, viewport
 * and selection stability, Show behaviour) still hold.
 */
class TalentTreeEditingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var readyState: TalentTreeUiState.Ready

    @BeforeTest
    fun loadRealWarriorState() {
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
    }

    private fun nodeIdOf(name: String): Int =
        assertNotNull(readyState.engine.tree.nodes.firstOrNull { it.name == name }).id

    /** A valid prepared build (engine-spent, never hand-written ranks). */
    private fun presetBuild(vararg ranks: Pair<String, Int>): BuildState {
        var session = TalentBuildEditingState.start(readyState.engine)
        for ((name, count) in ranks) {
            val nodeId = nodeIdOf(name)
            repeat(count) {
                val result = session.spend(readyState.engine, nodeId)
                assertTrue(result.changed, "preset spend on $name must succeed")
                session = result.editing
            }
        }
        return session.current
    }

    /** The M5.UX.1 section cameras of the composed screen (portrait shows one section per tab). */
    private lateinit var sectionCameras: TalentTreeSectionCameras

    /**
     * Composes the screen and returns the camera of what the canvas shows:
     * the open section's own camera in portrait, the whole-tree camera
     * otherwise.
     */
    private fun compose(
        state: TalentTreeUiState,
        width: Dp = 360.dp,
        height: Dp = 760.dp,
    ): TalentTreeViewportState {
        val viewportState = TalentTreeViewportState(initial = null)
        sectionCameras = TalentTreeSectionCameras(sessionKey = "editing-test", treeId = readyState.model.treeId)
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(state, viewportState = viewportState, sectionCameras = sectionCameras)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return if (width < height) sectionCameras.stateOf(sections().sections.first().index) else viewportState
    }

    private fun sections(): TalentTreeSectionsResult.Sectioned =
        buildTalentTreeSections(readyState.engine.tree, readyState.model) as TalentTreeSectionsResult.Sectioned

    /** Opens the portrait tab of [name]'s section (one tap, never a build change). */
    private fun openSectionOf(name: String) {
        val section = assertNotNull(sections().sectionOf(nodeIdOf(name)))
        val label = talentSectionDisplayLabel(
            datasetName = section.datasetName,
            classToken = readyState.classToken,
            sectionIndex = section.index,
            ordinal = section.ordinal,
            language = AppLanguage.ENGLISH,
        )
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    private fun select(name: String) {
        composeRule.onNodeWithContentDescription(name).performClick()
        composeRule.waitForIdle()
    }

    private fun spend(name: String) {
        select(name)
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
    }

    // ------------------------------------------------------------------ the frozen tap contract

    @Test
    fun tappingANodeNeverSpendsEvenWithInteractiveAllocationExisting() {
        compose(readyState)

        // five taps on an available talent…
        repeat(5) {
            composeRule.onNodeWithContentDescription("Improved Rend").performClick()
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithText("0 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "0/3", "Available")).assertExists()
        composeRule.onNodeWithContentDescription("Improved Rend").assertIsSelected()
    }

    // ------------------------------------------------------------------ compact action set

    @Test
    fun selectedAvailableTalentShowsAnEnabledPlusOnly() {
        compose(readyState)
        select("Improved Rend")

        composeRule.onNodeWithText("+").assertExists()
        composeRule.onNodeWithText("+").assertIsEnabled()
        composeRule.onNodeWithText("−").assertDoesNotExist()
        // B3 03: "There is no Max in the compact bar."
        composeRule.onNodeWithText("Max").assertDoesNotExist()
    }

    @Test
    fun plusSpendsExactlyOneRankPerPressAndUpdatesTheCounterLive() {
        compose(readyState)

        select("Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "1/3", "In progress")).assertExists()

        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "2/3", "In progress")).assertExists()

        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "3/3", "Maxed")).assertExists()
    }

    @Test
    fun partialTalentShowsMinusAndPlusSideBySide() {
        compose(readyState)
        select("Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("−").assertExists()
        composeRule.onNodeWithText("−").assertIsEnabled()
        composeRule.onNodeWithText("+").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "1/3", "In progress")).assertExists()
    }

    @Test
    fun maxedTalentShowsMinusAndANonInteractiveCheckInsteadOfPlus() {
        compose(readyState)
        select("Improved Rend")
        repeat(3) {
            composeRule.onNodeWithText("+").performClick()
            composeRule.waitForIdle()
        }

        composeRule.onNode(hasTextExactly("Improved Rend", "3/3", "Maxed")).assertExists()
        composeRule.onNodeWithText("−").assertExists()
        composeRule.onNodeWithText("✓").assertExists()
        // B3 03: "+ becomes a check badge (not a button)" — nothing to trigger.
        composeRule.onNodeWithText("✓").assertHasNoClickAction()
        composeRule.onNodeWithText("+").assertDoesNotExist()
    }

    @Test
    fun tierLockedTalentHasNoFalsePurchaseAction() {
        compose(readyState)
        select("Improved Charge")

        composeRule.onNodeWithText("Show").assertDoesNotExist()
        composeRule.onNodeWithText("+").assertDoesNotExist()
        composeRule.onNodeWithText("−").assertDoesNotExist()
        // the real tier meter stays the explanation
        composeRule.onNode(hasTextExactly("Improved Charge", "0/2", "Spend 5 more points in this tree"))
            .assertExists()
    }

    @Test
    fun prerequisiteLockedTalentKeepsShowAndNoPurchaseAction() {
        compose(readyState)
        select("Deep Wounds")

        composeRule.onNodeWithText("Show").assertExists()
        composeRule.onNodeWithText("+").assertDoesNotExist()
        composeRule.onNodeWithText("−").assertDoesNotExist()
    }

    @Test
    fun noPointsTalentShowsAVisibleButDisabledPlus() {
        val full = readyState.copy(buildState = fullBudgetBuild())
        compose(full)

        val noPoints = noPointsTalentName(full)
        openSectionOf(noPoints)
        select(noPoints)

        composeRule.onNodeWithText("+").assertExists()
        composeRule.onNodeWithText("+").assertIsNotEnabled()
        // B3 04 state 07: "+ is shown disabled and the state line explains
        // why" — the frozen wording carries the live budget count.
        composeRule.onNodeWithText("No talent points left · 51/51 spent").assertExists()
        // B3 04 state 07: "Refund still works on any talent with ranks." —
        // an unranked talent offers no −.
        if (rankOf(full, noPoints) == 0) {
            composeRule.onNodeWithText("−").assertDoesNotExist()
        }
    }

    @Test
    fun partialTalentAtTheFullBudgetKeepsItsStateAndExplainsTheDisabledPlus() {
        val full = readyState.copy(buildState = fullBudgetBuild())
        compose(full)

        // the greedy fill leaves exactly one talent mid-rank at 51/51: its
        // visual state stays PARTIAL ("In progress" is NOT the no-points
        // state — the UI precedence is deliberate), so the disabled + must
        // explain itself (B3 04 state 07).
        val partial = partialTalentName(full)
        openSectionOf(partial)
        select(partial)

        composeRule.onNodeWithText("+").assertExists()
        composeRule.onNodeWithText("+").assertIsNotEnabled()
        composeRule.onNodeWithText("−").assertExists() // "Refund still works on any talent with ranks."
        composeRule.onNodeWithText("No talent points left · 51/51 spent").assertExists()
    }

    @Test
    fun enabledActionsNeverShowADenialReason() {
        compose(readyState)
        select("Improved Rend")

        // available + enabled: the plain B3 state line, never a false reason
        composeRule.onNode(hasTextExactly("Improved Rend", "0/3", "Available")).assertExists()
        composeRule.onNodeWithText("No talent points left · 51/51 spent").assertDoesNotExist()
        composeRule.onNodeWithText("Needed by Deep Wounds").assertDoesNotExist()

        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()

        // partial with both actions enabled: still the plain state line
        composeRule.onNode(hasTextExactly("Improved Rend", "1/3", "In progress")).assertExists()
        composeRule.onNodeWithText("No talent points left · 51/51 spent").assertDoesNotExist()
        composeRule.onNodeWithText("Needed by Deep Wounds").assertDoesNotExist()
    }

    @Test
    fun minusIsDisabledWhenTheEngineDeniesTheRefund() {
        // Improved Rend 3/3 with Deep Wounds allocated: refunding Improved
        // Rend would break the dependent — the button must be disabled, not
        // a cascade.
        val preset = readyState.copy(
            buildState = presetBuild(
                "Improved Rend" to 3,
                "Deflection" to 5,
                "Improved Charge" to 2,
                "Deep Wounds" to 1,
            ),
        )
        compose(preset)
        select("Improved Rend")

        composeRule.onNodeWithText("−").assertExists()
        composeRule.onNodeWithText("−").assertIsNotEnabled()
        composeRule.onNodeWithText("+").assertDoesNotExist() // maxed: check badge instead
        // B3 07: a blocked refund shows its reason in the state line. A
        // disabled control can never be clicked into a denial, so the reason
        // must already be there — derived from the engine's typed issue, with
        // the real dependent's name.
        composeRule.onNode(hasTextExactly("Improved Rend", "3/3", "Needed by Deep Wounds"))
            .assertExists()
        // no hidden trigger either: expanding the inspector and reading the
        // full row still cannot alter the build while the refund is blocked
        composeRule.onNode(hasTextExactly("Improved Rend", "3/3", "Needed by Deep Wounds"))
            .performClick() // expands the sheet
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Max").assertExists()
        composeRule.onNodeWithText("−").assertIsNotEnabled()
        composeRule.onNodeWithText("11 / 51").assertExists()
    }

    @Test
    fun successfulMinusRemovesOneLegalRank() {
        val preset = readyState.copy(buildState = presetBuild("Improved Rend" to 3))
        compose(preset)
        select("Improved Rend")

        composeRule.onNodeWithText("−").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("2 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "2/3", "In progress")).assertExists()
    }

    // ------------------------------------------------------------------ expanded sheet extras

    @Test
    fun expandedSheetOffersTheFullRankMaxActionRow() {
        compose(readyState)
        select("Improved Rend")
        composeRule.onNodeWithText("Improved Rend").performClick() // name area expands
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Max").assertExists()
        composeRule.onNodeWithText("Rank").assertExists()
        composeRule.onNodeWithText("−").assertExists()
        composeRule.onNodeWithText("+").assertExists()
    }

    @Test
    fun maxSpendsAllRemainingRanksAsOneUndoableStep() {
        compose(readyState)
        select("Improved Rend")
        composeRule.onNodeWithText("Improved Rend").performClick() // expand
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Max").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("3 / 51").assertExists()
        composeRule.onNodeWithText("Maxed").assertExists()

        // ONE undo entry: a single header undo returns to 0/3
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0 / 51").assertExists()
        composeRule.onNodeWithText("Available").assertExists()
    }

    // ------------------------------------------------------------------ undo / redo

    @Test
    fun undoAndRedoButtonsReflectTheHistories() {
        compose(readyState)

        composeRule.onNodeWithContentDescription("Undo talent change").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Redo talent change").assertIsNotEnabled()

        spend("Improved Rend")

        composeRule.onNodeWithContentDescription("Undo talent change").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Redo talent change").assertIsNotEnabled()
    }

    @Test
    fun undoUpdatesTheEntireTreePresentationAndRedoReappliesIt() {
        compose(readyState)
        spend("Improved Rend")
        spend("Improved Rend")

        composeRule.onNodeWithText("2 / 51").assertExists()

        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "1/3", "In progress")).assertExists()

        composeRule.onNodeWithContentDescription("Redo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "2/3", "In progress")).assertExists()
    }

    @Test
    fun newMutationAfterUndoClearsRedo() {
        compose(readyState)
        spend("Improved Rend")
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Redo talent change").assertIsEnabled()

        select("Deflection")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Redo talent change").assertIsNotEnabled()
        composeRule.onNodeWithText("1 / 51").assertExists()
    }

    // ------------------------------------------------------------------ reset

    @Test
    fun resetClearsTheBuildAndShowsTheRecoverableFeedback() {
        compose(readyState)
        spend("Improved Rend")
        spend("Deflection")

        composeRule.onNodeWithText("2 / 51").assertExists()

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("0 / 51").assertExists()
        composeRule.onNodeWithText("Talents reset · 2 points cleared").assertExists()
    }

    @Test
    fun undoAfterResetRestoresTheExactPriorBuildThroughTheSameHistory() {
        compose(readyState)
        spend("Improved Rend")
        repeat(2) {
            composeRule.onNodeWithText("+").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("3 / 51").assertExists()

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0 / 51").assertExists()

        // the snackbar's Undo is the SAME history mechanism (no separate backup)
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("3 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "3/3", "Maxed")).assertExists()
    }

    @Test
    fun aLaterSuccessfulMutationInvalidatesTheResetNotice() {
        compose(readyState)
        spend("Improved Rend") // 1/51, still selected

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()

        // singular count copy: "1 point cleared", never "1 points cleared"
        composeRule.onNodeWithText("Talents reset · 1 point cleared").assertExists()
        composeRule.onNodeWithText("Talents reset · 1 points cleared").assertDoesNotExist()

        // a successful spend is a NEW latest history operation: the notice's
        // "Undo" would now revert the wrong action, so it must be gone
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()
        composeRule.onNodeWithText("Talents reset · 1 point cleared").assertDoesNotExist()

        // the normal header Undo undoes the SPEND (back to the post-reset
        // empty build), proving the notice was dismissed rather than left to
        // promise the wrong undo
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "0/3", "Available")).assertExists()
    }

    @Test
    fun nonMutationInteractionsKeepTheResetNotice() {
        compose(readyState)
        spend("Improved Rend")
        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Talents reset · 1 point cleared").assertExists()

        // selection changes, expansion and Show navigation touch no history,
        // so the reset notice (whose Undo still reverts exactly the reset)
        // stays valid — only a later SUCCESSFUL mutation dismisses it.
        select("Deflection")
        composeRule.onNodeWithText("Talents reset · 1 point cleared").assertExists()
        composeRule.onNodeWithText("Deflection").performClick() // expand
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Talents reset · 1 point cleared").assertExists()
        composeRule.onNodeWithText("×").performClick() // deselect
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Talents reset · 1 point cleared").assertExists()
    }

    // ------------------------------------------------------------------ reset feedback placement

    @Test
    fun resetFeedbackNeverCoversTheCompactInspector() {
        compose(readyState)
        spend("Improved Rend")

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()

        val notice = boundsOf(composeRule.onNodeWithText("Talents reset · 1 point cleared"))
        val inspector = boundsOf(
            composeRule.onNode(hasTextExactly("Improved Rend", "0/3", "Available")),
        )
        assertTrue(
            notice.bottom <= inspector.top,
            "the floating notice (bottom=${notice.bottom}) must sit fully above the " +
                "compact inspector (top=${inspector.top})",
        )
        // the inspector's + stays visible and actionable under the notice
        composeRule.onNodeWithText("+").assertExists()
        composeRule.onNodeWithText("+").assertIsEnabled()
    }

    @Test
    fun resetFeedbackLeavesTheExpandedSheetFullyUsable() {
        compose(readyState)
        select("Improved Rend")
        composeRule.onNodeWithText("Improved Rend").performClick() // expand the sheet
        composeRule.waitForIdle()
        composeRule.onNodeWithText("+").performClick() // 1/51 through the sheet's own row
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()

        val notice = boundsOf(composeRule.onNodeWithText("Talents reset · 1 point cleared"))
        val maxControl = boundsOf(composeRule.onNodeWithText("Max"))
        assertTrue(
            notice.bottom <= maxControl.top,
            "the floating notice (bottom=${notice.bottom}) must not reach the sheet's " +
                "action row (top=${maxControl.top})",
        )
        // the whole − + Rank Max row stays interactive
        composeRule.onNodeWithText("−").assertExists()
        composeRule.onNodeWithText("+").assertIsEnabled()
        composeRule.onNodeWithText("Max").assertIsEnabled()
    }

    @Test
    fun resetFeedbackWithNoSelectionFloatsAtTheTopOfTheCanvas() {
        compose(readyState)
        spend("Improved Rend")
        // deselect through the expanded sheet's × (compact portrait has no ×)
        composeRule.onNodeWithText("Improved Rend").performClick() // expand
        composeRule.waitForIdle()
        composeRule.onNodeWithText("×").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasTextExactly("Improved Rend", "1/3", "In progress")).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()

        // no inspector exists to collide with; the notice floats over the top
        // of the canvas — since M5.UX.1 the canvas starts below the 48 dp
        // section tabs, which the notice must leave usable (density 1 in this
        // test: header 44 + tabs 48 + 12 gap)
        val notice = boundsOf(composeRule.onNodeWithText("Talents reset · 1 point cleared"))
        val tabs = TalentTreeSectionTabsMetrics.TAB_HEIGHT_DP.value
        assertTrue(
            notice.top < 120f + tabs,
            "with nothing selected the notice stays at the top of the canvas (top=${notice.top})",
        )
        assertTrue(
            notice.top >= TalentTreeHeaderMetrics.HEADER_HEIGHT_DP.value + tabs,
            "the notice never covers the section tabs (top=${notice.top})",
        )
    }

    @Test
    fun resetOnAnEmptyBuildCreatesNoHistoryAndNoFeedback() {
        compose(readyState)

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("0 / 51").assertExists()
        composeRule.onNodeWithContentDescription("Undo talent change").assertIsNotEnabled()
        composeRule.onNodeWithText("Talents reset · 0 points cleared").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ stability invariants

    @Test
    fun viewportDoesNotResetAfterSpendRefundUndoOrRedo() {
        val viewportState = compose(readyState)
        select("Improved Rend")
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        val before = assertNotNull(viewportState.viewport)

        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("−").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Redo talent change").performClick()
        composeRule.waitForIdle()

        assertEquals(before, viewportState.viewport, "mutations never reset the viewport")
    }

    @Test
    fun selectionSurvivesSpendAndRefund() {
        compose(readyState)
        spend("Improved Rend")
        composeRule.onNodeWithContentDescription("Improved Rend").assertIsSelected()

        composeRule.onNodeWithText("−").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Improved Rend").assertIsSelected()
        composeRule.onNode(hasTextExactly("Improved Rend", "0/3", "Available")).assertExists()
    }

    @Test
    fun aSpendDoesNotTriggerShow() {
        compose(readyState)
        select("Deep Wounds")
        composeRule.onNodeWithText("Show").performClick()
        composeRule.waitForIdle()
        // Show selected Improved Rend; now spend one rank on it
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()

        // the mutation cleared the guide and kept the selection — no auto-Show
        composeRule.onNodeWithContentDescription("Deep Wounds").assertIsNotSelected()
        composeRule.onNodeWithText("Available · needed for Deep Wounds").assertDoesNotExist()
        composeRule.onNodeWithText("1 / 51").assertExists()
    }

    // ------------------------------------------------------------------ one build across layouts

    @Test
    fun portraitLandscapeAndTabletOperateOnTheSameCurrentBuildAndHistory() {
        var window by mutableStateOf(IntSize(360, 760))
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(window.width.dp, window.height.dp)) {
                        TalentTreeScreen(readyState)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // portrait: spend one rank
        select("Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()

        // → landscape: the same build (side inspector) keeps the rank
        composeRule.runOnIdle { window = IntSize(760, 360) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()
        // the side panel shows name / rank / state as separate detail nodes
        composeRule.onNodeWithText("Improved Rend").assertExists()
        composeRule.onNodeWithText("In progress").assertExists()
        // "1/3" twice: the node's rank chip + the side panel's rank line
        composeRule.onAllNodes(hasTextExactly("1/3")).assertCountEquals(2)

        // → tablet: the persistent inspector shows the same build + the history
        composeRule.runOnIdle { window = IntSize(840, 600) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()
        composeRule.onNodeWithContentDescription("Undo talent change").assertIsEnabled()

        // and undo works from the tablet layout
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0 / 51").assertExists()
    }

    // ------------------------------------------------------------------ helpers for real presets

    /** A valid 51/51 build produced by greedy engine spends (never hand-written). */
    private fun fullBudgetBuild(): BuildState {
        var session = TalentBuildEditingState.start(readyState.engine)
        while (readyState.engine.spentPoints(session.current) < 51) {
            val before = readyState.engine.spentPoints(session.current)
            for (node in readyState.engine.tree.nodes) {
                if (readyState.engine.spentPoints(session.current) >= 51) break
                val result = session.spend(readyState.engine, node.id)
                if (result.changed) session = result.editing
            }
            val after = readyState.engine.spentPoints(session.current)
            assertTrue(after > before, "the greedy fill must make progress on the real tree")
        }
        return session.current
    }

    /** An unranked talent that is reachable but has no points left. */
    private fun noPointsTalentName(state: TalentTreeUiState.Ready): String {
        val presentation = buildTalentTreePresentation(state.engine, state.buildState)
        val facts = talentNodeFacts(state.engine)
        val node = assertNotNull(
            presentation.nodes.values.firstOrNull {
                it.state == TalentNodeState.NO_POINTS && it.rank < it.maxRank
            },
        )
        return facts.nameOf(node.nodeId)
    }

    /** The mid-rank talent a greedy 51/51 fill leaves behind (engine-spent, never hand-written). */
    private fun partialTalentName(state: TalentTreeUiState.Ready): String {
        val presentation = buildTalentTreePresentation(state.engine, state.buildState)
        val facts = talentNodeFacts(state.engine)
        val node = assertNotNull(
            presentation.nodes.values.firstOrNull {
                it.rank in 1 until it.maxRank
            },
            "the greedy 51/51 fill must leave exactly one partial talent",
        )
        return facts.nameOf(node.nodeId)
    }

    private fun rankOf(state: TalentTreeUiState.Ready, name: String): Int =
        state.buildState.rankOf(nodeIdOf(name))

    /** Root-space bounds of one interaction's node (tests run at density 1: px == dp). */
    private fun boundsOf(interaction: SemanticsNodeInteraction): Rect =
        interaction.fetchSemanticsNode().let { node ->
            Rect(
                left = node.positionInRoot.x,
                top = node.positionInRoot.y,
                right = node.positionInRoot.x + node.size.width,
                bottom = node.positionInRoot.y + node.size.height,
            )
        }
}
