package io.github.alexeykravtsov95.talentforge.ui.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PR #19 motion: the pure section-pager rules (offsets are fractions of the page width, + = right). */
class TalentSectionPagingTest {

    private val order = listOf(0, 1, 2)

    @Test
    fun aDragCarriesTheActivePageAndBringsTheNeighbourFromTheOppositeEdge() {
        val left = talentSectionDragFrame(dragDp = -90f, widthDp = 360f, active = 0, order = order)
        assertEquals(1, left.companion, "finger left: Tree 2 comes in")
        assertEquals(-0.25f, left.activeOffset, 1e-5f)
        assertEquals(0.75f, left.companionOffset, 1e-5f, "from the right edge")
        val right = talentSectionDragFrame(dragDp = 90f, widthDp = 360f, active = 1, order = order)
        assertEquals(0, right.companion, "finger right: Tree 1 comes in")
        assertEquals(-0.75f, right.companionOffset, 1e-5f, "from the left edge")
        val rtl = talentSectionDragFrame(dragDp = 90f, widthDp = 360f, active = 0, order = order, rtl = true)
        assertEquals(1, rtl.companion, "right-to-left: the next tab is on the left")
        assertEquals(-0.75f, rtl.companionOffset, 1e-5f)
    }

    @Test
    fun theFirstAndLastSectionOnlyRubberBandAndARestingPageIsSettled() {
        val first = talentSectionDragFrame(dragDp = 200f, widthDp = 360f, active = 0, order = order)
        assertNull(first.companion, "no wrap-around: nothing comes in before Tree 1")
        assertEquals(TalentTreeGestureMetrics.SECTION_SWIPE_FEEDBACK_MAX_DP / 360f, first.activeOffset, 1e-5f)
        assertNull(talentSectionDragFrame(-200f, 360f, active = 2, order = order).companion)
        assertEquals(TalentSectionPageFrame.Settled, talentSectionDragFrame(0f, 360f, 0, order))
        assertEquals(TalentSectionPageFrame.Settled, talentSectionDragFrame(Float.NaN, 360f, 0, order))
        assertEquals(TalentSectionPageFrame.Settled, talentSectionDragFrame(-50f, 0f, 0, order))
        assertEquals(-1f, talentSectionDragFrame(-900f, 360f, 0, order).activeOffset, "never past one page")
    }

    @Test
    fun aSwitchLeavesTheOldPageTowardsTheGestureAndContinuesFromTheRelease() {
        // forward (Tree 1 → Tree 2): old page leaves left, new comes from the right
        assertEquals(-1, talentSectionTransitionSide(fromOrdinal = 0, toOrdinal = 1))
        assertEquals(1f, talentSectionTransitionStart(side = -1), "a tab tap: the new page starts one page right")
        // backward
        assertEquals(1, talentSectionTransitionSide(fromOrdinal = 2, toOrdinal = 1))
        assertEquals(-1f, talentSectionTransitionStart(side = 1))
        // right-to-left mirrors it
        assertEquals(1, talentSectionTransitionSide(fromOrdinal = 0, toOrdinal = 1, rtl = true))
        // a swipe released at -0.3: the old page stays at -0.3, the new one at 0.7 — no jump
        val release = talentSectionDragFrame(-108f, 360f, 0, order)
        val start = talentSectionTransitionStart(side = -1, releaseOffset = release.activeOffset)
        assertEquals(release.companionOffset, start, 1e-5f, "the new page is exactly where the finger left it")
        assertEquals(release.activeOffset, start + -1, 1e-5f, "the old page too")
        assertEquals(1f, talentSectionTransitionStart(-1, Float.NaN))
    }

    @Test
    fun theSettleIsEaseOutAndTakesAtMostTheTabTapDuration() {
        assertEquals(TalentMotionMetrics.PAGE_MS, talentSectionSettleMillis(1f, reducedMotion = false), "a tab tap: 260 ms")
        assertEquals(TalentMotionMetrics.PAGE_MS, talentSectionSettleMillis(-1f, reducedMotion = false))
        assertEquals(182, talentSectionSettleMillis(0.7f, reducedMotion = false), "a swipe released part-way: in proportion")
        assertEquals(TalentMotionMetrics.PAGE_MIN_MS, talentSectionSettleMillis(0.1f, reducedMotion = false))
        assertEquals(0, talentSectionSettleMillis(0f, reducedMotion = false))
        assertEquals(0, talentSectionSettleMillis(1f, reducedMotion = true), "reduced motion: instant")
        // ease-out: most of the way is covered in the first half
        assertTrue(TalentMotionMetrics.EASING.transform(0.5f) > 0.75f)
    }

    @Test
    fun theIndicatorFollowsTheVisibleShareOfThePagesContinuously() {
        assertEquals(0f, talentSectionIndicatorPosition(0, null, 0f))
        // dragging Tree 1 a quarter left: a quarter towards Tree 2
        assertEquals(0.25f, talentSectionIndicatorPosition(0, 1, -0.25f), 1e-5f)
        // the switch begins: Tree 2 is active at +0.75, Tree 1 still mostly shown — same position
        assertEquals(0.25f, talentSectionIndicatorPosition(1, 0, 0.75f), 1e-5f)
        // a tap Tree 1 → Tree 3 travels across Tree 2
        assertEquals(0f, talentSectionIndicatorPosition(2, 0, 1f), 1e-5f)
        assertEquals(1f, talentSectionIndicatorPosition(2, 0, 0.5f), 1e-5f)
        assertEquals(2f, talentSectionIndicatorPosition(2, 0, 0f), 1e-5f)
    }

    @Test
    fun onlyOneTransitionAtATimeAndATouchFinishesIt() {
        val pager = TalentSectionPager()
        pager.switchTo(from = 0, to = 1, order = order)
        val first = assertNotNull(pager.transition)
        assertEquals(1, first.active)
        assertEquals(0, first.companion)
        assertEquals(1f, first.activeOffset)
        assertEquals(0f, first.companionOffset, "the old page starts fully shown")
        assertTrue(first.running)

        pager.switchTo(from = 1, to = 2, order = order) // a tab tap during the animation
        val second = assertNotNull(pager.transition)
        assertFalse(first.running, "the previous transition ended — nothing is queued")
        assertEquals(2, second.active)
        assertEquals(1, second.companion)

        pager.finishNow() // a touch on the moving pages
        assertFalse(second.running)
        assertEquals(0f, second.activeOffset)

        pager.switchTo(from = 2, to = 2, order = order)
        assertNull(pager.transition, "no transition to the same section")
    }

    @Test
    fun aCancelledSwipeEasesBackFromWhereItWasReleased() {
        val pager = TalentSectionPager()
        val released = talentSectionDragFrame(-40f, 360f, 0, order)
        pager.settleBack(active = 0, frame = released)
        val back = assertNotNull(pager.transition)
        assertEquals(0, back.active)
        assertEquals(1, back.companion, "the peeking Tree 2 goes back out")
        assertEquals(released.activeOffset, back.activeOffset)
        assertEquals(released.companionOffset, back.companionOffset, 1e-5f)
        pager.settleBack(active = 0, frame = TalentSectionPageFrame.Settled)
        assertNull(pager.transition, "nothing to ease back")
    }
}
