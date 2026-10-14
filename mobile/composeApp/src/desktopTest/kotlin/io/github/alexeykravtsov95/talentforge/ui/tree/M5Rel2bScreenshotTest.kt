package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * M5.REL.2b PR evidence: the REAL localized talent editor, desktop-rendered
 * from the bundled product data and artwork in English and Russian — the
 * compact and expanded inspector, prerequisite- and tier-locked talents, the
 * build-actions sheet and the reset notice, the cross-section Back chip, the
 * autosave failure, the loading and error panes — at 393 × 852, the narrow
 * 320 × 568, 915 × 412 landscape and 200 % font scale.
 *
 * Skipped unless the caller opts in (evidence, not a regression assertion —
 * the assertions live in `EditorLocalizationUiTest` and
 * `EditorLocalizationLayoutUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5Rel2bScreenshotTest*" \
 *     -Dtalentforge.m5rel2b.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class M5Rel2bScreenshotTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    private val assets = ComposeResourceDatasetAssetSource()
    private val datasets = BundledTalentDatasetRepository(assets)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun onScreen(
        language: AppLanguage,
        widthDp: Int = 393,
        heightDp: Int = 852,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = (widthDp * DENSITY).toInt(), height = (heightDp * DENSITY).toInt()) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(DENSITY, fontScale),
                        LocalReducedMotion provides true,
                        LocalAppLanguage provides language,
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) { content() }
                    }
                }
            }
            waitForIdle()
            block()
        }
    }

    private fun editor(state: TalentTreeUiState): @Composable () -> Unit = {
        TalentTreeScreen(state = state, onBack = {})
    }

    private fun buildScreen(localId: String, repository: LocalBuildRepository): @Composable () -> Unit = {
        val scope = rememberCoroutineScope()
        TalentTreeBuildScreen(
            localId = localId,
            repository = repository,
            datasets = datasets,
            autosave = remember { BuildAutosaveCoordinator(repository, scope) },
            onBack = {},
            // the real artwork and descriptions, as the app opens a build
            icons = TalentIconsLoader(assets),
            descriptions = TalentDescriptionsLoader(assets),
        )
    }

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.select(talent: String) {
        onNodeWithContentDescription(talent).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        waitForIdle()
        val image = onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }

    @Test
    fun m5rel2bLocalizedEditor() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        val available = scenarios.getValue("available")
        val partial = scenarios.getValue("partial")
        val prerequisite = scenarios.getValue("prerequisite")
        val tier = scenarios.getValue("tier")
        val crossSection = crossSectionState(warrior)

        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            val tag = if (language == AppLanguage.RUSSIAN) "ru" else "en"
            val protection = if (language == AppLanguage.RUSSIAN) "Защита" else "Protection"
            // an unnamed build, as the app shows it: the localized Draft title
            fun state(scenario: InspectorScenario) = warrior.copy(buildState = scenario.build, buildTitle = copy.draft)

            // 393 × 852: compact, expanded, both locks, actions sheet, reset notice, Back chip
            onScreen(language, content = editor(state(available))) {
                select(available.nodeName)
                shoot("m5rel2b-$tag-393x852-compact.png")
            }
            onScreen(language, content = editor(state(partial))) {
                select(partial.nodeName)
                onNodeWithContentDescription(copy.expand).performClick()
                shoot("m5rel2b-$tag-393x852-expanded.png")
            }
            onScreen(language, content = editor(state(prerequisite))) {
                select(prerequisite.nodeName)
                shoot("m5rel2b-$tag-393x852-prerequisite-locked.png")
            }
            onScreen(language, content = editor(state(tier))) {
                select(tier.nodeName)
                shoot("m5rel2b-$tag-393x852-tier-locked.png")
            }
            onScreen(language, content = editor(state(partial))) {
                onNodeWithContentDescription(copy.buildActionsOpen).performClick()
                shoot("m5rel2b-$tag-393x852-build-actions.png")
                onNodeWithText(copy.resetTalents).performClick()
                shoot("m5rel2b-$tag-393x852-reset-feedback.png")
            }
            onScreen(language, content = editor(crossSection.first.copy(buildTitle = copy.draft))) {
                onNodeWithText(protection).performClick()
                waitForIdle()
                select(crossSection.second)
                onNodeWithText(copy.show).performClick()
                shoot("m5rel2b-$tag-393x852-cross-section-back-chip.png")
            }

            // 320 × 568
            onScreen(language, widthDp = 320, heightDp = 568, content = editor(state(prerequisite))) {
                select(prerequisite.nodeName)
                shoot("m5rel2b-$tag-320x568-prerequisite-locked.png")
            }
            onScreen(language, widthDp = 320, heightDp = 568, content = editor(state(partial))) {
                select(partial.nodeName)
                shoot("m5rel2b-$tag-320x568-compact.png")
                onNodeWithContentDescription(copy.expand).performClick()
                shoot("m5rel2b-$tag-320x568-expanded.png")
            }
            onScreen(language, widthDp = 320, heightDp = 568, content = editor(state(partial))) {
                onNodeWithContentDescription(copy.buildActionsOpen).performClick()
                waitForIdle()
                onNodeWithText(copy.resetTalents).performClick()
                shoot("m5rel2b-$tag-320x568-reset-feedback.png")
            }

            // 915 × 412 landscape: the side inspector
            onScreen(language, widthDp = 915, heightDp = 412, content = editor(state(partial))) {
                select(partial.nodeName)
                shoot("m5rel2b-$tag-915x412-side-panel.png")
            }
            onScreen(language, widthDp = 915, heightDp = 412, content = editor(state(tier))) {
                select(tier.nodeName)
                shoot("m5rel2b-$tag-915x412-tier-locked.png")
            }

            // 200 % text
            onScreen(language, fontScale = 2f, content = editor(state(prerequisite))) {
                select(prerequisite.nodeName)
                shoot("m5rel2b-$tag-393x852-font200-prerequisite-locked.png")
            }
            onScreen(language, fontScale = 2f, content = editor(state(partial))) {
                select(partial.nodeName)
                shoot("m5rel2b-$tag-393x852-font200-compact.png")
                onNodeWithContentDescription(copy.expand).performClick()
                shoot("m5rel2b-$tag-393x852-font200-expanded.png")
            }
            onScreen(language, widthDp = 320, heightDp = 568, fontScale = 2f, content = editor(state(tier))) {
                select(tier.nodeName)
                shoot("m5rel2b-$tag-320x568-font200-tier-locked.png")
            }

            // loading, error and autosave failure
            onScreen(language, content = editor(TalentTreeUiState.Loading)) {
                shoot("m5rel2b-$tag-393x852-loading.png")
            }
            onScreen(language, content = buildScreen("missing-build", repositoryOver(FakeBuildLibraryStorage()))) {
                awaitText(copy.openFailed)
                shoot("m5rel2b-$tag-393x852-open-error.png")
            }
            val storage = FakeBuildLibraryStorage()
            val repository = repositoryOver(storage)
            val draft = assertIs<DraftCreation.Created>(
                runBlocking { createClassDraft(repository, datasets, ClassToken("WARRIOR")) },
            ).record
            onScreen(language, content = buildScreen(draft.localId, repository)) {
                awaitText(copy.draft)
                storage.failWrites = true // opening the build already wrote successfully
                select("Improved Rend")
                onNodeWithContentDescription(copy.addRank("Improved Rend")).performClick()
                awaitText(copy.autosaveFailed)
                shoot("m5rel2b-$tag-393x852-autosave-failed.png")
            }
        }
    }

    private fun repositoryOver(storage: FakeBuildLibraryStorage) =
        LocalBuildRepository(storage, FixedClock(), SequentialIds())

    /** The synthetic cross-section prerequisite on the product Warrior ([crossSectionFixture]) and its origin talent. */
    private fun crossSectionState(warrior: TalentTreeUiState.Ready): Pair<TalentTreeUiState.Ready, String> {
        val fixture = crossSectionFixture(warrior.engine.document)
        val engine = TalentAllocationEngine(fixture.document)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(fixture.document)).model
        val state = warrior.copy(model = model, engine = engine, buildState = engine.emptyState())
        return state to assertNotNull(model.nodes.first { it.id == fixture.targetId }.name)
    }

    private companion object {
        const val PROPERTY = "talentforge.m5rel2b.screenshots"
        const val DENSITY = 2f
    }
}
