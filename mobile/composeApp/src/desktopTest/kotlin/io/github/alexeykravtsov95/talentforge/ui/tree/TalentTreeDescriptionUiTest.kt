package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule

/**
 * M5.V2: the real screen shows real, build-pinned talent descriptions in the
 * inspector — current rank, CURRENT / NEXT RANK, prospective rank 1 — and keeps
 * them in step with + / − / undo / redo / reset, while M5.V1 icons and the
 * frozen M4 controls stay where they were.
 */
class TalentTreeDescriptionUiTest {

    /** M5.REL.2b: the UI copy follows the platform locale — pinned to English, whatever the JVM default. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private fun readyStateOf(classToken: String): TalentTreeUiState.Ready = runBlocking {
        val assets = ComposeResourceDatasetAssetSource()
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(assets)
                .document(DatasetId(GameId("forever"), "1.60.1.70009", ClassToken(classToken))),
        ).value
        val tree = document.trees.single()
        val engine = TalentAllocationEngine(document)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model
        TalentTreeUiState.Ready(
            classDisplayName = document.classInfo.name,
            gameDisplayName = "Forever",
            classToken = document.classInfo.token,
            model = model,
            engine = engine,
            buildState = engine.emptyState(),
            buildTitle = "Draft",
            classAccent = classAccentFor(document.classInfo.token).color,
            icons = TalentIconsLoader(assets).load(document, tree).icons,
            descriptions = TalentDescriptionsLoader(assets).load(document, tree).descriptions,
        )
    }

    /** Landscape by default: the side panel shows the full detail without expanding. */
    private fun compose(state: TalentTreeUiState, width: Dp = 760.dp, height: Dp = 360.dp) {
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

    private fun select(name: String) {
        composeRule.onNodeWithContentDescription(name).performClick()
        composeRule.waitForIdle()
    }

    private fun click(text: String) {
        composeRule.onNodeWithText(text).performClick()
        composeRule.waitForIdle()
    }

    private fun rend(percent: Int) = "Increases the Bleed damage done by your Rend ability by $percent%."

    private fun assertComparison(current: String, next: String) {
        composeRule.onNodeWithText(EditorCopy.English.current).assertExists()
        composeRule.onNodeWithText(EditorCopy.English.nextRank).assertExists()
        composeRule.onNodeWithText(current).assertExists()
        composeRule.onNodeWithText(next).assertExists()
    }

    private fun count(tag: String): Int =
        composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun unspentTalentShowsRankOneAsTheProspectiveEffect() {
        compose(readyStateOf("WARRIOR"))
        select("Improved Rend")

        composeRule.onNodeWithText(rend(12)).assertExists()
        assertComparison(current = MISSING_MARK, next = "12%")
        // M5.V1 is untouched: the inspector still shows the talent's real icon
        assertEquals(1, count(TALENT_INSPECTOR_ICON_TAG))
    }

    @Test
    fun plusUpdatesTheCurrentDescriptionAndTheNextRank() {
        compose(readyStateOf("WARRIOR"))
        select("Improved Rend")

        click("+")
        composeRule.onNodeWithText(rend(12)).assertExists()
        assertComparison(current = "12%", next = "23%")

        click("+")
        composeRule.onNodeWithText(rend(23)).assertExists()
        assertComparison(current = "23%", next = "35%")

        click("+")
        composeRule.onNodeWithText(rend(35)).assertExists()
        composeRule.onNodeWithText(EditorCopy.English.nextRank).assertDoesNotExist()
    }

    @Test
    fun undoAndRedoUpdateTheDescription() {
        compose(readyStateOf("WARRIOR"))
        select("Improved Rend")
        click("+")
        click("+")
        composeRule.onNodeWithText(rend(23)).assertExists()

        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(rend(12)).assertExists()
        assertComparison(current = "12%", next = "23%")

        composeRule.onNodeWithContentDescription("Redo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(rend(23)).assertExists()
    }

    @Test
    fun resetReturnsToTheRankOnePreview() {
        compose(readyStateOf("WARRIOR"))
        select("Improved Rend")
        click("+")
        click("+")

        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        click("Reset talents")

        composeRule.onNodeWithText("0 / 51").assertExists()
        composeRule.onNodeWithText(rend(12)).assertExists()
        assertComparison(current = MISSING_MARK, next = "12%")
    }

    @Test
    fun longDescriptionsNeverPushTheControlsOutOfReach() {
        // Deadly Aspects (tier 1): a long description plus, from rank 1 on, the full
        // next-rank text (two values change) in the 360 dp tall landscape panel
        compose(readyStateOf("HUNTER"))
        select("Deadly Aspects")

        composeRule.onNode(hasText("Aspect of the Hawk", substring = true)).assertExists()
        composeRule.onNodeWithText("+").assertIsDisplayed()
        click("+")
        composeRule.onNodeWithText("−").assertIsDisplayed()
        composeRule.onNodeWithText("+").assertIsDisplayed()
        composeRule.onNodeWithText("Max").assertIsDisplayed()
    }

    @Test
    fun prerequisiteLockedTalentKeepsShowVisibleBelowItsDescription() {
        compose(readyStateOf("WARRIOR"))
        select("Deep Wounds")

        composeRule.onNode(hasText("Your critical strikes cause your opponent to Bleed", substring = true))
            .assertExists()
        composeRule.onNodeWithText("Show").assertIsDisplayed()
    }

    @Test
    fun portraitExpandedSheetAndTabletPanelShowTheDescription() {
        for ((width, height) in listOf(360.dp to 760.dp, 840.dp to 600.dp)) {
            compose(readyStateOf("WARRIOR"), width = width, height = height)
            select("Improved Rend")
            if (width < 400.dp) {
                // B3 03: the compact bar carries no description; the name expands the sheet
                composeRule.onNodeWithText(rend(12)).assertDoesNotExist()
                click("Improved Rend")
            }
            composeRule.onNodeWithText(rend(12)).assertExists()
            assertEquals(1, count(TALENT_INSPECTOR_DESCRIPTION_TAG), "$width×$height")
        }
    }

    @Test
    fun rawTemplateSyntaxNeverReachesTheScreen() {
        for ((classToken, talents) in listOf(
            // first-section talents (the spec shown on open): scaled values, durations,
            // partial descriptions, upper-case $S references
            "WARRIOR" to listOf("Improved Heroic Strike", "Improved Rend", "Deep Wounds"),
            "MAGE" to listOf("Improved Channeling", "Arcane Blast"),
            "ROGUE" to listOf("Improved Gouge", "Relentless Strikes"),
            "DRUID" to listOf("Improved Wrath", "Insect Swarm"),
        )) {
            compose(readyStateOf(classToken), width = 840.dp, height = 600.dp)
            for (talent in talents) {
                select(talent)
                assertEquals(1, count(TALENT_INSPECTOR_DESCRIPTION_TAG), "$classToken $talent")
                assertTrue(
                    composeRule.onAllNodes(hasText("$", substring = true)).fetchSemanticsNodes().isEmpty(),
                    "$classToken $talent shows template markup",
                )
                assertTrue(
                    composeRule.onAllNodes(hasText("|", substring = true)).fetchSemanticsNodes().isEmpty(),
                    "$classToken $talent shows colour markup",
                )
            }
        }
    }

    @Test
    fun partialDescriptionMarksTheMissingValue() {
        // Relentless Strikes: the chance scales with combo points (EffectPointsPerResource)
        compose(readyStateOf("ROGUE"), width = 840.dp, height = 600.dp)
        select("Relentless Strikes")

        composeRule.onNode(hasText("have a $MISSING_MARK% chance per Combo Point", substring = true))
            .assertExists()
        composeRule.onNodeWithText(EditorCopy.English.missingCharacter).assertExists()
    }

    @Test
    fun unavailableDescriptionsShowTheNeutralLineAndKeepTheTalentUsable() {
        compose(readyStateOf("WARRIOR").copy(descriptions = TalentNodeDescriptions.Unavailable))
        select("Improved Rend")

        composeRule.onNodeWithText(EditorCopy.English.descriptionUnavailable).assertExists()
        click("+")
        composeRule.onNodeWithText("1 / 51").assertExists()
        assertEquals(1, count(TALENT_INSPECTOR_ICON_TAG))
    }
}
