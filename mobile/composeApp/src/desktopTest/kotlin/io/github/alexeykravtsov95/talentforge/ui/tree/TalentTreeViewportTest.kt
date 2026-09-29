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
 * clamping and focal-point behaviour (B3 07: pinch zoom 0.8–2.2× around the
 * fingers), initial-viewport determinism and finiteness guarantees.
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
    fun zoomIsClampedToTheB3Bounds() {
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

/** Hit-testing of the B3 56 × 56 dp node targets. */
class TalentTreeHitTestTest {

    private fun twoNodeModel(): TalentTreeUiModel {
        val left = TalentTreeNodeUiModel(
            id = 1, name = "Left", monogram = "LE", maxRank = 3, sectionIndex = 0,
            centerX = 100f, centerY = 100f,
        )
        val right = TalentTreeNodeUiModel(
            id = 2, name = "Right", monogram = "RI", maxRank = 3, sectionIndex = 0,
            centerX = 176f, centerY = 100f,
        )
        return TalentTreeUiModel(
            treeId = 1,
            nodes = listOf(left, right),
            edges = emptyList(),
            bounds = TalentTreeBounds(76f, 76f, 200f, 124f),
        )
    }

    @Test
    fun nodeCentreAndTargetAreaHit() {
        val model = twoNodeModel()

        assertEquals(1, model.nodeAt(Offset(100f, 100f)))
        assertEquals(1, model.nodeAt(Offset(100f + 27.9f, 100f + 27.9f)), "inside the 56dp target")
        assertEquals(2, model.nodeAt(Offset(176f, 100f)))
    }

    @Test
    fun outsideEveryTargetIsNull() {
        val model = twoNodeModel()

        assertNull(model.nodeAt(Offset(100f + 28.1f, 100f + 28.1f)), "just outside the target")
        assertNull(model.nodeAt(Offset(0f, 0f)))
        assertNull(model.nodeAt(Offset(1_000f, 1_000f)))
    }

    @Test
    fun nearestNodeWinsWhenTargetsOverlap() {
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

        // point between both centres: belongs to the closer node
        assertEquals(10, model.nodeAt(Offset(15f, 0f)))
        assertEquals(11, model.nodeAt(Offset(25f, 0f)))
    }

    @Test
    fun nonFiniteWorldPositionHitsNothing() {
        assertNull(twoNodeModel().nodeAt(Offset(Float.NaN, 100f)))
        assertNull(twoNodeModel().nodeAt(Offset(100f, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun screenVariantMatchesWorldVariant() {
        val model = twoNodeModel()
        val viewport = TalentTreeViewport(2f, Offset(-50f, 30f))

        assertEquals(
            model.nodeAt(viewport.screenToWorld(Offset(150f, 230f))),
            model.nodeAtScreen(Offset(150f, 230f), viewport),
        )
    }
}
