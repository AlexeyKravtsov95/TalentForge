package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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

    private fun compose(
        state: TalentTreeUiState,
        width: Dp = 360.dp,
        height: Dp = 760.dp,
    ): TalentTreeViewportState {
        val viewportState = TalentTreeViewportState(initial = null)
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(state, viewportState = viewportState)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return viewportState
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
        val viewportState = compose(full)

        val noPoints = noPointsTalentName(full)
        focusNodeIntoView(noPoints, viewportState)
        select(noPoints)

        composeRule.onNodeWithText("+").assertExists()
        composeRule.onNodeWithText("+").assertIsNotEnabled()
        // B3 04 state 07: "the state line explains why. Refund still works on
        // any talent with ranks." — an unranked talent offers no −.
        if (rankOf(full, noPoints) == 0) {
            composeRule.onNodeWithText("−").assertDoesNotExist()
        }
    }

    /** Pans the canvas so [name]'s node is actually visible before tapping it. */
    private fun focusNodeIntoView(name: String, viewportState: TalentTreeViewportState) {
        val node = assertNotNull(readyState.model.nodes.firstOrNull { it.name == name })
        viewportState.requestFocus(
            world = androidx.compose.ui.geometry.Offset(node.centerX, node.centerY),
            visibleSize = androidx.compose.ui.geometry.Size(360f, 500f),
        )
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(
            (TalentInspectorMetrics.SHOW_PAN_MS + 200).toLong(),
        )
        composeRule.waitForIdle()
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
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
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

    private fun rankOf(state: TalentTreeUiState.Ready, name: String): Int =
        state.buildState.rankOf(nodeIdOf(name))
}
