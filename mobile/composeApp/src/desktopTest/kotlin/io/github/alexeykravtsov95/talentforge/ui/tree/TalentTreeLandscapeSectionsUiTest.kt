package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * M5.UX.2b.1 landscape phones on the REAL screen (reference Warrior dataset):
 * the section tabs in the B3 header bar (or below it when it is too narrow),
 * one section at a time over the ONE BuildState, the 300 dp side panel over
 * the canvas, cross-section Show and its Back chip, system Back, and every
 * portrait ↔ landscape transition — none of which changes the build.
 *
 * Density is pinned to 1, so px == dp in every injected position. The test
 * window is large enough for both orientations; the screen is sized by
 * [Session.window], which a rotation changes in place (iOS / desktop: the
 * composition survives) — [androidRecreationOnRotationRestoresEverythingWithoutAReport]
 * covers Android's activity recreation through the saved state.
 */
@OptIn(ExperimentalTestApi::class)
class TalentTreeLandscapeSectionsUiTest {

    private lateinit var readyState: TalentTreeUiState.Ready

    @BeforeTest
    fun loadRealWarriorState() {
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
    }

    private class Session(
        val cameras: TalentTreeSectionCameras,
        val reported: MutableList<BuildState>,
        val navigatedBack: IntArray,
        initial: IntSize,
    ) {
        var window by mutableStateOf(initial)
    }

    private fun onEditor(
        state: TalentTreeUiState.Ready = readyState,
        width: Int = 915,
        height: Int = 412,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        block: ComposeUiTest.(Session) -> Unit,
    ) = runDesktopComposeUiTest(width = 1000, height = 1000) {
        val session = Session(
            cameras = TalentTreeSectionCameras(sessionKey = "landscape", treeId = state.model.treeId),
            reported = mutableListOf(),
            navigatedBack = intArrayOf(0),
            initial = IntSize(width, height),
        )
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    // stands in for the app's navigation handler (registered first, like App.kt)
                    PlatformBackHandler(enabled = true) { session.navigatedBack[0] += 1 }
                    Box(Modifier.size(session.window.width.dp, session.window.height.dp)) {
                        TalentTreeScreen(
                            state,
                            onBack = {},
                            onBuildStateChanged = { session.reported += it },
                            sectionCameras = session.cameras,
                        )
                    }
                }
            }
        }
        settle()
        block(session)
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(900)
        waitForIdle()
    }

    private fun ComposeUiTest.rotate(session: Session, width: Int, height: Int) {
        runOnIdle { session.window = IntSize(width, height) }
        settle()
    }

    private fun ComposeUiTest.tab(label: String) {
        onNodeWithText(label).performClick()
        settle()
    }

    private fun ComposeUiTest.select(name: String) {
        onNodeWithContentDescription(name).performClick()
        settle()
    }

    private fun ComposeUiTest.bounds(text: String): Rect = onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.undoBounds(): Rect =
        onNodeWithContentDescription("Undo talent change").fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.panel() = onNode(hasTestTag(TALENT_INSPECTOR_SIDE_PANEL_TAG))

    private fun ComposeUiTest.hasPanel(): Boolean =
        onAllNodes(hasTestTag(TALENT_INSPECTOR_SIDE_PANEL_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasCompactBar(): Boolean =
        onAllNodes(hasTestTag(TALENT_INSPECTOR_COMPACT_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.tabState(label: String): String =
        onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun ComposeUiTest.isShown(name: String): Boolean =
        onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty()

    private fun sections(state: TalentTreeUiState.Ready = readyState): TalentTreeSectionsResult.Sectioned =
        buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned

    private fun nameOf(state: TalentTreeUiState.Ready, nodeId: Int): String =
        assertNotNull(state.model.nodes.first { it.id == nodeId }.name)

    /** A talent of [section] that the empty build can buy (first tier, no prerequisite). */
    private fun firstBuyableTalentOf(section: Int, state: TalentTreeUiState.Ready = readyState): String {
        val id = sections(state).section(section)!!.readingOrderNodeIds
            .first { state.engine.canSpend(state.engine.emptyState(), it).allowed }
        return nameOf(state, id)
    }

    /** The tabs share the header row with undo (B3 05 merged bar). */
    private fun ComposeUiTest.assertTabsInHeader() {
        val undo = undoBounds()
        for (label in listOf("Tree 1", "Tree 2", "Tree 3")) {
            val tab = bounds(label)
            assertTrue(tab.top < undo.bottom && tab.bottom > undo.top, "$label sits in the header row: $tab / $undo")
        }
    }

    /** The tabs keep their own row below the header (portrait, compact landscape). */
    private fun ComposeUiTest.assertTabsBelowHeader() {
        val undo = undoBounds()
        for (label in listOf("Tree 1", "Tree 2", "Tree 3")) {
            assertTrue(bounds(label).top >= undo.bottom - 0.5f, "$label is below the header: ${bounds(label)} / $undo")
        }
    }

    // ------------------------------------------------------------------ layout

    @Test
    fun aTypicalLandscapePhoneShowsTheTabsInTheOneHeaderBarAndOneSection() {
        onEditor(width = 915, height = 412) { session ->
            assertTabsInHeader()
            onNodeWithText("Tree 1").assertIsSelected()
            assertTrue(undoBounds().bottom <= TalentTreeLandscapeHeaderMetrics.HEADER_HEIGHT_DP + 0.5f, "one 48 dp bar")
            assertEquals(0, session.cameras.selectedSection)
            // one section at a time: Tree 1's talents are on the canvas; the other
            // pages are composed ahead but carry no talent semantics
            assertTrue(isShown("Improved Rend"))
            assertFalse(isShown(firstBuyableTalentOf(section = 1)), "Tree 2 is not on the canvas")
            assertFalse(isShown(firstBuyableTalentOf(section = 2)), "Tree 3 is not on the canvas")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun theSmallestLandscapePhoneKeepsTheTabRowUnderTheHeader() {
        onEditor(width = 568, height = 320) {
            assertTabsBelowHeader()
            for (label in listOf("Tree 1", "Tree 2", "Tree 3")) {
                val tab = bounds(label)
                assertTrue(tab.left >= 0f && tab.right <= 568f, "$label is reachable: $tab")
            }
            onNodeWithText("Tree 1").assertIsSelected()
            assertTrue(isShown("Improved Rend"))
        }
    }

    @Test
    fun enlargedTextMovesTheTabsOutOfTheHeaderOnlyWhereTheyNoLongerFit() {
        onEditor(width = 640, height = 360, fontScale = 1.3f) { assertTabsBelowHeader() }
        onEditor(width = 915, height = 412, fontScale = 1.3f) { assertTabsInHeader() }
    }

    @Test
    fun aTabletStillGetsTheUnchangedWholeTreeLayout() {
        onEditor(width = 840, height = 600) { session ->
            assertEquals(0, onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes().size)
            assertTrue(isShown(firstBuyableTalentOf(section = 1)), "the whole tree is on the one canvas")
            assertNull(session.cameras.selectedSection)
        }
    }

    // ------------------------------------------------------------------ section navigation and cameras

    @Test
    fun tabsSwitchSectionsAndEverySectionKeepsItsOwnCamera() {
        onEditor { session ->
            // scroll Tree 1 down to its lower tiers (a vertical drag pans)
            onRoot().performTouchInput {
                down(Offset(450f, 300f))
                repeat(20) { moveBy(Offset(0f, -3f)) }
                up()
            }
            settle()
            val tree1 = session.cameras.stateOf(0).viewport
            assertTrue(session.cameras.stateOf(0).userMoved)

            tab("Tree 2")
            onNodeWithText("Tree 2").assertIsSelected()
            onNodeWithText("Tree 1").assertIsNotSelected()
            assertEquals(1, session.cameras.selectedSection)
            assertTrue(isShown(firstBuyableTalentOf(section = 1)))
            tab("Tree 3")
            tab("Tree 1")
            assertEquals(tree1, session.cameras.stateOf(0).viewport, "Tree 1's camera is restored exactly")

            // a tap on the open tab returns its camera to the fit (1A v2 01)
            tab("Tree 1")
            assertFalse(session.cameras.stateOf(0).isAwayFromFit)
            assertTrue(session.reported.isEmpty(), "navigation never changes the build")
        }
    }

    /**
     * Device finding (TECNO KM5n, Android 15): Android accessibility skips every talent of a node
     * layer whose own transformed bounds are off screen. The layer must therefore contain every
     * talent of the open section, in both orientations — with a canvas-sized layer, Tree 2 in
     * portrait and Tree 3 in landscape lay outside it (desktop semantics never showed this).
     */
    @Test
    fun theTalentLayerContainsEveryTalentOfTheOpenSectionInBothOrientations() {
        val extent = talentNodeLayerExtent(readyState.model.nodes)
        val half = talentNodeContainerHalfSideDp()
        for ((width, height) in listOf(412 to 915, 915 to 412)) {
            onEditor(width = width, height = height) { session ->
                for (section in 0..2) {
                    tab("Tree ${section + 1}")
                    val viewport = assertNotNull(session.cameras.stateOf(section).viewport)
                    val layer = Rect(viewport.offset, Size(extent.width * viewport.scale, extent.height * viewport.scale))
                    val ids = sections().section(section)!!.readingOrderNodeIds.toSet()
                    for (node in readyState.model.nodes.filter { it.id in ids }) {
                        val center = viewport.worldToScreen(Offset(node.centerX, node.centerY))
                        val r = half * viewport.scale
                        assertTrue(
                            center.x - r >= layer.left - 0.01f && center.x + r <= layer.right + 0.01f &&
                                center.y - r >= layer.top - 0.01f && center.y + r <= layer.bottom + 0.01f,
                            "${width}x$height Tree ${section + 1}: ${node.name} at $center lies in the layer $layer",
                        )
                    }
                    assertTrue(isShown(firstBuyableTalentOf(section)), "${width}x$height Tree ${section + 1} is exposed")
                }
            }
        }
    }

    @Test
    fun aSwipeOpensTheNeighbourSectionLikeATabTap() {
        onEditor { session ->
            onRoot().performTouchInput {
                down(Offset(500f, 250f))
                repeat(3) { moveBy(Offset(-60f, 0f), delayMillis = 16) }
                up()
            }
            settle()
            onNodeWithText("Tree 2").assertIsSelected()
            assertEquals(1, session.cameras.selectedSection)
            assertTrue(session.reported.isEmpty())
        }
    }

    // ------------------------------------------------------------------ the side panel

    @Test
    fun aSelectionOpensTheSidePanelOverTheCanvasWithoutResizingOrMovingIt() {
        onEditor { session ->
            val camera = session.cameras.stateOf(0)
            val canvasBefore = camera.canvasSize
            val viewportBefore = camera.viewport
            select("Improved Rend")

            onNodeWithContentDescription("Improved Rend").assertIsSelected()
            val panel = panel().fetchSemanticsNode().boundsInRoot
            assertEquals(TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP.value, panel.width, 0.5f)
            assertEquals(915f, panel.right, 0.5f)
            assertTrue(panel.top >= undoBounds().bottom - 0.5f, "the panel never covers the header, tabs or undo")
            assertEquals(canvasBefore, camera.canvasSize, "the panel overlays the canvas: no resize, no re-fit")
            assertEquals(viewportBefore, camera.viewport, "the talent was not covered: the canvas stays still")
            assertEquals(TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP.value, camera.coveredRightDp)

            // a touch on the panel's text never reaches the canvas below (no deselect)
            onRoot().performTouchInput { click(Offset(panel.left + 150f, panel.center.y)) }
            settle()
            onNodeWithContentDescription("Improved Rend").assertIsSelected()

            // the tabs stay usable with the panel open: a tab change deselects
            tab("Tree 2")
            assertFalse(hasPanel())
            assertEquals(0f, session.cameras.stateOf(1).coveredRightDp)
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun theCloseButtonAndAnEmptyCanvasTapCloseThePanel() {
        onEditor {
            select("Improved Rend")
            onNodeWithText("×").performClick()
            settle()
            assertFalse(hasPanel())
            onNodeWithContentDescription("Improved Rend").assertIsNotSelected()

            select("Improved Rend")
            onRoot().performTouchInput { click(Offset(60f, 300f)) } // empty canvas, left of the section
            settle()
            assertFalse(hasPanel())
        }
    }

    @Test
    fun theCanvasMovesOnlyWhenThePanelWouldCoverTheSelectedTalent() {
        onEditor { session ->
            val camera = session.cameras.stateOf(0)
            val rend = readyState.model.nodes.first { it.name == "Improved Rend" }
            // put Improved Rend where the panel will open (right of 915 − 300)
            runOnIdle {
                val viewport = assertNotNull(camera.viewport)
                val x = viewport.worldToScreen(Offset(rend.centerX, rend.centerY)).x
                camera.applyTransform(viewport.panBy(Offset(780f - x, 0f)))
            }
            settle()
            val scale = assertNotNull(camera.viewport).scale
            select("Improved Rend")
            val node = onNodeWithContentDescription("Improved Rend").fetchSemanticsNode().boundsInRoot
            assertTrue(node.right <= 915f - 300f, "revealed left of the panel: $node")
            assertEquals(scale, assertNotNull(camera.viewport).scale, "only a pan, never a zoom")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun inRtlThePanelOpensOnTheLeftAndRevealsTalentsToItsRight() {
        onEditor(rtl = true) { session ->
            val camera = session.cameras.stateOf(0)
            val rend = readyState.model.nodes.first { it.name == "Improved Rend" }
            fun rendX() = assertNotNull(camera.viewport).worldToScreen(Offset(rend.centerX, rend.centerY)).x
            runOnIdle { camera.applyTransform(assertNotNull(camera.viewport).panBy(Offset(120f - rendX(), 0f))) }
            settle()
            // the accessibility (TalkBack) select: the canvas's node drawing is not
            // mirrored consistently in RTL yet (pre-existing, see known limitations),
            // so the camera's own frame is asserted rather than a touch position
            onNodeWithContentDescription("Improved Rend").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithContentDescription("Improved Rend").assertIsSelected()
            val panel = panel().fetchSemanticsNode().boundsInRoot
            assertEquals(0f, panel.left, 0.5f, "the panel opens at the layout end: the left in RTL")
            assertEquals(TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP.value, camera.coveredLeftDp)
            assertEquals(0f, camera.coveredRightDp)
            assertTrue(rendX() >= 300f, "revealed right of the panel: ${rendX()}")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun theSidePanelSpendsThroughTheOneBuildAndUndoStaysInTheHeader() {
        onEditor { session ->
            select("Improved Rend")
            onNodeWithContentDescription("Add rank to Improved Rend").performClick()
            settle()
            onNodeWithContentDescription("Add rank to Improved Rend").performClick()
            settle()
            assertEquals(2, session.reported.size)
            onNodeWithText("2 / 51").assertExists()
            assertEquals("2 points spent", tabState("Tree 1"))

            onNodeWithContentDescription("Undo talent change").performClick()
            settle()
            assertEquals(3, session.reported.size)
            onNodeWithText("1 / 51").assertExists()
            onNodeWithContentDescription("Improved Rend").assertIsSelected() // undo never touches selection
        }
    }

    // ------------------------------------------------------------------ Show and Back

    @Test
    fun aCrossSectionShowSwitchesTabsAndTheBackChipReturnsBesideThePanel() {
        val fixture = crossSectionFixture(readyState.engine.document)
        val engine = TalentAllocationEngine(fixture.document)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(fixture.document)).model
        val state = readyState.copy(model = model, engine = engine, buildState = engine.emptyState())
        val origin = nameOf(state, fixture.targetId)
        val target = nameOf(state, fixture.sourceId)

        onEditor(state) { session ->
            tab("Tree 3")
            select(origin)
            val tree3 = session.cameras.stateOf(2).viewport
            onNodeWithText("Show").performClick()
            settle()

            onNodeWithText("Tree 2").assertIsSelected()
            onNodeWithContentDescription(target).assertIsSelected()
            assertTrue(hasPanel(), "the panel shows the prerequisite")
            val chip = bounds("Back to $origin · Tree 3")
            assertTrue(chip.right <= 915f - 300f + 0.5f, "the Back chip is never under the panel: $chip")
            assertTrue(chip.top >= undoBounds().bottom - 0.5f, "the Back chip is below the header: $chip")
            assertEquals(tree3, session.cameras.stateOf(2).viewport, "Show leaves the origin's camera untouched")

            onNodeWithText("Back to $origin · Tree 3").performClick()
            settle()
            onNodeWithText("Tree 3").assertIsSelected()
            onNodeWithContentDescription(origin).assertIsSelected()
            assertEquals(tree3, session.cameras.stateOf(2).viewport)
            assertTrue(session.reported.isEmpty(), "Show and Back never spend")
        }
    }

    @Test
    fun systemBackClearsALandscapeSelectionInOneStepThenNavigates() {
        onEditor { session ->
            select("Improved Rend")
            runOnIdle { assertTrue(DesktopBackDispatcher.dispatch()) }
            settle()
            assertFalse(hasPanel())
            onNodeWithContentDescription("Improved Rend").assertIsNotSelected()
            assertEquals(0, session.navigatedBack[0])

            runOnIdle { DesktopBackDispatcher.dispatch() }
            settle()
            assertEquals(1, session.navigatedBack[0], "nothing open → the app's navigation Back")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aPortraitSheetExpansionAddsNoHiddenBackStepInLandscape() {
        onEditor(width = 412, height = 915) { session ->
            select("Improved Rend")
            onNodeWithContentDescription(INSPECTOR_EXPAND_LABEL).performClick()
            settle()
            rotate(session, 915, 412)
            assertTrue(hasPanel())
            runOnIdle { DesktopBackDispatcher.dispatch() }
            settle()
            assertFalse(hasPanel(), "one Back clears the landscape selection")
            assertEquals(0, session.navigatedBack[0])
        }
    }

    // ------------------------------------------------------------------ orientation transitions

    @Test
    fun portraitTree2RotatesToLandscapeTree2AndLandscapeTree3BackToPortraitTree3() {
        onEditor(width = 412, height = 915) { session ->
            assertTabsBelowHeader()
            tab("Tree 2")
            rotate(session, 915, 412)
            onNodeWithText("Tree 2").assertIsSelected()
            assertTabsInHeader()
            assertTrue(isShown(firstBuyableTalentOf(section = 1)))

            tab("Tree 3")
            rotate(session, 412, 915)
            onNodeWithText("Tree 3").assertIsSelected()
            assertTabsBelowHeader()
            assertTrue(isShown(firstBuyableTalentOf(section = 2)))
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aSelectionSurvivesRotationAndTheInspectorFollowsTheLayout() {
        val fury = firstBuyableTalentOf(section = 1)
        onEditor(width = 412, height = 915) { session ->
            tab("Tree 2")
            select(fury)
            assertTrue(hasCompactBar())

            rotate(session, 915, 412)
            onNodeWithContentDescription(fury).assertIsSelected()
            onNodeWithText("Tree 2").assertIsSelected()
            assertTrue(hasPanel(), "landscape: the side panel")
            assertFalse(hasCompactBar(), "never the portrait strip in landscape")

            rotate(session, 412, 915)
            onNodeWithContentDescription(fury).assertIsSelected()
            assertTrue(hasCompactBar())
            assertFalse(hasPanel())
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun rotationRefitsTheSectionCamerasPerTheSectionPolicy() {
        onEditor(width = 412, height = 915) { session ->
            val camera = session.cameras.stateOf(0)
            runOnIdle {
                val viewport = assertNotNull(camera.viewport)
                camera.applyUserTransform(viewport.zoomAt(Offset(200f, 300f), 1.4f))
            }
            settle()
            assertTrue(camera.isAwayFromFit)
            val portraitCanvas = camera.canvasSize

            // 1A v2 S5: "rotation keeps the section and selection, the camera is recomputed to fit"
            rotate(session, 915, 412)
            assertNotEquals(portraitCanvas, camera.canvasSize)
            assertFalse(camera.userMoved)
            assertTrue(assertNotNull(camera.viewport).isCloseTo(assertNotNull(camera.fitViewport())))
            assertEquals(camera.canvasSize, camera.fittedCanvasSize)
            assertEquals(0, onAllNodesWithContentDescription("Fit tree").fetchSemanticsNodes().size)
            // the neighbours are fitted for the new canvas before they slide in
            assertEquals(camera.canvasSize, session.cameras.stateOf(1).fittedCanvasSize)
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun spentPointsSurviveRotationAndRotationNeverReportsABuildChange() {
        onEditor { session ->
            select("Improved Rend")
            onNodeWithContentDescription("Add rank to Improved Rend").performClick()
            onNodeWithContentDescription("Add rank to Improved Rend").performClick()
            settle()
            val built = session.reported.toList()
            assertEquals(2, built.size)

            rotate(session, 412, 915)
            onNodeWithText("2 / 51").assertExists()
            assertEquals("2 points spent", tabState("Tree 1"))
            rotate(session, 915, 412)
            rotate(session, 568, 320)
            onNodeWithText("2 / 51").assertExists()
            assertEquals(built, session.reported, "an orientation change is never a build change")
        }
    }

    @Test
    fun theInspectorOpensAndClosesAfterRotation() {
        onEditor(width = 412, height = 915) { session ->
            rotate(session, 915, 412)
            select("Improved Rend")
            assertTrue(hasPanel())
            onNodeWithText("×").performClick()
            settle()
            assertFalse(hasPanel())

            rotate(session, 412, 915)
            select("Improved Rend")
            assertTrue(hasCompactBar())
            onNodeWithContentDescription(INSPECTOR_EXPAND_LABEL).performClick()
            settle()
            assertTrue(onAllNodes(hasTestTag(TALENT_INSPECTOR_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty())
            runOnIdle { DesktopBackDispatcher.dispatch() } // collapse
            settle()
            assertTrue(hasCompactBar())
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun androidRecreationOnRotationRestoresEverythingWithoutAReport() {
        val fury = firstBuyableTalentOf(section = 1)
        runDesktopComposeUiTest(width = 1000, height = 1000) {
            val reported = mutableListOf<BuildState>()
            var registry by mutableStateOf(SaveableStateRegistry(null) { true })
            var shown by mutableStateOf(true)
            var window by mutableStateOf(IntSize(412, 915))
            // what the host reopens after recreation: the build as autosave persisted it
            var state by mutableStateOf(readyState)
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        Box(Modifier.size(window.width.dp, window.height.dp)) {
                            if (shown) {
                                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                                    TalentTreeScreen(state, sessionKey = "build-1", onBuildStateChanged = { reported += it })
                                }
                            }
                        }
                    }
                }
            }
            settle()
            tab("Tree 2")
            select(fury)
            onNodeWithContentDescription("Add rank to $fury").performClick()
            settle()
            assertEquals(1, reported.size)

            var saved: Map<String, List<Any?>> = emptyMap()
            runOnIdle {
                saved = registry.performSave()
                shown = false
            }
            settle()
            runOnIdle {
                registry = SaveableStateRegistry(saved) { true }
                window = IntSize(915, 412)
                state = readyState.copy(buildState = reported.last())
                shown = true
            }
            settle()

            onNodeWithText("Tree 2").assertIsSelected()
            assertTabsInHeader()
            onNodeWithContentDescription(fury).assertIsSelected()
            assertTrue(hasPanel())
            onNodeWithText("1 / 51").assertExists()
            assertEquals(1, reported.size, "restoring the persisted build is not a new change")
        }
    }

    // ------------------------------------------------------------------ accessibility

    @Test
    fun headerTabsAndPanelControlsKeepTheirSemantics() {
        onEditor {
            val tabs = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
            assertEquals(3, tabs.size, "one Tab per section, in the header")
            assertEquals("0 points spent", tabState("Tree 2"))
            assertTrue(onNodeWithText("Tree 1").fetchSemanticsNode().config.contains(SemanticsActions.OnClick))

            select("Improved Rend")
            for (label in listOf("Add rank to Improved Rend", "Remove rank from Improved Rend", "Max out Improved Rend")) {
                val node = onNodeWithContentDescription(label).fetchSemanticsNode()
                assertEquals(Role.Button, node.config[SemanticsProperties.Role], label)
                assertTrue(node.boundsInRoot.height >= 48f, "$label keeps a 48 dp target: ${node.boundsInRoot}")
            }
            val close = onNodeWithText("×").fetchSemanticsNode()
            assertEquals("clear selection", close.config[SemanticsActions.OnClick].label)
        }
    }
}
