package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * PR #19 motion polish on the REAL screen (360 × 800 dp, density 1 so px = dp),
 * with the test clock driven by hand so the animation is sampled mid-way:
 * the section pages (both pages on screen, direction, finger-follow, ease-out,
 * cancel, fast repeats, a tab tap during a transition), the tab indicator
 * travelling with them, the sliding inspector sheet over its FINAL measured
 * geometry, reduced motion, and — through all of it — the cameras, the build
 * and autosave untouched.
 *
 * A page's bounds are its moving layer clipped to the canvas, so a page half
 * slid out shows as the visible half.
 */
@OptIn(ExperimentalTestApi::class)
class TalentTreeMotionUiTest {

    private class Session(
        val cameras: TalentTreeSectionCameras,
        val reported: MutableList<BuildState>,
        val sections: List<Int>,
    )

    private val w = 360f
    private val h = 800

    private fun onPhone(
        state: TalentTreeUiState.Ready = productState("WARRIOR"),
        reducedMotion: Boolean? = null,
        block: ComposeUiTest.(Session) -> Unit,
    ) = runDesktopComposeUiTest(width = w.toInt(), height = h) {
        mainClock.autoAdvance = false
        val sections = (buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned)
            .sections.map { it.index }
        val session = Session(
            cameras = TalentTreeSectionCameras(sessionKey = "motion", treeId = state.model.treeId),
            reported = mutableListOf(),
            sections = sections,
        )
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides reducedMotion) {
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
        rest()
        block(session)
    }

    private fun ComposeUiTest.rest() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    private fun ComposeUiTest.advance(ms: Long) {
        mainClock.advanceTimeBy(ms)
        waitForIdle()
    }

    /** The visible part of [section]'s page, or null when it is not composed. */
    private fun ComposeUiTest.page(section: Int): Rect? =
        onAllNodesWithTag(talentSectionPageTag(section)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            ?.takeIf { it.width > 0f }

    /** Whether [section]'s page is composed at all (a page waiting just off screen has no visible part). */
    private fun ComposeUiTest.composed(section: Int): Boolean =
        onAllNodesWithTag(talentSectionPageTag(section)).fetchSemanticsNodes().isNotEmpty()

    /** The tab indicator's position as a tab ordinal (three tabs share 360 dp). */
    private fun ComposeUiTest.indicator(): Float {
        val tab = w / 3f
        val bounds = onNodeWithTag(TALENT_SECTION_INDICATOR_TAG).fetchSemanticsNode().boundsInRoot
        return (bounds.center.x - tab / 2f) / tab
    }

    private fun Session.open(): Int = cameras.selectedSection ?: sections.first()

    private val centre = Offset(180f, 420f)

    private fun ComposeUiTest.assertSettledOn(session: Session, section: Int, message: String) {
        assertEquals(section, session.open(), message)
        val shown = assertNotNull(page(section), message)
        assertEquals(0f, shown.left, message)
        assertEquals(w, shown.right, message)
        for (other in session.sections - section) assertNull(page(other), "$message: only one page is composed at rest")
    }

    // ------------------------------------------------------------------ 1. direction + 2. exit / entry

    @Test
    fun aTabTapSlidesTheOldPageOutAndTheNewOneInFromTheTabsSide() {
        onPhone { session ->
            val (a, b) = session.sections
            onNodeWithText("Tree 2").performClick()
            advance(16)
            // the very first frame: Tree 1 still fully shown, Tree 2 composed just beyond the right edge —
            // the new tree never just appears
            assertEquals(w, assertNotNull(page(a), "Tree 1 is still on screen").width)
            assertTrue(composed(b), "Tree 2 is about to enter")
            advance(32)
            val b0 = assertNotNull(page(b), "Tree 2 is entering")
            assertTrue(b0.left > 150f && b0.right == w, "Tree 2 enters from the right: $b0")

            advance(68)
            val a1 = assertNotNull(page(a))
            val b1 = assertNotNull(page(b))
            assertEquals(0f, a1.left, "forward: Tree 1 leaves to the LEFT")
            assertEquals(w, b1.right, "… and Tree 2 comes in from the RIGHT")
            assertEquals(a1.right, b1.left, 1f, "the pages are side by side, no gap, no overlap")
            assertEquals(b1.width / w, indicator(), 0.03f, "the indicator shows exactly what the pages show")

            advance(400)
            assertSettledOn(session, b, "after 260 ms")
            onNodeWithText("Tree 2").assertIsSelected()
            assertEquals(1f, indicator(), 0.01f)

            // backward: Tree 2 leaves to the RIGHT, Tree 1 comes in from the LEFT
            onNodeWithText("Tree 1").performClick()
            advance(16 + 100)
            assertEquals(0f, assertNotNull(page(a)).left)
            assertEquals(w, assertNotNull(page(b)).right)
            assertTrue(assertNotNull(page(a)).width < w, "Tree 1 is still sliding in")
            rest()
            assertSettledOn(session, a, "back on Tree 1")
            assertTrue(session.reported.isEmpty(), "page transitions never reach the build or autosave")
        }
    }

    @Test
    fun theTransitionEndsWithAnEaseOutAndTakesAbout260ms() {
        onPhone { session ->
            val b = session.sections[1]
            onNodeWithText("Tree 2").performClick()
            advance(16) // the animation's first frame
            val samples = mutableListOf(w)
            repeat(17) {
                advance(16)
                assertTrue(composed(b))
                samples += page(b)?.left ?: w // still just beyond the right edge
            }
            val travelled = samples.first() - samples.last()
            val firstHalf = samples.first() - samples[8]
            assertTrue(firstHalf > 0.7f * travelled, "decelerating: $samples")
            assertTrue(samples.zipWithNext().all { (x, y) -> y <= x }, "never moves back: $samples")
            assertEquals(0f, samples.last(), 1f, "at rest after ≈ 260 ms: $samples")
        }
    }

    // ------------------------------------------------------------------ finger-follow, 3. cancel

    @Test
    fun aSwipeCarriesBothPagesThenFinishesFromWhereTheFingerLetGo() {
        onPhone { session ->
            val (a, b) = session.sections
            onRoot().performTouchInput {
                down(centre)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
            }
            advance(16)
            val a0 = assertNotNull(page(a))
            val b0 = assertNotNull(page(b), "Tree 2 follows the finger in")
            assertEquals(260f, a0.right, 2f, "Tree 1 moved with the finger, 1:1")
            assertEquals(a0.right, b0.left, 1f)
            assertEquals(b0.width / w, indicator(), 0.03f, "the indicator follows the finger too")
            session.assertCameraAtFit(a)

            onRoot().performTouchInput { up() }
            advance(16)
            val a1 = assertNotNull(page(a), "the leaving page continues from the release")
            assertTrue(abs(a1.right - a0.right) < 40f, "no jump at the release: ${a0.right} → ${a1.right}")
            assertEquals(b, session.open(), "the switch is decided at the release")
            advance(60)
            assertTrue(assertNotNull(page(a)).right < a1.right, "still leaving to the left")
            rest()
            assertSettledOn(session, b, "the swipe finished")
            session.assertCameraAtFit(a)
            session.assertCameraAtFit(b)
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aCancelledSwipeEasesBackToTheSameSection() {
        onPhone { session ->
            val (a, b) = session.sections
            onRoot().performTouchInput {
                down(centre)
                repeat(8) { moveBy(Offset(-5f, 0f), delayMillis = 60) } // 40 dp, slowly: below the threshold
            }
            advance(16)
            val peek = assertNotNull(page(b), "Tree 2 peeks in")
            assertEquals(320f, peek.left, 2f)
            onRoot().performTouchInput { up() }
            advance(16)
            assertEquals(a, session.open(), "not confirmed: still Tree 1")
            val start = assertNotNull(page(b)).left
            assertTrue(abs(start - peek.left) < 10f, "eases back from the release: $start")
            advance(50)
            assertTrue(assertNotNull(page(b)).left > start, "Tree 2 goes back out to the right")
            rest()
            assertSettledOn(session, a, "cancelled")
            assertTrue(session.reported.isEmpty())
        }
    }

    // ------------------------------------------------------------------ 4. fast repeats + 5. tab during a transition

    @Test
    fun fastRepeatedSwipesNeverQueueAnimations() {
        onPhone { session ->
            val (a, b, c) = session.sections
            fun fling() = onRoot().performTouchInput {
                down(centre)
                repeat(6) { moveBy(Offset(-20f, 0f), delayMillis = 10) }
                up()
            }
            fling()
            advance(48) // Tree 1 → Tree 2 is still moving …
            assertNotNull(page(a))
            fling() // … the next touch lets the pages rest at once and swipes on from Tree 2
            advance(16)
            assertEquals(c, session.open())
            assertNull(page(a), "the first transition ended: nothing queued behind it")
            assertNotNull(page(b))
            fling() // no wrap-around after the last section
            rest()
            assertSettledOn(session, c, "three quick swipes: Tree 3, deterministic")
            onNodeWithText("Tree 3").assertIsSelected()
            assertEquals(2f, indicator(), 0.01f)
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aTabTapDuringATransitionRetargetsWithoutAQueue() {
        onPhone { session ->
            val (a, b, c) = session.sections
            onNodeWithText("Tree 2").performClick()
            advance(16 + 80)
            onNodeWithText("Tree 3").performClick()
            advance(16)
            assertNull(page(a), "Tree 1's transition ended at once")
            assertNotNull(page(b), "Tree 2 leaves …")
            assertTrue(composed(c), "… and Tree 3 comes in")
            rest()
            assertSettledOn(session, c, "the last tap wins")
            onNodeWithText("Tree 3").assertIsSelected()
            assertTrue(session.reported.isEmpty())
        }
    }

    // ------------------------------------------------------------------ 6. cameras + 7. autosave

    private fun Session.assertCameraAtFit(section: Int) {
        val camera = cameras.stateOf(section)
        assertFalse(camera.userMoved, "section $section: its camera was never moved by a transition")
        assertEquals(camera.fitViewport(), camera.viewport)
    }

    @Test
    fun everySectionKeepsItsOwnCameraThroughTransitions() {
        onPhone { session ->
            val (a, b) = session.sections
            val cameraA = session.cameras.stateOf(a)
            runOnIdle {
                cameraA.applyUserTransform(assertNotNull(cameraA.viewport).zoomAt(Offset(180f, 300f), 1.4f))
            }
            rest()
            val zoomed = assertNotNull(cameraA.viewport)
            onNodeWithText("Tree 2").performClick()
            rest()
            session.assertCameraAtFit(b)
            // back by swipe at FIT on Tree 2: Tree 1 slides in with its zoom intact
            onRoot().performTouchInput {
                down(centre)
                repeat(10) { moveBy(Offset(12f, 0f)) }
            }
            advance(16)
            assertEquals(zoomed, cameraA.viewport, "the peeking Tree 1 keeps its zoom and pan")
            onRoot().performTouchInput { up() }
            rest()
            assertSettledOn(session, a, "back on Tree 1")
            assertEquals(zoomed, cameraA.viewport, "scale, offset and userMoved survive the round trip")
            assertTrue(cameraA.userMoved)
            session.assertCameraAtFit(b)
            assertTrue(session.reported.isEmpty(), "no autosave from any of it")
        }
    }

    // ------------------------------------------------------------------ 8. the sheet + 9. talent focus

    @Test
    fun theSheetSlidesUpAndDownOverItsFinalMeasuredGeometry() {
        onPhone { session ->
            val camera = session.cameras.stateOf(session.open())
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            rest()
            val strip = onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).fetchSemanticsNode().boundsInRoot

            onNodeWithContentDescription(INSPECTOR_EXPAND_LABEL).performClick()
            advance(16)
            val first = onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(first.top > strip.top - 60f, "the sheet starts at the strip's top edge: ${first.top} vs ${strip.top}")
            advance(16) // the sheet's real height is measured in its first layout (M5.UX.2a)
            val covered = camera.coveredBottomDp
            advance(84)
            val mid = onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).fetchSemanticsNode().boundsInRoot
            rest()
            val open = onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(mid.top > open.top && mid.top < first.top, "sliding up: ${first.top} → ${mid.top} → ${open.top}")
            assertEquals(open.height, covered, 1f, "the camera got the FINAL height from the first frame")
            assertEquals(covered, camera.coveredBottomDp, "and it never changed during the slide")

            // the controls work on the open sheet
            onNodeWithContentDescription("Add rank to $SHORT_DESCRIPTION_TALENT").performClick()
            rest()
            assertEquals(1, session.reported.size, "+ spends one rank; the animation spent nothing")

            onNodeWithContentDescription(INSPECTOR_COLLAPSE_LABEL).performClick()
            advance(16)
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertExists() // the strip is back (and interactive) at once
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertDoesNotExist()
            val closing = onNodeWithTag(TALENT_INSPECTOR_SHEET_CLOSING_TAG).fetchSemanticsNode().boundsInRoot
            advance(100)
            val closingMid = onNodeWithTag(TALENT_INSPECTOR_SHEET_CLOSING_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(closingMid.top > closing.top, "sliding down: ${closing.top} → ${closingMid.top}")
            assertEquals(strip.height, camera.coveredBottomDp, 1f, "covered = the strip from the first frame")
            rest()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_CLOSING_TAG).assertDoesNotExist()
            assertEquals(1, session.reported.size, "expand / collapse never reach autosave")
        }
    }

    @Test
    fun aSwipeUpOnTheStripSlidesTheSheetAndTheSelectedTalentStaysInView() {
        val warrior = productState("WARRIOR")
        val first = (buildTalentTreeSections(warrior.engine.tree, warrior.model) as TalentTreeSectionsResult.Sectioned)
            .sections.first()
        val lowest = assertNotNull(first.model.nodes.maxByOrNull { it.centerY }?.name, "a named bottom talent")
        onPhone(warrior) { session ->
            val camera = session.cameras.stateOf(session.open())
            onNodeWithContentDescription(lowest).performClick()
            rest()
            val before = camera.focusRequest?.id
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).performTouchInput { swipeUp(startY = bottom - 4f, endY = top - 60f) }
            advance(16 + 60)
            assertNotNull(onAllNodesWithTag(TALENT_INSPECTOR_SHEET_TAG).fetchSemanticsNodes().firstOrNull(), "expanding")
            rest()
            val sheet = onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).fetchSemanticsNode().boundsInRoot
            val node = onNodeWithContentDescription(lowest).fetchSemanticsNode().boundsInRoot
            assertTrue(node.bottom <= sheet.top + 1f, "the selected talent is revealed above the open sheet")
            val requests = (camera.focusRequest?.id ?: 0) - (before ?: 0)
            assertTrue(requests <= 1, "the camera was asked to move at most once, never per frame ($requests)")
            onNodeWithContentDescription(lowest).assertIsSelected()
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun selectingAnotherTalentOnTheOpenSheetShowsItsStripWithoutAClosingSlide() {
        val warrior = productState("WARRIOR")
        val first = (buildTalentTreeSections(warrior.engine.tree, warrior.model) as TalentTreeSectionsResult.Sectioned)
            .sections.first()
        val top = assertNotNull(
            first.model.nodes.filter { it.name != SHORT_DESCRIPTION_TALENT }.minByOrNull { it.centerY }?.name,
        )
        onPhone(warrior) { session ->
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            rest()
            onNodeWithContentDescription(INSPECTOR_EXPAND_LABEL).performClick()
            rest()
            onNodeWithContentDescription(top).performClick()
            advance(16)
            onNodeWithTag(TALENT_INSPECTOR_SHEET_CLOSING_TAG).assertDoesNotExist() // never the other talent's sheet sliding
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertDoesNotExist()
            rest()
            onNodeWithContentDescription(top).assertIsSelected()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertExists()
            assertTrue(session.reported.isEmpty())
        }
    }

    // ------------------------------------------------------------------ 10. reduced motion

    @Test
    fun withReducedMotionEverythingJumpsToItsEndAndStillWorks() {
        onPhone(reducedMotion = true) { session ->
            val (a, b, c) = session.sections
            onNodeWithText("Tree 2").performClick()
            advance(48)
            assertSettledOn(session, b, "a tab tap is instant")
            onRoot().performTouchInput {
                down(centre)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
                up()
            }
            advance(48)
            assertSettledOn(session, c, "a swipe still switches, instantly at the release")

            onNodeWithText("Tree 1").performClick()
            advance(48)
            assertSettledOn(session, a, "back")
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            advance(48)
            onNodeWithContentDescription(INSPECTOR_EXPAND_LABEL).performClick()
            advance(48)
            val now = onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).fetchSemanticsNode().boundsInRoot
            rest()
            assertEquals(now, onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).fetchSemanticsNode().boundsInRoot, "already at rest")
            onNodeWithContentDescription("Add rank to $SHORT_DESCRIPTION_TALENT").performClick()
            advance(48)
            assertEquals(1, session.reported.size, "the controls work")
        }
    }

    @Test
    fun aPinchStillNeverSwitchesAndLeavesNoPageBehind() {
        onPhone { session ->
            val (a, b) = session.sections
            onRoot().performTouchInput {
                pinch(
                    start0 = centre - Offset(20f, 0f), end0 = centre - Offset(150f, 0f),
                    start1 = centre + Offset(20f, 0f), end1 = centre + Offset(10f, 0f),
                    durationMillis = 300,
                )
            }
            rest()
            assertSettledOn(session, a, "a pinch zooms, it never pages")
            assertNull(page(b))
        }
    }

    // ------------------------------------------------------------------ 11. Show and undo / redo

    @Test
    fun aCrossSectionShowSlidesToTheTargetAndBackWithoutTouchingTheBuild() {
        val baseline = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
        val fixture = crossSectionFixture(baseline.engine.document)
        val engine = TalentAllocationEngine(fixture.document)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(fixture.document)).model
        val state = baseline.copy(model = model, engine = engine, buildState = engine.emptyState())
        val origin = assertNotNull(model.nodes.first { it.id == fixture.targetId }.name)
        val prerequisite = assertNotNull(model.nodes.first { it.id == fixture.sourceId }.name)
        onPhone(state) { session ->
            val (_, b, c) = session.sections
            onNodeWithText("Tree 3").performClick()
            rest()
            onNodeWithContentDescription(origin).performClick()
            rest()
            val originCamera = session.cameras.stateOf(c).viewport
            onNodeWithText("Show").performClick()
            advance(16 + 100)
            assertEquals(0f, assertNotNull(page(b), "Tree 2 slides in").left, "from the LEFT (backwards in the tab order)")
            assertNotNull(page(c), "Tree 3 slides out")
            rest()
            assertSettledOn(session, b, "Show switched to the prerequisite's section")
            onNodeWithContentDescription(prerequisite).assertIsSelected()
            assertEquals(originCamera, session.cameras.stateOf(c).viewport, "the origin camera is kept")

            onNodeWithText("Back to $origin · Tree 3").performClick()
            advance(16 + 100)
            assertEquals(w, assertNotNull(page(c), "the Back chip slides Tree 3 back in").right)
            rest()
            assertSettledOn(session, c, "back at the origin")
            onNodeWithContentDescription(origin).assertIsSelected()
            assertTrue(session.reported.isEmpty(), "Show and Back never spend")
        }
    }

    @Test
    fun undoAndRedoStayGlobalAcrossAnimatedSwitches() {
        val warrior = productState("WARRIOR")
        onPhone(warrior) { session ->
            val b = session.sections[1]
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            rest()
            onNodeWithContentDescription("Add rank to $SHORT_DESCRIPTION_TALENT").performClick()
            rest()
            onRoot().performTouchInput {
                down(centre)
                repeat(10) { moveBy(Offset(-12f, 0f)) }
                up()
            }
            advance(16 + 60) // undo DURING the page transition
            onNodeWithContentDescription("Undo talent change").performClick()
            rest()
            assertSettledOn(session, b, "the undo did not switch back")
            assertEquals(listOf(1, 0), session.reported.map { warrior.engine.spentPoints(it) })
            onNodeWithContentDescription("Redo talent change").performClick()
            rest()
            assertEquals(listOf(1, 0, 1), session.reported.map { warrior.engine.spentPoints(it) })
            assertEquals(b, session.open())
        }
    }
}
