package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PR #19 motion polish: the section pages (Tree 1 ⇄ Tree 2 ⇄ Tree 3) move like
 * a native pager. This replaces the 1A v2 S4 tab-change entry (180 ms fade +
 * 24 dp shift of the new section only) at the brief's request: both sections
 * now take part, and the tab indicator travels with them.
 *
 * Pure rules + two small state holders; the screen draws the pages and the
 * canvas's single gesture handler feeds the drag (no second detector).
 * Offsets are fractions of the page width, `+` = to the right.
 */
object TalentMotionMetrics {

    /** Brief: a tab tap's page transition ≈ 260 ms ease-out (also the longest swipe settle). */
    const val PAGE_MS: Int = 260

    /** A swipe released close to its target settles in proportion to the rest, never faster than this. */
    const val PAGE_MIN_MS: Int = 120

    /** Brief: the inspector sheet opens and closes in ≈ 220–260 ms. */
    const val SHEET_MS: Int = 240

    /** Ease-out for pages, indicator and sheet: the 1A v2 S4 curve `cubic(.2, .8, .2, 1)`. */
    val EASING: Easing = TalentTreeSectionTabsMetrics.INDICATOR_EASING
}

/** Test tag of a section page's moving layer (its bounds follow the page). */
fun talentSectionPageTag(section: Int): String = "talent-section-page-$section"

/** Test tag of the sheet while it slides closed (a picture: no other semantics, no input). */
const val TALENT_INSPECTOR_SHEET_CLOSING_TAG = "talent-inspector-sheet-closing"

/** One frame of the pager: the page [companion] (if any) next to the active one. */
data class TalentSectionPageFrame(
    val companion: Int?,
    val activeOffset: Float,
    val companionOffset: Float,
) {
    companion object {
        val Settled = TalentSectionPageFrame(companion = null, activeOffset = 0f, companionOffset = 0f)
    }
}

/**
 * The pages while a finger drags the active section by [dragDp] (the canvas's
 * `sectionOverscrollDp`: the finger's travel at FIT, the overscroll past the
 * edge when zoomed). The active page follows the finger and the neighbour in
 * the swipe direction ([sectionSwipeDirectionOf]) comes in from the opposite
 * edge, so the finger always "carries" both pages. At the first / last
 * section nothing comes in: only the bounded rubber band.
 */
fun talentSectionDragFrame(
    dragDp: Float,
    widthDp: Float,
    active: Int,
    order: List<Int>,
    rtl: Boolean = false,
): TalentSectionPageFrame {
    if (!dragDp.isFinite() || dragDp == 0f || !widthDp.isFinite() || widthDp <= 0f) return TalentSectionPageFrame.Settled
    val neighbour = neighbourSection(order, active, sectionSwipeDirectionOf(dragDp, rtl))
        ?: return TalentSectionPageFrame(null, talentSectionSwipeFeedbackDp(dragDp) / widthDp, 0f)
    val offset = (dragDp / widthDp).coerceIn(-1f, 1f)
    // the neighbour waits beyond the edge the finger moves away from
    return TalentSectionPageFrame(neighbour, offset, offset + if (dragDp < 0f) 1f else -1f)
}

/**
 * Where the page that was active ends up relative to the new one: `-1` left,
 * `+1` right. Moving forward in the tab order (Tree 1 → Tree 2) the old page
 * leaves to the left and the new one arrives from the right — the same way a
 * swipe left carries them; a right-to-left layout mirrors it.
 */
fun talentSectionTransitionSide(fromOrdinal: Int, toOrdinal: Int, rtl: Boolean = false): Int {
    val ltr = if (toOrdinal > fromOrdinal) -1 else 1
    return if (rtl) -ltr else ltr
}

/**
 * The new active page's starting offset: the old page stays exactly where it
 * was — where the finger released it ([releaseOffset]), fully shown for a tab
 * tap (0) — so the transition continues the gesture without a jump.
 */
fun talentSectionTransitionStart(side: Int, releaseOffset: Float = 0f): Float {
    val release = if (releaseOffset.isFinite()) releaseOffset else 0f
    return (release - side).coerceIn(-1f, 1f)
}

/**
 * Settle duration for [distance] (fraction of a page still to travel):
 * [TalentMotionMetrics.PAGE_MS] for a whole page (a tab tap), proportionally
 * less for a swipe released part-way, never below
 * [TalentMotionMetrics.PAGE_MIN_MS]; 0 with reduced motion.
 */
fun talentSectionSettleMillis(distance: Float, reducedMotion: Boolean): Int {
    if (reducedMotion || !distance.isFinite()) return 0
    val rest = abs(distance).coerceAtMost(1f)
    if (rest == 0f) return 0
    return (TalentMotionMetrics.PAGE_MS * rest).roundToInt()
        .coerceIn(TalentMotionMetrics.PAGE_MIN_MS, TalentMotionMetrics.PAGE_MS)
}

/**
 * The tab indicator's position (a fractional tab ordinal): it moves with the
 * visible share of the pages, so it never sits on Tree 2 while Tree 1 is what
 * the screen shows.
 */
fun talentSectionIndicatorPosition(activeOrdinal: Int, companionOrdinal: Int?, activeOffset: Float): Float {
    if (companionOrdinal == null || companionOrdinal < 0 || !activeOffset.isFinite()) return activeOrdinal.toFloat()
    return activeOrdinal + abs(activeOffset).coerceAtMost(1f) * (companionOrdinal - activeOrdinal)
}

/**
 * One running page transition towards [active] (already the logical section —
 * selection, cameras and tabs switched when it began). [companion] is the
 * page leaving (a switch) or the neighbour that peeked (a cancelled swipe),
 * resting at `activeOffset + companionSide`.
 */
@Stable
class TalentSectionTransition internal constructor(
    val active: Int,
    val companion: Int?,
    val companionSide: Int,
    start: Float,
    val id: Int,
) {
    var activeOffset: Float by mutableFloatStateOf(start)
        private set

    var running: Boolean by mutableStateOf(start != 0f)
        private set

    val companionOffset: Float get() = activeOffset + companionSide

    /** Eases the pages to rest, then ends; instant with [reducedMotion]. */
    suspend fun run(reducedMotion: Boolean) {
        if (!running) return
        val millis = talentSectionSettleMillis(activeOffset, reducedMotion)
        if (millis > 0) {
            animate(
                initialValue = activeOffset,
                targetValue = 0f,
                animationSpec = tween(durationMillis = millis, easing = TalentMotionMetrics.EASING),
            ) { value, _ -> activeOffset = value }
        }
        finish()
    }

    /** Jumps to the end (a new touch catches the pages, or a new transition replaces this one). */
    fun finish() {
        activeOffset = 0f
        running = false
    }
}

/**
 * The editor's ONE page transition at a time: a new switch replaces the
 * running one (it starts from the logical state, never queued behind it), and
 * a touch on the pages finishes it at once — fast repeated swipes and tab taps
 * are deterministic.
 */
@Stable
class TalentSectionPager {
    var transition: TalentSectionTransition? by mutableStateOf(null)
        private set

    private var nextId = 0

    /** Begins the switch [from] → [to]; [releaseOffset] is where a swipe released the old page. */
    fun switchTo(from: Int, to: Int, order: List<Int>, rtl: Boolean = false, releaseOffset: Float = 0f) {
        transition?.finish()
        val fromOrdinal = order.indexOf(from)
        val toOrdinal = order.indexOf(to)
        if (from == to || fromOrdinal < 0 || toOrdinal < 0) {
            transition = null
            return
        }
        val side = talentSectionTransitionSide(fromOrdinal, toOrdinal, rtl)
        transition = TalentSectionTransition(to, from, side, talentSectionTransitionStart(side, releaseOffset), ++nextId)
    }

    /** A swipe that did not switch: the pages ease back from [frame] to [active] alone. */
    fun settleBack(active: Int, frame: TalentSectionPageFrame) {
        transition?.finish()
        if (frame.activeOffset == 0f || !frame.activeOffset.isFinite()) {
            transition = null
            return
        }
        val side = when {
            frame.companion == null -> 0
            frame.companionOffset > frame.activeOffset -> 1
            else -> -1
        }
        transition = TalentSectionTransition(active, frame.companion, side, frame.activeOffset, ++nextId)
    }

    fun finishNow() {
        transition?.finish()
    }
}
