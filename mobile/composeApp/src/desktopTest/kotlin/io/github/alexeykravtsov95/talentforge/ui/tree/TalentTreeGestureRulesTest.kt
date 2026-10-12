package io.github.alexeykravtsov95.talentforge.ui.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR #19 pure gesture rules: the overscroll accumulator, the section-switch
 * decision, the rubber band, the neighbour lookup (no wrap-around), the bottom
 * sheet swipe and the 1A v2 S6 Back order.
 */
class TalentTreeGestureRulesTest {

    private val threshold = TalentTreeGestureMetrics.SECTION_SWIPE_OVERSCROLL_DP

    // ------------------------------------------------------------------ overscroll accumulator

    @Test
    fun panningInsideTheContentNeverAccumulatesOverscroll() {
        val o = TalentSectionOverscroll()
        repeat(20) {
            val given = o.beforePan(-10f)
            assertEquals(-10f, given, "inside the content the camera receives the whole movement")
            o.afterPan(givenDxDp = given, movedDxDp = given)
        }
        assertEquals(0f, o.overscrollDp)
    }

    @Test
    fun whatTheClampRefusesBecomesOverscrollAndAPullBackUnwindsItFirst() {
        val o = TalentSectionOverscroll()
        // the camera reaches its right bound after 4 dp of a 10 dp step, then refuses everything
        o.afterPan(givenDxDp = o.beforePan(-10f), movedDxDp = -4f)
        repeat(9) { o.afterPan(givenDxDp = o.beforePan(-10f), movedDxDp = 0f) }
        assertEquals(-96f, o.overscrollDp, 1e-4f)
        // moving back: the overscroll unwinds before the camera moves again
        assertEquals(0f, o.beforePan(30f))
        assertEquals(-66f, o.overscrollDp, 1e-4f)
        assertEquals(14f, o.beforePan(80f), 1e-4f, "only the rest pans the camera")
        assertEquals(0f, o.overscrollDp)
    }

    @Test
    fun overscrollIsSignedLikeTheFingerAndIgnoresRoundingAndNonFiniteInput() {
        val o = TalentSectionOverscroll()
        o.afterPan(givenDxDp = o.beforePan(12f), movedDxDp = 0f)
        assertEquals(12f, o.overscrollDp, "past the left edge: positive (→ previous section)")
        o.afterPan(givenDxDp = 5f, movedDxDp = 4.995f)
        assertEquals(12f, o.overscrollDp, 1e-4f, "sub-0.01 dp refusals are rounding, not overscroll")
        assertEquals(0f, o.beforePan(Float.NaN))
        o.afterPan(givenDxDp = Float.POSITIVE_INFINITY, movedDxDp = 0f)
        assertEquals(12f, o.overscrollDp, 1e-4f)
        o.reset()
        assertEquals(0f, o.overscrollDp)
    }

    // ------------------------------------------------------------------ switch decision

    private fun decide(
        overscroll: Float,
        dx: Float = overscroll * 2,
        dy: Float = 0f,
        slop: Boolean = true,
        multiTouch: Boolean = false,
        edge: Boolean = false,
    ) = talentSectionSwipeDirection(overscroll, dx, dy, slop, multiTouch, edge)

    @Test
    fun aConfirmedOverscrollSwitchesInTheFingersDirection() {
        assertEquals(1, decide(-threshold), "swipe left past the right edge → next")
        assertEquals(-1, decide(threshold), "swipe right past the left edge → previous")
        assertEquals(1, decide(-200f))
    }

    @Test
    fun shortDiagonalTapPinchEdgeAndPulledBackGesturesNeverSwitch() {
        assertEquals(0, decide(-(threshold - 1f)), "below the distance threshold")
        assertEquals(0, decide(-100f, dx = -200f, dy = 101f), "steeper than |dy| ≤ 0.5·|dx|")
        assertEquals(1, decide(-100f, dx = -200f, dy = 100f), "exactly at the slope bound still counts")
        assertEquals(0, decide(-100f, slop = false), "a tap")
        assertEquals(0, decide(-100f, multiTouch = true), "a pinch / second finger")
        assertEquals(0, decide(-100f, edge = true), "started in the Android system gesture zone")
        assertEquals(0, decide(-100f, dx = 40f), "net movement disagrees with the overscroll side")
        assertEquals(0, decide(Float.NaN))
    }

    @Test
    fun neighboursStopAtTheFirstAndLastSection() {
        val order = listOf(0, 1, 2)
        assertEquals(1, neighbourSection(order, 0, 1))
        assertEquals(2, neighbourSection(order, 1, 1))
        assertEquals(0, neighbourSection(order, 1, -1))
        assertEquals(null, neighbourSection(order, 2, 1), "no wrap-around past the last")
        assertEquals(null, neighbourSection(order, 0, -1), "no wrap-around before the first")
        assertEquals(null, neighbourSection(order, 7, 1), "unknown section")
        assertEquals(null, neighbourSection(order, 1, 0))
        assertEquals(20, neighbourSection(listOf(10, 20, 30), 10, 1), "dataset section indices, not positions")
    }

    @Test
    fun theFingerMovingLeftOpensTheNextSectionAndRtlMirrorsIt() {
        assertEquals(1, sectionSwipeDirectionOf(-80f), "finger left: the tree on the right (next) comes in")
        assertEquals(-1, sectionSwipeDirectionOf(80f), "finger right: the previous tree")
        assertEquals(-1, sectionSwipeDirectionOf(-80f, rtl = true), "right-to-left tabs are mirrored")
        assertEquals(1, sectionSwipeDirectionOf(80f, rtl = true))
        assertEquals(0, sectionSwipeDirectionOf(0f))
        assertEquals(0, sectionSwipeDirectionOf(Float.NaN))
        assertEquals(-1, talentSectionSwipeDirection(-100f, -200f, 0f, true, false, false, rtl = true))
    }

    // ------------------------------------------------------------------ FIT model

    private val field = TalentTreeCanvasMetrics.CAMERA_FIELD_DP

    @Test
    fun theFitModelNeedsTheFitZoomAndASectionThatFitsTheWidth() {
        assertEquals(TalentSectionSwipeModel.FIT_SWIPE, talentSectionSwipeModel(1f, 1f, 292f, 360f))
        assertEquals(
            TalentSectionSwipeModel.FIT_SWIPE,
            talentSectionSwipeModel(1f, 1f, 360f - 2 * field, 360f),
            "exactly the content plus the field on both sides",
        )
        assertEquals(TalentSectionSwipeModel.EDGE_OVERSCROLL, talentSectionSwipeModel(1.4f, 1f, 292f, 360f), "zoomed in")
        assertEquals(TalentSectionSwipeModel.EDGE_OVERSCROLL, talentSectionSwipeModel(0.8f, 1f, 292f, 360f), "zoomed out")
        assertEquals(
            TalentSectionSwipeModel.EDGE_OVERSCROLL,
            talentSectionSwipeModel(1f, 1f, 500f, 360f),
            "a section wider than the canvas (off-grid nodes) keeps sideways panning",
        )
        assertEquals(
            TalentSectionSwipeModel.FIT_SWIPE,
            talentSectionSwipeModel(1f + TalentTreeGestureMetrics.FIT_SCALE_EPSILON / 2, 1f, 292f, 360f),
            "rounding of the fit scale",
        )
        assertEquals(TalentSectionSwipeModel.EDGE_OVERSCROLL, talentSectionSwipeModel(Float.NaN, 1f, 292f, 360f))
    }

    @Test
    fun theFitModelLocksAHorizontalGestureAndScrollsAVerticalOne() {
        assertTrue(talentSectionFitSwipeLocks(-8f, 3f))
        assertTrue(talentSectionFitSwipeLocks(8f, -7.9f))
        assertFalse(talentSectionFitSwipeLocks(5f, 5f), "45°: a scroll, not a swipe")
        assertFalse(talentSectionFitSwipeLocks(2f, -8f))
        assertFalse(talentSectionFitSwipeLocks(Float.NaN, 0f))
    }

    private fun fit(
        dx: Float,
        dy: Float = 0f,
        velocity: Float = 0f,
        multiTouch: Boolean = false,
        edge: Boolean = false,
    ) = talentSectionFitSwipeDirection(dx, dy, velocity, multiTouch, edge)

    @Test
    fun aConfidentFitSwipeSwitchesByDistanceOrByFlick() {
        val distance = TalentTreeGestureMetrics.FIT_SWIPE_DISTANCE_DP
        val flickMin = TalentTreeGestureMetrics.FIT_SWIPE_FLING_MIN_DP
        val flickSpeed = TalentTreeGestureMetrics.FIT_SWIPE_FLING_VELOCITY_DP_S
        assertEquals(1, fit(-distance), "a slow but long swipe left → next")
        assertEquals(-1, fit(distance), "→ previous")
        assertEquals(0, fit(-(distance - 1f)), "a slow short swipe")
        assertEquals(1, fit(-flickMin, velocity = -flickSpeed), "a quick flick")
        assertEquals(0, fit(-(flickMin - 1f), velocity = -5_000f), "a flick too short to be intended")
        assertEquals(0, fit(-40f, velocity = -(flickSpeed - 1f)), "not quick enough")
        assertEquals(0, fit(-40f, velocity = 2_000f), "pulled back: moving the other way at the release")
        assertEquals(1, fit(-200f, velocity = 2_000f), "a long swipe still counts at any release speed")
    }

    @Test
    fun aFitSwipeNeverSwitchesWhenDiagonalPinchedOrStartedAtTheSystemEdge() {
        assertEquals(0, fit(-100f, dy = 51f), "steeper than |dy| ≤ 0.5·|dx|")
        assertEquals(1, fit(-100f, dy = 50f), "exactly at the slope bound")
        assertEquals(0, fit(-200f, multiTouch = true), "a pinch")
        assertEquals(0, fit(-200f, edge = true), "started in the Android system gesture zone")
        assertEquals(0, fit(Float.NaN))
        assertEquals(-1, talentSectionFitSwipeDirection(-100f, 0f, 0f, false, false, rtl = true))
    }

    @Test
    fun theRubberBandFollowsTheOverscrollAndIsBounded() {
        assertEquals(0f, talentSectionSwipeFeedbackDp(0f))
        assertEquals(-3.5f, talentSectionSwipeFeedbackDp(-10f), 1e-4f)
        assertEquals(TalentTreeGestureMetrics.SECTION_SWIPE_FEEDBACK_MAX_DP, talentSectionSwipeFeedbackDp(500f))
        assertEquals(0f, talentSectionSwipeFeedbackDp(Float.NaN))
    }

    // ------------------------------------------------------------------ bottom sheet

    @Test
    fun aSheetSwipeNeedsDistanceAndAMostlyVerticalPath() {
        val d = TalentTreeGestureMetrics.SHEET_SWIPE_DP
        assertEquals(TalentInspectorSwipe.UP, talentInspectorSwipe(0f, -d))
        assertEquals(TalentInspectorSwipe.DOWN, talentInspectorSwipe(10f, 80f))
        assertEquals(TalentInspectorSwipe.NONE, talentInspectorSwipe(0f, -(d - 1f)), "too short: no accidental open")
        assertEquals(TalentInspectorSwipe.NONE, talentInspectorSwipe(-61f, -80f), "too diagonal")
        assertEquals(TalentInspectorSwipe.NONE, talentInspectorSwipe(0f, Float.NaN))
    }

    // ------------------------------------------------------------------ system Back (1A v2 S6)

    @Test
    fun backCollapsesThenDeselectsThenNavigates() {
        assertEquals(EditorBackStep.COLLAPSE_INSPECTOR, editorBackStep(inspectorExpanded = true, hasSelection = true))
        assertEquals(EditorBackStep.CLEAR_SELECTION, editorBackStep(inspectorExpanded = false, hasSelection = true))
        assertEquals(EditorBackStep.NAVIGATE, editorBackStep(inspectorExpanded = false, hasSelection = false))
        // a stale "expanded" flag without a selection never swallows the navigation
        assertEquals(EditorBackStep.NAVIGATE, editorBackStep(inspectorExpanded = true, hasSelection = false))
        // UX/UI spec 02 §1: an open sheet closes first
        assertEquals(
            EditorBackStep.CLOSE_BUILD_ACTIONS,
            editorBackStep(inspectorExpanded = true, hasSelection = true, buildActionsOpen = true),
        )
    }
}
