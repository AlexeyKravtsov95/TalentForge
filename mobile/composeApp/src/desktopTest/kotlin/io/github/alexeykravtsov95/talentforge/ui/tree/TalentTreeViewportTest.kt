package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure transform tests of the canvas viewport: round-trip stability, zoom
 * clamping and focal-point behaviour (1A v2 S3: pinch zoom 0.62–1.6× around
 * the fingers), initial-viewport determinism and finiteness guarantees.
 */
class TalentTreeViewportTest {

    private val bounds = TalentTreeBounds(minX = 100f, minY = 200f, maxX = 500f, maxY = 700f)

    @Test
    fun worldToScreenToWorldRoundTripIsStableWithinTolerance() {
        val viewports = listOf(
            TalentTreeViewport(1f, Offset.Zero),
            TalentTreeViewport(0.8f, Offset(-120.5f, 300.25f)),
            TalentTreeViewport(2.2f, Offset(1_000f, -2_000f)),
            TalentTreeViewport(1.37f, Offset(-3.75f, 44.125f)),
        )
        val points = listOf(
            Offset(0f, 0f),
            Offset(105.2f, 269.8f),
            Offset(-986f, 1_024.5f),
            Offset(381.2f, 749.8f),
        )

        for (viewport in viewports) {
            for (world in points) {
                val roundTrip = viewport.screenToWorld(viewport.worldToScreen(world))
                assertTrue(
                    abs(roundTrip.x - world.x) < 1e-3f && abs(roundTrip.y - world.y) < 1e-3f,
                    "round-trip drifted for $world at $viewport -> $roundTrip",
                )
            }
        }
    }

    @Test
    fun zoomIsClampedToTheDirectionBounds() {
        val base = TalentTreeViewport(1f, Offset(50f, 60f))
        val focal = Offset(120f, 140f)

        assertEquals(TalentTreeCanvasMetrics.ZOOM_MIN, base.zoomAt(focal, 0.1f).scale)
        assertEquals(TalentTreeCanvasMetrics.ZOOM_MAX, base.zoomAt(focal, 10f).scale)
        assertEquals(1.5f, base.zoomAt(focal, 1.5f).scale)

        // already at a bound: stays put
        val minViewport = TalentTreeViewport(TalentTreeCanvasMetrics.ZOOM_MIN, Offset.Zero)
        assertEquals(
            TalentTreeCanvasMetrics.ZOOM_MIN,
            minViewport.zoomAt(focal, 0.5f).scale,
        )
    }

    @Test
    fun zoomKeepsTheWorldPointUnderTheFocalPoint() {
        val base = TalentTreeViewport(1.37f, Offset(-211f, 96.5f))
        val focal = Offset(320f, 480f)
        val worldUnderFocal = base.screenToWorld(focal)

        val zoomed = base.zoomAt(focal, 2.0f)
        val worldAfter = zoomed.screenToWorld(focal)

        assertTrue(
            abs(worldUnderFocal.x - worldAfter.x) < 1e-3f && abs(worldUnderFocal.y - worldAfter.y) < 1e-3f,
            "zoom must not move the world point under the focal point",
        )
    }

    @Test
    fun nonFiniteInputsNeverProduceNonFiniteState() {
        val base = TalentTreeViewport(1f, Offset(10f, 20f))

        assertSame(base, base.zoomAt(Offset(5f, 5f), Float.NaN), "NaN zoom target is ignored")
        assertSame(base, base.zoomAt(Offset(5f, 5f), Float.POSITIVE_INFINITY))
        assertSame(base, base.panBy(Offset(Float.NaN, 0f)), "NaN pan is ignored")
        assertSame(base, base.panBy(Offset(0f, Float.NEGATIVE_INFINITY)))

        // clamped zoom stays finite even at extreme factors
        val zoomed = base.zoomAt(Offset(5f, 5f), 1e30f)
        assertTrue(zoomed.scale.isFinite() && zoomed.offset.x.isFinite() && zoomed.offset.y.isFinite())

        // construction rejects non-finite state outright
        assertFailsWith<IllegalArgumentException> { TalentTreeViewport(Float.NaN, Offset.Zero) }
        assertFailsWith<IllegalArgumentException> { TalentTreeViewport(1f, Offset(Float.POSITIVE_INFINITY, 0f)) }
        assertFailsWith<IllegalArgumentException> { TalentTreeViewport(0f, Offset.Zero) }
    }

    @Test
    fun initialViewportIsDeterministicAndAnchorsContentAtTheMargin() {
        val canvas = Size(360f, 656f)
        val first = TalentTreeViewport.initialFor(bounds, canvas, landscape = false)
        val second = TalentTreeViewport.initialFor(bounds, canvas, landscape = false)

        assertEquals(first, second, "same bounds + canvas must always produce the same transform")
        assertEquals(TalentTreeCanvasMetrics.INITIAL_SCALE_PORTRAIT, first.scale)

        val leftEdgeOnScreen = first.worldToScreen(Offset(bounds.minX, bounds.centerY)).x
        assertEquals(
            TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP,
            leftEdgeOnScreen,
            1e-3f,
            "content left edge starts at the screen margin",
        )
    }

    @Test
    fun initialViewportCentresVerticallyWhenContentFits() {
        val canvas = Size(360f, 656f) // content 500dp tall fits
        val viewport = TalentTreeViewport.initialFor(bounds, canvas, landscape = false)

        val centreOnScreen = viewport.worldToScreen(Offset(bounds.centerX, bounds.centerY))
        assertEquals(canvas.height / 2f, centreOnScreen.y, 1e-3f, "content centre = canvas centre")
    }

    @Test
    fun initialViewportTopAlignsWhenContentDoesNotFit() {
        val canvas = Size(360f, 300f) // content 500dp tall does not fit
        val viewport = TalentTreeViewport.initialFor(bounds, canvas, landscape = false)

        val topEdgeOnScreen = viewport.worldToScreen(Offset(bounds.centerX, bounds.minY)).y
        assertEquals(
            TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP,
            topEdgeOnScreen,
            1e-3f,
            "content top edge starts at the screen margin",
        )
    }

    @Test
    fun initialViewportUsesTheB3LandscapeScaleInLandscape() {
        val viewport = TalentTreeViewport.initialFor(bounds, Size(760f, 360f), landscape = true)

        assertEquals(
            TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE,
            viewport.scale,
            1e-6f,
        )
        assertTrue(
            viewport.scale >= TalentTreeCanvasMetrics.ZOOM_MIN,
            "initial landscape scale stays inside the zoom bounds",
        )
    }
}

/**
 * Screen-space hit-testing of the node touch targets.
 *
 * M5.UX.1 (1A v2 S1): `hit = min(pitch − 2, max(48, size + 8))` with
 * `size = 52·s` and `pitch = 80·s` on screen. The target is the node plus
 * 4 dp, never below the 48 dp floor while zoomed out — and never closer than
 * 2 dp to the next grid position, so two neighbouring talents never share a
 * touch point. Visual node size and interaction target stay separate concerns.
 */
class TalentTreeHitTestTest {

    private fun twoNodeModel(): TalentTreeUiModel {
        val left = TalentTreeNodeUiModel(
            id = 1, name = "Left", monogram = "LE", maxRank = 3, sectionIndex = 0,
            centerX = 100f, centerY = 100f,
        )
        // one grid pitch (80 dp world) apart, like two neighbouring columns
        val right = TalentTreeNodeUiModel(
            id = 2, name = "Right", monogram = "RI", maxRank = 3, sectionIndex = 0,
            centerX = 180f, centerY = 100f,
        )
        return TalentTreeUiModel(
            treeId = 1,
            nodes = listOf(left, right),
            edges = emptyList(),
            bounds = TalentTreeBounds(74f, 74f, 206f, 126f),
        )
    }

    private fun hit(
        model: TalentTreeUiModel,
        viewport: TalentTreeViewport,
        minimumTargetDp: Float,
        dxFromNodeScreenCentre: Float = 0f,
        dyFromNodeScreenCentre: Float = 0f,
    ): Int? {
        val nodeScreen = viewport.worldToScreen(Offset(100f, 100f))
        return model.nodeAtScreen(
            Offset(nodeScreen.x + dxFromNodeScreenCentre, nodeScreen.y + dyFromNodeScreenCentre),
            viewport,
            minimumTargetDp,
        )
    }

    @Test
    fun identityScaleTargetIsTheNodePlusFourDpPerSide() {
        val model = twoNodeModel()
        val viewport = TalentTreeViewport(1f, Offset.Zero)

        // 52 dp node + 8 = 60 dp (below the 78 dp pitch cap, above the 48 dp floor)
        assertEquals(60f, effectiveNodeTargetDp(viewport, 48f), 1e-3f)
        assertEquals(1, hit(model, viewport, 48f, dxFromNodeScreenCentre = 29.9f))
        assertEquals(1, hit(model, viewport, 48f, dxFromNodeScreenCentre = -29.9f))
        assertEquals(1, hit(model, viewport, 48f, dyFromNodeScreenCentre = 29.9f))
        assertNull(hit(model, viewport, 48f, dxFromNodeScreenCentre = 30.1f), "outside the target")
    }

    @Test
    fun minimumZoomCapsTheTargetBelowThePitchSoNeighboursNeverOverlap() {
        val model = twoNodeModel()
        val viewport = TalentTreeViewport(TalentTreeCanvasMetrics.ZOOM_MIN, Offset(-40f, 20f))

        // 0.62×: node 32.2 dp, pitch 49.6 dp → min(47.6, max(48, 40.2)) = 47.6 dp
        assertEquals(47.6f, effectiveNodeTargetDp(viewport, TalentTreeCanvasMetrics.NODE_TARGET_MIN_DP), 1e-3f)
        assertEquals(1, hit(model, viewport, 48f, dxFromNodeScreenCentre = 23.7f))
        assertEquals(1, hit(model, viewport, 48f, dyFromNodeScreenCentre = 23.7f))
        assertNull(hit(model, viewport, 48f, dyFromNodeScreenCentre = 23.9f), "still bounded")
        // the point between the two neighbours belongs to neither
        val between = viewport.worldToScreen(Offset(140f, 100f))
        assertNull(model.nodeAtScreen(between, viewport, 48f))
    }

    @Test
    fun landscapeInitialScaleKeepsThe48dpFloor() {
        val model = twoNodeModel()
        val viewport = TalentTreeViewport(TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE, Offset(10f, -5f))
        val minimum = minimumNodeTargetDp(TalentTreeLayoutClass.LANDSCAPE)

        // 0.75×: node 39 dp + 8 = 47 dp, below the 48 dp floor → 48 dp
        assertEquals(48f, effectiveNodeTargetDp(viewport, minimum), 1e-3f)
        assertEquals(1, hit(model, viewport, minimum, dxFromNodeScreenCentre = 23.5f))
        assertEquals(1, hit(model, viewport, minimum, dyFromNodeScreenCentre = 23.5f))
        assertNull(hit(model, viewport, minimum, dxFromNodeScreenCentre = 24.5f), "still bounded")
    }

    @Test
    fun zoomInGrowsTheScreenTargetWithTheNode() {
        val model = twoNodeModel()
        val viewport = TalentTreeViewport(TalentTreeCanvasMetrics.ZOOM_MAX, Offset.Zero)

        // 1.6×: node 83.2 dp + 8 = 91.2 dp (pitch cap 126 dp)
        assertEquals(91.2f, effectiveNodeTargetDp(viewport, 48f), 1e-3f)
        assertEquals(1, hit(model, viewport, 48f, dxFromNodeScreenCentre = 45f), "grown target")
        assertNull(hit(model, viewport, 48f, dxFromNodeScreenCentre = 46f), "still bounded")
    }

    @Test
    fun gridNeighbourTargetsNeverOverlapAtAnyZoom() {
        var scale = TalentTreeCanvasMetrics.ZOOM_MIN
        while (scale <= TalentTreeCanvasMetrics.ZOOM_MAX + 1e-6f) {
            val viewport = TalentTreeViewport(scale, Offset.Zero)
            val pitch = TalentTreeCanvasMetrics.GRID_STEP_DP * scale
            for (layoutClass in TalentTreeLayoutClass.entries) {
                val target = effectiveNodeTargetDp(viewport, minimumNodeTargetDp(layoutClass))
                assertTrue(
                    pitch - target >= TalentTreeCanvasMetrics.NODE_TARGET_PITCH_GAP_DP - 1e-3f,
                    "targets one pitch apart overlap at ${scale}× ($layoutClass): target $target, pitch $pitch",
                )
                assertTrue(
                    target >= TalentTreeCanvasMetrics.NODE_VISUAL_DP * scale,
                    "the target always covers the whole node at ${scale}× ($layoutClass)",
                )
            }
            scale += 0.01f
        }
    }

    @Test
    fun outsideEveryEffectiveTargetIsNull() {
        val model = twoNodeModel()
        val viewport = TalentTreeViewport(1f, Offset.Zero)

        assertNull(model.nodeAtScreen(Offset(0f, 0f), viewport, 48f))
        assertNull(model.nodeAtScreen(Offset(1_000f, 1_000f), viewport, 48f))
        // between the two columns: 40 dp from each centre at a 30 dp half-target
        val midpoint = viewport.worldToScreen(Offset(140f, 100f))
        assertNull(model.nodeAtScreen(midpoint, viewport, 48f))
    }

    @Test
    fun nearestNodeWinsWhenEffectiveTargetsOverlap() {
        val base = TalentTreeNodeUiModel(
            id = 10, name = "A", monogram = "AA", maxRank = 1, sectionIndex = 0,
            centerX = 0f, centerY = 0f,
        )
        val nearby = TalentTreeNodeUiModel(
            id = 11, name = "B", monogram = "BB", maxRank = 1, sectionIndex = 0,
            centerX = 40f, centerY = 0f,
        )
        val model = TalentTreeUiModel(
            treeId = 1,
            nodes = listOf(base, nearby),
            edges = emptyList(),
            bounds = TalentTreeBounds(-24f, -24f, 64f, 24f),
        )
        val viewport = TalentTreeViewport(1f, Offset.Zero)

        // point between both centres: both 56 dp targets contain it, closer wins
        assertEquals(10, model.nodeAtScreen(Offset(15f, 0f), viewport, 56f))
        assertEquals(11, model.nodeAtScreen(Offset(25f, 0f), viewport, 56f))
        // deterministic across repeated queries
        assertEquals(
            model.nodeAtScreen(Offset(20f, 0f), viewport, 56f),
            model.nodeAtScreen(Offset(20f, 0f), viewport, 56f),
        )
    }

    @Test
    fun nonFiniteScreenPositionHitsNothing() {
        val model = twoNodeModel()
        val viewport = TalentTreeViewport(1f, Offset.Zero)

        assertNull(model.nodeAtScreen(Offset(Float.NaN, 100f), viewport, 56f))
        assertNull(
            model.nodeAtScreen(
                Offset(100f, Float.POSITIVE_INFINITY),
                viewport,
                56f,
            ),
        )
    }

    @Test
    fun effectiveTargetIsStableUnderPanning() {
        val model = twoNodeModel()
        // panning must not change hit results: offsets shift node and query alike
        val identity = TalentTreeViewport(1f, Offset.Zero)
        val panned = TalentTreeViewport(1f, Offset(123.5f, -321f))

        for (dx in listOf(-29.5f, -10f, 0f, 10f, 29.5f)) {
            assertEquals(
                hit(model, identity, 48f, dxFromNodeScreenCentre = dx),
                hit(model, panned, 48f, dxFromNodeScreenCentre = dx),
                "pan must not alter relative hit testing (dx=$dx)",
            )
        }
    }
}
