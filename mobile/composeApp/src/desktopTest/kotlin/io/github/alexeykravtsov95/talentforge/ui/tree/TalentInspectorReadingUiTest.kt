package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * Reading talent descriptions one after another (the inspector reading
 * refinement): once the reader expands the portrait sheet, selecting another
 * talent keeps it expanded and shows the new talent's detail from its top; an
 * explicit collapse is kept by the next selection; rank changes, undo / redo
 * never collapse it; no selection hides it and the next selection after that
 * opens the compact strip again. Real datasets, density 1 (px = dp).
 */
@OptIn(ExperimentalTestApi::class)
class TalentInspectorReadingUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private class Session(val reported: MutableList<BuildState>, val navigatedBack: IntArray, initial: IntSize) {
        var window by mutableStateOf(initial)
    }

    /** The editor in a [width] × [height] dp window that [rotate] resizes in place. */
    private fun onEditor(
        state: TalentTreeUiState.Ready,
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        block: ComposeUiTest.(Session) -> Unit,
    ) = runDesktopComposeUiTest(width = maxOf(width, height), height = maxOf(width, height)) {
        val session = Session(mutableListOf(), intArrayOf(0), IntSize(width, height))
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    // stands in for the app's navigation handler (registered first, like App.kt)
                    PlatformBackHandler(enabled = true) { session.navigatedBack[0] += 1 }
                    Box(Modifier.size(session.window.width.dp, session.window.height.dp)) {
                        TalentTreeScreen(
                            state,
                            onBack = {},
                            onBuildStateChanged = { session.reported += it },
                            sectionCameras = TalentTreeSectionCameras(sessionKey = "reading", treeId = state.model.treeId),
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

    private fun ComposeUiTest.rotate(session: Session, width: Int, height: Int) {
        runOnIdle { session.window = IntSize(width, height) }
        settle()
    }

    /** Selects a talent through its node's semantic click — the tap's own path. */
    private fun ComposeUiTest.select(name: String) {
        onNodeWithContentDescription(name).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** Selects a talent with a real finger tap on its node — the canvas gesture path. */
    private fun ComposeUiTest.tap(name: String) {
        val node = onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        onRoot().performTouchInput { click(node.center) }
        settle()
    }

    private fun ComposeUiTest.expand() {
        onNodeWithContentDescription(EditorCopy.English.expand).performClick()
        settle()
    }

    private fun ComposeUiTest.collapse() {
        onNodeWithContentDescription(EditorCopy.English.collapse).performClick()
        settle()
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.isExpanded(): Boolean = exists(hasTestTag(TALENT_INSPECTOR_SHEET_TAG))

    private fun ComposeUiTest.isCompact(): Boolean = exists(hasTestTag(TALENT_INSPECTOR_COMPACT_TAG))

    /** The expanded sheet shows [name] as its talent (its identity row). */
    private fun ComposeUiTest.assertSheetShows(name: String) {
        assertTrue(isExpanded(), "the sheet stays expanded for $name")
        assertTrue(!isCompact(), "no compact strip under the sheet for $name")
        onNode(hasText(name) and hasAnyAncestor(hasTestTag(TALENT_INSPECTOR_SHEET_TAG)), useUnmergedTree = true)
            .assertExists("the sheet shows $name")
        onNodeWithContentDescription(name).assertIsSelected()
    }

    /** The scroll range of the ONE inspector detail on screen, fetched now (a new talent has a new state). */
    private fun ComposeUiTest.detailScroll(): ScrollAxisRange = assertNotNull(
        onNodeWithTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange),
    )

    /** Another talent of [state]'s first section than [except], by reading order. */
    private fun otherTalent(state: TalentTreeUiState.Ready, except: String): String {
        val sections = buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned
        val names = state.engine.tree.nodes.associate { it.id to it.name.orEmpty() }
        return sections.sections.first().readingOrderNodeIds.map { names.getValue(it) }.first { it.isNotEmpty() && it != except }
    }

    // ------------------------------------------------------------------ portrait: reading in sequence

    @Test
    fun theFirstSelectionOpensTheCompactStripAndAnExpandedSheetStaysExpandedAcrossSelections() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        val available = scenarios.getValue("available")
        val prerequisite = scenarios.getValue("prerequisite")
        val tier = scenarios.getValue("tier")
        for ((w, h, scale) in listOf(Triple(320, 568, 1f), Triple(360, 800, 1.3f), Triple(393, 852, 2f))) {
            onEditor(warrior, w, h, scale) { session ->
                val where = "${w}x$h ${(scale * 100).toInt()}%"
                // no selection: no inspector at all
                assertTrue(!isCompact() && !isExpanded(), "hidden without a selection: $where")
                // the first selection keeps the existing compact strip (B3 03)
                select(available.nodeName)
                assertTrue(isCompact() && !isExpanded(), "first selection is compact: $where")
                expand()
                assertSheetShows(available.nodeName)
                // the reader goes on to other talents: the sheet stays, with the new detail
                for (next in listOf(tier.nodeName, prerequisite.nodeName, available.nodeName)) {
                    select(next)
                    assertSheetShows(next)
                    onNodeWithTag(TALENT_INSPECTOR_DESCRIPTION_TAG, useUnmergedTree = true).assertExists("$next: $where")
                }
                assertTrue(session.reported.isEmpty(), "reading never reaches the build or autosave: $where")
            }
        }
    }

    @Test
    fun realTapsOnTheCanvasReadInSequenceAndKeepACollapse() {
        val druid = productState("DRUID")
        // the first-row talents: on screen above the inspector in every presentation
        val sections = buildTalentTreeSections(druid.engine.tree, druid.model) as TalentTreeSectionsResult.Sectioned
        val section = sections.sections.first()
        val topY = section.model.nodes.minOf { it.centerY }
        val firstRow = section.model.nodes.filter { it.centerY == topY }.sortedBy { it.centerX }.map { it.name!! }
        assertTrue(firstRow.size >= 2, "two talents in the first row")
        val (first, second) = firstRow
        onEditor(druid, 360, 800) { session ->
            tap(first)
            assertTrue(isCompact() && !isExpanded(), "the first tap opens the compact strip")
            expand()
            tap(second)
            assertSheetShows(second)
            tap(first)
            assertSheetShows(first)
            collapse()
            tap(second)
            assertTrue(isCompact() && !isExpanded(), "after a collapse a tap keeps the strip")
            onNodeWithContentDescription(second).assertIsSelected()
            assertTrue(session.reported.isEmpty(), "a tap never spends")
        }
    }

    @Test
    fun everyNodeStateIsReadInTheExpandedSheetWithItsOwnActions() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        // every state the tree produces: available, prerequisite, tier, partial,
        // maxed, refund blocked, no points — reached from another talent's sheet
        assertTrue(scenarios.keys.containsAll(listOf("available", "prerequisite", "tier", "partial", "maxed", "no-points")))
        for ((label, case) in scenarios) {
            onEditor(warrior.copy(buildState = case.build), 360, 800) { session ->
                select(otherTalent(warrior, except = case.nodeName))
                expand()
                select(case.nodeName)
                assertSheetShows(case.nodeName)
                val (content, actions) = inspectorInputsOf(warrior, case.build, case.nodeName)
                if (actions.offersRankControls) {
                    if (actions.isMaxed) {
                        onNodeWithContentDescription(EditorCopy.English.maxRankReached).assertExists("$label: max state in the sheet")
                    } else {
                        onNodeWithContentDescription("Add rank to ${case.nodeName}").assertExists("$label: + in the sheet")
                    }
                    if (actions.rank > 0) {
                        onNodeWithContentDescription("Remove rank from ${case.nodeName}").assertExists("$label: − in the sheet")
                    }
                }
                if (content.showAction != null) onNodeWithText(EditorCopy.English.show).assertIsDisplayed()
                if (content.description?.comparison != null) {
                    onNodeWithTag(TALENT_INSPECTOR_COMPARISON_TAG, useUnmergedTree = true)
                        .assertExists("$label: current / next rank")
                }
                assertTrue(session.reported.isEmpty(), "$label: selecting never spends")
            }
        }
    }

    @Test
    fun anExplicitCollapseIsKeptByTheNextSelection() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        val first = scenarios.getValue("available").nodeName
        val second = scenarios.getValue("tier").nodeName
        val third = scenarios.getValue("prerequisite").nodeName
        onEditor(warrior, 360, 800) { session ->
            select(first)
            expand()
            // the chevron
            collapse()
            select(second)
            assertTrue(isCompact() && !isExpanded(), "a collapse by the chevron is kept")
            // the sheet header's swipe down
            expand()
            onNodeWithTag(TALENT_INSPECTOR_SHEET_HEADER_TAG).performTouchInput { swipeDown() }
            settle()
            assertTrue(isCompact() && !isExpanded(), "swiped down")
            select(third)
            assertTrue(isCompact() && !isExpanded(), "a collapse by the swipe is kept")
            // system Back's first step collapses the sheet — also an explicit collapse
            expand()
            runOnIdle { DesktopBackDispatcher.dispatch() }
            settle()
            assertTrue(isCompact() && !isExpanded(), "Back collapsed")
            select(first)
            assertTrue(isCompact() && !isExpanded(), "a collapse by Back is kept")
            assertEquals(0, session.navigatedBack[0], "the collapse never navigated")
            // expanding again resumes reading in sequence
            expand()
            select(second)
            assertSheetShows(second)
        }
    }

    @Test
    fun rankChangesUndoAndRedoNeverCollapseTheSheet() {
        val warrior = productState("WARRIOR")
        val name = SHORT_DESCRIPTION_TALENT
        onEditor(warrior, 360, 800) { session ->
            select(otherTalent(warrior, except = name))
            expand()
            select(name)
            for (step in listOf("Add rank to $name", "Add rank to $name", "Remove rank from $name")) {
                onNodeWithContentDescription(step).performClick()
                settle()
                assertSheetShows(name)
            }
            onNodeWithContentDescription(EditorCopy.English.maxOut(name)).performClick()
            settle()
            assertSheetShows(name)
            onNodeWithContentDescription(EditorCopy.English.undoAction).performClick()
            settle()
            assertSheetShows(name)
            onNodeWithContentDescription(EditorCopy.English.redoAction).performClick()
            settle()
            assertSheetShows(name)
            assertEquals(6, session.reported.size, "spend, spend, refund, max, undo, redo — each one autosave report")
        }
    }

    @Test
    fun noSelectionHidesTheInspectorAndTheNextSelectionIsCompactAgain() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        val first = scenarios.getValue("available").nodeName
        val second = scenarios.getValue("tier").nodeName
        onEditor(warrior, 360, 800) {
            // × clears the selection
            select(first)
            expand()
            onNodeWithText("×").performClick()
            settle()
            assertTrue(!isCompact() && !isExpanded(), "× hides the inspector")
            select(second)
            assertTrue(isCompact() && !isExpanded(), "after ×, a new first selection is compact")
            // a tab change clears it too
            expand()
            onNodeWithText("Fury").performClick()
            settle()
            assertTrue(!isCompact() && !isExpanded(), "a tab change hides the inspector")
            onNodeWithText("Arms").performClick()
            settle()
            select(first)
            assertTrue(isCompact() && !isExpanded(), "after a tab change, a new first selection is compact")
        }
    }

    @Test
    fun showStillCollapsesToTheStripAndTheReaderKeepsThatChoice() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        val locked = scenarios.getValue("prerequisite")
        val (content, _) = inspectorInputsOf(warrior, locked.build, locked.nodeName)
        val target = assertNotNull(assertNotNull(content.showAction).prerequisiteName.published)
        onEditor(warrior.copy(buildState = locked.build), 360, 800) { session ->
            select(otherTalent(warrior, except = locked.nodeName))
            expand()
            select(locked.nodeName)
            assertSheetShows(locked.nodeName)
            onNodeWithText(EditorCopy.English.show).performClick()
            settle()
            // B3 07 unchanged: one hop, compact strip, never spends
            onNodeWithContentDescription(target).assertIsSelected()
            assertTrue(isCompact() && !isExpanded(), "Show collapses to the strip as before")
            select(scenarios.getValue("tier").nodeName)
            assertTrue(isCompact() && !isExpanded(), "the next selection keeps the strip Show left")
            assertTrue(session.reported.isEmpty(), "Show never spends")
        }
    }

    // ------------------------------------------------------------------ long descriptions start at the top

    @Test
    fun aNewTalentsDescriptionStartsAtTheTopOfTheSheet() {
        val druid = productState("DRUID")
        val case = unlockedScenario(druid, LONG_DESCRIPTION_TALENT)
        val sections = buildTalentTreeSections(druid.engine.tree, druid.model) as TalentTreeSectionsResult.Sectioned
        val moonkin = druid.engine.tree.nodes.first { it.name == LONG_DESCRIPTION_TALENT }.id
        val names = druid.engine.tree.nodes.associate { it.id to it.name.orEmpty() }
        val neighbours = sections.sections.first { moonkin in it.readingOrderNodeIds }.readingOrderNodeIds
            .filter { it != moonkin }.map { names.getValue(it) }.filter { it.isNotEmpty() }
        onEditor(druid.copy(buildState = case.build), 320, 568, fontScale = 2f) { session ->
            select(LONG_DESCRIPTION_TALENT)
            expand()
            var checked = 0
            for (next in neighbours) {
                select(LONG_DESCRIPTION_TALENT)
                assertSheetShows(LONG_DESCRIPTION_TALENT)
                onNodeWithTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG, useUnmergedTree = true).performTouchInput { swipeUp() }
                settle()
                assertTrue(detailScroll().value() > 0f, "the long description scrolled")
                select(next)
                assertSheetShows(next)
                val range = detailScroll()
                assertEquals(0f, range.value(), "$next starts at its top")
                if (range.maxValue() > 0f) checked += 1
                if (checked >= 2) break
            }
            assertTrue(checked >= 2, "at least two other long descriptions were read from the top")
            assertTrue(session.reported.isEmpty())
        }
    }

    @Test
    fun theSidePanelAndTheTabletPanelAlsoStartANewTalentAtTheTop() {
        val druid = productState("DRUID")
        val case = unlockedScenario(druid, LONG_DESCRIPTION_TALENT)
        val other = otherTalent(druid, except = LONG_DESCRIPTION_TALENT)
        // landscape phone (B3 05 side panel) and tablet (persistent panel)
        for ((w, h, scale) in listOf(Triple(640, 360, 2f), Triple(1024, 600, 2f))) {
            onEditor(druid.copy(buildState = case.build), w, h, scale) {
                select(LONG_DESCRIPTION_TALENT)
                val before = detailScroll()
                assertTrue(before.maxValue() > 0f, "the long description overflows: ${w}x$h")
                onNodeWithTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, before.maxValue()) }
                settle()
                assertTrue(detailScroll().value() > 0f, "scrolled: ${w}x$h")
                select(other)
                assertEquals(0f, detailScroll().value(), "$other starts at its top: ${w}x$h")
            }
        }
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun anExpandedReadingSurvivesARotationThroughTheSidePanel() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        val first = scenarios.getValue("available").nodeName
        val second = scenarios.getValue("tier").nodeName
        val third = scenarios.getValue("prerequisite").nodeName
        onEditor(warrior, 412, 915) { session ->
            select(first)
            expand()
            rotate(session, 915, 412)
            // landscape keeps the B3 05 side panel: no sheet, no strip
            onNodeWithTag(TALENT_INSPECTOR_SIDE_PANEL_TAG).assertIsDisplayed()
            assertTrue(!isExpanded() && !isCompact())
            select(second)
            onNode(hasText(second) and hasAnyAncestor(hasTestTag(TALENT_INSPECTOR_SIDE_PANEL_TAG)), useUnmergedTree = true)
                .assertExists()
            rotate(session, 412, 915)
            assertSheetShows(second)
            select(third)
            assertSheetShows(third)
            assertTrue(session.reported.isEmpty(), "rotation and reading are view state only")
        }
    }
}
