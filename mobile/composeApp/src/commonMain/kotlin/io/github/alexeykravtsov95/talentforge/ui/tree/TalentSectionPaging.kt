package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * PR #19 motion: the section pages (Tree 1 ⇄ Tree 2 ⇄ Tree 3) move the way
 * Compose's HorizontalPager does (this replaces the 1A v2 S4 tab-change entry
 * at the brief's request).
 *
 * ONE continuous quantity drives everything: the pager POSITION, in tab
 * ordinals (0 = Tree 1 fully shown, 0.5 = halfway to Tree 2). Section i is
 * drawn at `(i − position) · width` (mirrored right-to-left), the tab
 * indicator sits at `position`. A finger moves it 1:1 from wherever it is; a
 * release continues it from there with the finger's velocity on
 * HorizontalPager's own snap spring; a touch stops it where it is drawn (never
 * a jump to the end); a tab tap moves it from where it is, passing the tabs in
 * between like a pager does. There is no second representation to keep in
 * sync, so nothing can jump between them.
 *
 * The canvas's single gesture handler still decides the swipe (round 2
 * rules); it only reports the drag and the release velocity.
 */
object TalentMotionMetrics {

    /**
     * Compose's own HorizontalPager snap spring (`PagerDefaults`):
     * `StiffnessMediumLow`, no bounce. A whole page from rest is 95 % done in
     * ≈ 240 ms and 99 % in ≈ 330 ms (the brief's 220–340 ms); a swipe released
     * part-way, or with speed, takes proportionally less.
     */
    const val PAGE_SPRING_STIFFNESS: Float = Spring.StiffnessMediumLow

    /** No bounce: a cancelled swipe returns without wobbling. */
    const val PAGE_SPRING_DAMPING: Float = Spring.DampingRatioNoBouncy

    /** The spring rests within 1/2000 of a page (sub-pixel), so its end never shows as a snap. */
    const val PAGE_REST_THRESHOLD: Float = 0.0005f

    /** A flick faster than this many pages per second is carried at this speed. */
    const val PAGE_MAX_VELOCITY: Float = 12f

    /**
     * A touch that lands while the pages are more than this far (pages) from
     * rest has CAUGHT a motion: it stops the pages, and its tap never selects a
     * talent (as on a native pager, catching a fling does not click).
     */
    const val PAGE_CAUGHT_THRESHOLD: Float = 0.01f

    /**
     * A touch this close (pages) to the tab a motion is heading to counts as
     * arrived there: a quick follow-up swipe goes on from that tab.
     */
    const val PAGE_ARRIVED_THRESHOLD: Float = 0.25f

    /**
     * Round 7: a page composed ahead grows by at most this many talent nodes per frame
     * (the bundled sections hold 16-18), so composing ahead never holds the main thread for a
     * whole page while the editor opens or between gestures.
     */
    const val WARM_BATCH_MAX: Int = 8

    /** A warm-up frame interval above this (two 60 Hz frames) halves the next batch … */
    const val WARM_SLOW_FRAME_NANOS: Long = 33_000_000L

    /** … and one below this doubles it (up to [WARM_BATCH_MAX]); in between it stays. */
    const val WARM_FAST_FRAME_NANOS: Long = 20_000_000L

    /** Brief: the inspector sheet opens and closes in ≈ 220–260 ms. */
    const val SHEET_MS: Int = 240

    /**
     * The sheet's curve: Material's standard ease-in-out. It starts from rest
     * (no jump on the first frame — the previous `cubic(.2, .8, .2, 1)` moved a
     * quarter of the way in one frame) and decelerates into place.
     */
    val SHEET_EASING: Easing = FastOutSlowInEasing
}

/** Test tag of a section page's moving layer (its bounds follow the page). */
fun talentSectionPageTag(section: Int): String = "talent-section-page-$section"

/** Test tag of the sheet while it slides closed (a picture: no other semantics, no input). */
const val TALENT_INSPECTOR_SHEET_CLOSING_TAG = "talent-inspector-sheet-closing"

/**
 * Where the section at tab [ordinal] is drawn for the pager [position]: in
 * pages, `+` = to the right. Left-to-right the tabs read Tree 1 | Tree 2 |
 * Tree 3, so Tree 2 waits one page to the right of Tree 1; right-to-left
 * mirrors it.
 */
fun talentSectionPageX(ordinal: Int, position: Float, rtl: Boolean = false): Float {
    val x = ordinal - position
    return if (rtl) -x else x
}

/**
 * The position while a finger holds the pages: where it grabbed them
 * ([grabbedAt]) moved 1:1 by the drag ([dragDp], the canvas's published
 * section drag; finger left = towards the next tab left-to-right). Past the
 * first / last tab only the bounded rubber band moves.
 */
fun talentSectionHeldPosition(
    grabbedAt: Float,
    dragDp: Float,
    widthDp: Float,
    count: Int,
    rtl: Boolean = false,
): Float {
    if (count <= 0 || !grabbedAt.isFinite()) return 0f
    if (!dragDp.isFinite() || !widthDp.isFinite() || widthDp <= 0f) return grabbedAt
    val last = (count - 1).toFloat()
    val raw = grabbedAt - (if (rtl) -dragDp else dragDp) / widthDp
    return when {
        raw < 0f -> -talentSectionSwipeFeedbackDp(-raw * widthDp) / widthDp
        raw > last -> last + talentSectionSwipeFeedbackDp((raw - last) * widthDp) / widthDp
        else -> raw
    }
}

/** The finger's horizontal velocity ([velocityDpPerSecond]) as the pager position's (tabs per second). */
fun talentSectionPositionVelocity(velocityDpPerSecond: Float, widthDp: Float, rtl: Boolean = false): Float {
    if (!velocityDpPerSecond.isFinite() || !widthDp.isFinite() || widthDp <= 0f) return 0f
    val v = (if (rtl) velocityDpPerSecond else -velocityDpPerSecond) / widthDp
    return v.coerceIn(-TalentMotionMetrics.PAGE_MAX_VELOCITY, TalentMotionMetrics.PAGE_MAX_VELOCITY)
}

/**
 * Where the pages go when the finger lets go — HorizontalPager's snap rule: a
 * swipe the canvas confirmed ([direction] `+1` next / `-1` previous) goes to
 * the next tab boundary in its direction from the position the touch grabbed
 * ([grabbedAt]) — from rest that is the neighbour; against a running motion it
 * undoes it; along it, it continues. A touch that grabbed the pages within
 * [TalentMotionMetrics.PAGE_ARRIVED_THRESHOLD] of the tab the motion was
 * heading to ([activeOrdinal]) counts as there already, so quick repeated
 * swipes advance one tab each. An unconfirmed touch rests on [activeOrdinal]:
 * a cancelled swipe returns, an interrupted transition completes. Never past
 * the first / last tab (no wrap-around).
 */
fun talentSectionReleaseTarget(grabbedAt: Float, activeOrdinal: Int, direction: Int, count: Int): Int {
    if (count <= 0) return 0
    val p = if (grabbedAt.isFinite()) grabbedAt else activeOrdinal.toFloat()
    val arrived = abs(p - activeOrdinal) <= TalentMotionMetrics.PAGE_ARRIVED_THRESHOLD
    val target = when {
        direction > 0 -> (if (arrived) activeOrdinal else floor(p).toInt()) + 1
        direction < 0 -> (if (arrived) activeOrdinal else ceil(p).toInt()) - 1
        else -> activeOrdinal
    }
    return target.coerceIn(0, count - 1)
}

/**
 * The tab ordinals to compose for [position]: the pages on screen now, the
 * active (interactive) page, and the pages already composed ahead ([warm]).
 *
 * Composing a page is expensive (measured on a TECNO Spark 40: 240–360 ms of
 * main-thread work for one section — composition, first measure, first draw),
 * so a page must not be composed while the pages move. The screen composes the
 * other pages ahead, at rest ([talentSectionNextWarmPage]), and keeps them:
 * a motion then only moves pages that already exist, and a page that leaves
 * the screen is never disposed mid-motion. Without [warm] this is
 * HorizontalPager's default (`beyondViewportPageCount = 0`): the visible pages
 * plus the active one.
 */
fun talentSectionComposedPages(
    position: Float,
    activeOrdinal: Int,
    count: Int,
    warm: Collection<Int> = emptyList(),
): List<Int> {
    if (count <= 0) return emptyList()
    val p = if (position.isFinite()) position else activeOrdinal.toFloat()
    return (listOf(floor(p).toInt(), ceil(p).toInt(), activeOrdinal) + warm)
        .filter { it in 0 until count }
        .distinct()
        .sorted()
}

/**
 * Round 7: how many nodes the next warm-up frame composes, from the previous batch and the
 * frame interval that batch cost ([frameIntervalNanos], `<= 0` when unknown): halved after a
 * slow frame (never below 1), doubled after a fast one (never above
 * [TalentMotionMetrics.WARM_BATCH_MAX]). A slow phone composes ahead in small steps and
 * stays responsive; a fast one finishes in a few frames.
 */
fun talentSectionWarmBatch(previous: Int, frameIntervalNanos: Long): Int {
    val batch = previous.coerceIn(1, TalentMotionMetrics.WARM_BATCH_MAX)
    return when {
        frameIntervalNanos <= 0L -> batch
        frameIntervalNanos > TalentMotionMetrics.WARM_SLOW_FRAME_NANOS -> (batch / 2).coerceAtLeast(1)
        frameIntervalNanos < TalentMotionMetrics.WARM_FAST_FRAME_NANOS -> (batch * 2).coerceAtMost(TalentMotionMetrics.WARM_BATCH_MAX)
        else -> batch
    }
}

/**
 * The next page to compose ahead (see [talentSectionComposedPages]), or `null`
 * when every page is composed: the nearest to the active page first, the next
 * tab before the previous one at the same distance. The active page itself is
 * always composed and never returned.
 */
fun talentSectionNextWarmPage(activeOrdinal: Int, count: Int, warm: Set<Int>): Int? {
    if (count <= 0 || activeOrdinal !in 0 until count) return null
    for (distance in 1 until count) {
        for (ordinal in intArrayOf(activeOrdinal + distance, activeOrdinal - distance)) {
            if (ordinal in 0 until count && ordinal !in warm) return ordinal
        }
    }
    return null
}

/**
 * The section pages' motion: ONE position, ONE animation at a time. A new
 * motion cancels the running one in the same call, synchronously, and starts
 * from the position currently drawn — so two animations never fight, and
 * fast repeated swipes or taps never jump or queue.
 */
@Stable
class TalentSectionPager(private val scope: CoroutineScope) {

    /** The pager position (tab ordinals) while no finger holds the pages. */
    var position: Float by mutableFloatStateOf(0f)
        private set

    /** The tab the motion rests on (the logical section once a switch began). */
    var target: Int by mutableIntStateOf(0)
        private set

    /** The position a holding finger grabbed the pages at; `null` when no finger holds them. */
    var heldFrom: Float? by mutableStateOf(null)
        private set

    /** Whether the spring is running (state: changes only when it starts and rests). */
    var moving: Boolean by mutableStateOf(false)
        private set

    /** The motion's current velocity (tabs per second), carried into a retarget. */
    var velocity: Float = 0f
        private set

    /** The current touch stopped a visible motion: its tap must not select a talent. */
    var caughtMotion: Boolean = false
        private set

    private var job: Job? = null

    /** The touch is over: the next tap is an ordinary tap again. */
    fun endTouch() {
        caughtMotion = false
    }

    /** At rest on another tab than [ordinal] (a change made without motion, the first frame): be there. */
    fun sync(ordinal: Int) {
        if (target != ordinal && !moving && heldFrom == null) {
            target = ordinal
            position = ordinal.toFloat()
        }
    }

    /** A touch lands on the pages: they stop exactly where they are drawn and follow the finger from there. */
    fun grab() {
        caughtMotion = abs(position - target) > TalentMotionMetrics.PAGE_CAUGHT_THRESHOLD
        job?.cancel()
        job = null
        moving = false
        velocity = 0f
        heldFrom = position
    }

    /**
     * Moves the pages from [from] (where they are drawn now) to rest on tab
     * [to] with [initialVelocity] (tabs/s). Instant with [reducedMotion].
     */
    fun settle(from: Float, to: Int, initialVelocity: Float, reducedMotion: Boolean, count: Int) {
        job?.cancel()
        job = null
        heldFrom = null
        val last = (count - 1).coerceAtLeast(0)
        val destination = to.coerceIn(0, last)
        target = destination
        val start = if (from.isFinite()) from else destination.toFloat()
        position = start
        if (reducedMotion || start == destination.toFloat()) {
            rest()
            return
        }
        val speed = if (initialVelocity.isFinite()) {
            initialVelocity.coerceIn(-TalentMotionMetrics.PAGE_MAX_VELOCITY, TalentMotionMetrics.PAGE_MAX_VELOCITY)
        } else {
            0f
        }
        // the pages never pass their rest: a fast flick stops at it instead of revealing the page beyond
        val end = destination.toFloat()
        val low = if (start < end) minOf(start, 0f) else end
        val high = if (start < end) end else maxOf(start, last.toFloat())
        moving = true
        velocity = speed
        job = scope.launch {
            val animation = TargetBasedAnimation(
                animationSpec = spring(
                    dampingRatio = TalentMotionMetrics.PAGE_SPRING_DAMPING,
                    stiffness = TalentMotionMetrics.PAGE_SPRING_STIFFNESS,
                    visibilityThreshold = TalentMotionMetrics.PAGE_REST_THRESHOLD,
                ),
                typeConverter = Float.VectorConverter,
                initialValue = start,
                targetValue = end,
                initialVelocity = speed,
            )
            // the system animator scale (Android developer options) stretches or skips the motion
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            if (scale <= 0f) {
                rest()
                return@launch
            }
            var startNanos = Long.MIN_VALUE
            var finished = false
            while (!finished) {
                withFrameNanos { frame ->
                    // the first frame already moves by one frame: the last finger frame is never repeated
                    if (startNanos == Long.MIN_VALUE) startNanos = frame - FRAME_LEAD_NANOS
                    val playTime = ((frame - startNanos) / scale).toLong()
                    position = animation.getValueFromNanos(playTime).coerceIn(low, high)
                    velocity = animation.getVelocityVectorFromNanos(playTime).value
                    finished = animation.isFinishedFromNanos(playTime)
                }
            }
            rest()
        }
    }

    private fun rest() {
        position = target.toFloat()
        velocity = 0f
        moving = false
    }

    private companion object {
        /** One 60 Hz frame: how far the motion already is on its first drawn frame. */
        const val FRAME_LEAD_NANOS: Long = 16_666_667L
    }
}
