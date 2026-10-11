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
import androidx.compose.ui.test.click
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

    /** The left edge of [section]'s page as drawn (its visible part's left edge, `w` when just off the right). */
    private fun ComposeUiTest.leftOf(section: Int): Float = page(section)?.left ?: w

    /** A drag of [steps] moves of [dx] dp, [millis] apart, from [start]; released when [release]. */
    private fun ComposeUiTest.drag(
        dx: Float,
        steps: Int,
        millis: Long = 16,
        start: Offset = centre,
        release: Boolean = true,
    ) {
        onRoot().performTouchInput {
            down(start)
            repeat(steps) { moveBy(Offset(dx, 0f), delayMillis = millis) }
            if (release) up()
        }
    }

    // ------------------------------------------------------------------ 1. direction + 2. both pages, the spring

    @Test
    fun aTabTapSlidesTheOldPageOutAndTheNewOneInFromTheTabsSide() {
        onPhone { session ->
            val (a, b) = session.sections
            onNodeWithText("Tree 2").performClick()
            advance(16)
            // the first frames: Tree 1 still almost fully shown, Tree 2 entering at the right edge
            assertTrue(assertNotNull(page(a), "Tree 1 is still on screen").width > 0.75f * w)
            assertTrue(composed(b), "Tree 2 is entering")
            assertTrue(leftOf(b) > 0.75f * w, "from the right edge: ${leftOf(b)}")
            advance(48)
            val a1 = assertNotNull(page(a))
            val b1 = assertNotNull(page(b))
            assertEquals(0f, a1.left, "forward: Tree 1 leaves to the LEFT")
            assertEquals(w, b1.right, "… and Tree 2 comes in from the RIGHT")
            assertEquals(a1.right, b1.left, 1f, "the pages are side by side, no gap, no overlap")
            rest()
            assertSettledOn(session, b, "at rest")
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
    fun aTabTapIsHorizontalPagersSpringVisuallyDoneIn220To340ms() {
        onPhone { session ->
            val b = session.sections[1]
            onNodeWithText("Tree 2").performClick()
            advance(16) // the animation's first frame
            val samples = mutableListOf(w) // Tree 2's left edge every frame
            repeat(22) {
                advance(16)
                samples += leftOf(b)
            }
            assertTrue(samples.zipWithNext().all { (x, y) -> y <= x }, "one direction, never back: $samples")
            assertTrue(samples[10] > 0.1f * w, "not instant: ${samples[10]} left at ≈ 160 ms")
            assertTrue(samples[21] < 0.015f * w, "visually done by ≈ 340 ms: ${samples[21]} dp left")
            // decelerating into place: each of the last steps is smaller than the one before
            val steps = samples.zipWithNext { x, y -> x - y }
            assertTrue(steps.takeLast(8).zipWithNext().all { (x, y) -> y <= x + 0.01f }, "eases out: $steps")
        }
    }

    // ------------------------------------------------------------------ continuity, 3. cancel

    @Test
    fun aSwipeContinuesFromWhereAndAsFastAsTheFingerLetGo() {
        onPhone { session ->
            val (a, b) = session.sections
            drag(dx = -10f, steps = 10, release = false) // 10 dp per 16 ms ≈ 625 dp/s
            advance(16)
            val a0 = assertNotNull(page(a))
            val b0 = assertNotNull(page(b), "Tree 2 follows the finger in")
            // the pages follow from where the swipe locked (after the first move): 90 dp, 1:1
            assertEquals(270f, a0.right, 2f, "Tree 1 moved with the finger, 1:1")
            assertEquals(a0.right, b0.left, 1f)
            assertEquals(b0.width / w, indicator(), 0.02f, "the indicator follows the finger too")
            session.assertCameraAtFit(a)

            onRoot().performTouchInput { up() }
            advance(16)
            val a1 = assertNotNull(page(a), "the leaving page continues from the release")
            val firstStep = a0.right - a1.right
            assertTrue(firstStep in 2f..30f, "no jump and no stop at the release: moved $firstStep dp in one frame")
            assertEquals(b, session.open(), "the switch is decided at the release")
            // the direction is kept to the end: always leftwards, never back
            var previous = a1.right
            repeat(12) {
                advance(16)
                val now = page(a)?.right ?: 0f
                assertTrue(now <= previous + 0.01f, "keeps moving left: $previous → $now")
                previous = now
            }
            rest()
            assertSettledOn(session, b, "the swipe finished")
            session.assertCameraAtFit(a)
            session.assertCameraAtFit(b)
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aCancelledSwipeEasesBackToTheSameSectionWithoutBounce() {
        onPhone { session ->
            val (a, b) = session.sections
            drag(dx = -5f, steps = 8, millis = 60, release = false) // 40 dp, slowly: below the threshold
            advance(16)
            val peek = assertNotNull(page(b), "Tree 2 peeks in")
            assertEquals(330f, peek.left, 2f, "30 dp after the slop lock")
            onRoot().performTouchInput { up() }
            advance(16)
            assertEquals(a, session.open(), "not confirmed: still Tree 1")
            val back = mutableListOf(leftOf(b))
            assertTrue(abs(back.first() - peek.left) < 6f, "eases back from the release: ${back.first()}")
            repeat(25) {
                advance(16)
                back += leftOf(b)
            }
            assertTrue(back.zipWithNext().all { (x, y) -> y >= x - 0.01f }, "straight back, no bounce: $back")
            assertEquals(w, back.last(), 0.5f)
            rest()
            assertSettledOn(session, a, "cancelled")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aFlickSwitchesButAFingerThatRestedBeforeTheLiftLetsGoWithoutSpeed() {
        onPhone { session ->
            val (a, b) = session.sections
            // 45 dp: past the flick minimum (32 dp), short of the distance rule (56 dp), so only the speed decides
            drag(dx = -15f, steps = 3) // ≈ 940 dp/s, lifted while still moving
            rest()
            assertSettledOn(session, b, "a quick flick switches")

            drag(dx = 15f, steps = 3, release = false) // the same flick back towards Tree 1 …
            advance(120)
            onRoot().performTouchInput {
                advanceEventTime(120) // … but the finger rests before it lifts
                up()
            }
            rest()
            assertSettledOn(session, b, "a finger that rested before the lift has no speed: the pages ease back")
            assertNull(page(a))
            assertTrue(session.reported.isEmpty())
        }
    }

    // ------------------------------------------------------------------ interruption, 4. fast repeats, 5. tab mid-motion

    @Test
    fun aTouchStopsThePagesWhereTheyAreAndTheFingerCarriesThemOn() {
        onPhone { session ->
            val (a, b) = session.sections
            onNodeWithText("Tree 2").performClick()
            advance(16 + 64)
            val before = leftOf(b)
            assertTrue(before in 60f..300f, "Tree 2 is mid-way: $before")
            onRoot().performTouchInput { down(centre) } // catch
            advance(16)
            assertEquals(before, leftOf(b), 0.5f, "caught exactly where it was drawn — no snap to the end")
            advance(100)
            assertEquals(before, leftOf(b), 0.5f, "and held there")
            // the finger now carries the pages from that position, 1:1 (after the slop lock)
            onRoot().performTouchInput {
                repeat(5) { moveBy(Offset(10f, 0f), delayMillis = 60) } // slowly back towards Tree 1
            }
            advance(16)
            assertEquals(before + 40f, leftOf(b), 1.5f, "held + the drag since the lock")
            assertEquals(b, session.open())
            // released without a confirmed swipe: the interrupted transition completes
            onRoot().performTouchInput { up() }
            rest()
            assertSettledOn(session, b, "interrupted, then completed")
            assertTrue(session.reported.isEmpty())

            // a confirmed swipe against a running transition undoes it, from where it is
            onNodeWithText("Tree 1").performClick()
            advance(16 + 64)
            val mid = leftOf(b)
            drag(dx = -15f, steps = 6, release = false) // finger left: back towards Tree 2
            advance(16)
            assertTrue(leftOf(b) < mid, "the pages follow the finger from mid-way")
            onRoot().performTouchInput { up() }
            rest()
            assertSettledOn(session, b, "the swipe took Tree 2 back")
            assertNull(page(a))
        }
    }

    @Test
    fun fastRepeatedSwipesAdvanceOneTabEachWithoutJumps() {
        onPhone { session ->
            val (a, b, c) = session.sections
            drag(dx = -20f, steps = 6, millis = 10) // a fling
            advance(120)
            val tree2 = leftOf(b)
            onRoot().performTouchInput { down(centre) } // the next swipe begins mid-motion
            advance(16)
            assertEquals(tree2, leftOf(b), 0.5f, "the next touch starts from the drawn position")
            onRoot().performTouchInput {
                repeat(6) { moveBy(Offset(-20f, 0f), delayMillis = 10) }
                up()
            }
            advance(16)
            assertEquals(c, session.open(), "the second swipe advanced one more tab")
            advance(120)
            drag(dx = -20f, steps = 6, millis = 10) // no wrap-around after the last tab
            rest()
            assertSettledOn(session, c, "three quick swipes: Tree 3, deterministic")
            assertNull(page(a))
            onNodeWithText("Tree 3").assertIsSelected()
            assertEquals(2f, indicator(), 0.01f)
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aTabTapDuringAMotionContinuesFromWhereItIsAndPassesTheTabBetween() {
        onPhone { session ->
            val (a, b, c) = session.sections
            onNodeWithText("Tree 2").performClick()
            advance(16 + 64)
            onNodeWithText("Tree 3").performClick() // (a click itself lets the test clock run a few frames)
            val aLeft = assertNotNull(page(a)).right
            advance(16)
            val aNext = assertNotNull(page(a), "Tree 1 does not vanish").right
            assertTrue(aNext <= aLeft && aLeft - aNext < 70f, "Tree 1 keeps moving left, one frame's way: $aLeft → $aNext")
            // the indicator travels across Tree 2 to Tree 3, like a pager
            var previous = indicator()
            repeat(20) {
                advance(16)
                val now = indicator()
                assertTrue(now >= previous - 0.001f, "the indicator only moves towards Tree 3: $previous → $now")
                previous = now
            }
            rest()
            assertSettledOn(session, c, "the last tap wins")
            onNodeWithText("Tree 3").assertIsSelected()
            assertNull(page(b))
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun theIndicatorMatchesThePagesOnEveryIntermediateFrame() {
        onPhone { session ->
            val b = session.sections[1]
            onNodeWithText("Tree 2").performClick()
            repeat(18) {
                advance(16)
                val shown = (w - leftOf(b)) / w // the visible share of Tree 2
                assertEquals(shown, indicator(), 0.02f, "frame $it")
            }
        }
    }

    @Test
    fun aTapThatCatchesMovingPagesNeverSelects() {
        onPhone { session ->
            onNodeWithText("Tree 2").performClick()
            advance(16 + 48)
            onRoot().performTouchInput { click(centre) }
            rest()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertDoesNotExist()
            assertSettledOn(session, session.sections[1], "the caught motion completed")
            // at rest a tap selects as always
            onNodeWithContentDescription(assertNotNull(firstTalentOf(session, 1))).performClick()
            rest()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertExists()
        }
    }

    private fun firstTalentOf(session: Session, ordinal: Int): String? {
        val warrior = productState("WARRIOR")
        val sections = (buildTalentTreeSections(warrior.engine.tree, warrior.model) as TalentTreeSectionsResult.Sectioned)
        return sections.sections.first { it.index == session.sections[ordinal] }.model.nodes.firstNotNullOfOrNull { it.name }
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
