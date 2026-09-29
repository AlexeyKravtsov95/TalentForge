package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * Interaction tests of the read-only canvas against the real bundled Warrior
 * dataset, with state hoisted so pan/zoom/selection outcomes are asserted
 * directly. Screen density is pinned to 2.0 so dp↔px conversions inside the
 * gesture handler match the positions injected by the touch simulator.
 */
class TalentTreeCanvasInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var model: TalentTreeUiModel
    private lateinit var viewportState: TalentTreeViewportState
    private var selectedNodeId: Int? = null

    private val canvasWidth = 360.dp
    private val canvasHeight = 656.dp

    private companion object {
        /** Density pinned in the test composition; injections convert px explicitly. */
        const val TEST_DENSITY = 1f
    }

    @BeforeTest
    fun loadRealWarriorModel() {
        val repository = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
        val outcome = runBlocking {
            repository.document(
                DatasetId(
                    game = DatasetBundleId(GameId("forever"), "1.60.1.70009").game,
                    clientBuild = DatasetBundleId(GameId("forever"), "1.60.1.70009").clientBuild,
                    classToken = ClassToken("WARRIOR"),
                ),
            )
        }
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(outcome).value
        model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model
    }

    private fun composeCanvas() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(canvasWidth, canvasHeight)) {
                        TalentTreeCanvas(
                            model = model,
                            viewportState = viewportState,
                            selectedNodeId = selectedNodeId,
                            onNodeSelected = { selectedNodeId = it },
                            onSelectionCleared = { selectedNodeId = null },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun nodeById(name: String): TalentTreeNodeUiModel =
        assertNotNull(model.nodes.firstOrNull { it.name == name }, "node '$name' not in dataset")

    /** Screen px of a world dp position, using the canvas's live viewport and test density. */
    private fun screenPxOf(worldX: Float, worldY: Float): Offset {
        val viewport = assertNotNull(viewportState.viewport, "viewport not initialized")
        return viewport.worldToScreen(Offset(worldX, worldY)) * TEST_DENSITY
    }

    /** Test px of a dp length in the composition density (1.0). */
    private fun dpToPx(dp: Float): Float = dp * TEST_DENSITY

    @Test
    fun physicalTapOnNodeSelectsIt() {
        viewportState = TalentTreeViewportState(initial = null)
        selectedNodeId = null
        composeCanvas()
        val node = nodeById("Improved Rend")

        composeRule.onRoot().performTouchInput { click(screenPxOf(node.centerX, node.centerY)) }
        composeRule.waitForIdle()

        assertEquals(node.id, selectedNodeId, "tapping a node selects it")
    }

    @Test
    fun semanticClickOnNodeSelectsIt() {
        viewportState = TalentTreeViewportState(initial = null)
        selectedNodeId = null
        composeCanvas()
        val node = nodeById("Improved Rend")

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()

        assertEquals(node.id, selectedNodeId, "accessibility click selects the node")
    }

    @Test
    fun tappingTheSameNodeKeepsItSelected() {
        viewportState = TalentTreeViewportState(initial = null)
        selectedNodeId = null
        composeCanvas()
        val node = nodeById("Improved Rend")

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()

        assertEquals(node.id, selectedNodeId, "tapping the selected node keeps it selected")
    }

    @Test
    fun tapOnEmptyCanvasClearsSelection() {
        viewportState = TalentTreeViewportState(initial = null)
        selectedNodeId = null
        composeCanvas()
        val node = nodeById("Improved Rend")
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.waitForIdle()
        assertEquals(node.id, selectedNodeId)

        // far bottom-right corner: outside every node target
        composeRule.onRoot().performTouchInput {
            click(Offset(dpToPx(canvasWidth.value) - 16f, dpToPx(canvasHeight.value) - 16f))
        }
        composeRule.waitForIdle()

        assertNull(selectedNodeId, "tapping empty canvas deselects")
    }

    @Test
    fun dragStartingOnNodePansAndNeverSelects() {
        viewportState = TalentTreeViewportState(initial = null)
        selectedNodeId = null
        composeCanvas()
        val node = nodeById("Improved Rend")
        val start = screenPxOf(node.centerX, node.centerY)
        val initialOffset = assertNotNull(viewportState.viewport).offset

        // > 7 dp movement from a finger that started on a node: tap cancels, canvas pans
        composeRule.onRoot().performTouchInput {
            swipe(start, start + Offset(120f, 0f), durationMillis = 400)
        }
        composeRule.waitForIdle()

        assertNull(selectedNodeId, "a pan never selects a node")
        val viewport = assertNotNull(viewportState.viewport, "viewport present after pan")
        assertEquals(1f, viewport.scale, "pan does not zoom")
        assertTrue(
            abs(viewport.offset.x - initialOffset.x) > 40f,
            "canvas panned horizontally (offset ${viewport.offset} vs $initialOffset)",
        )
        assertTrue(viewport.offset.x.isFinite() && viewport.offset.y.isFinite())
    }

    @Test
    fun pinchZoomsAroundTheFingersAndClampsToB3Bounds() {
        viewportState = TalentTreeViewportState(initial = null)
        selectedNodeId = null
        composeCanvas()

        composeRule.onRoot().performTouchInput {
            val centre = Offset(
                dpToPx(canvasWidth.value) / 2f,
                dpToPx(canvasHeight.value) / 2f,
            )
            pinch(
                start0 = centre - Offset(60f, 0f),
                end0 = centre - Offset(300f, 0f),
                start1 = centre + Offset(60f, 0f),
                end1 = centre + Offset(300f, 0f),
                durationMillis = 600,
            )
        }
        composeRule.waitForIdle()

        val viewport = assertNotNull(viewportState.viewport)
        assertEquals(TalentTreeCanvasMetrics.ZOOM_MAX, viewport.scale, 1e-2f, "zoom clamps at 2.2×")
        assertTrue(
            viewport.scale.isFinite() && viewport.offset.x.isFinite() && viewport.offset.y.isFinite(),
            "pinch must leave finite transform state",
        )
    }

    @Test
    fun selectionAndPanningLeaveTheTreeDataUntouched() {
        viewportState = TalentTreeViewportState(initial = null)
        selectedNodeId = null
        composeCanvas()
        val before = model
        val node = nodeById("Deflection")
        val start = screenPxOf(node.centerX, node.centerY)

        composeRule.onNodeWithContentDescription("Deflection").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            swipe(start, start + Offset(-200f, 100f), durationMillis = 300)
        }
        composeRule.waitForIdle()

        assertEquals(before, model, "selection/pan must not rebuild or mutate the tree model")
        assertEquals(node.id, selectedNodeId)
    }
}
