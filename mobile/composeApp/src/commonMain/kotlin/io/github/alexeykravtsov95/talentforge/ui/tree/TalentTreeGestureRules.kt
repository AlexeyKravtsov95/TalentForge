package io.github.alexeykravtsov95.talentforge.ui.tree

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Pure gesture-arbitration rules of the PR #19 Android fixes, shared by the
 * canvas, the inspector and the editor's Back handling. No Compose types: each
 * rule is unit-tested on its own, and the composables only feed it numbers.
 *
 * Every value here is implementation-safe: 1A v2 and B3 freeze no thresholds
 * for these gestures (B3 S6 only says "Drag > 8 dp is not a tap").
 */
object TalentTreeGestureMetrics {

    /**
     * Edge model (zoomed / wider section, see [TalentSectionSwipeModel]): the
     * horizontal overscroll (dp the finger travelled past the content's
     * horizontal bound) a release must still have to switch to the
     * neighbouring section. A short pan that merely reaches the edge never
     * switches. (PR #19 v1 asked for 64 dp; lowered after the device check.)
     */
    const val SECTION_SWIPE_OVERSCROLL_DP: Float = 40f

    /** FIT model: a release after at least this much horizontal travel switches … */
    const val FIT_SWIPE_DISTANCE_DP: Float = 56f

    /** … and so does a quick flick of at least [FIT_SWIPE_FLING_MIN_DP] … */
    const val FIT_SWIPE_FLING_MIN_DP: Float = 32f

    /** … whose horizontal release velocity (dp/s, same direction) reaches this. */
    const val FIT_SWIPE_FLING_VELOCITY_DP_S: Float = 600f

    /** FIT model: the camera scale counts as the fit scale within this (the FIT button's own tolerance). */
    const val FIT_SCALE_EPSILON: Float = TalentTreeCanvasMetrics.CAMERA_EPSILON_DP / 500f

    /** Section swipe: the gesture must be at most this steep (|dy| ≤ 0.5·|dx|, ≈ 27°). */
    const val SECTION_SWIPE_MAX_SLOPE: Float = 0.5f

    /** Section swipe feedback: the canvas follows the overscroll at this ratio … */
    const val SECTION_SWIPE_FEEDBACK_RATIO: Float = 0.35f

    /** … up to this many dp (a rubber-band hint that something is past the edge). */
    const val SECTION_SWIPE_FEEDBACK_MAX_DP: Float = 32f

    /** Bottom sheet: the vertical travel a release needs to expand or collapse. */
    const val SHEET_SWIPE_DP: Float = 32f

    /** Bottom sheet: the drag counts as vertical only while |dx| ≤ 0.75·|dy|. */
    const val SHEET_SWIPE_MAX_SLOPE: Float = 0.75f

    /**
     * Bottom sheet: the touch slop (dp) after which a vertical drag takes the
     * pointer from the buttons below it (B3 / 1A v2 S6: 7–8 dp is still a tap).
     */
    const val SHEET_DRAG_SLOP_DP: Float = 8f
}

/**
 * The horizontal overscroll of one single-finger canvas gesture, in dp, signed
 * like the finger: negative = the finger kept moving left after the content's
 * right edge was reached (→ next section), positive = past the left edge (→
 * previous section).
 *
 * Per pan step the canvas asks [beforePan] how much of the finger's
 * horizontal delta the camera should receive — a movement back towards the
 * content first unwinds the overscroll — and then reports in [afterPan] how
 * far the camera actually moved; whatever the content clamp refused becomes
 * overscroll. Inside the content nothing is ever withheld from the camera, so
 * normal panning is unchanged.
 */
class TalentSectionOverscroll {

    /** Current overscroll (dp, signed like the finger). */
    var overscrollDp: Float = 0f
        private set

    /** Returns the part of [fingerDxDp] the camera should pan by. */
    fun beforePan(fingerDxDp: Float): Float {
        if (!fingerDxDp.isFinite()) return 0f
        if (overscrollDp == 0f || fingerDxDp == 0f || sign(fingerDxDp) == sign(overscrollDp)) return fingerDxDp
        val unwound = overscrollDp + fingerDxDp
        return if (unwound == 0f || sign(unwound) == sign(overscrollDp)) {
            overscrollDp = unwound
            0f
        } else {
            overscrollDp = 0f
            unwound // the rest of the movement pans the camera again
        }
    }

    /** The camera was asked to move [givenDxDp] and moved [movedDxDp]: the refused part is overscroll. */
    fun afterPan(givenDxDp: Float, movedDxDp: Float) {
        if (!givenDxDp.isFinite() || !movedDxDp.isFinite()) return
        val refused = givenDxDp - movedDxDp
        // only a refusal in the finger's own direction is overscroll (rounding never counts)
        if (abs(refused) > REFUSAL_EPSILON_DP && sign(refused) == sign(givenDxDp)) overscrollDp += refused
    }

    fun reset() {
        overscrollDp = 0f
    }

    private companion object {
        const val REFUSAL_EPSILON_DP = 0.01f
    }
}

/**
 * PR #19 swipe DIRECTION, the one place it is decided: the content follows the
 * finger, like every platform pager (Android ViewPager / HorizontalPager, iOS
 * paging). In a left-to-right layout the tabs read Tree 1 | Tree 2 | Tree 3,
 * so moving the finger LEFT drags the current tree out to the left and brings
 * in the tree whose tab is on its RIGHT — the next one (`+1`); moving it right
 * brings in the previous one (`-1`). "Swipe right to the next tree" therefore
 * means "move right along the tabs"; the rubber band and the 1A v2 S4 entry
 * shift (the new tree arrives from the chosen tab's side) agree with it. A
 * right-to-left layout mirrors the tabs, so it mirrors this too.
 */
fun sectionSwipeDirectionOf(fingerDxDp: Float, rtl: Boolean = false): Int {
    if (!fingerDxDp.isFinite() || fingerDxDp == 0f) return 0
    val towardsNext = if (rtl) fingerDxDp > 0f else fingerDxDp < 0f
    return if (towardsNext) 1 else -1
}

/**
 * How a single-finger canvas gesture may switch the section.
 *
 * - [FIT_SWIPE]: the camera is at its fit zoom and the section fits the width
 *   (there is nothing to pan sideways). A horizontal swipe anywhere in the
 *   canvas switches directly — no pan to an edge first; the camera does not
 *   move. A vertical drag still scrolls a tall section (sideways locked), so
 *   scrolling down to the lower tiers keeps this model.
 * - [EDGE_OVERSCROLL]: zoomed in or out, or a section wider than the canvas.
 *   One finger pans as always; only the movement the content bound refuses
 *   counts towards a switch ([TalentSectionOverscroll]). FIT (or a re-tap of
 *   the tab) returns to [FIT_SWIPE].
 */
enum class TalentSectionSwipeModel { FIT_SWIPE, EDGE_OVERSCROLL }

/** [TalentSectionSwipeModel] for a camera at [scale] whose fit scale is [fitScale]. */
fun talentSectionSwipeModel(
    scale: Float,
    fitScale: Float,
    contentWidthDp: Float,
    canvasWidthDp: Float,
): TalentSectionSwipeModel {
    if (!scale.isFinite() || !fitScale.isFinite() || !contentWidthDp.isFinite() || !canvasWidthDp.isFinite()) {
        return TalentSectionSwipeModel.EDGE_OVERSCROLL
    }
    val atFitZoom = abs(scale - fitScale) <= TalentTreeGestureMetrics.FIT_SCALE_EPSILON
    // the same "fits" test as the section fit camera (content + the 12 dp field on both sides)
    val fitsWidth = contentWidthDp * scale + 2f * TalentTreeCanvasMetrics.CAMERA_FIELD_DP <= canvasWidthDp
    return if (atFitZoom && fitsWidth) TalentSectionSwipeModel.FIT_SWIPE else TalentSectionSwipeModel.EDGE_OVERSCROLL
}

/**
 * FIT model, at the touch slop: `true` locks the gesture as a section swipe
 * (more horizontal than vertical), `false` makes it a vertical scroll for the
 * rest of the gesture. Decided once per gesture.
 */
fun talentSectionFitSwipeLocks(dxDp: Float, dyDp: Float): Boolean =
    dxDp.isFinite() && dyDp.isFinite() && abs(dxDp) > abs(dyDp)

/**
 * FIT model, at the release of a locked swipe: `+1` / `-1` (see
 * [sectionSwipeDirectionOf]) or `0`. A confident swipe is either at least
 * [TalentTreeGestureMetrics.FIT_SWIPE_DISTANCE_DP] of horizontal travel, or a
 * flick of at least [TalentTreeGestureMetrics.FIT_SWIPE_FLING_MIN_DP] still
 * moving at [TalentTreeGestureMetrics.FIT_SWIPE_FLING_VELOCITY_DP_S] the same
 * way at the release. Never for a pinch, a gesture started in the system
 * gesture zone, a path steeper than |dy| ≤ 0.5·|dx|, or a swipe pulled back
 * below the distance.
 */
fun talentSectionFitSwipeDirection(
    totalDxDp: Float,
    totalDyDp: Float,
    velocityXDpPerSecond: Float,
    multiTouch: Boolean,
    startedInSystemGestureZone: Boolean,
    rtl: Boolean = false,
): Int {
    if (multiTouch || startedInSystemGestureZone) return 0
    if (!totalDxDp.isFinite() || !totalDyDp.isFinite()) return 0
    val distance = abs(totalDxDp)
    if (distance < TalentTreeGestureMetrics.FIT_SWIPE_FLING_MIN_DP) return 0
    if (abs(totalDyDp) > distance * TalentTreeGestureMetrics.SECTION_SWIPE_MAX_SLOPE) return 0
    val flick = velocityXDpPerSecond.isFinite() &&
        sign(velocityXDpPerSecond) == sign(totalDxDp) &&
        abs(velocityXDpPerSecond) >= TalentTreeGestureMetrics.FIT_SWIPE_FLING_VELOCITY_DP_S
    if (distance < TalentTreeGestureMetrics.FIT_SWIPE_DISTANCE_DP && !flick) return 0
    return sectionSwipeDirectionOf(totalDxDp, rtl)
}

/**
 * Edge model: decides, at the release of a canvas gesture, whether it switches
 * the section: `+1` the next section (finger left past the right edge), `-1`
 * the previous one (finger right past the left edge), `0` none — mirrored in
 * a right-to-left layout ([sectionSwipeDirectionOf]).
 *
 * [totalDxDp] / [totalDyDp] are the finger's movement SINCE the content edge
 * was reached, so any pan may come first and only the push past the edge has
 * to be horizontal.
 *
 * Never for a tap (no slop passed), a gesture that ever had two fingers
 * (pinch), a gesture that started in the system gesture zone (Android owns
 * edge swipes — 1A v2 / Android: the system Back gesture belongs to the OS),
 * a diagonal one, or one whose overscroll was not still at least
 * [TalentTreeGestureMetrics.SECTION_SWIPE_OVERSCROLL_DP] at the release (the
 * user can pull back to cancel).
 */
fun talentSectionSwipeDirection(
    overscrollDp: Float,
    totalDxDp: Float,
    totalDyDp: Float,
    passedSlop: Boolean,
    multiTouch: Boolean,
    startedInSystemGestureZone: Boolean,
    rtl: Boolean = false,
): Int {
    if (!passedSlop || multiTouch || startedInSystemGestureZone) return 0
    if (!overscrollDp.isFinite() || !totalDxDp.isFinite() || !totalDyDp.isFinite()) return 0
    if (abs(overscrollDp) < TalentTreeGestureMetrics.SECTION_SWIPE_OVERSCROLL_DP) return 0
    if (abs(totalDyDp) > abs(totalDxDp) * TalentTreeGestureMetrics.SECTION_SWIPE_MAX_SLOPE) return 0
    // the finger's net movement must agree with the overscroll's side
    if (totalDxDp == 0f || sign(totalDxDp) != sign(overscrollDp)) return 0
    return sectionSwipeDirectionOf(overscrollDp, rtl)
}

/** The rubber-band shift (dp) the canvas shows for [overscrollDp]: bounded, same sign. */
fun talentSectionSwipeFeedbackDp(overscrollDp: Float): Float {
    if (!overscrollDp.isFinite() || overscrollDp == 0f) return 0f
    return sign(overscrollDp) * min(
        TalentTreeGestureMetrics.SECTION_SWIPE_FEEDBACK_MAX_DP,
        abs(overscrollDp) * TalentTreeGestureMetrics.SECTION_SWIPE_FEEDBACK_RATIO,
    )
}

/**
 * The neighbour of [current] among [orderedSections] (the tabs' order) in
 * [direction] (`+1` / `-1`), or `null` at the first/last section — no
 * wrap-around.
 */
fun neighbourSection(orderedSections: List<Int>, current: Int, direction: Int): Int? {
    if (direction == 0) return null
    val at = orderedSections.indexOf(current)
    if (at < 0) return null
    return orderedSections.getOrNull(at + direction.coerceIn(-1, 1))
}

/** A vertical swipe on the bottom inspector. */
enum class TalentInspectorSwipe { UP, DOWN, NONE }

/**
 * Classifies a released drag on the inspector strip / sheet header: at least
 * [TalentTreeGestureMetrics.SHEET_SWIPE_DP] of vertical travel and mostly
 * vertical, otherwise [TalentInspectorSwipe.NONE] (a tap or an accidental
 * brush never expands or collapses).
 */
fun talentInspectorSwipe(totalDxDp: Float, totalDyDp: Float): TalentInspectorSwipe {
    if (!totalDxDp.isFinite() || !totalDyDp.isFinite()) return TalentInspectorSwipe.NONE
    if (abs(totalDyDp) < TalentTreeGestureMetrics.SHEET_SWIPE_DP) return TalentInspectorSwipe.NONE
    if (abs(totalDxDp) > abs(totalDyDp) * TalentTreeGestureMetrics.SHEET_SWIPE_MAX_SLOPE) return TalentInspectorSwipe.NONE
    return if (totalDyDp < 0f) TalentInspectorSwipe.UP else TalentInspectorSwipe.DOWN
}

/** What the editor does with one system Back (1A v2 S6). */
enum class EditorBackStep { CLOSE_BUILD_ACTIONS, COLLAPSE_INSPECTOR, CLEAR_SELECTION, NAVIGATE }

/**
 * 1A v2 S6 "Системный Back: Свернуть инспектор → снять выделение → закрыть
 * экран." — one step per Back, in this order, preceded by the open ⋯ sheet
 * (UX/UI spec 02 §1: "Sheets, dialogs and snackbars are not stack entries;
 * Back closes them first"). [EditorBackStep.NAVIGATE] is the app's normal
 * navigation Back (with its autosave flush and re-entrancy guard).
 */
fun editorBackStep(
    inspectorExpanded: Boolean,
    hasSelection: Boolean,
    buildActionsOpen: Boolean = false,
): EditorBackStep = when {
    buildActionsOpen -> EditorBackStep.CLOSE_BUILD_ACTIONS
    inspectorExpanded && hasSelection -> EditorBackStep.COLLAPSE_INSPECTOR
    hasSelection -> EditorBackStep.CLEAR_SELECTION
    else -> EditorBackStep.NAVIGATE
}
