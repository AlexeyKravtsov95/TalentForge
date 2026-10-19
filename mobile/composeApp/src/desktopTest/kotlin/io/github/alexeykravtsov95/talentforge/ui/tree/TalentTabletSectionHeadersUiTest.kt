package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M5.UI.TABLET.1 on the REAL tablet editor (product dataset with the Russian
 * overlay, density 1 so px = dp): every specialization tree is named above its
 * top row — Warrior and Paladin, English and Russian, on 840, 1024 and 1280 dp
 * wide tablets at 100 % and 200 % text — with the live points, anchored through
 * pan, zoom and FIT, beside (never under) the persistent inspector, across a
 * rotation, never taking a touch, and absent from every phone layout.
 */
@OptIn(ExperimentalTestApi::class)
class TalentTabletSectionHeadersUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val warrior by lazy { localizedProductState("WARRIOR") }
    private val paladin by lazy { localizedProductState("PALADIN") }

    private class Case(val state: TalentTreeUiState.Ready, val language: AppLanguage, val labels: List<String>, val points: List<Int>)

    private val cases by lazy {
        val warriorBuild = withSectionPoints(warrior, 13, 5, 0)
        val paladinBuild = withSectionPoints(paladin, 11, 0, 9)
        listOf(
            Case(warriorBuild, AppLanguage.ENGLISH, listOf("Arms", "Fury", "Protection"), listOf(13, 5, 0)),
            Case(warriorBuild, AppLanguage.RUSSIAN, listOf("Оружие", "Неистовство", "Защита"), listOf(13, 5, 0)),
            Case(paladinBuild, AppLanguage.ENGLISH, listOf("Holy", "Protection", "Retribution"), listOf(11, 0, 9)),
            Case(paladinBuild, AppLanguage.RUSSIAN, listOf("Свет", "Защита", "Воздаяние"), listOf(11, 0, 9)),
        )
    }

    /** 840, 1024 and 1280 dp wide tablets (landscape). */
    private val tablets = listOf(840 to 600, 1024 to 768, 1280 to 800)

    // ------------------------------------------------------------------ harness

    private class Window(width: Int, height: Int) {
        var width by mutableStateOf(width)
        var height by mutableStateOf(height)
    }

    private fun onEditor(
        state: TalentTreeUiState.Ready,
        width: Int,
        height: Int,
        language: AppLanguage = AppLanguage.ENGLISH,
        fontScale: Float = 1f,
        window: Window = Window(width, height),
        block: ComposeUiTest.(TalentTreeViewportState) -> Unit,
    ) = runDesktopComposeUiTest(width = 1280, height = 1366) {
        uiLanguage.language = language
        val camera = TalentTreeViewportState(initial = null)
        val sectionCameras = TalentTreeSectionCameras(sessionKey = "tablet1", treeId = state.model.treeId)
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalReducedMotion provides true,
                    LocalAppLanguage provides language,
                ) {
                    Box(Modifier.size(window.width.dp, window.height.dp)) {
                        TalentTreeScreen(state, viewportState = camera, sectionCameras = sectionCameras)
                    }
                }
            }
        }
        settle()
        block(camera)
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(900)
        waitForIdle()
    }

    private fun sections(state: TalentTreeUiState.Ready) =
        buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned

    /** The bounds of section [index]'s header when it is placed on screen (a header without room is not placed). */
    private fun ComposeUiTest.headerBounds(index: Int): Rect? =
        onAllNodes(hasTestTag(talentTabletSectionHeaderTag(index))).fetchSemanticsNodes()
            .firstOrNull { it.layoutInfo.isPlaced }
            ?.boundsInRoot

    private fun ComposeUiTest.anyHeaderShown(state: TalentTreeUiState.Ready): Boolean =
        sections(state).sections.any { headerBounds(it.index) != null }

    /** The canvas rectangle in the root: left edge at 0, the inspector beside it, down to the window bottom. */
    private fun canvasRect(camera: TalentTreeViewportState, windowHeight: Int): Rect {
        val size = camera.canvasSize
        return Rect(0f, windowHeight - size.height, size.width, windowHeight.toFloat())
    }

    /** Pans [camera] so section [ordinal]'s tree is centred horizontally (a real, clamped user transform). */
    private fun ComposeUiTest.centre(camera: TalentTreeViewportState, state: TalentTreeUiState.Ready, ordinal: Int) {
        val anchor = talentTabletHeaderAnchor(sections(state).sections[ordinal].model.nodes)!!
        runOnIdle {
            val viewport = camera.viewport!!
            val centre = (anchor.minX + anchor.maxX) / 2f * viewport.scale + viewport.offset.x
            camera.applyUserTransform(viewport.panBy(Offset(camera.canvasSize.width / 2f - centre, 0f)))
        }
        settle()
    }

    /**
     * Every header that is shown stands on its own tree under the live camera: bottom just
     * above its top row, horizontally over its own tree, inside the canvas (never under the
     * inspector), clear of every talent and of every other header — with [labels] / [points]
     * when given. Returns the section indices shown.
     */
    private fun ComposeUiTest.assertHeadersAnchored(
        state: TalentTreeUiState.Ready,
        camera: TalentTreeViewportState,
        windowHeight: Int,
        labels: List<String>? = null,
        points: List<Int>? = null,
    ): Set<Int> {
        val canvas = canvasRect(camera, windowHeight)
        val viewport = camera.viewport!!
        val shown = mutableMapOf<Int, Rect>()
        for ((ordinal, section) in sections(state).sections.withIndex()) {
            val bounds = headerBounds(section.index) ?: continue
            shown[section.index] = bounds
            if (labels != null && points != null) {
                onNodeWithTag(talentTabletSectionHeaderTag(section.index))
                    .assertTextEquals(labels[ordinal], points[ordinal].toString())
            }
            val anchor = talentTabletHeaderAnchor(section.model.nodes)!!
            val treeTop = canvas.top + anchor.topY * viewport.scale + viewport.offset.y
            val treeLeft = canvas.left + anchor.minX * viewport.scale + viewport.offset.x
            val treeRight = canvas.left + anchor.maxX * viewport.scale + viewport.offset.x
            assertEquals(treeTop - TalentTabletSectionHeaderMetrics.GAP_DP, bounds.bottom, 1f, "header $ordinal sits just above its top row")
            assertTrue(bounds.right > treeLeft && bounds.left < treeRight, "header $ordinal is over its own tree: $bounds vs $treeLeft..$treeRight")
            assertTrue(bounds.left >= canvas.left - 0.5f && bounds.right <= canvas.right + 0.5f, "header $ordinal inside the canvas $canvas: $bounds")
            val half = TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f * viewport.scale
            for (node in state.model.nodes) {
                val x = canvas.left + node.centerX * viewport.scale + viewport.offset.x
                val y = canvas.top + node.centerY * viewport.scale + viewport.offset.y
                assertFalse(bounds.overlaps(Rect(x - half, y - half, x + half, y + half)), "header $ordinal never covers talent ${node.name}")
            }
        }
        val rects = shown.values.toList()
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) assertFalse(rects[i].overlaps(rects[j]), "headers never overlap: ${rects[i]} / ${rects[j]}")
        }
        return shown.keys
    }

    private fun Rect.overlaps(other: Rect): Boolean =
        intersect(other).let { it.width > 0.5f && it.height > 0.5f }

    // ------------------------------------------------------------------ names and points

    @Test
    fun everySpecializationTreeIsNamedOnEveryTabletInBothLanguages() {
        for (case in cases) {
            for ((width, height) in tablets) {
                onEditor(case.state, width, height, case.language) { camera ->
                    // the fit: every header on screen is whole, and the first tree is named
                    val atFit = assertHeadersAnchored(case.state, camera, height, case.labels, case.points)
                    assertTrue(sections(case.state).sections.first().index in atFit, "$width: the first tree is named at the fit")
                    for (index in atFit) {
                        assertTrue(headerBounds(index)!!.top >= canvasRect(camera, height).top - 0.5f, "a fitted header is never cut")
                    }
                    // each tree in turn: panned to the centre, it is named
                    val named = mutableSetOf<Int>()
                    for (ordinal in 0..2) {
                        centre(camera, case.state, ordinal)
                        named += assertHeadersAnchored(case.state, camera, height, case.labels, case.points)
                    }
                    assertEquals(sections(case.state).sections.map { it.index }.toSet(), named, "${case.labels} on $width × $height")
                }
            }
        }
    }

    @Test
    fun atTwoHundredPercentTheNamesStayWholeAndClear() {
        for (case in cases) {
            for ((width, height) in listOf(840 to 600, 1280 to 800)) {
                onEditor(case.state, width, height, case.language, fontScale = 2f) { camera ->
                    for ((ordinal, section) in sections(case.state).sections.withIndex()) {
                        centre(camera, case.state, ordinal)
                        assertHeadersAnchored(case.state, camera, height, case.labels, case.points)
                        val label = case.labels[ordinal]
                        val text = onNode(
                            hasText(label) and hasAnyAncestor(hasTestTag(talentTabletSectionHeaderTag(section.index))),
                            useUnmergedTree = true,
                        ).fetchSemanticsNode()
                        val layouts = mutableListOf<TextLayoutResult>()
                        text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                        // whole: one line, no ellipsis, nothing cut vertically (a sub-pixel width rounding is not a cut)
                        val layout = layouts.single()
                        assertEquals(1, layout.lineCount, "'$label' on one line")
                        assertFalse(layout.isLineEllipsized(0), "'$label' is not ellipsized at 200 % on $width dp")
                        assertFalse(layout.didOverflowHeight, "'$label' is not cut vertically")
                        assertTrue(
                            layout.multiParagraph.intrinsics.maxIntrinsicWidth - layout.size.width < 1f,
                            "'$label' fits its width: ${layout.multiParagraph.intrinsics.maxIntrinsicWidth} in ${layout.size.width}",
                        )
                        assertEquals(
                            2f * 20f + 2f * TalentTabletSectionHeaderMetrics.VERTICAL_PADDING_DP,
                            headerBounds(section.index)!!.height,
                            1f,
                            "the header grows with the text",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun thePointsFollowEverySpendAndUndo() {
        val arms = talentTabletSectionHeaderTag(sections(warrior).sections.first().index)
        onEditor(warrior, 1280, 800) { camera ->
            onNodeWithTag(arms).assertTextEquals("Arms", "0")
            onNodeWithContentDescription(WarriorText.REND_EN).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithContentDescription("Add rank to ${WarriorText.REND_EN}").performClick()
            settle()
            onNodeWithTag(arms).assertTextEquals("Arms", "1")
            onNodeWithContentDescription("Add rank to ${WarriorText.REND_EN}").performClick()
            settle()
            onNodeWithTag(arms).assertTextEquals("Arms", "2")
            onNodeWithContentDescription("Undo talent change").performClick()
            settle()
            onNodeWithTag(arms).assertTextEquals("Arms", "1")
            assertEquals("1 point spent", onNodeWithTag(arms).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
            assertHeadersAnchored(warrior, camera, 800)
        }
    }

    // ------------------------------------------------------------------ camera

    @Test
    fun theHeadersFollowPanZoomAndFit() {
        val state = withSectionPoints(warrior, 13, 5, 0)
        val arms = sections(state).sections.first().index
        onEditor(state, 1280, 800) { camera ->
            val fit = camera.viewport!!
            val atFit = headerBounds(arms)!!
            assertHeadersAnchored(state, camera, 800)

            // pan: the header moves exactly with the tree
            runOnIdle { camera.applyUserTransform(fit.panBy(Offset(-60f, 40f))) }
            settle()
            val panned = camera.viewport!!
            val moved = headerBounds(arms)!!
            assertEquals(atFit.center.x + (panned.offset.x - fit.offset.x), moved.center.x, 1f)
            assertEquals(atFit.bottom + (panned.offset.y - fit.offset.y), moved.bottom, 1f)
            assertHeadersAnchored(state, camera, 800)

            // a real drag on the canvas pans; the header keeps standing on its tree
            onRoot().performTouchInput { swipe(Offset(450f, 600f), Offset(350f, 640f), durationMillis = 200) }
            settle()
            assertNotEquals(panned, camera.viewport, "the drag panned the canvas")
            assertHeadersAnchored(state, camera, 800)

            // zoom out and in: the text keeps its size, the header keeps its tree
            for (scale in listOf(TalentTreeCanvasMetrics.ZOOM_MIN, 1.4f, TalentTreeCanvasMetrics.ZOOM_MAX)) {
                runOnIdle { camera.applyUserTransform(camera.viewport!!.zoomAt(Offset(200f, 200f), scale)) }
                settle()
                assertHeadersAnchored(state, camera, 800)
                headerBounds(arms)?.let { assertEquals(atFit.height, it.height, 0.5f, "readable at zoom $scale") }
            }
            runOnIdle { camera.applyUserTransform(TalentTreeViewport(TalentTreeCanvasMetrics.ZOOM_MIN, Offset(10f, 60f))) }
            settle()
            assertEquals(
                sections(state).sections.map { it.index }.toSet(),
                assertHeadersAnchored(state, camera, 800),
                "zoomed out: all three trees named at once",
            )

            // FIT: back to the fit camera, the headers whole above their trees again
            runOnIdle { camera.requestFit() }
            settle()
            assertEquals(fit, camera.viewport)
            val refitted = headerBounds(arms)!!
            assertEquals(atFit.left, refitted.left, 0.5f)
            assertEquals(atFit.top, refitted.top, 0.5f)
            assertHeadersAnchored(state, camera, 800)
        }
    }

    /**
     * Regression (PR #56 review): Show centres its target through the camera's
     * programmatic clamp (`clampTarget`), which must keep the header room as the
     * gesture clamp does. Protection is off screen at the 1280 dp fit; Show from
     * Shield Slam (tier 7) centres Concussion Blow (tier 5), which pulls the tree
     * up against the canvas top — its header must stay whole.
     */
    @Test
    fun showKeepsTheHeaderOfTheTargetTreeWhole() {
        val protection = sections(warrior).sections[2].index
        onEditor(warrior, 1280, 800) { camera ->
            val fit = camera.viewport!!
            onNodeWithContentDescription("Shield Slam").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNode(hasText(EditorCopy.English.show)).performClick()
            settle()
            onNodeWithContentDescription("Concussion Blow").assertIsSelected()
            assertNotEquals(fit, camera.viewport, "Show moved the camera to the off-screen target")
            val canvas = canvasRect(camera, 800)
            val header = headerBounds(protection)
            assertTrue(header != null, "the target tree is named")
            assertTrue(header.top >= canvas.top - 0.5f, "Show never pushes the target tree's header off the canvas: $header in $canvas")
            assertTrue(protection in assertHeadersAnchored(warrior, camera, 800))
            // the target itself is brought on screen, as before
            val node = warrior.model.nodes.first { it.name == "Concussion Blow" }
            val viewport = camera.viewport!!
            val x = node.centerX * viewport.scale + viewport.offset.x
            assertTrue(x in 0f..camera.canvasSize.width, "the Show target is on screen")
        }
    }

    @Test
    fun theHeadersNeverTakeATouch() {
        val state = withSectionPoints(warrior, 13, 5, 0)
        val arms = talentTabletSectionHeaderTag(sections(state).sections.first().index)
        val selected = SemanticsMatcher("selected talent") { it.config.getOrNull(SemanticsProperties.Selected) == true }
        onEditor(state, 1280, 800) { camera ->
            onNodeWithContentDescription(WarriorText.DEEP_WOUNDS_EN).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithContentDescription(WarriorText.DEEP_WOUNDS_EN).assertIsSelected()
            // a tap on the header reaches the canvas below: an empty-canvas tap deselects
            onNodeWithTag(arms).performTouchInput { click(center) }
            settle()
            assertTrue(onAllNodes(selected).fetchSemanticsNodes().isEmpty(), "the tap went to the canvas (deselect), not to the header")
            // a drag that starts on the header pans the canvas
            val before = camera.viewport!!
            onNodeWithTag(arms).performTouchInput { swipe(center, center + Offset(-120f, 60f), durationMillis = 200) }
            settle()
            assertNotEquals(before, camera.viewport, "the drag from the header panned the canvas")
            assertHeadersAnchored(state, camera, 800)
        }
    }

    // ------------------------------------------------------------------ inspector, rotation, phones

    @Test
    fun theOpenInspectorNeverCoversAHeader() {
        val state = withSectionPoints(warrior, 13, 5, 0)
        for ((width, height) in tablets) {
            onEditor(state, width, height) { camera ->
                onNodeWithContentDescription(WarriorText.DEEP_WOUNDS_EN).performSemanticsAction(SemanticsActions.OnClick)
                settle()
                onNodeWithContentDescription(WarriorText.DEEP_WOUNDS_EN).assertIsSelected()
                val inspectorLeft = width - TalentInspectorMetrics.TABLET_PANEL_WIDTH_DP.value
                assertEquals(inspectorLeft, camera.canvasSize.width, 0.5f, "the canvas ends where the inspector starts")
                for (ordinal in 0..2) {
                    centre(camera, state, ordinal)
                    // pushed towards the inspector, the tree keeps its whole header beside it
                    runOnIdle { camera.applyUserTransform(camera.viewport!!.panBy(Offset(camera.canvasSize.width / 3f, 0f))) }
                    settle()
                    assertHeadersAnchored(state, camera, height)
                    for (section in sections(state).sections) {
                        headerBounds(section.index)?.let { assertTrue(it.right <= inspectorLeft + 0.5f, "$width: header beside the inspector") }
                    }
                }
            }
        }
    }

    @Test
    fun rotatingKeepsEveryHeaderOnItsTree() {
        val state = withSectionPoints(paladin, 11, 0, 9)
        val window = Window(1280, 800)
        onEditor(state, 1280, 800, window = window) { camera ->
            assertTrue(assertHeadersAnchored(state, camera, 800).isNotEmpty())
            // landscape → portrait tablet (still the tablet layout: 1024 ≥ 840 dp)
            window.width = 1024
            window.height = 1366
            settle()
            assertTrue(assertHeadersAnchored(state, camera, 1366).isNotEmpty(), "portrait tablet: named")
            // a narrower portrait window is a phone: its section tabs, no tablet header
            window.width = 800
            window.height = 1280
            settle()
            assertFalse(anyHeaderShown(state), "the phone layout shows its tabs only")
            assertTrue(onAllNodes(hasText("Holy")).fetchSemanticsNodes().isNotEmpty(), "the Holy tab")
            // and back
            window.width = 1280
            window.height = 800
            settle()
            assertTrue(assertHeadersAnchored(state, camera, 800).isNotEmpty(), "back on the tablet: named again")
        }
    }

    @Test
    fun phonesKeepTheirTabsAndShowNoTabletHeader() {
        val state = withSectionPoints(warrior, 13, 5, 0)
        for ((width, height) in listOf(360 to 800, 393 to 852, 640 to 360, 915 to 412)) {
            onEditor(state, width, height) { camera ->
                assertFalse(anyHeaderShown(state), "$width × $height: no tablet header")
                assertTrue(onAllNodes(hasText("Arms")).fetchSemanticsNodes().isNotEmpty(), "$width × $height: the Arms tab")
                assertEquals(0f, camera.contentTopReserveDp, "the whole-tree camera keeps no header room on a phone")
            }
        }
    }
}
