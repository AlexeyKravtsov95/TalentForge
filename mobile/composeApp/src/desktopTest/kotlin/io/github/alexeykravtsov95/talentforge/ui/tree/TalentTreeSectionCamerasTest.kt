package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M5.UX.1 per-section cameras (1A v2 S3), pure: the section fit, independent
 * cameras per section, FIT bookkeeping, the content clamp (the user can never
 * lose the tree), gesture/animation interplay and the fail-closed saved form
 * bound to one build.
 */
class TalentTreeSectionCamerasTest {

    /** A standard four-column, seven-row section (292 × 532 dp at scale 1). */
    private val section = TalentTreeBounds(minX = 1000f, minY = 200f, maxX = 1292f, maxY = 732f)

    private val phone = Size(360f, 668f)

    private fun preparedCamera(size: Size = phone): TalentTreeViewportState =
        TalentTreeViewportState(initial = null, fitPolicy = TalentTreeFitPolicy.SECTION).apply {
            prepare(section, size, TalentTreeLayoutClass.PORTRAIT)
        }

    // ------------------------------------------------------------------ fit

    @Test
    fun theFirstCameraFitsTheSectionToTheVisibleWidth() {
        for ((width, scale) in listOf(320f to 320f / 354f, 360f to 1f, 393f to 1f)) {
            val camera = preparedCamera(Size(width, 600f))
            val viewport = assertNotNull(camera.viewport)
            assertEquals(scale, viewport.scale, 1e-4f, "s = clamp(W/354, 0.62, 1.0) at $width dp")
            val left = viewport.worldToScreen(Offset(section.minX, section.minY))
            val right = viewport.worldToScreen(Offset(section.maxX, section.maxY))
            assertEquals(left.x, width - right.x, 1e-3f, "centred at $width dp")
            assertEquals(TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP, left.y, 1e-3f, "top-aligned")
            assertFalse(camera.userMoved)
            assertFalse(camera.isAwayFromFit, "a fresh camera is the fit: no FIT control")
        }
    }

    @Test
    fun theScaleNeverLeavesTheZoomBoundsEvenOnTinyOrHugeCanvases() {
        val tiny = TalentTreeViewport.sectionFitFor(section, Size(120f, 300f), TalentTreeLayoutClass.PORTRAIT)
        val huge = TalentTreeViewport.sectionFitFor(section, Size(2_000f, 300f), TalentTreeLayoutClass.PORTRAIT)
        assertEquals(TalentTreeCanvasMetrics.ZOOM_MIN, tiny.scale)
        assertEquals(1f, huge.scale, "the phone cap")
        assertEquals(
            TalentTreeViewport.TABLET_SECTION_FIT_CAP,
            TalentTreeViewport.sectionFitFor(section, Size(2_000f, 300f), TalentTreeLayoutClass.TABLET).scale,
        )
        assertEquals(
            TalentTreeCanvasMetrics.INITIAL_SCALE_LANDSCAPE,
            TalentTreeViewport.sectionFitFor(section, Size(2_000f, 300f), TalentTreeLayoutClass.LANDSCAPE).scale,
        )
        val degenerate = TalentTreeViewport.sectionFitFor(section, Size.Zero, TalentTreeLayoutClass.PORTRAIT)
        assertTrue(degenerate.scale.isFinite() && degenerate.offset.x.isFinite())
    }

    // ------------------------------------------------------------------ independence

    @Test
    fun everySectionOwnsAnIndependentCamera() {
        val cameras = TalentTreeSectionCameras(sessionKey = "build-a", treeId = 1)
        val first = cameras.stateOf(0)
        val second = cameras.stateOf(1)
        assertNotSame(first, second)
        assertSame(first, cameras.stateOf(0), "a section keeps its camera")
        first.prepare(section, phone, TalentTreeLayoutClass.PORTRAIT)
        second.prepare(section, phone, TalentTreeLayoutClass.PORTRAIT)
        val secondBefore = second.viewport

        first.applyUserTransform(assertNotNull(first.viewport).panBy(Offset(-30f, -120f)))

        assertEquals(secondBefore, second.viewport, "moving one section never moves another")
        assertTrue(first.userMoved)
        assertFalse(second.userMoved)
        assertEquals(setOf(0, 1), cameras.knownSections)
    }

    // ------------------------------------------------------------------ FIT

    @Test
    fun fitIsOfferedOnlyAfterTheUserMovedAwayAndCompletesBackToTheFit() {
        val camera = preparedCamera()
        val fit = assertNotNull(camera.fitViewport())
        assertFalse(camera.isAwayFromFit)

        camera.applyUserTransform(fit.zoomAt(Offset(180f, 300f), 1.4f))
        assertTrue(camera.userMoved)
        assertTrue(camera.isAwayFromFit, "a pinch away from the fit offers FIT")

        camera.requestFit()
        assertTrue(camera.consumeFitRequest(), "one pending FIT")
        assertFalse(camera.consumeFitRequest(), "each FIT request runs once")
        camera.completeFit(fit)
        assertEquals(fit, camera.viewport)
        assertFalse(camera.userMoved)
        assertFalse(camera.isAwayFromFit)
    }

    @Test
    fun aUserMoveThatEndsBackAtTheFitOffersNoFit() {
        val camera = preparedCamera()
        val fit = assertNotNull(camera.fitViewport())
        camera.applyUserTransform(fit.panBy(Offset(0f, 20f)))
        assertTrue(camera.isAwayFromFit)
        camera.applyUserTransform(assertNotNull(camera.viewport).panBy(Offset(0f, -20f)))
        assertTrue(camera.userMoved)
        assertFalse(camera.isAwayFromFit, "FIT only when the camera differs from the fit")
    }

    @Test
    fun everyGestureStepAdvancesTheEpochThatInterruptsAnimations() {
        val camera = preparedCamera()
        val before = camera.gestureEpoch
        camera.applyUserTransform(assertNotNull(camera.viewport).panBy(Offset(0f, -10f)))
        camera.applyUserTransform(assertNotNull(camera.viewport).panBy(Offset(0f, -10f)))
        assertEquals(before + 2, camera.gestureEpoch)
        // programmatic transforms (animations) never count as gestures
        camera.applyTransform(assertNotNull(camera.viewport))
        assertEquals(before + 2, camera.gestureEpoch)
    }

    @Test
    fun aFocusRequestRunsOnceEvenIfTheCanvasReentersComposition() {
        val camera = preparedCamera()
        camera.requestFocus(Offset(1100f, 600f), Size(360f, 576f))
        val request = assertNotNull(camera.focusRequest)
        assertTrue(camera.consumeFocusRequest(request))
        assertFalse(camera.consumeFocusRequest(request), "a returning tab never replays an old Show")
    }

    // ------------------------------------------------------------------ clamp (12 dp field)

    @Test
    fun panningCanNeverLoseTheSection() {
        val camera = preparedCamera()
        val field = TalentTreeCanvasMetrics.CAMERA_FIELD_DP
        // fling far in every direction
        for (delta in listOf(Offset(5_000f, 0f), Offset(-5_000f, 0f), Offset(0f, 5_000f), Offset(0f, -5_000f))) {
            camera.applyUserTransform(assertNotNull(camera.viewport).panBy(delta))
            val viewport = assertNotNull(camera.viewport)
            val topLeft = viewport.worldToScreen(Offset(section.minX, section.minY))
            val bottomRight = viewport.worldToScreen(Offset(section.maxX, section.maxY))
            // the fitting section stays fully inside the canvas, within the field
            assertTrue(topLeft.x >= field - 1e-3f && bottomRight.x <= phone.width - field + 1e-3f, "x for $delta")
            assertTrue(topLeft.y >= field - 1e-3f && bottomRight.y <= phone.height - field + 1e-3f, "y for $delta")
        }
    }

    @Test
    fun aSectionTallerThanTheCanvasScrollsToItsLowerTiersButNotBeyond() {
        // 320 × 448 dp (the 320 × 568 phone minus header and tabs): the section does not fit vertically
        val small = Size(320f, 448f)
        val camera = preparedCamera(small)
        val field = TalentTreeCanvasMetrics.CAMERA_FIELD_DP
        camera.applyUserTransform(assertNotNull(camera.viewport).panBy(Offset(0f, -5_000f)))
        val viewport = assertNotNull(camera.viewport)
        val bottom = viewport.worldToScreen(Offset(section.maxX, section.maxY)).y
        assertEquals(small.height - field, bottom, 1e-3f, "the lowest tier reaches the bottom field and stops")

        camera.applyUserTransform(viewport.panBy(Offset(0f, 5_000f)))
        val top = assertNotNull(camera.viewport).worldToScreen(Offset(section.minX, section.minY)).y
        assertTrue(top <= TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP + 1e-3f, "and back up to the top")
    }

    @Test
    fun theCoveredInspectorAreaCanBeScrolledAbove() {
        val camera = preparedCamera()
        camera.coveredBottomDp = 448f // the expanded sheet
        camera.applyUserTransform(assertNotNull(camera.viewport).panBy(Offset(0f, -5_000f)))
        val bottom = assertNotNull(camera.viewport).worldToScreen(Offset(section.maxX, section.maxY)).y
        assertEquals(phone.height - 448f - TalentTreeCanvasMetrics.CAMERA_FIELD_DP, bottom, 1e-3f)
    }

    @Test
    fun theClampNeverJumpsACameraThatAlreadySitsOutsideTheBounds() {
        val outside = TalentTreeViewport(1f, Offset(400f, 300f)) // section far right, partly off screen
        val nudged = outside.panBy(Offset(-2f, 0f))
        // moving back towards the bounds is accepted as is …
        assertEquals(nudged, nudged.clampedTo(section, phone, reference = outside))
        // … moving further out is refused (stays where the reference was)
        val further = outside.panBy(Offset(50f, 0f))
        assertEquals(outside.offset.x, further.clampedTo(section, phone, reference = outside).offset.x, 1e-3f)
    }

    @Test
    fun zoomingOutAroundAFocusKeepsTheContentInsideTheField() {
        val camera = preparedCamera()
        val start = assertNotNull(camera.viewport)
        camera.applyUserTransform(start.zoomAt(Offset(340f, 650f), TalentTreeCanvasMetrics.ZOOM_MIN))
        val viewport = assertNotNull(camera.viewport)
        assertEquals(TalentTreeCanvasMetrics.ZOOM_MIN, viewport.scale)
        val topLeft = viewport.worldToScreen(Offset(section.minX, section.minY))
        val bottomRight = viewport.worldToScreen(Offset(section.maxX, section.maxY))
        assertTrue(topLeft.x >= 0f && bottomRight.x <= phone.width, "still on screen horizontally")
        assertTrue(topLeft.y >= 0f && bottomRight.y <= phone.height, "still on screen vertically")
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun aDifferentCanvasRefitsWhileTheSameCanvasKeepsTheUsersCamera() {
        val camera = preparedCamera()
        camera.applyUserTransform(assertNotNull(camera.viewport).panBy(Offset(0f, -60f)))
        val moved = camera.viewport

        camera.initializeOnce(section) // same canvas: a recomposition keeps everything
        assertEquals(moved, camera.viewport)
        assertTrue(camera.userMoved)

        // 1A v2 S5: an orientation change recomputes the camera to the fit
        camera.onCanvasSizeChanged(Size(320f, 448f), density = 1f)
        camera.initializeOnce(section)
        assertEquals(
            TalentTreeViewport.sectionFitFor(section, Size(320f, 448f), TalentTreeLayoutClass.PORTRAIT),
            camera.viewport,
        )
        assertFalse(camera.userMoved)
    }

    // ------------------------------------------------------------------ saved form

    @Test
    fun savedCamerasRestoreForTheSameBuildOnly() {
        val cameras = TalentTreeSectionCameras(sessionKey = "build-a", treeId = 1117)
        cameras.selectedSection = 2
        val tree2 = cameras.stateOf(1).apply { prepare(section, phone, TalentTreeLayoutClass.PORTRAIT) }
        tree2.applyUserTransform(assertNotNull(tree2.viewport).panBy(Offset(0f, -50f)))
        cameras.stateOf(2) // created, never laid out

        val saved = TalentTreeSectionCameras.encode(cameras)
        val restored = assertNotNull(TalentTreeSectionCameras.decode(saved, "build-a", 1117))

        assertEquals(2, restored.selectedSection)
        assertEquals(setOf(1, 2), restored.knownSections)
        assertEquals(tree2.viewport, restored.stateOf(1).viewport)
        assertTrue(restored.stateOf(1).userMoved)
        assertEquals(phone, restored.stateOf(1).fittedCanvasSize)
        assertEquals(TalentTreeFitPolicy.SECTION, restored.stateOf(1).fitPolicy)
        assertNull(restored.stateOf(2).viewport, "an unlaid camera restores uninitialized")

        // another build — or another tree — never inherits these cameras
        assertNull(TalentTreeSectionCameras.decode(saved, "build-b", 1117))
        assertNull(TalentTreeSectionCameras.decode(saved, "build-a", 1091))
    }

    @Test
    fun corruptSavedCamerasFailClosed() {
        val cameras = TalentTreeSectionCameras(sessionKey = "k", treeId = 1)
        cameras.stateOf(0).prepare(section, phone, TalentTreeLayoutClass.PORTRAIT)
        val saved = TalentTreeSectionCameras.encode(cameras)

        assertNull(TalentTreeSectionCameras.decode(saved.dropLast(1), "k", 1), "truncated")
        assertNull(TalentTreeSectionCameras.decode(saved + 0f, "k", 1), "trailing data")
        assertNull(TalentTreeSectionCameras.decode(listOf(99) + saved.drop(1), "k", 1), "unknown version")
        assertNull(TalentTreeSectionCameras.decode(saved.toMutableList().also { it[4] = "x" }, "k", 1), "bad count")
        assertNull(TalentTreeSectionCameras.decode(emptyList(), "k", 1))
        // a non-finite camera restores as an uninitialized camera, never a broken transform
        val nan = saved.toMutableList().also { it[7] = Float.NaN }
        assertNull(assertNotNull(TalentTreeSectionCameras.decode(nan, "k", 1)).stateOf(0).viewport)
        val zeroScale = saved.toMutableList().also { it[7] = 0f }
        assertNull(assertNotNull(TalentTreeSectionCameras.decode(zeroScale, "k", 1)).stateOf(0).viewport)
    }

    @Test
    fun theWholeTreeCameraKeepsItsM4RestorationContract() {
        val state = TalentTreeViewportState(initial = null)
        state.onCanvasSizeChanged(Size(760f, 360f), density = 1f)
        state.layoutClass = TalentTreeLayoutClass.LANDSCAPE
        state.initializeOnce(section)
        val first = assertNotNull(state.viewport)

        val restored = TalentTreeViewportState.decode(TalentTreeViewportState.encode(state), TalentTreeFitPolicy.WHOLE_TREE)
        assertEquals(first, restored.viewport)
        // a whole-tree camera is never re-fitted by a size change (M4: never reset)
        restored.onCanvasSizeChanged(Size(360f, 760f), density = 1f)
        restored.initializeOnce(section)
        assertEquals(first, restored.viewport)
    }
}
