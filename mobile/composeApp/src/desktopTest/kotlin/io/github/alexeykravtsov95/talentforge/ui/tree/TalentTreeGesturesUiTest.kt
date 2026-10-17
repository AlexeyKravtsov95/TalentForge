package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import org.junit.Rule

/**
 * PR #19 on the REAL screen (product Warrior, 360 × 800 dp, density 1 so
 * px = dp, gestures injected at real canvas coordinates through the root):
 * the section swipe of the canvas — direct at FIT, edge overscroll when
 * zoomed or moved (see [TalentSectionSwipeModel]) — the bottom inspector's
 * vertical swipes and the 1A v2 S6 system Back order — with the independent
 * cameras, undo/redo and the autosave observer around them.
 *
 * Android's own edge zone is not reproducible here (the desktop host reports
 * no system gesture insets); its rule is covered by
 * `TalentTreeGestureRulesTest` and must be checked on a device.
 */
@OptIn(ExperimentalTestApi::class)
class TalentTreeGesturesUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private class Session(
        val cameras: TalentTreeSectionCameras,
        val reported: MutableList<BuildState>,
        val navigatedBack: IntArray,
        val sections: List<Int>,
    )

    private val w = 360
    private val h = 800

    private fun onPhone(state: TalentTreeUiState.Ready, block: ComposeUiTest.(Session) -> Unit) =
        runDesktopComposeUiTest(width = w, height = h) {
            val sections = (buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned)
                .sections.map { it.index }
            val session = Session(
                cameras = TalentTreeSectionCameras(sessionKey = "gestures", treeId = state.model.treeId),
                reported = mutableListOf(),
                navigatedBack = intArrayOf(0),
                sections = sections,
            )
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                        // stands in for the app's navigation handler (registered first, like App.kt)
                        PlatformBackHandler(enabled = true) { session.navigatedBack[0] += 1 }
                        Box(Modifier.size(w.dp, h.dp)) {
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

    private val centre = Offset(180f, 420f)

    private companion object {
        val CLASS_TOKENS = listOf(
            "DRUID", "HUNTER", "MAGE", "PALADIN", "PRIEST", "ROGUE", "SHAMAN", "WARLOCK", "WARRIOR",
        )
    }

    private fun Session.open(): Int = cameras.selectedSection ?: sections.first()

    /** A single-finger drag of ([dx], [dy]) dp from [start] in 20 steps, released at the end. */
    private fun ComposeUiTest.drag(dx: Float, dy: Float = 0f, start: Offset = centre) {
        onRoot().performTouchInput {
            down(start)
            val steps = 20
            repeat(steps) { moveBy(Offset(dx / steps, dy / steps)) }
            up()
        }
        settle()
    }

    /** A quick flick of [dx] dp in [steps] moves 16 ms apart (≈ 940 dp/s for 45 dp in 3). */
    private fun ComposeUiTest.flick(dx: Float, start: Offset = centre, steps: Int = 3) {
        onRoot().performTouchInput {
            down(start)
            repeat(steps) { moveBy(Offset(dx / steps, 0f), delayMillis = 16) }
            up()
        }
        settle()
    }

    private fun Session.assertAtFit(section: Int, message: String) {
        val camera = cameras.stateOf(section)
        assertFalse(camera.userMoved, "$message: still at FIT")
        assertEquals(camera.fitViewport(), camera.viewport, "$message: the camera never moved")
    }

    /** Zooms the open section's camera to 1.4× (a user move, like a pinch). */
    private fun ComposeUiTest.zoomIn(session: Session): TalentTreeViewportState {
        val camera = session.cameras.stateOf(session.open())
        runOnIdle {
            val viewport = assertNotNull(camera.viewport)
            camera.applyUserTransform(viewport.zoomAt(Offset(180f, 300f), 1.4f))
        }
        settle()
        return camera
    }

    // ------------------------------------------------------------------ section swipe: FIT model

    @Test
    fun atFitASwipeAnywhereInsideTheCanvasSwitchesWithoutPanningFirst() {
        onPhone(productState("WARRIOR")) { session ->
            val (a, b, c) = session.sections
            val cameraA = session.cameras.stateOf(a)
            assertEquals(
                TalentSectionSwipeModel.FIT_SWIPE,
                talentSectionSwipeModel(
                    assertNotNull(cameraA.viewport).scale,
                    assertNotNull(cameraA.fitViewport()).scale,
                    assertNotNull(cameraA.contentBounds).width,
                    cameraA.canvasSize.width,
                ),
                "a Warrior section fits a 360 dp phone at FIT",
            )
            // mid-gesture: the camera stays where it is, only the rubber band follows the finger
            onRoot().performTouchInput {
                down(centre)
                repeat(8) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            session.assertAtFit(a, "during the swipe")
            assertTrue(cameraA.sectionOverscrollDp < 0f, "the canvas follows the finger: ${cameraA.sectionOverscrollDp}")
            onRoot().performTouchInput { up() }
            settle()
            assertEquals(b, session.open(), "80 dp left from the centre → Fury, no pan to an edge first")
            assertEquals(0f, cameraA.sectionOverscrollDp)
            session.assertAtFit(a, "Arms after leaving")

            // from the left third of the canvas, then from the right third
            drag(dx = -80f, start = Offset(110f, 300f))
            assertEquals(c, session.open(), "left third → Protection")
            drag(dx = 80f, start = Offset(260f, 600f))
            assertEquals(b, session.open(), "right third, finger right → back to Fury")
            // a quick 45 dp flick
            flick(dx = 45f)
            assertEquals(a, session.open(), "a flick → Arms")
            onNodeWithText("Arms").assertIsSelected()
            for (section in session.sections) session.assertAtFit(section, "section $section")
            assertTrue(session.reported.isEmpty(), "switching sections never touches the BuildState or autosave")
        }
    }

    @Test
    fun everyBundledClassSwitchesFromTreeOneToTreeTwoBySwipe() {
        val edgeModel = mutableListOf<String>()
        val withoutTabs = mutableListOf<String>()
        for (bundle in ALL_BUNDLES) {
            for (token in CLASS_TOKENS) {
                val state = readyStateOnBundle(bundle.clientBuild, token)
                if (state.engine.tree.sections.isEmpty()) {
                    // one whole-tree canvas, nothing to swipe to (TalentTreeSectionTabsUiTest)
                    withoutTabs += "${bundle.clientBuild} $token"
                    continue
                }
                onPhone(state) { session ->
                    val label = "${bundle.clientBuild} $token"
                    val camera = session.cameras.stateOf(session.open())
                    val model = talentSectionSwipeModel(
                        assertNotNull(camera.viewport, label).scale,
                        assertNotNull(camera.fitViewport(), label).scale,
                        assertNotNull(camera.contentBounds, label).width,
                        camera.canvasSize.width,
                    )
                    if (model == TalentSectionSwipeModel.FIT_SWIPE) {
                        drag(dx = -80f)
                    } else {
                        // a section wider than the phone at FIT: it pans to its edge, then overscrolls
                        edgeModel += label
                        drag(dx = -340f, start = Offset(350f, 420f))
                    }
                    assertEquals(session.sections[1], session.open(), "$label: Arms → Fury ($model)")
                    assertTrue(session.reported.isEmpty(), label)
                }
            }
        }
        // only the product build's Mage tree has no tabs (docs/forever-dataset.md, M5.DATA.4)
        assertEquals(listOf("${PRODUCT_BUNDLE.clientBuild} MAGE"), withoutTabs)
        println("PR #19 sections wider than a 360 dp phone at FIT (edge model): ${edgeModel.ifEmpty { listOf("none") }}")
    }

    @Test
    fun atFitASwipeThatStartsOnATalentSwitchesAndSelectsNothing() {
        onPhone(productState("WARRIOR")) { session ->
            val talent = onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).fetchSemanticsNode().boundsInRoot
            drag(dx = -90f, start = talent.center)
            assertEquals(session.sections[1], session.open(), "a swipe from a node still switches")
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertDoesNotExist()
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun atFitAVerticalDragScrollsWithoutSidewaysPanAndKeepsTheSwipe() {
        onPhone(productState("WARRIOR")) { session ->
            val (a, b) = session.sections
            val camera = session.cameras.stateOf(a)
            val fitX = assertNotNull(camera.viewport).offset.x
            drag(dx = -30f, dy = -160f)
            assertEquals(a, session.open(), "a vertical drag never switches")
            assertEquals(fitX, assertNotNull(camera.viewport).offset.x, "sideways locked while scrolling at FIT")
            drag(dx = -80f)
            assertEquals(b, session.open(), "a scrolled section at the fit zoom still swipes directly")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aPinchAtFitZoomsAndNeverSwitches() {
        onPhone(productState("WARRIOR")) { session ->
            val first = session.open()
            val camera = session.cameras.stateOf(first)
            val fitScale = assertNotNull(camera.viewport).scale
            // both fingers drift left by a lot: still a pinch
            onRoot().performTouchInput {
                pinch(
                    start0 = centre - Offset(20f, 0f), end0 = centre - Offset(170f, 0f),
                    start1 = centre + Offset(20f, 0f), end1 = centre + Offset(10f, 0f),
                    durationMillis = 400,
                )
            }
            settle()
            assertEquals(first, session.open(), "a pinch never switches")
            assertTrue(assertNotNull(camera.viewport).scale > fitScale, "the pinch zoomed")
            assertEquals(0f, camera.sectionOverscrollDp)
        }
    }

    // ------------------------------------------------------------------ section swipe: zoomed / moved (edge model)

    @Test
    fun zoomedOneFingerPansAndFitRestoresTheDirectSwipe() {
        onPhone(productState("WARRIOR")) { session ->
            val (a, b) = session.sections
            val camera = zoomIn(session)
            val before = assertNotNull(camera.viewport).offset.x
            drag(dx = -30f)
            assertEquals(a, session.open(), "zoomed: a short drag pans")
            assertTrue(assertNotNull(camera.viewport).offset.x < before, "the camera followed the finger")
            onNodeWithContentDescription("Fit tree").performClick()
            settle()
            session.assertAtFit(a, "after FIT")
            drag(dx = -80f)
            assertEquals(b, session.open(), "FIT brings back the direct swipe")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun zoomedTheEdgePushCountsFromWhereTheEdgeWasReached() {
        onPhone(productState("WARRIOR")) { session ->
            val b = session.sections[1]
            val camera = zoomIn(session)
            // a steep pan first (inside the content), then a horizontal push past
            // the edge: only the push is judged (the whole path is steeper than 0.5)
            onRoot().performTouchInput {
                down(centre)
                repeat(6) { moveBy(Offset(-2f, -12f)) }
                repeat(10) { moveBy(Offset(-10f, 0f)) }
                up()
            }
            settle()
            assertEquals(b, session.open(), "any pan, then ≥ 40 dp past the edge → next")
            assertTrue(camera.userMoved, "Arms keeps the user's camera")
            // zoomed Fury: past the right bound, then pulled back below the threshold before the release
            zoomIn(session)
            onRoot().performTouchInput {
                down(centre)
                repeat(12) { moveBy(Offset(-10f, 0f)) }
                repeat(6) { moveBy(Offset(10f, 0f)) }
                up()
            }
            settle()
            assertEquals(b, session.open(), "pulled back: cancelled")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun panToTheEdgeAloneNeverSwitchesAndTheOverscrollThenDoes() {
        onPhone(productState("WARRIOR")) { session ->
            val first = session.open()
            val camera = zoomIn(session)
            // pan left until the camera stops at its right bound, then release
            onRoot().performTouchInput { down(centre) }
            var previous = assertNotNull(camera.viewport).offset.x
            var steps = 0
            while (steps < 80) {
                onRoot().performTouchInput { moveBy(Offset(-10f, 0f)) }
                waitForIdle()
                val now = assertNotNull(camera.viewport).offset.x
                steps++
                if (now == previous && steps > 2) break
                previous = now
            }
            assertTrue(steps < 80, "the zoomed camera reached its right bound")
            val atEdge = assertNotNull(camera.viewport)
            assertTrue(camera.sectionOverscrollDp > -TalentTreeGestureMetrics.SECTION_SWIPE_OVERSCROLL_DP)
            onRoot().performTouchInput { up() }
            settle()
            assertEquals(first, session.open(), "reaching the edge does not switch")
            assertEquals(atEdge, camera.viewport, "the pan stays where it stopped")

            // the next gesture past the edge: overscroll → release → next section
            onRoot().performTouchInput { down(centre) }
            repeat(12) { onRoot().performTouchInput { moveBy(Offset(-10f, 0f)) } }
            waitForIdle()
            assertTrue(
                camera.sectionOverscrollDp <= -TalentTreeGestureMetrics.SECTION_SWIPE_OVERSCROLL_DP,
                "overscroll accumulates past the bound: ${camera.sectionOverscrollDp}",
            )
            assertEquals(atEdge, camera.viewport, "the camera itself never passes its bound")
            onRoot().performTouchInput { up() }
            settle()
            assertEquals(session.sections[1], session.open(), "swipe left at the right edge → next section")
            assertEquals(0f, camera.sectionOverscrollDp, "the rubber band is gone")
            assertEquals(atEdge, session.cameras.stateOf(first).viewport, "the left section keeps its zoom and pan")
            assertTrue(session.reported.isEmpty(), "a section switch never reaches autosave")
        }
    }

    @Test
    fun swipesWalkTheSectionsBothWaysWithoutWrapAround() {
        onPhone(productState("WARRIOR")) { session ->
            val (a, b, c) = session.sections
            drag(dx = 220f) // right at the first section: there is no previous one
            assertEquals(a, session.open(), "no wrap-around before the first section")
            drag(dx = -220f)
            assertEquals(b, session.open())
            drag(dx = -220f) // at FIT: a direct swipe, Fury's camera does not move
            assertEquals(c, session.open())
            val cameraB = assertNotNull(session.cameras.stateOf(b).viewport)
            assertEquals(session.cameras.stateOf(b).fitViewport(), cameraB, "Fury is left at FIT")
            drag(dx = -220f)
            assertEquals(c, session.open(), "no wrap-around past the last section")
            drag(dx = 220f)
            assertEquals(b, session.open(), "swipe right at the left edge → previous section")
            assertEquals(cameraB, session.cameras.stateOf(b).viewport, "each section keeps its own camera")
            onNodeWithText("Fury").assertIsSelected()
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun shortDiagonalPulledBackAndPinchGesturesNeverSwitch() {
        onPhone(productState("WARRIOR")) { session ->
            val first = session.open()
            drag(dx = -40f)
            assertEquals(first, session.open(), "a short swipe")
            drag(dx = -200f, dy = 200f)
            assertEquals(first, session.open(), "a diagonal swipe")
            // past the edge, then back before the release: cancelled
            onRoot().performTouchInput {
                down(centre)
                repeat(20) { moveBy(Offset(-12f, 0f)) }
                repeat(20) { moveBy(Offset(10f, 0f)) }
                up()
            }
            settle()
            assertEquals(first, session.open(), "pulled back before the release")
            // a pinch whose fingers both drift left
            onRoot().performTouchInput {
                pinch(
                    start0 = centre - Offset(30f, 0f), end0 = centre - Offset(200f, 0f),
                    start1 = centre + Offset(30f, 0f), end1 = centre + Offset(10f, 0f),
                    durationMillis = 400,
                )
            }
            settle()
            assertEquals(first, session.open(), "a pinch never switches")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aTapStillSelectsAndASwitchClearsTheSelectionButKeepsTheHistory() {
        val warrior = productState("WARRIOR")
        onPhone(warrior) { session ->
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            settle()
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).assertIsSelected()
            assertEquals(session.sections.first(), session.open(), "a tap is not a swipe")
            onNodeWithContentDescription("Add rank to $SHORT_DESCRIPTION_TALENT").performClick()
            settle()
            assertEquals(1, session.reported.size)
            drag(dx = -220f)
            assertEquals(session.sections[1], session.open())
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertDoesNotExist() // like a tab tap: selection cleared
            assertEquals(1, session.reported.size, "the switch did not touch the BuildState")
            onNodeWithContentDescription("Undo talent change").performClick()
            settle()
            assertEquals(2, session.reported.size, "undo still undoes the spend made in the other section")
            assertEquals(warrior.engine.emptyState(), session.reported.last())
        }
    }

    // ------------------------------------------------------------------ bottom sheet

    @Test
    fun swipeUpExpandsTheStripAndSwipeDownOnTheHeaderCollapsesIt() {
        onPhone(productState("WARRIOR")) { session ->
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            settle()
            val strip = onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG)
            // a brush shorter than the threshold does nothing
            strip.performTouchInput { swipe(center, center - Offset(0f, 20f), durationMillis = 200) }
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertDoesNotExist()
            strip.performTouchInput { swipeUp(startY = bottom - 4f, endY = top - 60f) }
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertIsDisplayed()
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).assertIsSelected()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_HEADER_TAG).performTouchInput { swipeDown() }
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertDoesNotExist()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertIsDisplayed()
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).assertIsSelected()
            // taps still work: the handle and the name
            onNodeWithContentDescription(EditorCopy.English.expand).performClick()
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertIsDisplayed()
            onNodeWithContentDescription(EditorCopy.English.collapse).performClick()
            settle()
            onNodeWithText(SHORT_DESCRIPTION_TALENT).performClick()
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertIsDisplayed()
            assertTrue(session.reported.isEmpty(), "expand/collapse never reaches autosave")
            assertTrue(session.cameras.stateOf(session.open()).coveredBottomDp > 200f, "covered = the measured sheet")
        }
    }

    @Test
    fun aSwipeThatStartsOnAButtonNeverSpends() {
        onPhone(productState("WARRIOR")) { session ->
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            settle()
            onNodeWithContentDescription("Add rank to $SHORT_DESCRIPTION_TALENT")
                .performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
            settle()
            assertTrue(session.reported.isEmpty(), "dragging over + never spends")
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertIsDisplayed() // it was a swipe up on the strip
            onNodeWithContentDescription("Max out $SHORT_DESCRIPTION_TALENT")
                .performTouchInput { swipe(center, center + Offset(0f, 30f), durationMillis = 150) }
            settle()
            assertTrue(session.reported.isEmpty(), "dragging over Max never maxes")
        }
    }

    @Test
    fun aLongDescriptionScrollsWithoutCollapsingTheSheet() {
        val druid = productState("DRUID")
        val case = unlockedScenario(druid, LONG_DESCRIPTION_TALENT)
        runDesktopComposeUiTest(width = 320, height = 568) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                        Box(Modifier.size(320.dp, 568.dp)) {
                            TalentTreeScreen(
                                druid.copy(buildState = case.build),
                                sectionCameras = TalentTreeSectionCameras("scroll", druid.model.treeId),
                            )
                        }
                    }
                }
            }
            settle()
            onNodeWithContentDescription(case.nodeName).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithContentDescription(EditorCopy.English.expand).performClick()
            settle()
            val scroll = onNodeWithTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG, useUnmergedTree = true)
            val range = assertNotNull(
                scroll.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange),
            )
            scroll.performTouchInput { swipeUp() }
            settle()
            assertTrue(range.value() > 0f, "the description scrolled")
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertIsDisplayed()
            scroll.performTouchInput { swipeDown() }
            settle()
            assertEquals(0f, range.value(), "scrolled back to the top")
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertIsDisplayed() // a swipe down in the text never collapses
        }
    }

    // ------------------------------------------------------------------ system Back (1A v2 S6)

    @Test
    fun systemBackCollapsesThenDeselectsThenNavigates() {
        onPhone(productState("WARRIOR")) { session ->
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            settle()
            onNodeWithContentDescription(EditorCopy.English.expand).performClick()
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertIsDisplayed()

            runOnIdle { assertTrue(DesktopBackDispatcher.dispatch()) }
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertDoesNotExist()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertIsDisplayed()
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).assertIsSelected()
            assertEquals(0, session.navigatedBack[0], "1: expanded → compact")

            runOnIdle { DesktopBackDispatcher.dispatch() }
            settle()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertDoesNotExist()
            assertEquals(0, session.navigatedBack[0], "2: compact → deselected")

            runOnIdle { DesktopBackDispatcher.dispatch() }
            settle()
            assertEquals(1, session.navigatedBack[0], "3: nothing open → the app's navigation Back")
            assertTrue(session.reported.isEmpty(), "Back on the inspector never reaches autosave")
        }
    }

    @Test
    fun systemBackClosesTheBuildActionsSheetFirst() {
        onPhone(productState("WARRIOR")) { session ->
            onNodeWithContentDescription("open build actions").performClick()
            settle()
            onNodeWithText("Reset talents").assertIsDisplayed()
            runOnIdle { DesktopBackDispatcher.dispatch() }
            settle()
            assertTrue(onAllNodesWithText("Reset talents").fetchSemanticsNodes().isEmpty(), "the ⋯ sheet closed")
            assertEquals(0, session.navigatedBack[0], "the editor stayed")
        }
    }
}
