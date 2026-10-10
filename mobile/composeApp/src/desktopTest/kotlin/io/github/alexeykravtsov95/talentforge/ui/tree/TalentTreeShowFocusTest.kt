package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M4.2 Show-focus and adaptive-layout tests of the pure viewport layer:
 * `focusedOn` (B3 07 prerequisite navigation), the layout-class initial
 * scales/target floors, and the finiteness guarantees across layout switches.
 */
class TalentTreeShowFocusTest {

    private val bounds = TalentTreeBounds(minX = 0f, minY = 0f, maxX = 500f, maxY = 700f)
    private val visible = Size(360f, 500f)

    @Test
    fun focusKeepsTheViewportWhenTheTargetIsAlreadyFullyVisible() {
        // node centred well inside the visible area
        val viewport = TalentTreeViewport(1f, Offset(100f, 100f))
        val world = Offset(200f, 200f)

        assertSame(viewport, viewport.focusedOn(world, visible), "canvas stays still (B3 07)")
    }

    @Test
    fun focusCentresTheTargetWhenItWouldBeCovered() {
        val viewport = TalentTreeViewport(1f, Offset(0f, 0f))
        val world = Offset(300f, 600f) // below the visible area (inspector covers it)

        val focused = viewport.focusedOn(world, visible)

        assertEquals(viewport.scale, focused.scale, "focus never resets the scale")
        assertEquals(visible.width / 2f, focused.worldToScreen(world).x, 1e-3f)
        assertEquals(visible.height / 2f, focused.worldToScreen(world).y, 1e-3f)
        assertTrue(
            focused.offset.x.isFinite() && focused.offset.y.isFinite(),
            "the focused transform is finite",
        )
    }

    @Test
    fun focusRespectsTheClearanceOnEverySide() {
        val viewport = TalentTreeViewport(1f, Offset(0f, 0f))
        // target exactly at the visible edge (outside the clearance band)
        val world = Offset(0f, 0f)

        val focused = viewport.focusedOn(world, visible)
        val clearance = TalentInspectorMetrics.FOCUS_CLEARANCE_DP
        val screen = focused.worldToScreen(world)

        assertTrue(screen.x >= clearance - 1e-3f, "left clearance kept (${screen.x})")
        assertTrue(screen.y >= clearance - 1e-3f, "top clearance kept (${screen.y})")
        assertTrue(screen.x <= visible.width - clearance + 1e-3f)
        assertTrue(screen.y <= visible.height - clearance + 1e-3f)
    }

    @Test
    fun focusRejectsDegenerateInputLeavingTheViewportUnchanged() {
        val viewport = TalentTreeViewport(1f, Offset(10f, 10f))

        assertSame(viewport, viewport.focusedOn(Offset(Float.NaN, 0f), visible))
        assertSame(viewport, viewport.focusedOn(Offset(0f, Float.POSITIVE_INFINITY), visible))
        assertSame(viewport, viewport.focusedOn(Offset(10f, 10f), Size.Zero))
        assertSame(viewport, viewport.focusedOn(Offset(10f, 10f), Size(-5f, 10f)))
    }

    @Test
    fun focusProducesFiniteTransformsAcrossLayoutClasses() {
        // portrait → landscape → tablet switches with a pending focus must
        // never produce non-finite viewport state
        for (landscape in listOf(true, false)) {
            val initial = TalentTreeViewport.initialFor(bounds, Size(760f, 360f), landscape)
            val focused = initial.focusedOn(Offset(bounds.centerX, bounds.maxY), Size(460f, 316f))
            assertTrue(focused.scale.isFinite() && focused.offset.x.isFinite() && focused.offset.y.isFinite())
        }
    }

    @Test
    fun layoutClassesPickTheirSpecInitialScales() {
        val portrait = TalentTreeViewport.initialFor(bounds, Size(360f, 656f), TalentTreeLayoutClass.PORTRAIT)
        val landscape = TalentTreeViewport.initialFor(bounds, Size(760f, 360f), TalentTreeLayoutClass.LANDSCAPE)
        val tablet = TalentTreeViewport.initialFor(bounds, Size(460f, 556f), TalentTreeLayoutClass.TABLET)

        assertEquals(1f, portrait.scale, 1e-4f)
        assertEquals(TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE, landscape.scale, 1e-4f)
        // UX/UI spec 13: tablet nodes are 56 dp (52 dp world) → ×56/52
        assertEquals(
            TalentInspectorMetrics.TABLET_NODE_VISUAL_DP / TalentTreeCanvasMetrics.NODE_VISUAL_DP,
            tablet.scale,
            1e-4f,
        )
        for (viewport in listOf(portrait, landscape, tablet)) {
            assertTrue(
                viewport.scale in TalentTreeCanvasMetrics.ZOOM_MIN..TalentTreeCanvasMetrics.ZOOM_MAX,
                "initial scale stays inside the 1A v2 zoom bounds",
            )
            assertNotNull(viewport.offset)
        }
    }

    @Test
    fun layoutClassesPickTheirSpecScreenTargetFloors() {
        // 1A v2 S1: max(48, size + 8) on phones; the UX/UI spec 13 tablet target stays
        assertEquals(48f, minimumNodeTargetDp(TalentTreeLayoutClass.PORTRAIT))
        assertEquals(48f, minimumNodeTargetDp(TalentTreeLayoutClass.LANDSCAPE))
        assertEquals(68f, minimumNodeTargetDp(TalentTreeLayoutClass.TABLET))
    }

    @Test
    fun layoutClassifierFollowsTheSpecWidthClass() {
        assertEquals(TalentTreeLayoutClass.TABLET, talentTreeLayoutClass(840f, 600f))
        assertEquals(TalentTreeLayoutClass.TABLET, talentTreeLayoutClass(1280f, 800f))
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeLayoutClass(760f, 360f))
        assertEquals(TalentTreeLayoutClass.PORTRAIT, talentTreeLayoutClass(360f, 760f))
        // a tall tablet-ish window under 840 dp wide is still portrait layout
        assertEquals(TalentTreeLayoutClass.PORTRAIT, talentTreeLayoutClass(800f, 1280f))
    }

    @Test
    fun revealMovesOnlyTheOutOfBoundsAxisByTheMinimumAmount() {
        val viewport = TalentTreeViewport(1f, Offset(0f, 0f))
        val clearance = TalentInspectorMetrics.FOCUS_CLEARANCE_DP

        // target below the visible band: only y moves, by exactly the deficit
        val world = Offset(200f, 600f)
        val revealed = viewport.panToReveal(world, visible)
        assertEquals(viewport.scale, revealed.scale)
        assertEquals(viewport.offset.x, revealed.offset.x, 1e-4f, "x axis is in bounds and must not move")
        assertEquals(
            visible.height - clearance,
            revealed.worldToScreen(world).y,
            1e-3f,
            "y lands exactly on the bottom bound of the visible band",
        )
        assertEquals(
            viewport.offset.y - (600f - (visible.height - clearance)),
            revealed.offset.y,
            1e-3f,
            "the pan is the minimum deficit, not a centring",
        )

        // target inside: no move at all
        assertSame(viewport, viewport.panToReveal(Offset(200f, 200f), visible))

        // both axes out: both move minimally (x to the left bound, y to the right/bottom bound)
        val corner = viewport.panToReveal(Offset(-500f, 900f), visible)
        assertEquals(clearance, corner.worldToScreen(Offset(-500f, 900f)).x, 1e-3f)
        assertEquals(
            visible.height - clearance,
            corner.worldToScreen(Offset(-500f, 900f)).y,
            1e-3f,
        )
    }

    @Test
    fun revealClampsDegenerateRegionsAndStaysFinite() {
        val viewport = TalentTreeViewport(1.5f, Offset(30f, 30f))

        // region smaller than twice the clearance: still finite, in-bounds clamp
        val tiny = viewport.panToReveal(Offset(10f, 10f), Size(20f, 20f))
        assertTrue(tiny.offset.x.isFinite() && tiny.offset.y.isFinite())

        assertSame(viewport, viewport.panToReveal(Offset(Float.NaN, 1f), visible))
        assertSame(viewport, viewport.panToReveal(Offset(1f, 1f), Size.Zero))
    }

    @Test
    fun focusRequestStateHolderRejectsNonFiniteAndEmptyTargets() {
        val state = TalentTreeViewportState(initial = null)
        state.requestFocus(Offset(Float.NaN, 1f), visible)
        assertEquals(null, state.focusRequest)
        state.requestFocus(Offset(1f, 1f), Size.Zero)
        assertEquals(null, state.focusRequest)

        state.requestFocus(Offset(10f, 10f), visible)
        val request = assertNotNull(state.focusRequest)
        assertEquals(Offset(10f, 10f), request.world)
        assertEquals(visible, request.visibleSize)

        state.requestFocus(Offset(20f, 20f), visible)
        assertTrue(assertNotNull(state.focusRequest).id > request.id, "each request gets a fresh id")
        assertTrue(abs(state.focusRequest!!.world.x - 20f) < 1e-4f)
        assertEquals(TalentTreeFocusMode.CENTRE, state.focusRequest!!.mode, "Show keeps the centring mode")

        state.requestFocus(Offset(20f, 20f), visible, mode = TalentTreeFocusMode.REVEAL)
        assertEquals(TalentTreeFocusMode.REVEAL, state.focusRequest!!.mode)
    }
}
