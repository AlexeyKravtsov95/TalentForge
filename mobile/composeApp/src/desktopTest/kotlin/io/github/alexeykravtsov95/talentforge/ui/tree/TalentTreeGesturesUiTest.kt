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
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR #19 on the REAL screen (product Warrior, 360 × 800 dp, density 1 so
 * px = dp): the overscroll section swipe of the canvas, the bottom inspector's
 * vertical swipes and the 1A v2 S6 system Back order — with the independent
 * cameras, undo/redo and the autosave observer around them.
 *
 * Android's own edge zone is not reproducible here (the desktop host reports
 * no system gesture insets); its rule is covered by
 * `TalentTreeGestureRulesTest` and must be checked on a device.
 */
@OptIn(ExperimentalTestApi::class)
class TalentTreeGesturesUiTest {

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
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
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

    // ------------------------------------------------------------------ section swipe

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
            drag(dx = -220f) // first pans Tree 2 to its bound, then overscrolls into Tree 3
            assertEquals(c, session.open())
            // Tree 2's camera as the swipe left it
            val cameraB = assertNotNull(session.cameras.stateOf(b).viewport)
            drag(dx = -220f)
            assertEquals(c, session.open(), "no wrap-around past the last section")
            drag(dx = 220f)
            assertEquals(b, session.open(), "swipe right at the left edge → previous section")
            assertEquals(cameraB, session.cameras.stateOf(b).viewport, "each section keeps its own camera")
            onNodeWithText("Tree 2").assertIsSelected()
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
            onNodeWithContentDescription(INSPECTOR_EXPAND_LABEL).performClick()
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertIsDisplayed()
            onNodeWithContentDescription(INSPECTOR_COLLAPSE_LABEL).performClick()
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
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
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
            onNodeWithContentDescription(INSPECTOR_EXPAND_LABEL).performClick()
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
            onNodeWithContentDescription(INSPECTOR_EXPAND_LABEL).performClick()
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
