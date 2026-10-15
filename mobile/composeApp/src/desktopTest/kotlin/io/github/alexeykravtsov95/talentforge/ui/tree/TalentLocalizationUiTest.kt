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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.localization.LocalizationLoadError
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentLocalizationLoader
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/** Real Warrior talents and their client ruRU names (identical on all four bundled builds). */
internal object WarriorText {
    const val HEROIC_STRIKE_EN = "Improved Heroic Strike"
    const val HEROIC_STRIKE_RU = "Улучшенный удар героя"
    const val REND_EN = "Improved Rend"
    const val REND_RU = "Улучшенное кровопускание"
    const val DEEP_WOUNDS_EN = "Deep Wounds"
    const val DEEP_WOUNDS_RU = "Глубокие раны"
    const val BLOODTHIRST_EN = "Bloodthirst"
    const val BLOODTHIRST_RU = "Кровожадность"
    const val TACTICAL_MASTERY_RU = "Улучшенное тактическое превосходство"

    fun heroicStrikeRu(rage: Int) = "Снижает затраты ярости на \"Удар героя\" на $rage ед."
    fun heroicStrikeEn(rage: Int) = "Reduces the cost of your Heroic Strike ability by $rage Rage."
}

/**
 * The Ready state of [classToken] on bundled [clientBuild] exactly as an editor session builds
 * it (M5.REL.3b.2): icons, descriptions and the localization overlay checked against them.
 */
internal fun localizedStateOnBundle(
    clientBuild: String,
    classToken: String,
    assets: DatasetAssetSource = ComposeResourceDatasetAssetSource(),
): TalentTreeUiState.Ready = runBlocking {
    val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
        BundledTalentDatasetRepository(assets)
            .document(DatasetId(PRODUCT_BUNDLE.game, clientBuild, ClassToken(classToken))),
    ).value
    val tree = document.trees.single()
    val engine = TalentAllocationEngine(document)
    val icons = TalentIconsLoader(assets).load(document, tree)
    val descriptions = TalentDescriptionsLoader(assets).load(document, tree)
    TalentTreeUiState.Ready(
        classDisplayName = document.classInfo.name,
        gameDisplayName = "Forever",
        classToken = document.classInfo.token,
        model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model,
        engine = engine,
        buildState = engine.emptyState(),
        buildTitle = "${document.classInfo.name} Build",
        classAccent = classAccentFor(document.classInfo.token).color,
        icons = icons.icons,
        descriptions = descriptions.descriptions,
        localization = TalentLocalizationLoader(assets).load(document, tree, descriptions.tooltips, icons.visuals),
    )
}

internal fun localizedProductState(classToken: String): TalentTreeUiState.Ready =
    localizedStateOnBundle(PRODUCT_BUNDLE.clientBuild, classToken)

/** Real bundled assets with single paths replaced ([overrides]) — e.g. a corrupt overlay. */
internal class OverridingAssetSource(
    private val overrides: Map<String, ByteArray?>,
    private val delegate: DatasetAssetSource = ComposeResourceDatasetAssetSource(),
) : DatasetAssetSource {
    override suspend fun read(path: String): ByteArray =
        if (path in overrides) overrides[path] ?: throw AssetNotFoundException(path) else delegate.read(path)
}

/**
 * M5.REL.3b.2: the talent editor shows the client's own Russian talent names and the
 * descriptions it can show honestly when the language is Russian, unchanged English otherwise,
 * English for a whole class whose overlay is missing or rejected — and a language change is
 * presentation only. Real bundled data throughout.
 */
@OptIn(ExperimentalTestApi::class)
class TalentLocalizationUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val warrior by lazy { localizedProductState("WARRIOR") }

    // ------------------------------------------------------------------ harness

    private fun onEditor(
        language: AppLanguage,
        state: TalentTreeUiState.Ready,
        width: Int = 760,
        height: Int = 360,
        reported: MutableList<BuildState> = mutableListOf(),
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalReducedMotion provides true,
                        LocalAppLanguage provides language,
                    ) {
                        Box(Modifier.size(width.dp, height.dp)) {
                            TalentTreeScreen(state = state, onBack = {}, onBuildStateChanged = { reported += it })
                        }
                    }
                }
            }
            waitForIdle()
            block()
        }
    }

    private fun ComposeUiTest.select(name: String) {
        onNodeWithContentDescription(name).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.allNodes(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    private fun ComposeUiTest.texts(): Set<String> =
        allNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }.toSet()

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

    private fun ComposeUiTest.assertNotShown(vararg unexpected: String) {
        val shown = texts() + a11yLabels()
        for (text in unexpected) assertTrue(shown.none { it.contains(text) }, "'$text' must not be on screen")
    }

    /** The full text of the inspector's description of the current/prospective rank. */
    private fun ComposeUiTest.descriptionTexts(): List<String> = allNodes()
        .filter { node -> generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == TALENT_INSPECTOR_DESCRIPTION_TAG } }
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun loadedLocalization(state: TalentTreeUiState.Ready): TalentNodeLocalization.Loaded =
        assertIs<TalentNodeLocalization.Loaded>(state.localization)

    private fun nodeIdOf(state: TalentTreeUiState.Ready, englishName: String): Int =
        state.engine.tree.nodes.single { it.name == englishName }.id

    private fun TalentTooltipRank.text(): String = segments.joinToString("") { it.text }

    // ------------------------------------------------------------------ Russian vs English

    @Test
    fun aRussianDeviceShowsTheRussianNameAndDescription() {
        onEditor(AppLanguage.RUSSIAN, warrior) {
            select(WarriorText.HEROIC_STRIKE_RU)
            assertShows(WarriorText.HEROIC_STRIKE_RU, WarriorText.heroicStrikeRu(1))
            assertNotShown(WarriorText.HEROIC_STRIKE_EN, "Reduces the cost of your Heroic Strike")
            // the rank-1 preview of an unspent talent: CURRENT —, NEXT RANK 1
            assertShows(EditorCopy.Russian.current, EditorCopy.Russian.nextRank, MISSING_MARK, "1")
        }
    }

    @Test
    fun anEnglishDeviceKeepsTheEnglishEditorUnchanged() {
        onEditor(AppLanguage.ENGLISH, warrior) {
            select(WarriorText.HEROIC_STRIKE_EN)
            assertShows(WarriorText.HEROIC_STRIKE_EN, WarriorText.heroicStrikeEn(1))
            assertNotShown(WarriorText.HEROIC_STRIKE_RU, "Снижает затраты ярости")
            assertTrue(a11yLabels().none { label -> label.any { it in 'А'..'я' } }, "no Russian talent name on an English device")
        }
        // the English presentation is the one without any overlay at all
        val english = talentNodeDisplayText(AppLanguage.ENGLISH, warrior.descriptions, warrior.localization)
        assertEquals(emptyMap(), english.names)
        assertEquals(warrior.descriptions, english.descriptions)
    }

    @Test
    fun russianDescriptionsFollowEveryRankWithTheirComparison() {
        val rend = nodeIdOf(warrior, WarriorText.REND_EN)
        val russian = assertNotNull(loadedLocalization(warrior).ranks[rend])
        assertEquals(3, russian.size)
        onEditor(AppLanguage.RUSSIAN, warrior) {
            select(WarriorText.REND_RU)
            assertShows(russian[0].text())
            for (rank in 1..3) {
                onNodeWithContentDescription(EditorCopy.Russian.addRank(WarriorText.REND_RU)).performClick()
                waitForIdle()
                assertShows("$rank/3", russian[rank - 1].text())
                if (rank < 3) {
                    // CURRENT / NEXT RANK show the Russian ranks' changing values
                    val current = russian[rank - 1].segments.filter { it.kind.name == "VALUE" }.map { it.text }
                    val next = russian[rank].segments.filter { it.kind.name == "VALUE" }.map { it.text }
                    assertShows(EditorCopy.Russian.current, EditorCopy.Russian.nextRank, *(current - next).toTypedArray(), *(next - current).toTypedArray())
                }
            }
            assertNotShown("Increases the Bleed damage")
        }
    }

    @Test
    fun aRussianNameCanSitOverItsEnglishDescription() {
        // Improved Slam: its 0.5 s is a decimal (decimalFormatUnverified) — the name is the
        // client's Russian, the description the unchanged English
        val slam = nodeIdOf(warrior, "Improved Slam")
        assertTrue(slam !in loadedLocalization(warrior).ranks)
        onEditor(AppLanguage.RUSSIAN, warrior, width = 1280, height = 800) {
            select("Улучшенное сокрушение")
            assertShows("Улучшенное сокрушение")
            assertTrue(descriptionTexts().any { it.startsWith("Reduces the global cooldown and cast time of your Slam") }, "${descriptionTexts()}")
        }
        // Bloodthirst: the English sidecar is partial (sourceNotResolved) — its missing-value
        // marker stays, under the Russian name (tablet: the whole tree is on screen)
        val bloodthirst = nodeIdOf(warrior, WarriorText.BLOODTHIRST_EN)
        assertTrue(bloodthirst !in loadedLocalization(warrior).ranks)
        onEditor(AppLanguage.RUSSIAN, warrior, width = 1280, height = 800) {
            select(WarriorText.BLOODTHIRST_RU)
            assertShows(WarriorText.BLOODTHIRST_RU)
            assertTrue(descriptionTexts().any { it.startsWith("Instantly attack the target") }, "${descriptionTexts()}")
            assertTrue(descriptionTexts().any { MISSING_MARK in it }, "the English gap marker stays")
        }
    }

    @Test
    fun prerequisiteShowAndNeededForNameTheRussianTalents() {
        onEditor(AppLanguage.RUSSIAN, warrior) {
            select(WarriorText.DEEP_WOUNDS_RU)
            assertShows(EditorCopy.Russian.requiresPrerequisite(WarriorText.REND_RU, 3))
            val show = onNodeWithText(EditorCopy.Russian.show)
            assertEquals(
                EditorCopy.Russian.showAction(WarriorText.REND_RU),
                show.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
            )
            show.performClick()
            waitForIdle()
            onNodeWithContentDescription(WarriorText.REND_RU).assertIsSelected()
            assertShows(EditorCopy.Russian.neededFor(EditorCopy.Russian.available, WarriorText.DEEP_WOUNDS_RU))
            assertNotShown(WarriorText.REND_EN, WarriorText.DEEP_WOUNDS_EN)
        }
    }

    @Test
    fun nodeAccessibilityLabelsAndRankActionsUseTheRussianNames() {
        val localization = loadedLocalization(warrior)
        onEditor(AppLanguage.RUSSIAN, warrior) {
            // every visible single-entry node with a translated name is labelled with it
            val labels = a11yLabels()
            val visibleRussian = localization.names.values.filter { it in labels }
            assertTrue(visibleRussian.size >= 10, "Russian node labels on screen: $visibleRussian")
            assertTrue(WarriorText.HEROIC_STRIKE_EN !in labels)
            select(WarriorText.HEROIC_STRIKE_RU)
            val actions = a11yLabels()
            assertTrue(EditorCopy.Russian.addRank(WarriorText.HEROIC_STRIKE_RU) in actions, "$actions")
            assertTrue(EditorCopy.Russian.maxOut(WarriorText.HEROIC_STRIKE_RU) in actions)
        }
    }

    // ------------------------------------------------------------------ fallback

    @Test
    fun aCorruptOverlayShowsTheWholeClassInEnglishWithoutFailingTheEditor() {
        val path = "localization/forever/${PRODUCT_BUNDLE.clientBuild}/ruRU/warrior.json"
        val corrupt = runBlocking { ComposeResourceDatasetAssetSource().read(path) }.decodeToString()
            .replace(WarriorText.HEROIC_STRIKE_RU, "Подделка") // tampered without a new contentHash
            .encodeToByteArray()
        val state = localizedStateOnBundle(PRODUCT_BUNDLE.clientBuild, "WARRIOR", OverridingAssetSource(mapOf(path to corrupt)))
        assertIs<LocalizationLoadError.ContentHashMismatch>(assertIs<TalentNodeLocalization.Rejected>(state.localization).error)
        onEditor(AppLanguage.RUSSIAN, state) {
            // Russian UI copy, English talent text — for every talent of the class
            select(WarriorText.HEROIC_STRIKE_EN)
            assertShows(WarriorText.HEROIC_STRIKE_EN, WarriorText.heroicStrikeEn(1), EditorCopy.Russian.available)
            assertNotShown("Подделка", WarriorText.HEROIC_STRIKE_RU, WarriorText.REND_RU)
        }
    }

    @Test
    fun aMissingOverlayAndMissingEnglishInputsFallBackToEnglish() {
        val path = "localization/forever/${PRODUCT_BUNDLE.clientBuild}/ruRU/warrior.json"
        val missing = localizedStateOnBundle(PRODUCT_BUNDLE.clientBuild, "WARRIOR", OverridingAssetSource(mapOf(path to null)))
        assertEquals(
            LocalizationLoadError.AssetMissing(path),
            assertIs<TalentNodeLocalization.Rejected>(missing.localization).error,
        )
        // without verified English tooltips there is nothing to check an overlay against
        val tooltipsPath = "tooltips/forever/${PRODUCT_BUNDLE.clientBuild}/warrior.json"
        val noTooltips = localizedStateOnBundle(PRODUCT_BUNDLE.clientBuild, "WARRIOR", OverridingAssetSource(mapOf(tooltipsPath to null)))
        assertEquals(TalentNodeDescriptions.Unavailable, noTooltips.descriptions)
        assertEquals(TalentNodeLocalization.None, noTooltips.localization)
        onEditor(AppLanguage.RUSSIAN, noTooltips) {
            select(WarriorText.HEROIC_STRIKE_EN)
            assertShows(WarriorText.HEROIC_STRIKE_EN, EditorCopy.Russian.descriptionUnavailable)
        }
    }

    // ------------------------------------------------------------------ sessions and builds

    @Test
    fun savedBuildsOfEveryClientVersionOpenWithTheirOwnBuildsText() {
        val storage = FakeBuildLibraryStorage()
        val repository = LocalBuildRepository(storage, FixedClock(), SequentialIds())
        val assets = ComposeResourceDatasetAssetSource()
        val datasets = BundledTalentDatasetRepository(assets)
        for (bundle in ALL_BUNDLES) {
            for (token in listOf("WARRIOR", "HUNTER", "PRIEST")) {
                val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
                    runBlocking { datasets.document(DatasetId(GameId("forever"), bundle.clientBuild, ClassToken(token))) },
                ).value
                val engine = TalentAllocationEngine(document)
                val file = buildFileOf(engine.emptyState(), document.game, bundle.clientBuild, ClassToken(token))
                val localId = assertIs<BuildLibraryOutcome.Loaded<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord>>(
                    repository.createDraft(file),
                ).value.localId
                val session = assertIs<EditorLoad.Ready>(
                    runBlocking {
                        loadEditorSession(
                            repository, datasets, localId,
                            icons = TalentIconsLoader(assets),
                            descriptions = TalentDescriptionsLoader(assets),
                            localization = TalentLocalizationLoader(assets),
                        )
                    },
                ).session
                val localization = assertIs<TalentNodeLocalization.Loaded>(session.localization, "${bundle.clientBuild} $token")
                assertEquals(bundle.clientBuild, session.record.build.clientBuild)
                // the names come from THIS build's overlay: compare with the bundled file directly
                val overlay = runBlocking { assets.read("localization/forever/${bundle.clientBuild}/ruRU/${token.lowercase()}.json") }
                    .decodeToString()
                for (name in localization.names.values) {
                    val quoted = kotlinx.serialization.json.JsonPrimitive(name).toString() // JSON-escaped, e.g. \"…\"
                    assertTrue(quoted in overlay, "${bundle.clientBuild} $token: $name")
                }
                assertTrue(localization.names.size > 30, "${bundle.clientBuild} $token: ${localization.names.size} names")
            }
        }
    }

    @Test
    fun aDatasetFailureStaysTheTypedEditorFailure() {
        val repository = LocalBuildRepository(FakeBuildLibraryStorage(), FixedClock(), SequentialIds())
        val assets = ComposeResourceDatasetAssetSource()
        // a build of a client build that is not bundled: the dataset gate fails first
        val file = buildFileOf(warrior.engine.emptyState(), GameId("forever"), "1.60.1.1", ClassToken("WARRIOR"))
        val localId = assertIs<BuildLibraryOutcome.Loaded<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord>>(
            repository.createDraft(file),
        ).value.localId
        val load = runBlocking {
            loadEditorSession(
                repository, BundledTalentDatasetRepository(assets), localId,
                icons = TalentIconsLoader(assets),
                descriptions = TalentDescriptionsLoader(assets),
                localization = TalentLocalizationLoader(assets),
            )
        }
        assertIs<io.github.alexeykravtsov95.talentforge.ui.library.EditorFailure.Dataset>(assertIs<EditorLoad.Failed>(load).failure)
    }

    @Test
    fun theProductionEditorOpensARussianBuildInRussian() {
        val storage = FakeBuildLibraryStorage()
        val repository = LocalBuildRepository(storage, FixedClock(), SequentialIds())
        val assets = ComposeResourceDatasetAssetSource()
        val datasets = BundledTalentDatasetRepository(assets)
        val draft = assertIs<io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation.Created>(
            runBlocking { io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft(repository, datasets, ClassToken("WARRIOR")) },
        ).record
        uiLanguage.language = AppLanguage.RUSSIAN
        runDesktopComposeUiTest(width = 760, height = 360) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalReducedMotion provides true,
                        LocalAppLanguage provides AppLanguage.RUSSIAN,
                    ) {
                        Box(Modifier.size(760.dp, 360.dp)) { productionEditor(draft.localId, repository, datasets, assets) }
                    }
                }
            }
            awaitLabel(WarriorText.HEROIC_STRIKE_RU)
            select(WarriorText.HEROIC_STRIKE_RU)
            assertShows(WarriorText.HEROIC_STRIKE_RU, WarriorText.heroicStrikeRu(1))
        }
    }

    // ------------------------------------------------------------------ language change

    @Test
    fun aLanguageChangeSwapsTheTalentTextAndNothingElse() {
        val cameras = TalentTreeSectionCameras(sessionKey = "rel3b2-switch", treeId = warrior.model.treeId)
        val reported = mutableListOf<BuildState>()
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
                        Box(Modifier.size(393.dp, 852.dp)) {
                            TalentTreeScreen(warrior, onBuildStateChanged = { reported += it }, sectionCameras = cameras)
                        }
                    }
                }
            }
            waitForIdle()
            select(WarriorText.REND_EN)
            onNodeWithContentDescription(EditorCopy.English.addRank(WarriorText.REND_EN)).performClick()
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
            // the talent text changed …
            onNodeWithContentDescription(WarriorText.REND_RU).assertIsSelected()
            assertShows(WarriorText.REND_RU, "1/3")
            assertNotShown(WarriorText.REND_EN, "Increases the Bleed damage")
            assertTrue(onAllNodesWithContentDescription(EditorCopy.Russian.collapse).fetchSemanticsNodes().isNotEmpty())
            // … nothing else did: build, selection, expansion, camera, no report
            assertEquals(build, reported.single(), "a language change never reports a build change")
            assertEquals(camera, cameras.stateOf(0).viewport, "the camera is untouched")

            // undo still undoes the spend made before the switch
            onNodeWithContentDescription(EditorCopy.Russian.undoAction).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(2, reported.size)
            assertEquals(0, reported.last().rankOf(nodeIdOf(warrior, WarriorText.REND_EN)))

            runOnIdle {
                uiLanguage.language = AppLanguage.ENGLISH
                language = AppLanguage.ENGLISH
            }
            waitForIdle()
            onNodeWithContentDescription(WarriorText.REND_EN).assertIsSelected()
            assertEquals(2, reported.size, "switching back reports nothing either")
        }
    }

    @Test
    fun aLanguageChangeInTheBuildEditorNeverAutosaves() {
        val storage = FakeBuildLibraryStorage()
        val repository = LocalBuildRepository(storage, FixedClock(), SequentialIds())
        val assets = ComposeResourceDatasetAssetSource()
        val datasets = BundledTalentDatasetRepository(assets)
        val draft = assertIs<io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation.Created>(
            runBlocking { io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft(repository, datasets, ClassToken("WARRIOR")) },
        ).record
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
                        Box(Modifier.size(393.dp, 852.dp)) { productionEditor(draft.localId, repository, datasets, assets) }
                    }
                }
            }
            awaitLabel(WarriorText.REND_EN)
            val opened = storage.writeCount
            select(WarriorText.REND_EN)
            onNodeWithContentDescription(EditorCopy.English.addRank(WarriorText.REND_EN)).performClick()
            waitUntil(timeoutMillis = 20_000) { storage.writeCount == opened + 1 }
            waitForIdle()
            val writes = storage.writeCount
            val payload = storage.payload

            runOnIdle {
                uiLanguage.language = AppLanguage.RUSSIAN
                language = AppLanguage.RUSSIAN
            }
            awaitLabel(WarriorText.REND_RU)
            onNodeWithContentDescription(WarriorText.REND_RU).assertIsSelected()
            assertShows("1/3")
            waitForIdle()
            assertEquals(writes, storage.writeCount, "a language change never autosaves")
            assertEquals(payload, storage.payload, "the stored build is byte-identical")
        }
    }

    @Composable
    private fun productionEditor(
        localId: String,
        repository: LocalBuildRepository,
        datasets: BundledTalentDatasetRepository,
        assets: DatasetAssetSource,
    ) {
        val scope = rememberCoroutineScope()
        TalentTreeBuildScreen(
            localId = localId,
            repository = repository,
            datasets = datasets,
            autosave = remember { BuildAutosaveCoordinator(repository, scope) },
            onBack = {},
            icons = remember { TalentIconsLoader(assets) },
            descriptions = remember { TalentDescriptionsLoader(assets) },
            localization = remember { TalentLocalizationLoader(assets) },
        )
    }

    private fun ComposeUiTest.awaitLabel(label: String) {
        try {
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("'$label' never appeared; labels: ${a11yLabels()}", timeout)
        }
        waitForIdle()
    }
}
