package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5.UI.TABLET.1, pure: the tablet header model (labels, live points), the
 * anchor of every bundled section (off-grid talents never drag it off its
 * tree), the slot geometry (no overlap, never outside the canvas, at any
 * camera) and the camera's header room (fit and clamp; 0 changes nothing).
 */
class TalentTabletSectionHeadersTest {

    private val classes = listOf("WARRIOR", "PALADIN", "HUNTER", "ROGUE", "PRIEST", "SHAMAN", "MAGE", "WARLOCK", "DRUID")

    private fun sectioned(state: TalentTreeUiState.Ready) =
        buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned

    /** Four grid columns: three pitches plus one node visual. */
    private val fourColumns = 3f * TalentTreeCanvasMetrics.GRID_STEP_DP + TalentTreeCanvasMetrics.NODE_VISUAL_DP

    @Test
    fun headersCarryTheResolvedLabelsAndTheLivePoints() {
        val warrior = withSectionPoints(productState("WARRIOR"), 13, 5, 0)
        val sections = sectioned(warrior)
        val points = sectionSpentPoints(sections, warrior.buildState)
        val english = talentTabletSectionHeaders(sections, points, warrior.classToken, AppLanguage.ENGLISH)
        assertEquals(listOf("Arms", "Fury", "Protection"), english.map { it.label })
        assertEquals(listOf(13, 5, 0), english.map { it.points })
        val russian = talentTabletSectionHeaders(sections, points, warrior.classToken, AppLanguage.RUSSIAN)
        assertEquals(listOf("Оружие", "Неистовство", "Защита"), russian.map { it.label })
        assertEquals(sections.sections.map { it.index }, russian.map { it.index })

        for (token in classes) {
            val state = productState(token)
            val classSections = sectioned(state)
            for (language in AppLanguage.entries) {
                val headers = talentTabletSectionHeaders(classSections, emptyMap(), state.classToken, language)
                assertEquals(
                    classSections.sections.map {
                        TalentSpecializationNames.displayNameOf(ClassToken(token), it.index, language)
                    },
                    headers.map { it.label },
                    "$token $language: the approved names, never a second table",
                )
                assertTrue(headers.all { it.points == 0 })
            }
        }
    }

    @Test
    fun everyBundledSectionAnchorsOnItsOwnGridEvenWithOffGridTalents() {
        for (token in classes) {
            val state = productState(token)
            for (section in sectioned(state).sections) {
                val anchor = assertNotNull(talentTabletHeaderAnchor(section.model.nodes), "$token ${section.index}")
                assertTrue(
                    anchor.maxX - anchor.minX <= fourColumns + 0.5f,
                    "$token ${section.index}: the anchor spans at most the four grid columns, was ${anchor.maxX - anchor.minX}",
                )
                val topRow = section.model.nodes.minOf { it.centerY }
                assertTrue(
                    abs(anchor.topY - (topRow - talentNodeContainerHalfSideDp())) < 2f,
                    "$token ${section.index}: the header stands on the top row",
                )
            }
        }
        // the documented far outlier (Hunter, Lightning Reflexes) is in the section, not under its header
        val hunter = productState("HUNTER")
        val survival = sectioned(hunter).sections[2]
        val anchor = talentTabletHeaderAnchor(survival.model.nodes)!!
        assertTrue(survival.model.bounds.maxX > anchor.maxX + 1000f, "the section's bounds reach the outlier")
        assertNull(talentTabletHeaderAnchor(emptyList()), "an empty section has nothing to stand above")
    }

    @Test
    fun slotsNeverOverlapNorLeaveTheCanvasAtAnyCamera() {
        val metrics = TalentTabletSectionHeaderMetrics
        for (token in classes) {
            val state = productState(token)
            val anchors = sectioned(state).sections.map { talentTabletHeaderAnchor(it.model.nodes) }
            for (canvasWidth in listOf(460f, 644f, 900f)) {
                for (scale in listOf(TalentTreeCanvasMetrics.ZOOM_MIN, 0.8f, TalentInspectorMetrics.INITIAL_SCALE_TABLET, TalentTreeCanvasMetrics.ZOOM_MAX)) {
                    var x = -2600f
                    while (x <= 600f) {
                        val viewport = TalentTreeViewport(scale, Offset(x, 40f))
                        val slots = talentTabletHeaderSlots(anchors, viewport, canvasWidth)
                        val shown = slots.filterNotNull()
                        for (slot in shown) {
                            assertTrue(slot.left >= metrics.SIDE_GAP_DP - 0.01f && slot.right <= canvasWidth - metrics.SIDE_GAP_DP + 0.01f, "$token inside the canvas")
                            assertTrue(slot.right - slot.left >= metrics.MIN_WIDTH_DP, "$token readable width")
                        }
                        val sorted = shown.sortedBy { it.left }
                        for ((a, b) in sorted.zipWithNext()) {
                            assertTrue(a.right <= b.left + 0.01f, "$token $scale $x: neighbouring header columns never overlap")
                        }
                        // a tree on screen with room for a header always gets one
                        for ((i, anchor) in anchors.withIndex()) {
                            val left = anchor!!.minX * scale + x
                            val right = anchor.maxX * scale + x
                            if (left >= 0f && right <= canvasWidth) {
                                assertNotNull(slots[i], "$token $scale $x: the fully visible tree $i is named")
                            }
                            if (right <= 0f || left >= canvasWidth) assertNull(slots[i], "$token: a tree off screen has no header")
                        }
                        x += 37f
                    }
                }
            }
        }
    }

    @Test
    fun theHeaderStandsAboveTheTopRowAndMovesWithTheCamera() {
        val anchor = TalentTabletHeaderAnchor(minX = 100f, maxX = 392f, topY = 50f)
        val viewport = TalentTreeViewport(1.2f, Offset(-20f, 30f))
        val slot = talentTabletHeaderSlots(listOf(anchor), viewport, 900f).single()!!
        assertEquals(50f * 1.2f + 30f - TalentTabletSectionHeaderMetrics.GAP_DP, slot.bottom, 0.001f)
        assertEquals((100f + 392f) / 2f * 1.2f - 20f, slot.centerX, 0.001f)
        val panned = talentTabletHeaderSlots(listOf(anchor), viewport.panBy(Offset(15f, -7f)), 900f).single()!!
        assertEquals(slot.centerX + 15f, panned.centerX, 0.001f)
        assertEquals(slot.bottom - 7f, panned.bottom, 0.001f)
    }

    @Test
    fun theReserveCoversAHeaderAtEveryTextSize() {
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            val density = Density(1f, fontScale)
            val height = talentTabletHeaderHeightDp(density)
            assertEquals(20f * fontScale + 2f * TalentTabletSectionHeaderMetrics.VERTICAL_PADDING_DP, height, 0.01f)
            assertTrue(talentTabletHeaderReserveDp(density) > height + TalentTabletSectionHeaderMetrics.GAP_DP)
        }
    }

    // ------------------------------------------------------------------ camera room

    private val bounds = TalentTreeBounds(minX = 110f, minY = 260f, maxX = 1480f, maxY = 790f)

    @Test
    fun aZeroReserveIsExactlyTheExistingCamera() {
        for (layoutClass in TalentTreeLayoutClass.entries) {
            for (canvas in listOf(Size(900f, 748f), Size(460f, 300f))) {
                val scale = TalentTreeViewport.initialFor(bounds, canvas, layoutClass).scale
                val expectedY = if (bounds.height * scale <= canvas.height) {
                    canvas.height / 2f - bounds.centerY * scale
                } else {
                    TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP - bounds.minY * scale
                }
                assertEquals(expectedY, TalentTreeViewport.initialFor(bounds, canvas, layoutClass).offset.y)
                assertEquals(
                    TalentTreeViewport.initialFor(bounds, canvas, layoutClass),
                    TalentTreeViewport.initialFor(bounds, canvas, layoutClass, topReserveDp = 0f),
                )
            }
        }
        val moved = TalentTreeViewport(1f, Offset(-50f, -400f))
        val reference = TalentTreeViewport(1f, Offset(0f, 0f))
        assertEquals(
            moved.clampedTo(bounds, Size(900f, 748f), reference = reference),
            moved.clampedTo(bounds, Size(900f, 748f), reference = reference, contentTopReserveDp = 0f),
        )
    }

    @Test
    fun theTabletFitLeavesTheHeaderRoomAboveTheTrees() {
        val reserve = 46f
        val scale = TalentInspectorMetrics.INITIAL_SCALE_TABLET
        // fits: the tree and its header room are centred together
        val tall = Size(900f, 748f)
        val fit = TalentTreeViewport.initialFor(bounds, tall, TalentTreeLayoutClass.TABLET, reserve)
        val top = bounds.minY * scale + fit.offset.y
        val bottom = bounds.maxY * scale + fit.offset.y
        assertEquals(tall.height - bottom, top - reserve, 0.01f)
        assertTrue(top - reserve > 0f)
        // too tall: the tree starts below the room and the margin
        val short = Size(900f, 500f)
        val topAligned = TalentTreeViewport.initialFor(bounds, short, TalentTreeLayoutClass.TABLET, reserve)
        assertEquals(
            TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP + reserve,
            bounds.minY * scale + topAligned.offset.y,
            0.01f,
        )
    }

    @Test
    fun theClampKeepsTheHeaderRoomReachable() {
        val reserve = 46f
        val canvas = Size(900f, 748f)
        val field = TalentTreeCanvasMetrics.CAMERA_FIELD_DP
        // content that fits with its room: never pushed so high that the room leaves the canvas
        val fit = TalentTreeViewport.initialFor(bounds, canvas, TalentTreeLayoutClass.TABLET, reserve)
        val pushedUp = fit.panBy(Offset(0f, -600f)).clampedTo(bounds, canvas, reference = fit, contentTopReserveDp = reserve)
        assertTrue(bounds.minY * pushedUp.scale + pushedUp.offset.y - reserve >= field - 0.01f)
        // zoomed in (larger than the canvas): the room can be pulled fully into view, never further than the field
        val zoomed = TalentTreeViewport(1.6f, Offset(fit.offset.x, reserve - bounds.minY * 1.6f))
        val pulledDown = zoomed.panBy(Offset(0f, 2000f)).clampedTo(bounds, canvas, reference = zoomed, contentTopReserveDp = reserve)
        assertEquals(field, bounds.minY * pulledDown.scale + pulledDown.offset.y - reserve, 0.01f)
    }

    @Test
    fun theProgrammaticClampKeepsTheHeaderRoomLikeTheGestureClamp() {
        val reserve = 46f
        val canvas = Size(900f, 748f)
        val state = TalentTreeViewportState(initial = null)
        state.onCanvasSizeChanged(canvas, density = 1f)
        state.layoutClass = TalentTreeLayoutClass.TABLET
        state.contentTopReserveDp = reserve
        state.initializeOnce(bounds)
        val fit = state.viewport!!
        // a Show-style centring target that pulls the trees up against the canvas top
        val target = fit.panBy(Offset(0f, -300f))
        val clamped = state.clampTarget(target)
        assertEquals(
            target.clampedTo(bounds, canvas, reference = fit, contentTopReserveDp = reserve),
            clamped,
            "Show's clamp is the gesture clamp, header room included",
        )
        assertTrue(bounds.minY * clamped.scale + clamped.offset.y - reserve >= TalentTreeCanvasMetrics.CAMERA_FIELD_DP - 0.01f)
    }

    @Test
    fun theWholeTreeCameraFitsWithTheReserveItWasGiven() {
        val state = TalentTreeViewportState(initial = null)
        state.onCanvasSizeChanged(Size(900f, 748f), density = 1f)
        state.layoutClass = TalentTreeLayoutClass.TABLET
        state.contentTopReserveDp = 46f
        state.initializeOnce(bounds)
        assertEquals(TalentTreeViewport.initialFor(bounds, Size(900f, 748f), TalentTreeLayoutClass.TABLET, 46f), state.viewport)
        assertEquals(state.viewport, state.fitViewport())
    }
}
