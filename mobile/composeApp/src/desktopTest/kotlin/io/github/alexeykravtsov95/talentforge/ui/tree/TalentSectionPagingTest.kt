package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.FloatSpringSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PR #19 motion: the pure pager rules (positions in tab ordinals, x in pages, + = right). */
class TalentSectionPagingTest {

    private val width = 360f

    @Test
    fun everySectionIsDrawnAtItsOrdinalMinusThePositionMirroredRightToLeft() {
        assertEquals(0f, talentSectionPageX(ordinal = 0, position = 0f))
        assertEquals(1f, talentSectionPageX(ordinal = 1, position = 0f), "Tree 2 waits one page right")
        assertEquals(-0.25f, talentSectionPageX(ordinal = 0, position = 0.25f))
        assertEquals(0.75f, talentSectionPageX(ordinal = 1, position = 0.25f), "side by side, no gap")
        assertEquals(-1f, talentSectionPageX(ordinal = 1, position = 0f, rtl = true), "right-to-left: on the left")
    }

    @Test
    fun aHeldPositionFollowsTheFinger1to1FromWhereItWasGrabbed() {
        assertEquals(0.25f, talentSectionHeldPosition(grabbedAt = 0f, dragDp = -90f, widthDp = width, count = 3), 1e-5f)
        assertEquals(0.4f, talentSectionHeldPosition(0.65f, dragDp = 90f, widthDp = width, count = 3), 1e-5f, "mid-motion")
        assertEquals(0.25f, talentSectionHeldPosition(0f, dragDp = 90f, widthDp = width, count = 3, rtl = true), 1e-5f)
        // past the first / last tab: only the bounded rubber band
        val beyond = talentSectionHeldPosition(0f, dragDp = 300f, widthDp = width, count = 3)
        assertEquals(-TalentTreeGestureMetrics.SECTION_SWIPE_FEEDBACK_MAX_DP / width, beyond, 1e-5f)
        assertTrue(talentSectionHeldPosition(2f, dragDp = -300f, widthDp = width, count = 3) <= 2f + 32f / width)
        assertEquals(1f, talentSectionHeldPosition(1f, Float.NaN, width, 3))
    }

    @Test
    fun theFingerVelocityBecomesThePositionVelocityWithTheSameSign() {
        assertEquals(2f, talentSectionPositionVelocity(-720f, width), 1e-5f, "finger left: position grows")
        assertEquals(-2f, talentSectionPositionVelocity(720f, width), 1e-5f)
        assertEquals(-2f, talentSectionPositionVelocity(-720f, width, rtl = true), 1e-5f)
        assertEquals(TalentMotionMetrics.PAGE_MAX_VELOCITY, talentSectionPositionVelocity(-1e6f, width))
        assertEquals(0f, talentSectionPositionVelocity(Float.NaN, width))
    }

    @Test
    fun aReleaseGoesToTheNextBoundaryInTheSwipeDirection() {
        // from rest: one tab, no wrap-around
        assertEquals(1, talentSectionReleaseTarget(grabbedAt = 0f, activeOrdinal = 0, direction = 1, count = 3))
        assertEquals(0, talentSectionReleaseTarget(0f, 0, direction = -1, count = 3), "nothing before Tree 1")
        assertEquals(2, talentSectionReleaseTarget(2f, 2, direction = 1, count = 3), "nothing after Tree 3")
        assertEquals(1, talentSectionReleaseTarget(1f, 1, direction = 0, count = 3), "not confirmed: rest")
        // against a running motion (Tree 2 → Tree 1, grabbed at 0.6): a forward swipe undoes it
        assertEquals(1, talentSectionReleaseTarget(0.6f, activeOrdinal = 0, direction = 1, count = 3))
        // along it: continues; unconfirmed: completes
        assertEquals(0, talentSectionReleaseTarget(0.6f, activeOrdinal = 0, direction = -1, count = 3))
        assertEquals(0, talentSectionReleaseTarget(0.6f, activeOrdinal = 0, direction = 0, count = 3))
        // almost arrived (within a quarter page): a quick follow-up swipe goes on from there
        assertEquals(2, talentSectionReleaseTarget(0.8f, activeOrdinal = 1, direction = 1, count = 3))
        assertEquals(1, talentSectionReleaseTarget(0.6f, activeOrdinal = 1, direction = 1, count = 3), "mid-way: to Tree 2")
    }

    @Test
    fun onlyThePagesOnScreenAndTheActiveOneAreComposed() {
        assertEquals(listOf(0), talentSectionComposedPages(position = 0f, activeOrdinal = 0, count = 3), "at rest: one page")
        assertEquals(listOf(0, 1), talentSectionComposedPages(position = 0.3f, activeOrdinal = 0, count = 3))
        assertEquals(listOf(0, 1, 2), talentSectionComposedPages(position = 0.4f, activeOrdinal = 2, count = 3), "a tap across")
        assertEquals(listOf(0), talentSectionComposedPages(position = -0.05f, activeOrdinal = 0, count = 3), "rubber band")
        assertEquals(emptyList(), talentSectionComposedPages(0f, 0, 0))
    }

    @Test
    fun pagesComposedAheadStayComposedWhereverThePagesAre() {
        assertEquals(listOf(0, 1, 2), talentSectionComposedPages(position = 0f, activeOrdinal = 0, count = 3, warm = listOf(1, 2)), "at rest")
        assertEquals(
            listOf(0, 1, 2),
            talentSectionComposedPages(position = 0.5f, activeOrdinal = 1, count = 3, warm = listOf(0, 2)),
            "mid-motion: nothing is added or dropped",
        )
        assertEquals(listOf(0, 1), talentSectionComposedPages(position = 0f, activeOrdinal = 0, count = 3, warm = listOf(1, 7, -1)), "out of range ignored")
    }

    @Test
    fun theWarmUpBatchShrinksAfterASlowFrameAndGrowsAfterAFastOne() {
        val max = TalentMotionMetrics.WARM_BATCH_MAX
        assertEquals(1, talentSectionWarmBatch(previous = 1, frameIntervalNanos = 0L), "unknown interval: keep")
        assertEquals(2, talentSectionWarmBatch(previous = 1, frameIntervalNanos = 16_666_667L), "a 60 Hz frame: double")
        assertEquals(max, talentSectionWarmBatch(previous = max, frameIntervalNanos = 8_333_333L), "never above the cap")
        assertEquals(2, talentSectionWarmBatch(previous = 4, frameIntervalNanos = 50_000_000L), "a slow frame: halve")
        assertEquals(1, talentSectionWarmBatch(previous = 1, frameIntervalNanos = 300_000_000L), "never below one")
        assertEquals(4, talentSectionWarmBatch(previous = 4, frameIntervalNanos = 25_000_000L), "in between: keep")
        assertEquals(max, talentSectionWarmBatch(previous = 99, frameIntervalNanos = 25_000_000L), "out of range is clamped")
    }

    @Test
    fun theNearestPageIsComposedAheadFirstAndTheNextTabBeforeThePrevious() {
        assertEquals(1, talentSectionNextWarmPage(activeOrdinal = 0, count = 3, warm = emptySet()))
        assertEquals(2, talentSectionNextWarmPage(activeOrdinal = 0, count = 3, warm = setOf(1)))
        assertEquals(null, talentSectionNextWarmPage(activeOrdinal = 0, count = 3, warm = setOf(1, 2)), "all composed")
        assertEquals(2, talentSectionNextWarmPage(activeOrdinal = 1, count = 3, warm = emptySet()), "the next tab first at the same distance")
        assertEquals(0, talentSectionNextWarmPage(activeOrdinal = 1, count = 3, warm = setOf(2)))
        assertEquals(1, talentSectionNextWarmPage(activeOrdinal = 2, count = 3, warm = emptySet()))
        assertEquals(null, talentSectionNextWarmPage(activeOrdinal = 0, count = 1, warm = emptySet()), "one page: nothing ahead")
        assertEquals(null, talentSectionNextWarmPage(activeOrdinal = -1, count = 3, warm = emptySet()), "no active page")
    }

    @Test
    fun theSpringIsHorizontalPagersAndAWholePageIsVisuallyDoneIn220To340ms() {
        val spring = FloatSpringSpec(
            dampingRatio = TalentMotionMetrics.PAGE_SPRING_DAMPING,
            stiffness = TalentMotionMetrics.PAGE_SPRING_STIFFNESS,
            visibilityThreshold = TalentMotionMetrics.PAGE_REST_THRESHOLD,
        )
        fun remaining(ms: Long) = spring.getValueFromNanos(ms * 1_000_000, 1f, 0f, 0f)
        assertEquals(400f, TalentMotionMetrics.PAGE_SPRING_STIFFNESS, "Spring.StiffnessMediumLow (PagerDefaults)")
        assertEquals(1f, TalentMotionMetrics.PAGE_SPRING_DAMPING, "no bounce")
        assertTrue(remaining(160) > 0.1f, "not instant")
        assertTrue(remaining(240) < 0.05f, "95 % by 240 ms: ${remaining(240)}")
        assertTrue(remaining(340) < 0.01f, "99 % by 340 ms: ${remaining(340)}")
        // the release velocity is kept: the spring starts exactly at the finger's speed …
        assertEquals(-3f, spring.getVelocityFromNanos(0, 0.4f, 0f, -3f), 1e-4f)
        // … and a release with speed arrives sooner than one without
        assertTrue(spring.getValueFromNanos(120_000_000, 0.6f, 0f, -4f) < spring.getValueFromNanos(120_000_000, 0.6f, 0f, 0f))
    }
}
