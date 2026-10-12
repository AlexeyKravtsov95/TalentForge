package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
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
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * M5.UX.2b.1 PR evidence from the REAL screen (desktop-rendered, product
 * dataset, real icons and descriptions): landscape phones with the section
 * tabs, the 300 dp side panel, FIT and a cross-section Show with its Back
 * chip, on compact (640 × 360, 568 × 320), typical (915 × 412) and
 * iPhone-class (852 × 393) landscape phones, at 100 %, 130 % and 200 % font.
 *
 * The desktop host has no system insets, so the "cutout" shots emulate them:
 * the screen applies the safe area as ONE padding (`windowInsetsPadding(
 * WindowInsets.safeDrawing)`), and these shots wrap it in that same padding,
 * marking the system areas (a side camera cutout, the home indicator).
 *
 * Skipped unless requested — it is evidence, not a regression assertion (the
 * assertions live in `TalentTreeLandscapeSectionsUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeM5Ux2b1ScreenshotTest*" \
 *     -Dtalentforge.m5ux2b1.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class TalentTreeM5Ux2b1ScreenshotTest {

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun readyStateOf(classToken: String, title: String): TalentTreeUiState.Ready = runBlocking {
        val assets = ComposeResourceDatasetAssetSource()
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(assets)
                .document(DatasetId(PRODUCT_BUNDLE.game, PRODUCT_BUNDLE.clientBuild, ClassToken(classToken))),
        ).value
        val engine = TalentAllocationEngine(document)
        TalentTreeUiState.Ready(
            classDisplayName = document.classInfo.name,
            gameDisplayName = "Forever",
            model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model,
            engine = engine,
            buildState = engine.emptyState(),
            buildTitle = title,
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

    /** A talent of [section] the build can still put a rank into (a "+" to show in the panel). */
    private fun spendableTalentOf(state: TalentTreeUiState.Ready, section: Int): String {
        val sections = buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned
        val id = sections.section(section)!!.readingOrderNodeIds.first { id ->
            state.engine.canSpend(state.buildState, id).allowed
        }
        return assertNotNull(state.model.nodes.first { it.id == id }.name)
    }

    /**
     * Runs [block] on a test window of exactly [width] × [height] dp (at
     * [DENSITY]); [safeArea] emulates the system insets (see the class doc).
     */
    private fun onPhone(
        state: TalentTreeUiState.Ready,
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        safeArea: PaddingValues? = null,
        block: ComposeUiTest.(TalentTreeSectionCameras) -> Unit,
    ) = runDesktopComposeUiTest(width = (width * DENSITY).toInt(), height = (height * DENSITY).toInt()) {
        val cameras = TalentTreeSectionCameras(sessionKey = "m5ux2b1-shots", treeId = state.model.treeId)
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(DENSITY, fontScale)) {
                    Box(Modifier.size(width.dp, height.dp).background(talentForgeColors().background)) {
                        Box(Modifier.fillMaxSize().padding(safeArea ?: PaddingValues(0.dp))) {
                            TalentTreeScreen(state, onBack = {}, sectionCameras = cameras)
                        }
                        if (safeArea != null) SystemAreas()
                    }
                }
            }
        }
        settle()
        block(cameras)
    }

    /** The emulated side camera cutout and home indicator, drawn in the system areas only. */
    @Composable
    private fun BoxScope.SystemAreas() {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp)
                .size(width = 30.dp, height = 120.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color.Black),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .size(width = 200.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFFB0B0B0)),
        )
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(900)
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    private fun ComposeUiTest.select(name: String) {
        onNodeWithContentDescription(name).performClick()
        settle()
    }

    private fun ComposeUiTest.tab(label: String) {
        onNodeWithText(label).performClick()
        settle()
    }

    @Test
    fun typicalLandscapePhone() {
        val druid = readyStateOf("DRUID", "Moonkin PvE")
        val state = druid.copy(buildState = presetBuild(druid, mapOf(0 to 13, 1 to 8)))
        val tree2Talent = spendableTalentOf(state, section = 1)

        onPhone(state, 915, 412) { cameras ->
            shoot("m5ux2b1-915x412-1-tree1.png")
            tab("Tree 2")
            shoot("m5ux2b1-915x412-2-tree2.png")
            select(tree2Talent)
            shoot("m5ux2b1-915x412-3-tree2-selected.png")
            tab("Tree 3")
            // pinch-zoom with the real gesture (px = dp × density), then FIT is offered
            onRoot().performTouchInput {
                val centre = Offset(457f * DENSITY, 230f * DENSITY)
                pinch(
                    start0 = centre - Offset(50f * DENSITY, 0f), end0 = centre - Offset(100f * DENSITY, 0f),
                    start1 = centre + Offset(50f * DENSITY, 0f), end1 = centre + Offset(100f * DENSITY, 0f),
                    durationMillis = 400,
                )
            }
            settle()
            assertTrue(cameras.stateOf(2).isAwayFromFit, "the pinch moved the camera away from the fit")
            shoot("m5ux2b1-915x412-4-tree3-zoomed-fit-visible.png")
        }
    }

    @Test
    fun compactLandscapePhones() {
        val druid = readyStateOf("DRUID", "Moonkin PvE")
        val state = druid.copy(buildState = presetBuild(druid, mapOf(0 to 13, 1 to 8)))
        val tree1Talent = spendableTalentOf(state, section = 0)

        onPhone(state, 640, 360) {
            shoot("m5ux2b1-640x360-1-tree1.png")
            select(tree1Talent)
            shoot("m5ux2b1-640x360-2-selected.png")
        }
        onPhone(state, 568, 320) {
            shoot("m5ux2b1-568x320-1-tree1.png")
            select(tree1Talent)
            shoot("m5ux2b1-568x320-2-selected.png")
        }
    }

    @Test
    fun enlargedFont() {
        val warrior = readyStateOf("WARRIOR", "Arms leveling")
        val state = warrior.copy(buildState = presetBuild(warrior, mapOf(0 to 10, 1 to 5)))
        val talent = spendableTalentOf(state, section = 0)
        for (scale in listOf(1.3f, 2f)) {
            val pct = (scale * 100).toInt()
            onPhone(state, 915, 412, fontScale = scale) {
                select(talent)
                shoot("m5ux2b1-915x412-font$pct-selected.png")
            }
            onPhone(state, 640, 360, fontScale = scale) {
                select(talent)
                shoot("m5ux2b1-640x360-font$pct-selected.png")
            }
        }
    }

    @Test
    fun cutoutAndGestureNavigation() {
        val warrior = readyStateOf("WARRIOR", "Arms leveling")
        val state = warrior.copy(buildState = presetBuild(warrior, mapOf(0 to 10, 1 to 5)))
        val talent = spendableTalentOf(state, section = 0)
        // iPhone-class landscape: 59 dp side insets (the camera side and its mirror), 21 dp home indicator
        onPhone(state, 852, 393, safeArea = PaddingValues(start = 59.dp, end = 59.dp, bottom = 21.dp)) {
            shoot("m5ux2b1-852x393-cutout-1-tree1.png")
            select(talent)
            shoot("m5ux2b1-852x393-cutout-2-selected.png")
        }
        // Android landscape: status bar 24 dp, camera cutout 32 dp on the left, gesture bar 20 dp
        onPhone(state, 915, 412, safeArea = PaddingValues(start = 32.dp, top = 24.dp, bottom = 20.dp)) {
            select(talent)
            shoot("m5ux2b1-915x412-cutout-selected.png")
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

        onPhone(state, 915, 412) {
            tab("Tree 3")
            select(origin)
            shoot("m5ux2b1-915x412-5-show-origin-tree3.png")
            onNodeWithText("Show").performClick()
            settle()
            shoot("m5ux2b1-915x412-6-show-target-tree2.png")
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5ux2b1.screenshots"
        const val DENSITY = 2f
    }
}
