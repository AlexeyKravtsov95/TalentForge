package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.REFERENCE_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage

/**
 * M5.UX.1 portrait section tabs on the real screen (1A v2): one tap per
 * section, an independent camera per tab with FIT, undo/redo/points shared by
 * all tabs, cross-section Show with the Back chip (synthetic fixture), state
 * restoration, and the small-phone geometry — and through all of it the
 * build is only ever changed by explicit rank controls.
 *
 * Density is pinned to 1, so px == dp in every injected position.
 */
class TalentTreeSectionTabsUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var readyState: TalentTreeUiState.Ready
    private lateinit var cameras: TalentTreeSectionCameras
    private val reported = mutableListOf<BuildState>()

    @BeforeTest
    fun loadRealWarriorState() {
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
        reported.clear()
    }

    private fun compose(
        state: TalentTreeUiState.Ready = readyState,
        width: Dp = 360.dp,
        height: Dp = 760.dp,
    ) {
        cameras = TalentTreeSectionCameras(sessionKey = "tabs-test", treeId = state.model.treeId)
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(
                            state,
                            onBuildStateChanged = { reported += it },
                            sectionCameras = cameras,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun sections(state: TalentTreeUiState.Ready = readyState): TalentTreeSectionsResult.Sectioned =
        buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned

    private fun openTab(label: String) {
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    private fun select(name: String) {
        composeRule.onNodeWithContentDescription(name).performClick()
        composeRule.waitForIdle()
    }

    private fun tabState(label: String): String =
        composeRule.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun nameOf(state: TalentTreeUiState.Ready, nodeId: Int): String =
        assertNotNull(state.model.nodes.first { it.id == nodeId }.name)

    /** A talent of [section] that the current empty build can buy (first tier, no prerequisite). */
    private fun firstBuyableTalentOf(section: Int, state: TalentTreeUiState.Ready = readyState): String {
        val ids = sections(state).section(section)!!.readingOrderNodeIds
        val id = ids.first { state.engine.canSpend(state.engine.emptyState(), it).allowed }
        return nameOf(state, id)
    }

    /** Drags the open canvas down by [dy] dp from a point inside it (below header 44 + tabs 48). */
    private fun dragCanvasDown(dy: Float) {
        composeRule.onRoot().performTouchInput {
            swipe(Offset(300f, 300f), Offset(300f, 300f + dy), durationMillis = 300)
        }
        composeRule.waitForIdle()
    }

    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
    }

    // ------------------------------------------------------------------ tabs and points

    @Test
    fun tabsShowDataDrivenLabelsAndLivePointsPerSection() {
        compose()

        composeRule.onNodeWithText("Arms").assertIsSelected()
        composeRule.onNodeWithText("Fury").assertIsNotSelected()
        composeRule.onNodeWithText("Protection").assertIsNotSelected()
        for (label in listOf("Arms", "Fury", "Protection")) assertEquals("0 points spent", tabState(label))

        select("Improved Rend")
        repeat(2) {
            composeRule.onNodeWithText("+").performClick()
            composeRule.waitForIdle()
        }
        assertEquals("2 points spent", tabState("Arms"))

        val fury = firstBuyableTalentOf(section = 1)
        openTab("Fury")
        select(fury)
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()

        assertEquals("2 points spent", tabState("Arms"))
        assertEquals("1 point spent", tabState("Fury"))
        assertEquals("0 points spent", tabState("Protection"))
        // the section counts add up to the one global counter
        composeRule.onNodeWithText("3 / 51").assertExists()
    }

    @Test
    fun oneTapSwitchesTheSectionClosesTheInspectorAndNeverTouchesTheBuild() {
        compose()
        select("Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        assertEquals(1, reported.size)
        select("Deflection")
        composeRule.onNodeWithText("+").assertExists() // the compact inspector is open

        openTab("Fury")

        composeRule.onNodeWithText("Fury").assertIsSelected()
        // only Fury's talents are on the canvas — no pan through the other trees
        for (node in sections().section(1)!!.model.nodes) {
            composeRule.onNodeWithContentDescription(assertNotNull(node.name)).assertExists()
        }
        for (node in sections().section(0)!!.model.nodes) {
            composeRule.onNodeWithContentDescription(assertNotNull(node.name)).assertDoesNotExist()
        }
        // selection cleared, inspector closed
        composeRule.onNodeWithText("+").assertDoesNotExist()
        // build, counter and history untouched
        assertEquals(1, reported.size, "a tab switch is not a build change")
        composeRule.onNodeWithText("1 / 51").assertExists()
        composeRule.onNodeWithContentDescription("Undo talent change").assertIsEnabled()

        openTab("Protection")
        openTab("Arms")
        assertEquals(
            "1/3, in progress",
            composeRule.onNodeWithContentDescription("Improved Rend").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription],
        )
        assertEquals(1, reported.size)
    }

    @Test
    fun everyNodeIsReachableThroughTheTabsOnBothBundledBuilds() {
        for ((bundle, classToken) in listOf(REFERENCE_BUNDLE to "WARRIOR", PRODUCT_BUNDLE to "DRUID")) {
            val state = readyStateOf(bundle, classToken)
            compose(state)
            val seen = mutableSetOf<Int>()
            for (section in sections(state).sections) {
                val label = talentSectionDisplayLabel(
                    datasetName = section.datasetName,
                    classToken = state.classToken,
                    sectionIndex = section.index,
                    ordinal = section.ordinal,
                    language = AppLanguage.ENGLISH,
                )
                openTab(label)
                for (node in section.model.nodes) {
                    val description = node.name ?: "talent ${node.id}"
                    assertTrue(
                        composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty(),
                        "${bundle.clientBuild} $classToken $label: $description",
                    )
                    seen += node.id
                }
            }
            assertEquals(state.model.nodes.map { it.id }.toSet(), seen, "${bundle.clientBuild} $classToken")
            assertTrue(reported.isEmpty(), "browsing the tabs never changes a build")
        }
    }

    // ------------------------------------------------------------------ cameras and FIT

    @Test
    fun everyTabKeepsItsOwnCameraAndRestoresItOnReturn() {
        compose()
        val tree1 = cameras.stateOf(0)
        val fit1 = assertNotNull(tree1.viewport)

        dragCanvasDown(80f)
        val moved1 = assertNotNull(tree1.viewport)
        assertNotEquals(fit1, moved1, "the drag moved Arms's camera")

        openTab("Fury")
        val tree2 = cameras.stateOf(1)
        val fit2 = assertNotNull(tree2.viewport, "Fury gets its own camera on first open")
        assertFalse(tree2.userMoved)
        assertEquals(moved1, tree1.viewport, "switching away never touches Arms's camera")

        openTab("Arms")
        assertEquals(moved1, tree1.viewport, "Arms's pan/zoom is restored")
        assertEquals(fit2, tree2.viewport, "and Fury's camera is untouched")
    }

    @Test
    fun fitAppearsOnlyAfterAManualMoveAndAnimatesBackToTheFit() {
        compose()
        val camera = cameras.stateOf(0)
        val fit = assertNotNull(camera.viewport)
        composeRule.onNodeWithContentDescription("Fit tree").assertDoesNotExist()

        composeRule.onRoot().performTouchInput {
            pinch(
                start0 = Offset(140f, 300f), end0 = Offset(100f, 300f),
                start1 = Offset(220f, 300f), end1 = Offset(260f, 300f),
                durationMillis = 400,
            )
        }
        composeRule.waitForIdle()
        assertTrue(assertNotNull(camera.viewport).scale > fit.scale, "the pinch zoomed in")
        composeRule.onNodeWithContentDescription("Fit tree").assertExists()

        composeRule.onNodeWithContentDescription("Fit tree").performClick()
        settle()

        assertTrue(assertNotNull(camera.viewport).isCloseTo(fit), "FIT returns to the first-open camera")
        assertFalse(camera.userMoved)
        composeRule.onNodeWithContentDescription("Fit tree").assertDoesNotExist()
        assertTrue(reported.isEmpty(), "pan, zoom and FIT are not build changes")
    }

    @Test
    fun tappingTheActiveTabAgainFitsItsCamera() {
        compose()
        val camera = cameras.stateOf(0)
        val fit = assertNotNull(camera.viewport)
        dragCanvasDown(80f)
        assertTrue(camera.isAwayFromFit)

        openTab("Arms") // already open
        settle()

        assertTrue(assertNotNull(camera.viewport).isCloseTo(fit))
        composeRule.onNodeWithText("Arms").assertIsSelected()
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aGestureDuringFitStopsTheAnimationWithoutAJump() {
        compose()
        val camera = cameras.stateOf(0)
        val fit = assertNotNull(camera.viewport)
        dragCanvasDown(80f)

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("Fit tree").performClick()
        composeRule.mainClock.advanceTimeBy(100) // FIT is under way (280 ms)
        // one gesture step lands mid-animation (the canvas gesture entry point)
        var afterGesture: TalentTreeViewport? = null
        composeRule.runOnIdle {
            camera.applyUserTransform(assertNotNull(camera.viewport).panBy(Offset(0f, 4f)))
            afterGesture = camera.viewport
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        // the gesture won: the animation stopped where the gesture left the camera
        assertEquals(afterGesture, camera.viewport, "no jump back to the fit after the gesture")
        assertTrue(camera.userMoved, "an interrupted FIT leaves the camera to the user")
        assertFalse(assertNotNull(camera.viewport).isCloseTo(fit))
    }

    // ------------------------------------------------------------------ shared history

    @Test
    fun undoAndRedoStayGlobalAcrossTabsWithoutSwitchingTab() {
        compose()
        select("Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()

        openTab("Fury")
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Fury").assertIsSelected() // 1A v2 S8: no tab switch
        composeRule.onNodeWithText("0 / 51").assertExists()
        assertEquals("0 points spent", tabState("Arms"))

        composeRule.onNodeWithContentDescription("Redo talent change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 51").assertExists()
        assertEquals("1 point spent", tabState("Arms"))
        composeRule.onNodeWithText("Fury").assertIsSelected()
        // spend, undo, redo — and nothing else — reached the autosave observer
        assertEquals(listOf(1, 0, 1), reported.map { readyState.engine.spentPoints(it) })
    }

    // ------------------------------------------------------------------ cross-section Show (synthetic)

    @Test
    fun crossSectionShowSwitchesTabSelectsTheTargetAndBackReturnsToTheOrigin() {
        val fixture = crossSectionFixture(readyState.engine.document)
        val engine = TalentAllocationEngine(fixture.document)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(fixture.document)).model
        val state = readyState.copy(model = model, engine = engine, buildState = engine.emptyState())
        val origin = nameOf(state, fixture.targetId) // the locked talent in Protection
        val prerequisite = nameOf(state, fixture.sourceId) // its synthetic prerequisite in Fury
        compose(state)

        openTab("Protection")
        select(origin)
        composeRule.onNodeWithText("Show").assertExists()
        val tree3Camera = assertNotNull(cameras.stateOf(2).viewport)

        composeRule.onNodeWithText("Show").performClick()
        settle()

        composeRule.onNodeWithText("Fury").assertIsSelected()
        composeRule.onNodeWithContentDescription(prerequisite).assertIsSelected()
        composeRule.onNodeWithText("Available · needed for $origin").assertExists()
        composeRule.onNodeWithText("Back to $origin · Protection").assertExists()
        assertEquals(tree3Camera, cameras.stateOf(2).viewport, "the origin tab's camera is kept")
        composeRule.onNodeWithText("0 / 51").assertExists()
        assertTrue(reported.isEmpty(), "Show never spends")

        composeRule.onNodeWithText("Back to $origin · Protection").performClick()
        settle()

        composeRule.onNodeWithText("Protection").assertIsSelected()
        composeRule.onNodeWithContentDescription(origin).assertIsSelected()
        composeRule.onNodeWithText("Back to $origin · Protection").assertDoesNotExist()
        assertEquals(tree3Camera, cameras.stateOf(2).viewport)
        assertTrue(reported.isEmpty())
    }

    // ------------------------------------------------------------------ restoration

    @Test
    fun sectionSelectionCameraAndBuildSurviveStateRestoration() {
        // Emulates a configuration change: save every rememberSaveable, drop
        // the composition, recompose the same tree from the saved values.
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        var shown by mutableStateOf(true)
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        if (shown) {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                                TalentTreeScreen(readyState, sessionKey = "build-1")
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        select("Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        val fury = firstBuyableTalentOf(section = 1)
        openTab("Fury")
        dragCanvasDown(60f)
        select(fury)
        val before = composeRule.onNodeWithContentDescription(fury).fetchSemanticsNode().positionInRoot

        var saved: Map<String, List<Any?>> = emptyMap()
        composeRule.runOnIdle {
            saved = registry.performSave()
            shown = false
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fury").assertDoesNotExist()
        composeRule.runOnIdle {
            registry = SaveableStateRegistry(saved) { true }
            shown = true
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Fury").assertIsSelected()
        composeRule.onNodeWithContentDescription(fury).assertIsSelected()
        assertEquals(before, composeRule.onNodeWithContentDescription(fury).fetchSemanticsNode().positionInRoot,
            "the tab's pan is restored")
        composeRule.onNodeWithText("1 / 51").assertExists()
        assertEquals("1 point spent", tabState("Arms"))
    }

    @Test
    fun rotatingKeepsTheSectionAndSelectionInBothOrientations() {
        var window by mutableStateOf(IntSize(360, 760))
        cameras = TalentTreeSectionCameras(sessionKey = "rotate", treeId = readyState.model.treeId)
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(window.width.dp, window.height.dp)) {
                        TalentTreeScreen(readyState, sectionCameras = cameras)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val fury = firstBuyableTalentOf(section = 1)
        openTab("Fury")
        select(fury)

        // landscape (M5.UX.2b.1): the same section tabs, the same section and selection
        composeRule.runOnIdle { window = IntSize(760, 360) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fury").assertIsSelected()
        composeRule.onNodeWithContentDescription(fury).assertIsSelected()

        // back to portrait: the same section and the same selection
        composeRule.runOnIdle { window = IntSize(360, 760) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fury").assertIsSelected()
        composeRule.onNodeWithContentDescription(fury).assertIsSelected()
    }

    // ------------------------------------------------------------------ small phones

    @Test
    fun smallPhoneShowsAWholeSectionWithUsableNonOverlappingTalents() {
        // 320 × 568 phone: no safe-area insets in the desktop test host
        compose(width = 320.dp, height = 568.dp)
        val section = sections().sections.first()

        // on-screen (transformed) bounds of every talent's container: visual + selection-ring room
        val boxes: List<Rect> = section.model.nodes.map { node ->
            composeRule.onNodeWithContentDescription(assertNotNull(node.name)).fetchSemanticsNode().boundsInRoot
        }
        for (box in boxes) {
            assertTrue(box.left >= 0f && box.right <= 320f, "every talent of the section is on screen horizontally: $box")
            assertTrue(box.width >= 45f, "talents stay at least 45 dp on 320 dp: ${box.width}")
        }
        // the node containers (visual + selection-ring room) never overlap
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                assertFalse(boxes[i].overlaps(boxes[j]), "talents overlap: ${boxes[i]} / ${boxes[j]}")
            }
        }
        // below 360 dp the tab icons give way to the labels (1A v2 03)
        assertEquals(
            0,
            composeRule.onAllNodes(hasTestTag(TALENT_SECTION_TAB_ICON_TAG), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        // a physical tap still selects (never spends)
        val rend = boxes[section.model.nodes.indexOfFirst { it.name == "Improved Rend" }]
        composeRule.onRoot().performTouchInput { click(rend.center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Improved Rend").assertIsSelected()
        composeRule.onNodeWithText("0 / 51").assertExists()
    }

    @Test
    fun widePhonesShowTheSectionIconsInTheTabs() {
        compose(width = 393.dp, height = 852.dp)
        assertEquals(
            3,
            composeRule.onAllNodes(hasTestTag(TALENT_SECTION_TAB_ICON_TAG), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun anUnsectionableTreeFallsBackToTheWholeTreeWithoutTabs() {
        val tree = readyState.engine.tree
        val broken = readyState.engine.document.copy(
            trees = listOf(tree.copy(nodes = tree.nodes.map { if (it == tree.nodes.first()) it.copy(sectionIndex = null) else it })),
        )
        val engine = TalentAllocationEngine(broken)
        val state = readyState.copy(engine = engine, buildState = engine.emptyState())
        assertIs<TalentTreeSectionsResult.Unsectioned>(buildTalentTreeSections(engine.tree, state.model))

        compose(state)

        composeRule.onNodeWithText("Arms").assertDoesNotExist()
        // no node is hidden: the whole tree is on the one canvas
        for (node in state.model.nodes) {
            assertTrue(
                composeRule.onAllNodesWithContentDescription(node.name ?: "talent ${node.id}")
                    .fetchSemanticsNodes().isNotEmpty(),
                "${node.name} must stay visible",
            )
        }
        assertNull(cameras.selectedSection)
    }

    @Test
    fun theProductMageArcaneTabShowsArcaneWrath() {
        // 1.60.1.70450: Arcane Wrath is counted by no gate; the dataset places it in Arcane by its column
        val state = readyStateOf(PRODUCT_BUNDLE, "MAGE")
        assertIs<TalentTreeSectionsResult.Sectioned>(buildTalentTreeSections(state.engine.tree, state.model))

        compose(state)

        composeRule.onNodeWithText("Arcane").assertIsSelected()
        composeRule.onNodeWithText("Fire").assertExists()
        composeRule.onNodeWithText("Frost").assertExists()
        composeRule.onNodeWithContentDescription("Arcane Wrath").assertExists()
        composeRule.onNodeWithText("0 / 51").assertExists()
    }

    // ------------------------------------------------------------------ helpers

    private fun readyStateOf(bundle: DatasetBundleId, classToken: String): TalentTreeUiState.Ready = runBlocking {
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
                .document(DatasetId(bundle.game, bundle.clientBuild, ClassToken(classToken))),
        ).value
        val engine = TalentAllocationEngine(document)
        TalentTreeUiState.Ready(
            classDisplayName = document.classInfo.name,
            gameDisplayName = "Forever",
            classToken = document.classInfo.token,
            model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model,
            engine = engine,
            buildState = engine.emptyState(),
            buildTitle = "Draft",
            classAccent = classAccentFor(document.classInfo.token).color,
        )
    }
}
