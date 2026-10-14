package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import org.junit.Rule

/**
 * M5.UX.1 PR evidence from the REAL screen (desktop-rendered, product
 * dataset 1.60.1.70245, real icons): Balance → Feral Combat → Restoration by one tap
 * each, FIT after a pinch zoom, one tree on a 320 × 568 phone, and a
 * cross-section Show on the synthetic test fixture.
 *
 * Skipped unless requested — it is evidence, not a regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeM5UxScreenshotTest*" \
 *     -Dtalentforge.m5ux.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class TalentTreeM5UxScreenshotTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun readyStateOf(classToken: String): TalentTreeUiState.Ready = runBlocking {
        val assets = ComposeResourceDatasetAssetSource()
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(assets)
                .document(DatasetId(PRODUCT_BUNDLE.game, PRODUCT_BUNDLE.clientBuild, ClassToken(classToken))),
        ).value
        val engine = TalentAllocationEngine(document)
        TalentTreeUiState.Ready(
            classDisplayName = document.classInfo.name,
            gameDisplayName = "Forever",
            classToken = document.classInfo.token,
            model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model,
            engine = engine,
            buildState = engine.emptyState(),
            buildTitle = "Moonkin PvE",
            classAccent = classAccentFor(document.classInfo.token).color,
            icons = TalentIconsLoader(assets).load(document, document.trees.single()).icons,
            descriptions = TalentDescriptionsLoader(assets).load(document, document.trees.single()).descriptions,
        )
    }

    /** Spends greedily through the ENGINE in reading order until each section reaches its target points. */
    private fun presetBuild(state: TalentTreeUiState.Ready, targets: Map<Int, Int>): BuildState {
        val sections = buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned
        var session = TalentBuildEditingState.start(state.engine)
        for ((index, target) in targets) {
            val order = sections.section(index)!!.readingOrderNodeIds
            var progressed = true
            while (progressed && (sectionSpentPoints(sections, session.current)[index] ?: 0) < target) {
                progressed = false
                for (nodeId in order) {
                    if ((sectionSpentPoints(sections, session.current)[index] ?: 0) >= target) break
                    val result = session.spend(state.engine, nodeId)
                    if (result.changed) {
                        session = result.editing
                        progressed = true
                    }
                }
            }
        }
        return session.current
    }

    /**
     * Runs [block] on a test window of exactly [width] × [height] dp (at
     * [DENSITY]) showing [state] — the default 1024 × 768 px test window is
     * too small for a 393 × 852 phone.
     */
    private fun onPhone(
        state: TalentTreeUiState.Ready,
        width: Int,
        height: Int,
        block: ComposeUiTest.(TalentTreeSectionCameras) -> Unit,
    ) = runDesktopComposeUiTest(width = (width * DENSITY).toInt(), height = (height * DENSITY).toInt()) {
        val cameras = TalentTreeSectionCameras(sessionKey = "screenshots", treeId = state.model.treeId)
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(DENSITY), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        TalentTreeScreen(state, sectionCameras = cameras)
                    }
                }
            }
        }
        settle()
        block(cameras)
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(800)
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    @Test
    fun oneTapPerTreeFitAfterZoomAndSmallPhones() {
        val druid = readyStateOf("DRUID")
        val state = druid.copy(buildState = presetBuild(druid, mapOf(0 to 13, 1 to 8)))

        onPhone(state, 393, 852) { cameras ->
            shoot("m5ux-393x852-1-tree1.png")
            onNodeWithText("Feral Combat").performClick()
            settle()
            shoot("m5ux-393x852-2-tree2.png")
            onNodeWithText("Restoration").performClick()
            settle()
            shoot("m5ux-393x852-3-tree3.png")

            // pinch-zoom Balance with the real gesture (px = dp × density)
            onNodeWithText("Balance").performClick()
            settle()
            onRoot().performTouchInput {
                val centre = Offset(196f * DENSITY, 360f * DENSITY)
                pinch(
                    start0 = centre - Offset(50f * DENSITY, 0f), end0 = centre - Offset(90f * DENSITY, 0f),
                    start1 = centre + Offset(50f * DENSITY, 0f), end1 = centre + Offset(90f * DENSITY, 0f),
                    durationMillis = 400,
                )
            }
            settle()
            assertTrue(cameras.stateOf(0).isAwayFromFit, "the pinch moved the camera away from the fit")
            shoot("m5ux-393x852-4-zoomed-fit-visible.png")
            onNodeWithContentDescription("Fit tree").performClick()
            settle()
            shoot("m5ux-393x852-5-after-fit.png")
        }

        onPhone(state, 360, 800) {
            onNodeWithContentDescription("Moonglow").performClick()
            settle()
            shoot("m5ux-360x800-tree1-selected.png")
        }

        onPhone(state, 320, 568) {
            shoot("m5ux-320x568-tree1.png")
        }
    }

    @Test
    fun crossSectionShowOnTheSyntheticFixture() {
        val warrior = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
        val fixture = crossSectionFixture(warrior.engine.document)
        val engine = TalentAllocationEngine(fixture.document)
        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(fixture.document)).model
        val state = warrior.copy(model = model, engine = engine, buildState = engine.emptyState(), buildTitle = "Synthetic link")
        val origin = model.nodes.first { it.id == fixture.targetId }.name!!

        onPhone(state, 393, 852) {
            onNodeWithText("Protection").performClick()
            settle()
            onNodeWithContentDescription(origin).performClick()
            settle()
            shoot("m5ux-393x852-6-show-origin-tree3.png")
            onNodeWithText("Show").performClick()
            settle()
            shoot("m5ux-393x852-7-show-target-tree2.png")
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5ux.screenshots"
        const val DENSITY = 2f
    }
}
