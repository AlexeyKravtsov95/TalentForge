package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import org.junit.Rule

/**
 * M5.UX.2a on the REAL screen (real datasets, icons, descriptions; density 1,
 * so px = dp): the compact strip in all seven states and the adaptive header
 * at 320 × 568, 360 × 800 and 393 × 852 with 100 / 130 / 200 % text, the
 * content-sized sheet with a short and a long description, and the camera,
 * Show, undo/redo, autosave and tab invariants around the inspector.
 */
@OptIn(ExperimentalTestApi::class)
class TalentInspectorAdaptiveUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private class Session(val cameras: TalentTreeSectionCameras, val reported: MutableList<BuildState>)

    /** Composes [state] on a [width] × [height] dp phone with [fontScale] text. */
    private fun onPhone(
        state: TalentTreeUiState.Ready,
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        block: ComposeUiTest.(Session) -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        val session = Session(
            cameras = TalentTreeSectionCameras(sessionKey = "adaptive", treeId = state.model.treeId),
            reported = mutableListOf(),
        )
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        TalentTreeScreen(
                            state,
                            onBack = {},
                            onBuildStateChanged = { session.reported += it },
                            sectionCameras = session.cameras,
                        )
                    }
                }
            }
        }
        settle()
        block(session)
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(900)
        waitForIdle()
    }

    /** Selects a talent through its node's semantic click (works off-screen and under the strip). */
    private fun ComposeUiTest.select(name: String) {
        onNodeWithContentDescription(name).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun ComposeUiTest.expand() {
        onNodeWithContentDescription(EditorCopy.English.expand).performClick()
        settle()
    }

    private fun ComposeUiTest.bounds(matcher: SemanticsMatcher): Rect =
        onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.boundsOrNull(matcher: SemanticsMatcher): Rect? =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun Rect.overlaps(other: Rect): Boolean = intersect(other).let { it.width > 0.5f && it.height > 0.5f }

    private fun Rect.within(outer: Rect, tolerance: Float = 0.5f): Boolean =
        left >= outer.left - tolerance && top >= outer.top - tolerance &&
            right <= outer.right + tolerance && bottom <= outer.bottom + tolerance

    private fun points(state: TalentTreeUiState.Ready, spent: Int) = "$spent / ${state.engine.pointBudget}"

    private val phones = listOf(320 to 568, 360 to 800, 393 to 852)
    private val fontScales = listOf(1f, 1.3f, 2f)

    // ------------------------------------------------------------------ compact strip

    @Test
    fun everyCompactStateFitsWithItsRealReasonOnEveryPhoneAndTextSize() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        assertEquals(7, scenarios.size, "all seven compact states exist: ${scenarios.keys}")
        for ((w, h) in phones) {
            for (scale in fontScales) {
                var current by mutableStateOf(scenarios.values.first())
                runDesktopComposeUiTest(width = w, height = h) {
                    setContent {
                        TalentForgeTheme {
                            CompositionLocalProvider(LocalDensity provides Density(1f, scale), LocalAppLanguage provides AppLanguage.ENGLISH) {
                                Box(Modifier.size(w.dp, h.dp)) {
                                    // a fresh screen per scenario (the editing session is keyed by engine)
                                    key(current) {
                                        TalentTreeScreen(
                                            warrior.copy(buildState = current.build),
                                            onBack = {},
                                            sectionCameras = TalentTreeSectionCameras("compact", warrior.model.treeId),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val screen = Rect(0f, 0f, w.toFloat(), h.toFloat())
                    for ((label, scenario) in scenarios) {
                        current = scenario
                        settle()
                        select(scenario.nodeName)
                        val where = "$label ${scenario.nodeName} at ${w}x$h ${(scale * 100).toInt()}%"
                        val strip = bounds(hasTestTag(TALENT_INSPECTOR_COMPACT_TAG))
                        assertTrue(strip.within(screen), "strip inside the screen: $where $strip")
                        assertTrue(strip.height >= TalentInspectorMetrics.COMPACT_STRIP_DP.value - 0.5f, where)

                        // the reason is the engine's own wording, shown in words
                        val (content, _) = inspectorInputsOf(warrior, scenario.build, scenario.nodeName)
                        val reason = EditorCopy.English.textOf(
                            assertNotNull(content.prerequisiteLine ?: content.tierLine ?: content.stateLine),
                        )
                        onNode(hasTestTag(TALENT_INSPECTOR_REASON_TAG) and hasText(reason), useUnmergedTree = true)
                            .assertIsDisplayed()
                        val reasonRect = bounds(hasTestTag(TALENT_INSPECTOR_REASON_TAG))
                        val name = bounds(hasText(scenario.nodeName) and !hasTestTag(TALENT_INSPECTOR_REASON_TAG))
                        assertTrue(reasonRect.within(strip) && name.within(strip), "texts inside the strip: $where")
                        assertTrue(!reasonRect.overlaps(name), "name and reason overlap: $where")

                        // every action present is inside the strip, a real target, and overlaps no text
                        val actions = listOf(
                            hasContentDescription("Add rank to ${scenario.nodeName}"),
                            hasContentDescription("Remove rank from ${scenario.nodeName}"),
                            hasContentDescription("Max rank reached"),
                            hasContentDescription("Tier points", substring = true),
                        ).mapNotNull { boundsOrNull(it) } +
                            // Show: the clickable button itself (merged tree), not its inner text
                            listOfNotNull(
                                onAllNodes(hasText("Show") and hasClickAction()).fetchSemanticsNodes()
                                    .firstOrNull()?.boundsInRoot,
                            )
                        assertTrue(actions.isNotEmpty(), "the state offers its 1A v2 action: $where")
                        for (action in actions) {
                            assertTrue(action.within(strip), "action inside the strip: $where $action")
                            assertTrue(action.height >= 44f - 0.5f, "action ≥ 44 dp tall: $where $action")
                            assertTrue(!action.overlaps(name) && !action.overlaps(reasonRect), "action over text: $where")
                        }
                        for ((i, a) in actions.withIndex()) {
                            for (b in actions.drop(i + 1)) assertTrue(!a.overlaps(b), "actions overlap: $where")
                        }
                        onNodeWithContentDescription(EditorCopy.English.expand).assertIsDisplayed()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ header

    @Test
    fun headerKeepsEveryControlReachableWithoutOverlapOnEveryPhoneAndTextSize() {
        val warrior = productState("WARRIOR")
        val state = warrior.copy(
            buildState = scenarioOf(warrior, SHORT_DESCRIPTION_TALENT, spend = 3).build,
            buildTitle = "Arms PvP leveling build",
        )
        for ((w, h) in phones) {
            for (scale in fontScales) {
                onPhone(state, w, h, scale) {
                    val where = "${w}x$h ${(scale * 100).toInt()}%"
                    val screen = Rect(0f, 0f, w.toFloat(), h.toFloat())
                    val title = bounds(hasTestTag(TALENT_TREE_HEADER_TITLE_TAG))
                    val counter = bounds(hasTestTag(TALENT_TREE_HEADER_POINTS_TAG))
                    onNode(hasTestTag(TALENT_TREE_HEADER_POINTS_TAG) and hasText(points(state, 3)), useUnmergedTree = true)
                        .assertIsDisplayed()
                    val controls = listOf("go back", "Undo talent change", "Redo talent change", "open build actions")
                        .map { label -> label to bounds(hasContentDescription(label)) }
                    for ((label, rect) in controls) {
                        assertTrue(rect.within(screen), "$label inside the screen: $where")
                        assertTrue(rect.width >= 47.5f && rect.height >= 47.5f, "$label is 48 × 48: $where $rect")
                        assertTrue(!rect.overlaps(title), "$label overlaps the title: $where")
                        assertTrue(!rect.overlaps(counter), "$label overlaps the counter: $where")
                    }
                    for ((i, a) in controls.withIndex()) {
                        for (b in controls.drop(i + 1)) {
                            assertTrue(!a.second.overlaps(b.second), "${a.first} / ${b.first} overlap: $where")
                        }
                    }
                    assertTrue(counter.within(screen), "counter inside the screen: $where")
                    assertEquals(
                        talentTreeHeaderLayout(w.dp, scale) == TalentTreeHeaderLayout.POINTS_IN_META,
                        counter.within(title),
                        "1A v2 S1: the counter sits in the meta line exactly when the rule says so: $where",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ expanded sheet

    @Test
    fun aShortDescriptionGetsAContentSizedSheetWithoutEmptySpace() {
        val warrior = productState("WARRIOR")
        val case = scenarioOf(warrior, SHORT_DESCRIPTION_TALENT, spend = 1)
        for ((w, h) in phones) {
            for (scale in listOf(1f, 1.3f)) {
                onPhone(warrior.copy(buildState = case.build), w, h, scale) {
                    select(case.nodeName)
                    expand()
                    val where = "${w}x$h ${(scale * 100).toInt()}%"
                    val sheet = bounds(hasTestTag(TALENT_INSPECTOR_SHEET_TAG))
                    val detail = bounds(hasTestTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG))
                    val actions = bounds(hasTestTag(TALENT_INSPECTOR_SHEET_ACTIONS_TAG))
                    val floor = TalentInspectorMetrics.EXPANDED_SHEET_MIN_DP.value
                    assertTrue(sheet.height < 448f, "no longer the fixed 448 dp: $where ${sheet.height}")
                    assertTrue(sheet.height >= floor - 0.5f, "1A v2: at least 260 dp: $where")
                    // no empty band: the actions follow the detail directly, unless the 260 dp floor applies
                    val gap = actions.top - detail.bottom
                    assertTrue(
                        gap <= 0.5f || abs(sheet.height - floor) <= 0.5f,
                        "empty space between the description and the actions: $gap dp at $where",
                    )
                    assertTrue(actions.within(sheet), "the actions are pinned inside the sheet: $where")
                    val range = onNodeWithTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG, useUnmergedTree = true)
                        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    assertEquals(0f, range?.maxValue?.invoke() ?: 0f, "a short description does not scroll: $where")
                }
            }
        }
    }

    @Test
    fun aLongDescriptionScrollsInsideTheSheetWhileTheActionsStayUsable() {
        val druid = productState("DRUID")
        val case = unlockedScenario(druid, LONG_DESCRIPTION_TALENT)
        // overflow is required only where it holds on every platform's fonts (320 dp, 200 %);
        // 393 × 852 at 130 % fits on macOS fonts but scrolls on Linux ones
        val cases = listOf(
            Triple(320, 568, 1f) to true,
            Triple(320, 568, 2f) to true,
            Triple(393, 852, 2f) to true,
            Triple(393, 852, 1.3f) to false,
        )
        for ((size, mustScroll) in cases) {
            val (w, h, scale) = size
            onPhone(druid.copy(buildState = case.build), w, h, scale) { session ->
                select(case.nodeName)
                expand()
                val where = "${w}x$h ${(scale * 100).toInt()}%"
                val sheet = bounds(hasTestTag(TALENT_INSPECTOR_SHEET_TAG))
                // H − top = the canvas region below the header and the tabs
                val area = session.cameras.stateOf(0).canvasSize.height
                val max = minOf(h * TalentInspectorMetrics.EXPANDED_SHEET_MAX_SCREEN_FRACTION, area - 104f)
                assertTrue(sheet.height <= max + 1f, "never above the 1A v2 maximum: $where ${sheet.height} > $max")
                val scroll = onNodeWithTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG, useUnmergedTree = true)
                val range = assertNotNull(
                    scroll.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange),
                )
                val overflows = range.maxValue() > 0f
                if (mustScroll) assertTrue(overflows, "the description scrolls inside the sheet: $where")
                if (overflows) {
                    assertEquals(max, sheet.height, 1f, "an overflowing sheet takes its 1A v2 maximum: $where")
                    scroll.performTouchInput { swipeUp() }
                    settle()
                    assertTrue(range.value() > 0f, "scrolled: $where")
                }
                val add = onNodeWithContentDescription("Add rank to ${case.nodeName}")
                add.assertIsDisplayed().assertIsEnabled()
                assertTrue(bounds(hasContentDescription("Add rank to ${case.nodeName}")).within(sheet), where)
                add.performClick()
                settle()
                assertEquals(1, session.reported.size, "the purchase reached the ONE change path: $where")
                onNodeWithContentDescription("Max rank reached").assertIsDisplayed()
            }
        }
    }

    @Test
    fun expandingAndCollapsingKeepTheSelection() {
        val warrior = productState("WARRIOR")
        val case = scenarioOf(warrior, SHORT_DESCRIPTION_TALENT, spend = 1)
        onPhone(warrior.copy(buildState = case.build), 360, 800) { session ->
            select(case.nodeName)
            expand()
            onNodeWithContentDescription(case.nodeName).assertIsSelected()
            onNodeWithContentDescription(EditorCopy.English.collapse).performClick()
            settle()
            onNodeWithContentDescription(case.nodeName).assertIsSelected()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertIsDisplayed()
            assertTrue(session.reported.isEmpty(), "expand/collapse never reaches autosave")
        }
    }

    // ------------------------------------------------------------------ camera

    @Test
    fun theInspectorUsesItsMeasuredHeightAndNeverResetsZoom() {
        val warrior = productState("WARRIOR")
        val sections = buildTalentTreeSections(warrior.engine.tree, warrior.model) as TalentTreeSectionsResult.Sectioned
        val section = sections.sections.first()
        val bottomNode = section.model.nodes.maxBy { it.centerY }
        onPhone(warrior, 360, 800) { session ->
            val camera = session.cameras.stateOf(section.index)
            // a user zoom first (1.4×, inside 0.62–1.6) — it must survive everything below
            runOnIdle {
                val viewport = assertNotNull(camera.viewport)
                camera.applyUserTransform(viewport.zoomAt(Offset(180f, 200f), 1.4f))
            }
            settle()
            val zoomed = assertNotNull(camera.viewport)
            assertTrue(camera.userMoved)
            // a talent the user can see near the top of the zoomed camera: opening and
            // expanding cover nothing → the camera stays still
            val clearance = TalentInspectorMetrics.FOCUS_CLEARANCE_DP
            val topNode = section.model.nodes
                .map { it to zoomed.worldToScreen(Offset(it.centerX, it.centerY)) }
                .filter { (_, p) -> p.x in clearance..(camera.canvasSize.width - clearance) && p.y >= clearance + 8f }
                .minBy { (_, p) -> p.y }
                .first
            select(topNode.name!!)
            assertEquals(zoomed, camera.viewport, "nothing covered → no camera move")
            expand()
            assertEquals(zoomed, camera.viewport, "nothing covered → no camera move")
            val sheet = bounds(hasTestTag(TALENT_INSPECTOR_SHEET_TAG))
            assertEquals(sheet.height, camera.coveredBottomDp, 0.5f, "covered = the MEASURED sheet")

            // the deepest talent: expanding covers it → minimal reveal above the measured sheet, same scale
            onNodeWithText("×").performClick()
            settle()
            assertEquals(0f, camera.coveredBottomDp, "closed → nothing covered")
            select(bottomNode.name!!)
            val strip = bounds(hasTestTag(TALENT_INSPECTOR_COMPACT_TAG))
            assertEquals(strip.height, camera.coveredBottomDp, 0.5f, "covered = the MEASURED strip")
            expand()
            val after = assertNotNull(camera.viewport)
            assertEquals(zoomed.scale, after.scale, "the scale is never touched")
            val sheetNow = bounds(hasTestTag(TALENT_INSPECTOR_SHEET_TAG))
            assertEquals(sheetNow.height, camera.coveredBottomDp, 0.5f)
            val screenY = after.worldToScreen(Offset(bottomNode.centerX, bottomNode.centerY)).y
            assertTrue(
                screenY <= camera.canvasSize.height - camera.coveredBottomDp -
                    TalentInspectorMetrics.FOCUS_CLEARANCE_DP + 0.5f,
                "the selected talent is visible above the real sheet: y=$screenY",
            )
            onNodeWithContentDescription(bottomNode.name!!).assertIsSelected()

            // collapsing never moves the camera (B3 08); FIT still returns to the fit
            onNodeWithContentDescription(EditorCopy.English.collapse).performClick()
            settle()
            assertEquals(after, camera.viewport, "collapsing never moves the camera")
            assertTrue(camera.isAwayFromFit)
            onNodeWithContentDescription("Fit tree").performClick()
            settle()
            assertEquals(camera.fitViewport(), camera.viewport, "FIT still works")
            assertTrue(session.reported.isEmpty(), "the camera never reaches autosave")
        }
    }

    @Test
    fun closingTheInspectorLeavesTheTreeUsable() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        val available = scenarios.getValue("available")
        val other = scenarios.getValue("tier")
        onPhone(warrior, 320, 568) { session ->
            select(available.nodeName)
            expand()
            onNodeWithText("×").performClick()
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertDoesNotExist()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertDoesNotExist()
            assertEquals(0f, session.cameras.stateOf(0).coveredBottomDp)
            select(other.nodeName)
            onNodeWithContentDescription(other.nodeName).assertIsSelected()
            onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).assertIsDisplayed()
        }
    }

    // ------------------------------------------------------------------ Show, history, tabs, purchases

    @Test
    fun showPrerequisiteStillNavigatesOneHopFromTheStripAndTheSheet() {
        val warrior = productState("WARRIOR")
        val locked = compactScenarios(warrior).getValue("prerequisite")
        val (content, _) = inspectorInputsOf(warrior, locked.build, locked.nodeName)
        val target = assertNotNull(assertNotNull(content.showAction).prerequisiteName.published)
        for (fromSheet in listOf(false, true)) {
            onPhone(warrior.copy(buildState = locked.build), 320, 568) { session ->
                select(locked.nodeName)
                if (fromSheet) expand()
                onNodeWithText("Show").performClick()
                settle()
                onNodeWithContentDescription(target).assertIsSelected()
                onNode(hasTestTag(TALENT_INSPECTOR_COMPACT_TAG)).assertIsDisplayed()
                onNode(hasText(target) and !hasContentDescription(target), useUnmergedTree = true).assertExists()
                assertTrue(session.reported.isEmpty(), "Show never spends (from sheet = $fromSheet)")
            }
        }
    }

    @Test
    fun undoAndRedoKeepTheirHistoryAcrossExpandAndCollapse() {
        val warrior = productState("WARRIOR")
        fun counter(spent: Int) = hasTestTag(TALENT_TREE_HEADER_POINTS_TAG) and hasText(points(warrior, spent))
        onPhone(warrior, 360, 800) { session ->
            select(SHORT_DESCRIPTION_TALENT)
            onNodeWithContentDescription("Add rank to $SHORT_DESCRIPTION_TALENT").performClick()
            settle()
            expand()
            onNodeWithContentDescription("Add rank to $SHORT_DESCRIPTION_TALENT").performClick()
            settle()
            onNodeWithContentDescription(EditorCopy.English.collapse).performClick()
            settle()
            onNode(counter(2), useUnmergedTree = true).assertExists()
            onNodeWithContentDescription("Undo talent change").performClick()
            settle()
            onNode(counter(1), useUnmergedTree = true).assertExists()
            expand()
            onNodeWithContentDescription("Redo talent change").performClick()
            settle()
            onNode(counter(2), useUnmergedTree = true).assertExists()
            onNodeWithContentDescription("Undo talent change").performClick()
            settle()
            onNodeWithContentDescription("Undo talent change").performClick()
            settle()
            onNode(counter(0), useUnmergedTree = true).assertExists()
            assertEquals(6, session.reported.size, "spend, spend, undo, redo, undo, undo")
            assertEquals(warrior.engine.emptyState(), session.reported.last())
        }
    }

    @Test
    fun atTwoHundredPercentTheTabsShowTheirNumberAndKeepTheirName() {
        val warrior = productState("WARRIOR")
        onPhone(warrior, 320, 568, fontScale = 2f) { session ->
            // 1A v2 S8: "вкладки только «N» и очки" — the label stays the accessible name
            onNodeWithText("Fury").assertDoesNotExist()
            onNode(hasContentDescription("Fury") and hasText("2", substring = true)).assertIsDisplayed().performClick()
            settle()
            val second = (buildTalentTreeSections(warrior.engine.tree, warrior.model) as TalentTreeSectionsResult.Sectioned)
                .sections[1].index
            assertEquals(second, session.cameras.selectedSection)
            assertTrue(session.reported.isEmpty(), "a tab switch is view state only")
        }
        onPhone(warrior, 320, 568, fontScale = 1.3f) {
            onNodeWithText("Fury").assertIsDisplayed() // below 175 % the label is unchanged
        }
    }

    @Test
    fun switchingTabsWithTheInspectorOpenNeverAutosaves() {
        val warrior = productState("WARRIOR")
        onPhone(warrior, 360, 800) { session ->
            select(SHORT_DESCRIPTION_TALENT)
            expand()
            onNodeWithText("Fury").performClick()
            settle()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_TAG).assertDoesNotExist()
            onNodeWithText("Arms").performClick()
            settle()
            assertTrue(session.reported.isEmpty(), "a tab switch is view state only")
        }
    }

    @Test
    fun aPurchaseFromTheStripUsesTheExistingChangePath() {
        val warrior = productState("WARRIOR")
        val node = warrior.engine.tree.nodes.first { it.name == SHORT_DESCRIPTION_TALENT }
        onPhone(warrior, 320, 568) { session ->
            select(SHORT_DESCRIPTION_TALENT)
            onNodeWithContentDescription("Add rank to $SHORT_DESCRIPTION_TALENT").performClick()
            settle()
            assertEquals(1, session.reported.size)
            val expected = warrior.engine.spend(warrior.engine.emptyState(), node.id)
            assertEquals(expected.state, session.reported.single(), "exactly the engine's own spend result")
        }
    }

    // ------------------------------------------------------------------ all bundled builds

    @Test
    fun buildsOnEveryBundledDatasetOpenWithTheirOwnInspector() {
        for (bundle in ALL_BUNDLES) {
            val state = readyStateOnBundle(bundle.clientBuild, "WARRIOR")
            val partial = compactScenarios(state).getValue("partial")
            val spent = state.engine.spentPoints(partial.build)
            onPhone(state.copy(buildState = partial.build), 320, 568) { session ->
                onNode(hasTestTag(TALENT_TREE_HEADER_POINTS_TAG) and hasText(points(state, spent)), useUnmergedTree = true)
                    .assertExists("the build opens with its own points on ${bundle.clientBuild}")
                select(partial.nodeName)
                onNodeWithContentDescription("Remove rank from ${partial.nodeName}").assertIsDisplayed()
                expand()
                val sheet = bounds(hasTestTag(TALENT_INSPECTOR_SHEET_TAG))
                assertTrue(sheet.height >= TalentInspectorMetrics.EXPANDED_SHEET_MIN_DP.value - 0.5f)
                onNodeWithTag(TALENT_INSPECTOR_DESCRIPTION_TAG, useUnmergedTree = true)
                    .assertExists("${bundle.clientBuild} shows its own description")
                assertTrue(session.reported.isEmpty(), "opening never rewrites the build on ${bundle.clientBuild}")
            }
        }
    }
}
