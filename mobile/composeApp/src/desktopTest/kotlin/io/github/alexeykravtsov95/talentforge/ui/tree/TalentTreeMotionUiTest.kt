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
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
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
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import org.junit.Rule

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

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

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
        settle: Boolean = true,
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
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides reducedMotion, LocalAppLanguage provides AppLanguage.ENGLISH) {
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
        if (settle) rest()
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
        for (other in session.sections - section) assertNull(page(other), "$message: only one page is visible at rest")
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
            onNodeWithText("Fury").performClick()
            advance(16)
            // the first frames: Arms still almost fully shown, Fury entering at the right edge
            assertTrue(assertNotNull(page(a), "Arms is still on screen").width > 0.75f * w)
            assertTrue(composed(b), "Fury is entering")
            assertTrue(leftOf(b) > 0.75f * w, "from the right edge: ${leftOf(b)}")
            advance(48)
            val a1 = assertNotNull(page(a))
            val b1 = assertNotNull(page(b))
            assertEquals(0f, a1.left, "forward: Arms leaves to the LEFT")
            assertEquals(w, b1.right, "… and Fury comes in from the RIGHT")
            assertEquals(a1.right, b1.left, 1f, "the pages are side by side, no gap, no overlap")
            rest()
            assertSettledOn(session, b, "at rest")
            onNodeWithText("Fury").assertIsSelected()
            assertEquals(1f, indicator(), 0.01f)

            // backward: Fury leaves to the RIGHT, Arms comes in from the LEFT
            onNodeWithText("Arms").performClick()
            advance(16 + 100)
            assertEquals(0f, assertNotNull(page(a)).left)
            assertEquals(w, assertNotNull(page(b)).right)
            assertTrue(assertNotNull(page(a)).width < w, "Arms is still sliding in")
            rest()
            assertSettledOn(session, a, "back on Arms")
            assertTrue(session.reported.isEmpty(), "page transitions never reach the build or autosave")
        }
    }

    @Test
    fun aTabTapIsHorizontalPagersSpringVisuallyDoneIn220To340ms() {
        onPhone { session ->
            val b = session.sections[1]
            onNodeWithText("Fury").performClick()
            advance(16) // the animation's first frame
            val samples = mutableListOf(w) // Fury's left edge every frame
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
            val b0 = assertNotNull(page(b), "Fury follows the finger in")
            // the pages follow from where the swipe locked (after the first move): 90 dp, 1:1
            assertEquals(270f, a0.right, 2f, "Arms moved with the finger, 1:1")
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
            val peek = assertNotNull(page(b), "Fury peeks in")
            assertEquals(330f, peek.left, 2f, "30 dp after the slop lock")
            onRoot().performTouchInput { up() }
            advance(16)
            assertEquals(a, session.open(), "not confirmed: still Arms")
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

            drag(dx = 15f, steps = 3, release = false) // the same flick back towards Arms …
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
            onNodeWithText("Fury").performClick()
            advance(16 + 64)
            val before = leftOf(b)
            assertTrue(before in 60f..300f, "Fury is mid-way: $before")
            onRoot().performTouchInput { down(centre) } // catch
            advance(16)
            assertEquals(before, leftOf(b), 0.5f, "caught exactly where it was drawn — no snap to the end")
            advance(100)
            assertEquals(before, leftOf(b), 0.5f, "and held there")
            // the finger now carries the pages from that position, 1:1 (after the slop lock)
            onRoot().performTouchInput {
                repeat(5) { moveBy(Offset(10f, 0f), delayMillis = 60) } // slowly back towards Arms
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
            onNodeWithText("Arms").performClick()
            advance(16 + 64)
            val mid = leftOf(b)
            drag(dx = -15f, steps = 6, release = false) // finger left: back towards Fury
            advance(16)
            assertTrue(leftOf(b) < mid, "the pages follow the finger from mid-way")
            onRoot().performTouchInput { up() }
            rest()
            assertSettledOn(session, b, "the swipe took Fury back")
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
            assertSettledOn(session, c, "three quick swipes: Protection, deterministic")
            assertNull(page(a))
            onNodeWithText("Protection").assertIsSelected()
            assertEquals(2f, indicator(), 0.01f)
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aTabTapDuringAMotionContinuesFromWhereItIsAndPassesTheTabBetween() {
        onPhone { session ->
            val (a, b, c) = session.sections
            onNodeWithText("Fury").performClick()
            advance(16 + 64)
            onNodeWithText("Protection").performClick() // (a click itself lets the test clock run a few frames)
            val aLeft = assertNotNull(page(a)).right
            advance(16)
            val aNext = assertNotNull(page(a), "Arms does not vanish").right
            assertTrue(aNext <= aLeft && aLeft - aNext < 70f, "Arms keeps moving left, one frame's way: $aLeft → $aNext")
            // the indicator travels across Fury to Protection, like a pager
            var previous = indicator()
            repeat(20) {
                advance(16)
                val now = indicator()
                assertTrue(now >= previous - 0.001f, "the indicator only moves towards Protection: $previous → $now")
                previous = now
            }
            rest()
            assertSettledOn(session, c, "the last tap wins")
            onNodeWithText("Protection").assertIsSelected()
            assertNull(page(b))
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun theIndicatorMatchesThePagesOnEveryIntermediateFrame() {
        onPhone { session ->
            val b = session.sections[1]
            onNodeWithText("Fury").performClick()
            repeat(18) {
                advance(16)
                val shown = (w - leftOf(b)) / w // the visible share of Fury
                assertEquals(shown, indicator(), 0.02f, "frame $it")
            }
        }
    }

    @Test
    fun aTapThatCatchesMovingPagesNeverSelects() {
        onPhone { session ->
            onNodeWithText("Fury").performClick()
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

    // ------------------------------------------------------------------ round 6: pages composed ahead

    @Test
    fun everyPageIsComposedAheadAtRestAndNoSwipeComposesOrDisposesOne() {
        onPhone { session ->
            val (a, b, c) = session.sections
            // after the opening rest the other pages already exist, off screen …
            for (section in session.sections) assertTrue(composed(section), "section $section is composed ahead")
            assertNull(page(b), "Fury is off screen")
            assertNull(page(c), "Protection is off screen")
            // … and hidden from accessibility: only the active page's talents can be found
            val tree1Talent = assertNotNull(firstTalentOf(session, 0))
            val tree2Talent = assertNotNull(firstTalentOf(session, 1))
            onNodeWithContentDescription(tree1Talent).assertExists()
            assertTrue(onAllNodesWithContentDescription(tree2Talent).fetchSemanticsNodes().isEmpty(), "Fury is not exposed")

            drag(dx = -10f, steps = 10, release = false)
            advance(16)
            for (section in session.sections) assertTrue(composed(section), "mid-drag: no page composed or disposed")
            onRoot().performTouchInput { up() }
            repeat(25) {
                advance(16)
                for (section in session.sections) assertTrue(composed(section), "mid-settle: no page composed or disposed")
            }
            rest()
            assertSettledOn(session, b, "switched")
            assertNotNull(page(b))
            assertNull(page(a))
            for (section in session.sections) assertTrue(composed(section), "every page stays composed after the motion")
            onNodeWithContentDescription(tree2Talent).assertExists()
            assertTrue(onAllNodesWithContentDescription(tree1Talent).fetchSemanticsNodes().isEmpty(), "Arms left the accessibility tree")
            assertTrue(session.reported.isEmpty(), "composing ahead never reaches the build or autosave")
        }
    }

    /** The talent names that exist only in the section at [ordinal] (unique across the tree). */
    private fun talentsOnlyOf(session: Session, ordinal: Int): List<String> {
        val warrior = productState("WARRIOR")
        val sections = (buildTalentTreeSections(warrior.engine.tree, warrior.model) as TalentTreeSectionsResult.Sectioned).sections
        val names = sections.first { it.index == session.sections[ordinal] }.model.nodes.mapNotNull { it.name }
        val elsewhere = sections.filter { it.index != session.sections[ordinal] }.flatMap { s -> s.model.nodes.mapNotNull { it.name } }.toSet()
        return names.filter { it !in elsewhere }.distinct()
    }

    /** How many of [names] are composed anywhere (unmerged tree: an off-screen page's talents too). */
    private fun ComposeUiTest.composedTalents(names: List<String>): Int =
        names.count { onAllNodesWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aPageIsComposedAheadAFewTalentsPerFrameAndATouchPausesIt() {
        onPhone(settle = false) { session ->
            val tree2 = talentsOnlyOf(session, 1)
            assertTrue(tree2.size >= 10, "Fury has talents of its own: $tree2")
            val counts = mutableListOf<Int>()
            repeat(3) { // batches 1, 2, 4 at a 16 ms frame: at most 7 talents, never the whole page
                mainClock.advanceTimeByFrame()
                counts += composedTalents(tree2)
            }
            assertTrue(counts.last() in 1 until tree2.size, "Fury is part-way composed after a few frames: $counts")
            assertTrue(
                counts.zipWithNext().all { (x, y) -> y - x in 0..TalentMotionMetrics.WARM_BATCH_MAX },
                "never more than ${TalentMotionMetrics.WARM_BATCH_MAX} talents in one frame: $counts",
            )
            // a finger on the pages pauses composing ahead
            onRoot().performTouchInput { down(centre) }
            val held = composedTalents(tree2)
            repeat(10) { mainClock.advanceTimeByFrame() }
            assertEquals(held, composedTalents(tree2), "no composing ahead while a finger holds the pages")
            onRoot().performTouchInput { up() }
            rest()
            assertEquals(tree2.size, composedTalents(tree2), "after the touch, Fury is completed")
            assertSettledOn(session, session.sections[0], "the touch alone switched nothing")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun aSwipeBeforeTheNextPageIsWarmGrowsItAFewTalentsPerFrame() {
        onPhone(settle = false) { session ->
            val b = session.sections[1]
            val tree2 = talentsOnlyOf(session, 1)
            repeat(2) { mainClock.advanceTimeByFrame() }
            val before = composedTalents(tree2)
            assertTrue(before < tree2.size, "Fury is not warm yet: $before of ${tree2.size}")
            // the swipe brings Fury on screen part-way composed: it keeps growing a few talents
            // per frame through the drag and the motion, never the rest of the page in one frame.
            // Each move is injected on its own (the test clock runs the frames up to its time),
            // so Fury is counted after every one of them.
            val counts = mutableListOf(before)
            var shownPartly = false
            fun sample() {
                val composed = composedTalents(tree2)
                if (page(b) != null && composed < tree2.size) shownPartly = true
                counts += composed
            }
            onRoot().performTouchInput { down(centre) }
            repeat(10) {
                onRoot().performTouchInput { moveBy(Offset(-10f, 0f), delayMillis = 16) }
                sample()
            }
            onRoot().performTouchInput { up() }
            repeat(40) {
                mainClock.advanceTimeByFrame()
                sample()
            }
            assertTrue(shownPartly, "Fury slid in before it was complete: $counts")
            assertTrue(
                counts.zipWithNext().all { (x, y) -> y - x in 0..TalentMotionMetrics.WARM_BATCH_MAX },
                "never more than ${TalentMotionMetrics.WARM_BATCH_MAX} talents in one frame: $counts",
            )
            rest()
            assertSettledOn(session, b, "the first swipe landed")
            assertEquals(tree2.size, composedTalents(tree2), "Fury is completed")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun thePageTheEditorOpenedOnStaysComposedThroughTheFirstSwitch() {
        onPhone { session ->
            val (a, b) = session.sections
            val tree1 = talentsOnlyOf(session, 0)
            assertTrue(tree1.size >= 10, "Arms has talents of its own: $tree1")
            assertEquals(tree1.size, composedTalents(tree1), "the opening page is composed whole")
            drag(dx = -10f, steps = 10)
            // every frame of the motion, its end and the rest after it: Arms is never
            // disposed (and so never composed again in one long frame)
            repeat(90) {
                mainClock.advanceTimeByFrame()
                assertEquals(tree1.size, composedTalents(tree1), "frame $it: Arms stays composed whole")
            }
            rest()
            assertSettledOn(session, b, "switched")
            assertNull(page(a))
            assertEquals(tree1.size, composedTalents(tree1), "Arms is kept off screen after the switch")
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
            onNodeWithText("Fury").performClick()
            rest()
            session.assertCameraAtFit(b)
            // back by swipe at FIT on Fury: Arms slides in with its zoom intact
            onRoot().performTouchInput {
                down(centre)
                repeat(10) { moveBy(Offset(12f, 0f)) }
            }
            advance(16)
            assertEquals(zoomed, cameraA.viewport, "the peeking Arms keeps its zoom and pan")
            onRoot().performTouchInput { up() }
            rest()
            assertSettledOn(session, a, "back on Arms")
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
            onNodeWithText("Fury").performClick()
            advance(48)
            assertSettledOn(session, b, "a tab tap is instant")
            onRoot().performTouchInput {
                down(centre)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
                up()
            }
            advance(48)
            assertSettledOn(session, c, "a swipe still switches, instantly at the release")

            onNodeWithText("Arms").performClick()
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
            onNodeWithText("Protection").performClick()
            rest()
            onNodeWithContentDescription(origin).performClick()
            rest()
            val originCamera = session.cameras.stateOf(c).viewport
            onNodeWithText("Show").performClick()
            advance(16 + 100)
            assertEquals(0f, assertNotNull(page(b), "Fury slides in").left, "from the LEFT (backwards in the tab order)")
            assertNotNull(page(c), "Protection slides out")
            rest()
            assertSettledOn(session, b, "Show switched to the prerequisite's section")
            onNodeWithContentDescription(prerequisite).assertIsSelected()
            assertEquals(originCamera, session.cameras.stateOf(c).viewport, "the origin camera is kept")

            onNodeWithText("Back to $origin · Protection").performClick()
            advance(16 + 100)
            assertEquals(w, assertNotNull(page(c), "the Back chip slides Protection back in").right)
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
