package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * Screen-level contract tests: the explicit Loading/Ready/Failed states, and
 * the frozen read-only semantic contract — a node click selects, never spends,
 * and an empty-canvas click clears the selection.
 */
class TalentTreeScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var readyState: TalentTreeUiState.Ready

    @BeforeTest
    fun loadRealWarriorState() {
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
    }

    private fun compose(state: TalentTreeUiState) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        TalentTreeScreen(state)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun loadingStateRendersWithoutData() {
        compose(TalentTreeUiState.Loading)

        composeRule.onNodeWithText("TalentForge").assertExists()
        composeRule.onNodeWithText("Loading talent data…").assertExists()
    }

    @Test
    fun failedStateRendersTheTypedReason() {
        val failure = TalentTreeUiState.Failed(
            TalentTreeUiFailure.Dataset(
                DatasetLoadError.AssetMissing(path = "dataset/forever/1.60.1.70009/warrior.json"),
            ),
        )

        compose(failure)

        composeRule.onNodeWithText("Talent data failed to load").assertExists()
        composeRule.onNodeWithText("missing dataset asset: dataset/forever/1.60.1.70009/warrior.json")
            .assertExists()
    }

    @Test
    fun readyStateShowsTheRealClassGameContextAndEveryNode() {
        compose(readyState)

        composeRule.onNodeWithText("Warrior · Forever").assertExists()
        composeRule.onAllNodes(anyContentDescription()).assertCountEquals(readyState.model.nodes.size)
    }

    @Test
    fun nodeClickSelectsAndNeverSpends() {
        compose(readyState)
        assertNotNull(readyState.model.nodes.firstOrNull { it.name == "Deep Wounds" })

        composeRule.onNodeWithContentDescription("Deep Wounds")
            .assertIsNotSelected()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Deep Wounds").assertIsSelected()

        // Read-only M4.2 contract: rank chips derive from the real BuildState
        // (one "0/3" per shown maxRank-3 node + the inspector's rank line),
        // but no spend/refund control exists anywhere.
        val shownChips = run {
            val presentation = buildTalentTreePresentation(readyState.engine, readyState.buildState)
            val hiddenByGlyph = setOf(
                TalentNodeState.TIER_LOCKED,
                TalentNodeState.PREREQUISITE_LOCKED,
                TalentNodeState.UNAVAILABLE,
            )
            presentation.nodes.values.count { it.rankText == "0/3" && it.state !in hiddenByGlyph }
        }
        composeRule.onAllNodes(hasTextExactly("0/3")).assertCountEquals(shownChips)
        composeRule.onAllNodes(hasText("+", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("−", substring = true)).assertCountEquals(0)
        assertTrue(readyState.buildState.allocations.isEmpty(), "the build state stays empty")
    }

    @Test
    fun emptyCanvasClickClearsTheSelection() {
        compose(readyState)

        composeRule.onNodeWithContentDescription("Deep Wounds").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Deep Wounds").assertIsSelected()

        // bottom-right of the screen: inside the canvas, outside every target.
        // px are explicit because the injection scope's density is not the
        // composition.s pinned 1.0.
        composeRule.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(360.dp.value * 1f - 16f, 760.dp.value * 1f - 16f))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Deep Wounds").assertIsNotSelected()
    }

    @Test
    fun selectionDoesNotChangeAnyNodeIdentity() {
        compose(readyState)
        val names = readyState.model.nodes.mapNotNull { it.name }

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()

        for (name in names) {
            composeRule.onNodeWithContentDescription(name).assertExists()
        }
    }
}

/** Matches any node carrying a ContentDescription (the tree's real node names). */
private fun anyContentDescription() = SemanticsMatcher("has content description") {
    it.config.contains(SemanticsProperties.ContentDescription)
}
