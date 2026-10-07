package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * M5.V1: the real screen renders real, bundled talent icons as the normal node
 * content — for Warrior, Mage and Rogue — while every frozen M4 behaviour
 * (tap selects only, explicit + / − / Max, state overlays, selection) and the
 * monogram fallback keep working. Uses the packaged resources through the
 * production loaders; nothing is mocked except where a test removes icons on
 * purpose.
 */
class TalentTreeIconUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A Ready state for any bundled class, built the way production builds it. */
    private fun readyStateOf(classToken: String): TalentTreeUiState.Ready = runBlocking {
        val assets = ComposeResourceDatasetAssetSource()
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(assets)
                .document(DatasetId(GameId("forever"), "1.60.1.70009", ClassToken(classToken))),
        ).value
        val engine = TalentAllocationEngine(document)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model
        val icons = TalentIconsLoader(assets).load(document, document.trees.single())
        assertEquals(null, icons.visualsError)
        assertTrue(icons.failedNodes.isEmpty())
        TalentTreeUiState.Ready(
            classDisplayName = document.classInfo.name,
            gameDisplayName = "Forever",
            model = model,
            engine = engine,
            buildState = engine.emptyState(),
            buildTitle = "Draft",
            classAccent = classAccentFor(document.classInfo.token).color,
            icons = icons.icons,
        )
    }

    private fun compose(
        state: TalentTreeUiState,
        width: Dp = 360.dp,
        height: Dp = 760.dp,
        onBuildStateChanged: ((BuildState) -> Unit)? = null,
    ) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(state, onBuildStateChanged = onBuildStateChanged)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun count(tag: String): Int =
        composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun select(name: String) {
        composeRule.onNodeWithContentDescription(name).performClick()
        composeRule.waitForIdle()
    }

    private fun stateDescriptionOf(name: String): String =
        composeRule.onNodeWithContentDescription(name)
            .fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]

    // ------------------------------------------------------------------ icon-backed trees

    private fun assertEveryNodeIsIconBacked(classToken: String, expectedNodes: Int) {
        val state = readyStateOf(classToken)
        assertEquals(expectedNodes, state.model.nodes.size)
        assertEquals(state.model.nodes.map { it.id }.toSet(), state.icons.nodeIds)

        compose(state)

        assertEquals(expectedNodes, count(TALENT_NODE_ICON_TAG), "$classToken icon-backed nodes")
        assertEquals(0, count(TALENT_NODE_MONOGRAM_TAG), "$classToken monogram fallbacks")
    }

    @Test
    fun warriorTreeRendersRealIconsOnEveryNode() = assertEveryNodeIsIconBacked("WARRIOR", 53)

    @Test
    fun mageTreeRendersRealIconsOnEveryNode() = assertEveryNodeIsIconBacked("MAGE", 54)

    @Test
    fun rogueTreeRendersRealIconsOnEveryNode() = assertEveryNodeIsIconBacked("ROGUE", 53)

    // ------------------------------------------------------------------ frozen interaction

    @Test
    fun tappingAnIconNodeSelectsAndNeverSpends() {
        var reported = 0
        compose(readyStateOf("WARRIOR"), onBuildStateChanged = { reported += 1 })

        repeat(4) { select("Improved Rend") }

        composeRule.onNodeWithContentDescription("Improved Rend").assertIsSelected()
        composeRule.onNodeWithText("0 / 51").assertExists()
        composeRule.onNode(hasTextExactly("Improved Rend", "0/3", "Available")).assertExists()
        assertEquals(0, reported, "a node tap must never change the build")
    }

    @Test
    fun plusMinusAndMaxStillWorkOnIconNodes() {
        val changes = mutableListOf<BuildState>()
        val state = readyStateOf("WARRIOR")
        val rend = assertNotNull(state.engine.tree.nodes.firstOrNull { it.name == "Improved Rend" }).id
        compose(state, onBuildStateChanged = { changes += it })

        select("Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()
        assertEquals("1/3, in progress", stateDescriptionOf("Improved Rend"))

        composeRule.onNodeWithText("−").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0 / 51").assertExists()
        assertEquals("0/3, available", stateDescriptionOf("Improved Rend"))

        // Max lives in the full detail (B3 03: "There is no Max in the compact bar").
        composeRule.onNodeWithText("Improved Rend").performClick() // name area expands
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Max").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 / 51").assertExists()
        assertEquals("3/3, maxed", stateDescriptionOf("Improved Rend"))

        assertEquals(listOf(1, 0, 3), changes.map { it.rankOf(rend) })
        // the icon is still the node content in every state
        assertEquals(53, count(TALENT_NODE_ICON_TAG))
    }

    // ------------------------------------------------------------------ overlays on icons

    @Test
    fun rankChipsLockStatesAndSelectionCoexistWithIcons() {
        compose(readyStateOf("WARRIOR"))

        // rank chips are still real text on top of the icon nodes
        assertTrue(composeRule.onAllNodesWithText("0/3").fetchSemanticsNodes().isNotEmpty())
        // lock reasons are still engine-backed state, not colour
        assertEquals("0/3, locked by prerequisite", stateDescriptionOf("Deep Wounds"))
        assertEquals("0/3, available", stateDescriptionOf("Improved Rend"))

        select("Deep Wounds")
        composeRule.onNodeWithContentDescription("Deep Wounds").assertIsSelected()
        // selection is independent of the locked state and of the icon
        assertEquals("0/3, locked by prerequisite", stateDescriptionOf("Deep Wounds"))
        assertEquals(53, count(TALENT_NODE_ICON_TAG))
    }

    @Test
    fun lockedIconIsDesaturatedAndAvailableIconKeepsItsColour() {
        compose(readyStateOf("WARRIOR"))
        val image = composeRule.onRoot().captureToImage().toAwtImage()

        // Improved Rend (available): the real icon is strongly red.
        assertTrue(
            maxChannelSpread(image, "Improved Rend") > 60,
            "an available talent shows its icon in full colour",
        )
        // Deep Wounds (locked): the same kind of red artwork, greyscale + dimmed.
        assertTrue(
            maxChannelSpread(image, "Deep Wounds") < 24,
            "a locked talent's icon is desaturated (state is also carried by border + chip)",
        )
    }

    /** Largest max(r,g,b) − min(r,g,b) over the central 18×18 px of a node's body. */
    private fun maxChannelSpread(image: BufferedImage, nodeName: String): Int {
        val bounds = composeRule.onNodeWithContentDescription(nodeName).fetchSemanticsNode().boundsInRoot
        val centerX = bounds.center.x.roundToInt()
        val centerY = bounds.center.y.roundToInt()
        var spread = 0
        for (y in centerY - 9 until centerY + 9) {
            for (x in centerX - 9 until centerX + 9) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                spread = maxOf(spread, maxOf(r, g, b) - minOf(r, g, b))
            }
        }
        return abs(spread)
    }

    // ------------------------------------------------------------------ inspector

    @Test
    fun inspectorShowsTheSelectedTalentsIconInEveryLayout() {
        for ((width, height) in listOf(360.dp to 760.dp, 760.dp to 360.dp, 840.dp to 600.dp)) {
            compose(readyStateOf("WARRIOR"), width = width, height = height)
            assertEquals(0, count(TALENT_INSPECTOR_ICON_TAG), "no selection, no inspector icon ($width×$height)")

            select("Improved Rend")

            assertEquals(1, count(TALENT_INSPECTOR_ICON_TAG), "inspector icon at $width×$height")
        }
    }

    @Test
    fun expandedInspectorKeepsTheIconAndNeverShowsARawTemplate() {
        compose(readyStateOf("WARRIOR"))
        select("Improved Rend")
        composeRule.onNodeWithText("Improved Rend").performClick() // name area expands
        composeRule.waitForIdle()

        assertEquals(1, count(TALENT_INSPECTOR_ICON_TAG))
        // Improved Rend's template is "Increases the Bleed damage done by your Rend
        // ability by $s1%." — unevaluated client markup is never displayed (M5.V2).
        assertTrue(composeRule.onAllNodes(hasText("$", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(
            composeRule.onAllNodes(hasText("Bleed damage", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
    }

    // ------------------------------------------------------------------ fallback

    @Test
    fun aNodeWithoutAnIconFallsBackToItsMonogramWithoutAffectingTheRest() {
        val state = readyStateOf("WARRIOR")
        val rend = assertNotNull(state.model.nodes.firstOrNull { it.name == "Improved Rend" })
        val withoutRend = TalentNodeIcons(
            state.icons.nodeIds
                .filter { it != rend.id }
                .associateWith { assertNotNull(state.icons.iconOf(it)) },
        )

        compose(state.copy(icons = withoutRend))

        assertEquals(52, count(TALENT_NODE_ICON_TAG))
        assertEquals(1, count(TALENT_NODE_MONOGRAM_TAG))
        composeRule.onNodeWithText(rend.monogram).assertExists()

        // the fallback node is a fully working node, and its inspector has no icon tile
        select("Improved Rend")
        composeRule.onNodeWithContentDescription("Improved Rend").assertIsSelected()
        assertEquals(0, count(TALENT_INSPECTOR_ICON_TAG))
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()
    }

    @Test
    fun withoutAnyVisualsTheWholeTreeRendersTheMonogramFallback() {
        compose(readyStateOf("WARRIOR").copy(icons = TalentNodeIcons.None))

        assertEquals(0, count(TALENT_NODE_ICON_TAG))
        assertEquals(53, count(TALENT_NODE_MONOGRAM_TAG))
        select("Improved Rend")
        composeRule.onNode(hasTextExactly("Improved Rend", "0/3", "Available")).assertExists()
    }
}
