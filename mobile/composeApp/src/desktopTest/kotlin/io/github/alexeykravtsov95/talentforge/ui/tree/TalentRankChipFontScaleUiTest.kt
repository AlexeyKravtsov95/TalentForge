package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * TF-RC1-BADGE-200 on the real Warrior tree (density 1, so px = dp): the rank
 * chip keeps its B3 06 height of 15 dp at 100 / 150 / 200 % text, its rank
 * text is never cut (no visual overflow, inside the chip), and the text grows
 * with large text up to the cap, then stops.
 */
@OptIn(ExperimentalTestApi::class)
class TalentRankChipFontScaleUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun theChipFontScaleFollowsTheSystemScaleUpToTheChipHeight() {
        // the capped em box fits the 15 dp chip
        assertTrue(TalentInspectorMetrics.RANK_CHIP_FONT_SP * TalentInspectorMetrics.RANK_CHIP_MAX_FONT_SCALE < 15f)
        assertEquals(1f, rankChipFontScale(1f))
        assertEquals(1.1f, rankChipFontScale(1.1f))
        assertEquals(TalentInspectorMetrics.RANK_CHIP_MAX_FONT_SCALE, rankChipFontScale(1.5f))
        assertEquals(TalentInspectorMetrics.RANK_CHIP_MAX_FONT_SCALE, rankChipFontScale(2f))
        assertEquals(1f, rankChipFontScale(Float.NaN))
        assertEquals(1f, rankChipFontScale(0f))
    }

    @Test
    fun rankTextIsNeverCutAndTheChipKeepsItsHeightAtEveryTextSize() {
        val warrior = assertIs<TalentTreeUiState.Ready>(runBlocking { loadBaselineWarriorTreeState() })
        val rankWidths = mutableMapOf<Float, Float>()
        for (scale in listOf(1f, 1.5f, 2f)) {
            runDesktopComposeUiTest(width = 393, height = 852) {
                setContent {
                    TalentForgeTheme {
                        CompositionLocalProvider(
                            LocalDensity provides Density(1f, scale),
                            LocalAppLanguage provides AppLanguage.ENGLISH,
                        ) {
                            Box(Modifier.size(393.dp, 852.dp)) { TalentTreeScreen(warrior, onBack = {}) }
                        }
                    }
                }
                waitForIdle()
                mainClock.advanceTimeBy(900)
                waitForIdle()

                // the visible section only: off-screen pages report empty bounds
                val chips = onAllNodesWithTag(TALENT_RANK_CHIP_TAG, useUnmergedTree = true).fetchSemanticsNodes()
                    .filter { it.isVisible() }
                assertTrue(chips.isNotEmpty(), "$scale: the tree shows rank chips")
                for (chip in chips) {
                    assertEquals(15f, chip.size.height.toFloat(), 0.5f, "$scale: a chip stays 15 dp tall (B3 06)")
                }
                val rankTexts = onAllNodes(
                    SemanticsMatcher("rank text") { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { RANK.matches(it.text) }
                    },
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().filter { it.isVisible() }
                assertTrue(rankTexts.isNotEmpty(), "$scale: the tree shows rank text")
                for (text in rankTexts) {
                    val label = text.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()
                    // what clips text is its height: the paragraph is laid out on the
                    // full constraint width, so `didOverflowWidth` is no cut here —
                    // the laid-out line must fit the text's own size instead
                    val layout = layoutOf(text)
                    assertFalse(layout.didOverflowHeight, "$scale: '$label' is not cut vertically (${layout.size}, paragraph ${layout.multiParagraph.height})")
                    assertTrue(
                        layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 0.5f &&
                            layout.getLineRight(0) <= layout.size.width + 0.5f,
                        "$scale: '$label' fits its box ${layout.size}",
                    )
                    val bounds = text.boundsInRoot
                    val chip = assertNotNull(
                        chips.firstOrNull { it.boundsInRoot.contains(bounds.center) },
                        "$scale: '$label' $bounds sits on a chip",
                    ).boundsInRoot
                    assertTrue(
                        bounds.top >= chip.top - 0.5f && bounds.bottom <= chip.bottom + 0.5f &&
                            bounds.left >= chip.left - 0.5f && bounds.right <= chip.right + 0.5f,
                        "$scale: '$label' $bounds stays inside its chip $chip",
                    )
                }
                rankWidths[scale] = rankTexts.maxOf { it.size.width }.toFloat()
            }
        }
        val at100 = rankWidths.getValue(1f)
        val at150 = rankWidths.getValue(1.5f)
        val at200 = rankWidths.getValue(2f)
        assertTrue(at150 > at100, "the rank text grows with large text ($at100 → $at150)")
        assertEquals(at150, at200, 0.5f, "above the cap the rank text stops growing")
    }

    private fun SemanticsNode.isVisible(): Boolean = boundsInRoot.width > 0f && boundsInRoot.height > 0f

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)).action?.invoke(results)
        return results.single()
    }

    private companion object {
        val RANK = Regex("""\d+/\d+""")
    }
}
