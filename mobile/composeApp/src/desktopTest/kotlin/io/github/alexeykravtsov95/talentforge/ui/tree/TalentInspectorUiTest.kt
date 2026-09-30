package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
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
 * M4.2 adaptive-inspector behaviour against the real Warrior screen: portrait
 * compact bar hidden/appearing, landscape side panel, tablet persistent panel
 * with the empty prompt, tier-only lock without Show, and the read-only Show
 * navigation (one prerequisite hop, never mutating the BuildState).
 */
class TalentInspectorUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var readyState: TalentTreeUiState.Ready

    @BeforeTest
    fun loadRealWarriorState() {
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
    }

    private fun compose(state: TalentTreeUiState, width: Dp = 360.dp, height: Dp = 760.dp) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(state)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }


    /** Node chips that currently show this rank text (locked chips show glyphs instead). */
    private fun expectedRankChipCount(state: TalentTreeUiState.Ready, rankText: String): Int {
        val presentation = buildTalentTreePresentation(state.engine, state.buildState)
        val hiddenByGlyph = setOf(
            TalentNodeState.TIER_LOCKED,
            TalentNodeState.PREREQUISITE_LOCKED,
            TalentNodeState.UNAVAILABLE,
        )
        return presentation.nodes.values.count { it.rankText == rankText && it.state !in hiddenByGlyph }
    }

    // ------------------------------------------------------------------ portrait (360 × 760)

    @Test
    fun portraitWithoutSelectionShowsNoInspector() {
        compose(readyState)

        composeRule.onNodeWithText("Available").assertDoesNotExist()
        composeRule.onNodeWithText("Show").assertDoesNotExist()
        composeRule.onNodeWithText("Requires Improved Rend 3/3").assertDoesNotExist()
        composeRule.onNodeWithText("×").assertDoesNotExist()
    }

    @Test
    fun portraitSelectionOpensCompactInspectorWithRealNameAndRank() {
        compose(readyState)

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()

        // the compact bar's name area is one merged semantics node
        // (clickable): its text list is exactly name + rank + state line
        composeRule.onNode(hasTextExactly("Improved Rend", "0/3", "Available")).assertExists()
        composeRule.onAllNodes(hasTextExactly("0/3"))
            .assertCountEquals(expectedRankChipCount(readyState, "0/3"))
    }

    @Test
    fun portraitRankIsDerivedFromTheBuildStateNotHardcoded() {
        val improvedRend = readyState.engine.tree.nodes.first { it.name == "Improved Rend" }
        val twoRanks = readyState.copy(
            buildState = BuildState.of(
                readyState.engine.tree.id,
                listOf(NodeAllocation(improvedRend.id, improvedRend.entries.single().id, 2)),
            ),
        )
        compose(twoRanks)

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasTextExactly("Improved Rend", "2/3", "In progress")).assertExists()
        composeRule.onAllNodes(hasTextExactly("2/3"))
            .assertCountEquals(expectedRankChipCount(twoRanks, "2/3"))
    }

    @Test
    fun portraitInspectorAppearanceDoesNotResetTheViewport() {
        compose(readyState)
        val deflection = composeRule.onNodeWithContentDescription("Deflection")
            .fetchSemanticsNode().positionInRoot

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()

        val after = composeRule.onNodeWithContentDescription("Deflection")
            .fetchSemanticsNode().positionInRoot
        assertEquals(deflection, after, "the compact inspector overlays the canvas; nothing moves")
    }

    // ------------------------------------------------------------------ landscape (760 × 360)

    @Test
    fun landscapeSelectionOpensTheSideInspector() {
        compose(readyState, width = 760.dp, height = 360.dp)

        composeRule.onNodeWithText("×").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Improved Rend").assertExists()
        composeRule.onNodeWithText("Available").assertExists()
        composeRule.onNodeWithText("×").assertExists()
        composeRule.onNodeWithContentDescription("Improved Rend").assertIsSelected()
    }

    @Test
    fun landscapeDeselectViaEmptyCanvasClosesTheSideInspector() {
        compose(readyState, width = 760.dp, height = 360.dp)

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("×").assertExists()

        // × dismisses like tapping empty canvas (B3 05)
        composeRule.onNodeWithText("×").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("×").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Improved Rend").assertIsNotSelected()
    }

    // ------------------------------------------------------------------ tablet (840+)

    @Test
    fun tabletShowsPersistentInspectorWithEmptyPromptAndSelectionContent() {
        compose(readyState, width = 840.dp, height = 600.dp)

        // UX/UI spec 13: the side inspector "stays open permanently and shows
        // an empty prompt until you select something".
        composeRule.onNodeWithText("Select a talent to inspect it").assertExists()

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Select a talent to inspect it").assertDoesNotExist()
        composeRule.onNodeWithText("Improved Rend").assertExists()
        composeRule.onNodeWithText("Available").assertExists()
    }

    // ------------------------------------------------------------------ tier lock (no Show)

    @Test
    fun tierOnlyLockedTalentShowsMeterAndNoShow() {
        compose(readyState)

        composeRule.onNodeWithContentDescription("Improved Charge").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(
            hasTextExactly("Improved Charge", "0/2", "Spend 5 more points in this tree"),
        ).assertExists()
        // the tier meter label ("0/5") + every shown 0/5 rank chip
        composeRule.onAllNodes(hasTextExactly("0/5"))
            .assertCountEquals(expectedRankChipCount(readyState, "0/5") + 1)
        composeRule.onNodeWithText("Show").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ prerequisite Show

    @Test
    fun showNavigatesOnePrerequisiteHopAndNeverMutatesTheBuildState() {
        compose(readyState)

        composeRule.onNodeWithContentDescription("Deep Wounds").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Requires Improved Rend 3/3").assertExists()

        composeRule.onNodeWithText("Show").performClick()
        composeRule.waitForIdle()

        // one hop: the direct prerequisite is selected, the origin is not
        composeRule.onNodeWithContentDescription("Improved Rend").assertIsSelected()
        composeRule.onNodeWithContentDescription("Deep Wounds").assertIsNotSelected()
        // B3 05: the target state line reads "Available · needed for <origin>"
        composeRule.onNodeWithText("Available · needed for Deep Wounds").assertExists()
        // read-only: the exact build state object the screen holds is untouched
        assertTrue(readyState.buildState.allocations.isEmpty(), "Show must not spend")
        assertEquals(0, readyState.engine.spentPoints(readyState.buildState))
    }

    @Test
    fun plainSelectionAfterShowClearsTheGuide() {
        compose(readyState)

        composeRule.onNodeWithContentDescription("Deep Wounds").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Show").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Available · needed for Deep Wounds").assertExists()

        composeRule.onNodeWithContentDescription("Deflection").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Available · needed for Deep Wounds").assertDoesNotExist()
        composeRule.onNodeWithText("Deflection").assertExists()
    }

    @Test
    fun noSpendRefundOrUndoControlsExistAnywhere() {
        compose(readyState)
        composeRule.onNodeWithContentDescription("Deep Wounds").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasText("+", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("−", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Max", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Undo", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Redo", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Reset", substring = true)).assertCountEquals(0)
    }

    @Test
    fun selectionFocusIsIndependentOfAllocationLockState() {
        compose(readyState)

        // Deep Wounds is prerequisite+tier locked in the empty build, yet the
        // neutral focus ring selects it like any available node.
        composeRule.onNodeWithContentDescription("Deep Wounds")
            .assertIsNotSelected()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Deep Wounds").assertIsSelected()
        composeRule.onNodeWithText("Requires Improved Rend 3/3").assertExists()
        assertNotNull(readyState.buildState)
    }
}
