package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.REFERENCE_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * M5.REL.2b: the talent editor in English and Russian against REAL bundled
 * data — loading and failure panes, selection and every inspector state,
 * spend / refund, blocked actions, prerequisite and tier locks, cross-section
 * Show with the Back chip and Fit, Reset + Undo, the header's accessibility
 * labels, the autosave failure with Retry, the description labels, a language
 * switch that changes only words, the nameless/unavailable fallbacks and user
 * build titles shown verbatim. Expected copy comes from the literal
 * [EditorCopy] oracle; a Russian screen must show no English editor copy, in
 * text or in accessibility labels (dataset talent names and tooltip text stay
 * English until M5.REL.3).
 */
@OptIn(ExperimentalTestApi::class)
class EditorLocalizationUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val reported = mutableListOf<BuildState>()
    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    // ------------------------------------------------------------------ harness

    private fun onEditor(
        language: AppLanguage,
        width: Int = 393,
        height: Int = 852,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        reported.clear()
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, fontScale),
                        LocalReducedMotion provides true,
                        LocalAppLanguage provides language,
                    ) {
                        Box(Modifier.size(width.dp, height.dp)) { content() }
                    }
                }
            }
            waitForIdle()
            block()
        }
    }

    private fun tree(
        state: TalentTreeUiState,
        cameras: TalentTreeSectionCameras? = null,
    ): @Composable () -> Unit = {
        TalentTreeScreen(
            state = state,
            onBack = {},
            onBuildStateChanged = { reported += it },
            sectionCameras = cameras,
        )
    }

    private fun buildScreen(localId: String, repository: LocalBuildRepository): @Composable () -> Unit = {
        val scope = rememberCoroutineScope()
        TalentTreeBuildScreen(
            localId = localId,
            repository = repository,
            datasets = datasets,
            autosave = remember { BuildAutosaveCoordinator(repository, scope) },
            onBack = {},
        )
    }

    private fun ComposeUiTest.awaitText(text: String) {
        try {
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("'$text' never appeared; on screen: ${texts()}", timeout)
        }
    }

    private fun ComposeUiTest.select(name: String) {
        onNodeWithContentDescription(name).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.click(text: String) {
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.allNodes(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    /** The dataset's own tooltip text (M5.V2) — game content, English until M5.REL.3. */
    private fun SemanticsNode.isDatasetDescription(): Boolean {
        var node: SemanticsNode? = this
        while (node != null) {
            if (node.config.getOrNull(SemanticsProperties.TestTag) == TALENT_INSPECTOR_DESCRIPTION_TAG) return true
            node = node.parent
        }
        return false
    }

    private fun ComposeUiTest.texts(): Set<String> =
        allNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }.toSet()

    /** Every click label, content description and state description on screen. */
    private fun ComposeUiTest.a11yLabels(): Set<String> = allNodes().flatMap { node ->
        listOfNotNull(
            node.config.getOrNull(SemanticsActions.OnClick)?.label,
            node.config.getOrNull(SemanticsProperties.StateDescription),
        ) + node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.toSet()

    private fun ComposeUiTest.assertShows(vararg expected: String) {
        val shown = texts()
        for (text in expected) assertTrue(text in shown, "'$text' is on screen (shown: $shown)")
    }

    private fun ComposeUiTest.assertA11y(vararg expected: String) {
        val labels = a11yLabels()
        for (label in expected) assertTrue(label in labels, "a11y label '$label' exists (labels: $labels)")
    }

    private fun ComposeUiTest.stateDescriptionOf(talent: String): String =
        onNodeWithContentDescription(talent).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    /** The compact strip's reason line (state, lock or blocked reason). */
    private fun ComposeUiTest.reasonLine(): String =
        onAllNodes(hasTestTag(TALENT_INSPECTOR_REASON_TAG), useUnmergedTree = true).fetchSemanticsNodes()
            .single().config[SemanticsProperties.Text].joinToString("") { it.text }

    /**
     * No English editor copy on a Russian screen — no fixed phrase and none
     * of the parameterised English forms — in text or accessibility labels.
     * Talent names, the dataset's tooltip text, numbers and the brand stay as
     * they are (M5.REL.3 translates game content).
     */
    private fun ComposeUiTest.assertNoEnglishEditorCopy(where: String) {
        val english = EditorCopy.English.fixedPhrases
        val englishForms = listOf(
            "Add rank to ", "Remove rank from ", "Max out ", "show prerequisite ", "Tier points ", "Back to ",
            "Requires ", "Spend ", "Needed by ", "No talent points left", " points spent", " point spent",
            "Talents reset", " needed for ", "the local build library", "the allocation engine", "the dataset ",
        )
        val uiTexts = allNodes().filterNot { it.isDatasetDescription() }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val leaked = (uiTexts + a11yLabels()).filter { text ->
            text in english || englishForms.any { form -> text.contains(form) }
        }
        assertTrue(leaked.isEmpty(), "$where: English editor copy on a Russian screen: $leaked")
    }

    private fun repositoryOver(storage: BuildLibraryStorage) = LocalBuildRepository(storage, FixedClock(), SequentialIds())

    private fun warriorDraft(repository: LocalBuildRepository): LocalBuildRecord =
        assertIs<DraftCreation.Created>(runBlocking { createClassDraft(repository, datasets, ClassToken("WARRIOR")) })
            .record

    private fun baselineWarrior(): TalentTreeUiState.Ready =
        assertIs<TalentTreeUiState.Ready>(runBlocking { loadBaselineWarriorTreeState() })

    private fun storedRecords(storage: FakeBuildLibraryStorage): List<LocalBuildRecord> =
        assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repositoryOver(storage).load()).value

    // ------------------------------------------------------------------ 1. loading and failure

    @Test
    fun loadingAndFailureStatesReadInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            val noTrees = if (language == AppLanguage.RUSSIAN) {
                "в данных нет дерева талантов"
            } else {
                "the dataset contains no talent tree"
            }
            onEditor(language, content = tree(TalentTreeUiState.Loading)) {
                assertShows(copy.loadingTalentData, "TalentForge")
                assertA11y(copy.goBack)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("loading")
            }
            val failed = TalentTreeUiState.Failed(TalentTreeUiFailure.UiModel(TalentTreeUiBuildError.NoTrees))
            onEditor(language, content = tree(failed)) {
                assertShows(copy.talentDataFailed, noTrees)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("failed")
            }
            // a build that no longer exists: the typed library error, localized
            onEditor(language, content = buildScreen("missing-build", repositoryOver(FakeBuildLibraryStorage()))) {
                awaitText(copy.openFailed)
                assertShows(copy.libraryFailure(copy.unknownRecordDetail))
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("open failed")
            }
            // the opening pane, held open by a storage read that waits
            val storage = FakeBuildLibraryStorage()
            val draft = warriorDraft(repositoryOver(storage))
            val gate = GatedStorage(storage)
            onEditor(language, content = buildScreen(draft.localId, repositoryOver(gate))) {
                awaitText(copy.openingBuild)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("opening")
                gate.release()
                awaitText(copy.unnamedWarriorTitle)
            }
        }
    }

    // ------------------------------------------------------------------ 2, 3. selection and every inspector state

    @Test
    fun everyInspectorStateReadsInBothLanguages() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        assertEquals(
            setOf("available", "prerequisite", "tier", "partial", "maxed", "refund-blocked", "no-points"),
            scenarios.keys,
        )
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            for ((label, scenario) in scenarios) {
                val (content, _) = inspectorInputsOf(warrior, scenario.build, scenario.nodeName)
                val node = buildTalentTreePresentation(warrior.engine, scenario.build).nodes.getValue(content.nodeId)
                val talent = scenario.nodeName
                onEditor(language, content = tree(warrior.copy(buildState = scenario.build))) {
                    select(talent)
                    onNodeWithContentDescription(talent).assertIsSelected()
                    // the canvas speaks rank + state; the strip shows the engine's reason in words
                    assertEquals("${node.rankText}, ${copy.nodeState(node.state)}", stateDescriptionOf(talent), label)
                    val reason = assertNotNull(content.prerequisiteLine ?: content.tierLine ?: content.stateLine)
                    assertEquals(copy.textOf(reason), reasonLine(), "$label in $language")
                    when (label) {
                        "available" -> onNodeWithContentDescription(copy.addRank(talent)).assertIsEnabled()
                        "partial" -> {
                            onNodeWithContentDescription(copy.addRank(talent)).assertIsEnabled()
                            onNodeWithContentDescription(copy.removeRank(talent)).assertIsEnabled()
                        }

                        "maxed" -> {
                            assertA11y(copy.maxRankReached)
                            onNodeWithContentDescription(copy.removeRank(talent)).assertIsEnabled()
                        }

                        "prerequisite" -> {
                            val prerequisite = copy.nameOf(assertNotNull(content.showAction).prerequisiteName)
                            assertShows(copy.show)
                            assertA11y(copy.showAction(prerequisite))
                        }

                        "tier" -> {
                            val gate = content.tierMeters.first()
                            assertA11y(copy.tierPoints(gate.spentPoints, gate.requiredPoints))
                        }

                        "no-points" -> onNodeWithContentDescription(copy.addRank(talent)).assertIsNotEnabled()
                        "refund-blocked" -> onNodeWithContentDescription(copy.removeRank(talent)).assertIsNotEnabled()
                    }
                    if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("$label strip")
                    // the expanded sheet: its labels, the pinned actions and the collapse control
                    onNodeWithContentDescription(copy.expand).performClick()
                    waitForIdle()
                    assertA11y(copy.collapse, copy.clearSelection)
                    if (label == "available" || label == "partial") assertShows(copy.rankCaption, copy.max)
                    if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("$label sheet")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 4. spending and refunding

    @Test
    fun spendingAndRefundingReadInBothLanguages() {
        val warrior = baselineWarrior()
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            onEditor(language, content = tree(warrior)) {
                select("Improved Rend")
                assertEquals(copy.available, reasonLine())
                onNodeWithContentDescription(copy.addRank("Improved Rend")).performClick()
                waitForIdle()
                assertEquals(copy.inProgress, reasonLine())
                assertEquals("1/3, ${copy.nodeState(TalentNodeState.PARTIAL)}", stateDescriptionOf("Improved Rend"))
                assertShows("1 / 51")
                repeat(2) {
                    onNodeWithContentDescription(copy.addRank("Improved Rend")).performClick()
                    waitForIdle()
                }
                assertEquals(copy.maxed, reasonLine())
                assertA11y(copy.maxRankReached)
                assertEquals("3/3, ${copy.nodeState(TalentNodeState.MAXED)}", stateDescriptionOf("Improved Rend"))
                assertEquals(3, reported.size, "three spends reported")
                onNodeWithContentDescription(copy.removeRank("Improved Rend")).performClick()
                waitForIdle()
                assertEquals(copy.inProgress, reasonLine())
                assertShows("2 / 51")
                assertEquals(4, reported.size, "and the refund")
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("spend/refund")
            }
        }
    }

    // ------------------------------------------------------------------ 5. blocked allocation feedback

    @Test
    fun blockedActionsExplainThemselvesInBothLanguages() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        for (label in listOf("refund-blocked", "no-points")) {
            val scenario = scenarios.getValue(label)
            val (content, _) = inspectorInputsOf(warrior, scenario.build, scenario.nodeName)
            val reason = assertNotNull(content.stateLine)
            if (label == "no-points") assertEquals(InspectorText.NoPointsLeft(51, 51), reason)
            for (language in AppLanguage.entries) {
                val copy = EditorCopy.of(language)
                onEditor(language, content = tree(warrior.copy(buildState = scenario.build))) {
                    select(scenario.nodeName)
                    assertEquals(copy.textOf(reason), reasonLine(), "$label in $language")
                    if (language == AppLanguage.RUSSIAN) {
                        when (reason) {
                            is InspectorText.NeededBy -> assertTrue(reasonLine().startsWith("Нужен для «"), reasonLine())
                            is InspectorText.NoPointsLeft ->
                                assertEquals("Нет свободных очков · потрачено 51/51", reasonLine())
                            else -> Unit
                        }
                        assertNoEnglishEditorCopy(label)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 6. prerequisite and tier restrictions

    @Test
    fun prerequisiteAndTierLocksReadInBothLanguages() {
        val warrior = baselineWarrior()
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            onEditor(language, content = tree(warrior)) {
                select("Deep Wounds")
                assertEquals(copy.requiresPrerequisite("Improved Rend", 3), reasonLine())
                assertEquals(
                    "0/3, ${copy.nodeState(TalentNodeState.PREREQUISITE_LOCKED)}",
                    stateDescriptionOf("Deep Wounds"),
                )
                assertA11y(copy.showAction("Improved Rend"))
                click(copy.show)
                onNodeWithContentDescription("Improved Rend").assertIsSelected()
                assertEquals(copy.neededFor(copy.available, "Deep Wounds"), reasonLine())
                assertTrue(reported.isEmpty(), "Show never spends")

                select("Improved Charge")
                assertEquals(copy.spendMorePoints(5), reasonLine())
                assertA11y(copy.tierPoints(0, 5))
                assertEquals("0/2, ${copy.nodeState(TalentNodeState.TIER_LOCKED)}", stateDescriptionOf("Improved Charge"))
                if (language == AppLanguage.RUSSIAN) {
                    assertEquals("Вложите ещё 5 очков в эту ветку", reasonLine())
                    assertNoEnglishEditorCopy("locks")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 7. cross-section navigation, Back chip, Fit

    @Test
    fun crossSectionShowTheBackChipAndFitReadInBothLanguages() {
        val baseline = baselineWarrior()
        val fixture = crossSectionFixture(baseline.engine.document)
        val engine = TalentAllocationEngine(fixture.document)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(fixture.document)).model
        val state = baseline.copy(model = model, engine = engine, buildState = engine.emptyState())
        val origin = assertNotNull(model.nodes.first { it.id == fixture.targetId }.name)
        val prerequisite = assertNotNull(model.nodes.first { it.id == fixture.sourceId }.name)
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            val fury = if (language == AppLanguage.RUSSIAN) "Неистовство" else "Fury"
            val protection = if (language == AppLanguage.RUSSIAN) "Защита" else "Protection"
            val cameras = TalentTreeSectionCameras(sessionKey = "rel2b-$language", treeId = model.treeId)
            onEditor(language, content = tree(state, cameras)) {
                click(protection)
                select(origin)
                click(copy.show)
                onNodeWithText(fury).assertIsSelected()
                onNodeWithContentDescription(prerequisite).assertIsSelected()
                assertEquals(copy.neededFor(copy.available, origin), reasonLine())
                val chip = "${copy.backTo(origin)} · $protection"
                assertShows(chip)
                assertA11y(copy.returnAction)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("cross-section Show")
                click(chip)
                onNodeWithText(protection).assertIsSelected()
                onNodeWithContentDescription(origin).assertIsSelected()
                assertTrue(reported.isEmpty(), "navigation never spends")

                // Fit: a pan moves the camera away, the localized Fit action brings it back
                onRoot().performTouchInput { swipe(Offset(200f, 300f), Offset(200f, 420f), durationMillis = 300) }
                waitForIdle()
                assertA11y(copy.fitTree, copy.fitTreeAction)
                onNodeWithContentDescription(copy.fitTree).performClick()
                waitForIdle()
                assertTrue(reported.isEmpty(), "Fit never spends")
            }
        }
    }

    // ------------------------------------------------------------------ 8. Reset talents and Undo

    @Test
    fun resetAndUndoReadInBothLanguagesWithPluralCounts() {
        val warrior = productState("WARRIOR")
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            for (points in listOf(1, 21)) {
                val build = buildWithPoints(warrior, points)
                onEditor(language, content = tree(warrior.copy(buildState = build))) {
                    assertShows("$points / 51")
                    onNodeWithContentDescription(copy.buildActionsOpen).performClick()
                    waitForIdle()
                    assertShows(copy.buildActionsTitle, copy.resetTalents)
                    assertA11y(copy.buildActionsClose)
                    click(copy.resetTalents)
                    assertShows("0 / 51", copy.resetNotice(points), copy.undo)
                    assertA11y(copy.undoAction)
                    if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("reset $points")
                    click(copy.undo)
                    assertShows("$points / 51")
                    assertEquals(build, reported.last(), "Undo restores exactly the reset build")
                }
            }
        }
        assertEquals("Таланты сброшены · снято 21 очко", EditorCopy.Russian.resetNotice(21))
        assertEquals("Таланты сброшены · снято 1 очко", EditorCopy.Russian.resetNotice(1))
    }

    // ------------------------------------------------------------------ 9. header

    @Test
    fun headerButtonsAndTheirAccessibilityLabelsReadInBothLanguages() {
        val warrior = productState("WARRIOR").copy(buildTitle = "Arms PvP")
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            val warriorName = if (language == AppLanguage.RUSSIAN) "Воин" else "Warrior"
            onEditor(language, content = tree(warrior)) {
                assertShows("Arms PvP", "$warriorName · Forever", "0 / 51")
                assertA11y(copy.undoAction, copy.redoAction, copy.buildActionsOpen, copy.goBack)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("header")
            }
        }
    }

    // ------------------------------------------------------------------ 10. autosave failure and Retry

    @Test
    fun autosaveFailureAndRetryReadInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            val storage = FakeBuildLibraryStorage()
            val repository = repositoryOver(storage)
            val draft = warriorDraft(repository)
            onEditor(language, content = buildScreen(draft.localId, repository)) {
                awaitText(copy.unnamedWarriorTitle)
                // writes fail from now on — opening the build already wrote successfully
                storage.failWrites = true
                select("Improved Rend")
                onNodeWithContentDescription(copy.addRank("Improved Rend")).performClick()
                awaitText(copy.autosaveFailed)
                assertShows(copy.retry)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("autosave failure")
                storage.failWrites = false
                click(copy.retry)
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithText(copy.autosaveFailed).fetchSemanticsNodes().isEmpty()
                }
            }
            val saved = storedRecords(storage).single()
            assertEquals(1, saved.build.allocations.single().rank, "Retry persisted the newest build")
        }
    }

    // ------------------------------------------------------------------ 11. description labels

    @Test
    fun descriptionLabelsAndTheUnavailableLineReadInBothLanguages() {
        // the reference bundle's verified tooltips (as in TalentTreeDescriptionUiTest)
        val warrior = readyStateOnBundle(REFERENCE_BUNDLE.clientBuild, "WARRIOR")
        val rogue = readyStateOnBundle(REFERENCE_BUNDLE.clientBuild, "ROGUE")
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            onEditor(language, content = tree(warrior)) {
                select("Improved Rend")
                onNodeWithContentDescription(copy.expand).performClick()
                waitForIdle()
                assertShows(copy.current, copy.nextRank)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("comparison")
            }
            onEditor(language, content = tree(warrior.copy(descriptions = TalentNodeDescriptions.Unavailable))) {
                select("Improved Rend")
                onNodeWithContentDescription(copy.expand).performClick()
                waitForIdle()
                assertShows(copy.descriptionUnavailable)
            }
            // landscape side panel: the full detail of a description with a character-dependent value
            onEditor(language, width = 840, height = 600, content = tree(rogue)) {
                select("Relentless Strikes")
                assertShows(copy.missingCharacter)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("missing note")
            }
        }
    }

    // ------------------------------------------------------------------ 12. a language switch is presentation only

    @Test
    fun switchingTheLanguageChangesOnlyTheWords() {
        val warrior = productState("WARRIOR")
        val cameras = TalentTreeSectionCameras(sessionKey = "rel2b-switch", treeId = warrior.model.treeId)
        var language by mutableStateOf(AppLanguage.ENGLISH)
        uiLanguage.language = AppLanguage.ENGLISH
        reported.clear()
        runDesktopComposeUiTest(width = 393, height = 852) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalReducedMotion provides true,
                        LocalAppLanguage provides language,
                    ) {
                        Box(Modifier.size(393.dp, 852.dp)) {
                            TalentTreeScreen(warrior, onBuildStateChanged = { reported += it }, sectionCameras = cameras)
                        }
                    }
                }
            }
            waitForIdle()
            select("Improved Rend")
            onNodeWithContentDescription(EditorCopy.English.addRank("Improved Rend")).performClick()
            waitForIdle()
            onRoot().performTouchInput { swipe(Offset(200f, 300f), Offset(200f, 380f), durationMillis = 300) }
            waitForIdle()
            onNodeWithContentDescription(EditorCopy.English.expand).performClick()
            waitForIdle()
            val camera = assertNotNull(cameras.stateOf(0).viewport)
            val build = reported.single()

            runOnIdle {
                uiLanguage.language = AppLanguage.RUSSIAN
                language = AppLanguage.RUSSIAN
            }
            waitForIdle()

            // the words changed …
            assertShows(EditorCopy.Russian.inProgress)
            assertA11y(EditorCopy.Russian.collapse, EditorCopy.Russian.removeRank("Improved Rend"))
            assertEquals(
                EditorCopy.Russian.pointsSpent(1),
                onNodeWithText("Оружие").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
            )
            assertNoEnglishEditorCopy("after the switch")
            // … nothing else did: build, selection, expansion, camera, reports
            assertEquals(build, reported.single(), "a language change never reports a build change")
            onNodeWithContentDescription("Improved Rend").assertIsSelected()
            assertShows("1 / 51", "1/3")
            assertEquals(camera, cameras.stateOf(0).viewport, "the camera is untouched")

            runOnIdle {
                uiLanguage.language = AppLanguage.ENGLISH
                language = AppLanguage.ENGLISH
            }
            waitForIdle()
            assertA11y(EditorCopy.English.collapse)
            assertEquals(build, reported.single())
            assertEquals(camera, cameras.stateOf(0).viewport)
        }
    }

    @Test
    fun aLanguageSwitchNeverAutosavesOrPersistsCopy() {
        val storage = FakeBuildLibraryStorage()
        val repository = repositoryOver(storage)
        val draft = warriorDraft(repository)
        var language by mutableStateOf(AppLanguage.ENGLISH)
        uiLanguage.language = AppLanguage.ENGLISH
        runDesktopComposeUiTest(width = 393, height = 852) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalReducedMotion provides true,
                        LocalAppLanguage provides language,
                    ) {
                        Box(Modifier.size(393.dp, 852.dp)) { buildScreen(draft.localId, repository)() }
                    }
                }
            }
            awaitText(EditorCopy.English.unnamedWarriorTitle)
            waitForIdle()
            val opened = storage.writeCount
            select("Improved Rend")
            onNodeWithContentDescription(EditorCopy.English.addRank("Improved Rend")).performClick()
            // the spend's autosave lands …
            waitUntil(timeoutMillis = 20_000) { storage.writeCount == opened + 1 }
            waitForIdle()
            val writes = storage.writeCount
            val payload = storage.payload

            runOnIdle {
                uiLanguage.language = AppLanguage.RUSSIAN
                language = AppLanguage.RUSSIAN
            }
            awaitText(EditorCopy.Russian.unnamedWarriorTitle)
            assertNoEnglishEditorCopy("build screen after the switch")
            waitForIdle()
            assertEquals(writes, storage.writeCount, "a language change never autosaves")
            assertEquals(payload, storage.payload, "the stored build is byte-identical")
        }
        assertNull(storedRecords(storage).single().name, "the localized unnamed-build title is never persisted")
    }

    // ------------------------------------------------------------------ 14. nameless and unavailable talents

    @Test
    fun namelessAndUnavailableTalentsUseTheLocalizedFallbacks() {
        val baseline = baselineWarrior()
        val document = baseline.engine.document
        val talentTree = document.trees.single()
        val rend = talentTree.nodes.first { it.name == "Improved Rend" }.id
        val deflection = talentTree.nodes.first { it.name == "Deflection" }
        val synthetic = document.copy(
            trees = listOf(
                talentTree.copy(
                    nodes = talentTree.nodes.map { node ->
                        when (node.id) {
                            rend -> node.copy(name = null) // the dataset publishes no name
                            deflection.id -> node.copy(flags = 2) // NeverPurchasable: fail-closed unavailable
                            else -> node
                        }
                    },
                ),
            ),
        )
        val engine = TalentAllocationEngine(synthetic)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(synthetic)).model
        val state = baseline.copy(model = model, engine = engine, buildState = engine.emptyState())
        val (unavailable, _) = inspectorInputsOf(state, state.buildState, "Deflection")
        assertEquals(InspectorText.Unavailable, unavailable.stateLine)
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            val nameless = copy.fallbackName(rend)
            onEditor(language, content = tree(state)) {
                select(nameless)
                assertShows(nameless)
                assertEquals(copy.available, reasonLine())
                select("Deep Wounds")
                assertEquals(copy.requiresPrerequisite(nameless, 3), reasonLine())
                assertA11y(copy.showAction(nameless))
                select("Deflection")
                val reason = assertNotNull(unavailable.prerequisiteLine ?: unavailable.tierLine ?: unavailable.stateLine)
                assertEquals(copy.textOf(reason), reasonLine())
                assertEquals(
                    "0/${deflection.maxRank}, ${copy.nodeState(TalentNodeState.UNAVAILABLE)}",
                    stateDescriptionOf("Deflection"),
                )
                if (language == AppLanguage.RUSSIAN) assertNoEnglishEditorCopy("fallbacks")
            }
        }
    }

    // ------------------------------------------------------------------ 15. user build titles

    @Test
    fun userBuildTitlesStayVerbatimAndTheUnnamedTitleIsTheLocalizedClassBuild() {
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            val storage = FakeBuildLibraryStorage()
            val repository = repositoryOver(storage)
            val unnamed = warriorDraft(repository)
            val named = warriorDraft(repository)
            assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.renameBuild(named.localId, "Arms — PvP Draft"))
            onEditor(language, content = buildScreen(unnamed.localId, repository)) {
                // TF-RC1-DRAFT-TITLE: the same `<Class> build` title Home and
                // All Builds show — never the former "Draft" / "Черновик"
                awaitText(copy.unnamedWarriorTitle)
                assertTrue("Draft" !in texts() && "Черновик" !in texts(), "no separate Draft title in the editor")
            }
            onEditor(language, content = buildScreen(named.localId, repository)) {
                awaitText("Arms — PvP Draft")
                assertTrue(copy.unnamedWarriorTitle !in texts(), "a user name is never replaced by the unnamed-build title")
            }
            assertEquals(
                setOf(null, "Arms — PvP Draft"),
                storedRecords(storage).map { it.name }.toSet(),
                "nothing localized is stored",
            )
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** A build with exactly [points] spent, spent greedily through the ENGINE in tree order. */
    private fun buildWithPoints(state: TalentTreeUiState.Ready, points: Int): BuildState {
        val engine = state.engine
        var session = TalentBuildEditingState.start(engine)
        var progressed = true
        while (engine.spentPoints(session.current) < points && progressed) {
            progressed = false
            for (node in engine.tree.nodes) {
                if (engine.spentPoints(session.current) >= points) break
                val result = session.spend(engine, node.id)
                if (result.changed) {
                    session = result.editing
                    progressed = true
                }
            }
        }
        check(engine.spentPoints(session.current) == points) { "could not spend exactly $points" }
        return session.current
    }

    /** A storage whose reads wait for [release] — holds the editor on its opening pane. */
    private class GatedStorage(private val delegate: FakeBuildLibraryStorage) : BuildLibraryStorage {
        private val gate = CountDownLatch(1)

        fun release() = gate.countDown()

        override fun read(): String? {
            gate.await(30, TimeUnit.SECONDS)
            return delegate.read()
        }

        override fun write(payload: String) = delegate.write(payload)
    }
}
