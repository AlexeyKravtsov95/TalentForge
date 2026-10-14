package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule

/**
 * Produces M5.V1 visual evidence of the real screen with the real bundled
 * icons, rendered by the **desktop** target (real dataset, real renderer, real
 * icon files). These are not Android or iOS device screenshots and must not be
 * presented as such.
 *
 * Skipped unless the caller opts in, because it is PR evidence, not a
 * regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeM5V2ScreenshotTest*" \
 *     -Dtalentforge.m5v2.screenshots=<output-dir>
 * ```
 */
class TalentTreeM5V2ScreenshotTest {

    /** M5.REL.2b: the UI copy follows the platform locale — pinned to English, whatever the JVM default. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File
        get() = File(System.getProperty("talentforge.m5v2.screenshots")!!)

    @BeforeTest
    fun setUp() {
        assumeTrue(
            "screenshot capture not requested",
            System.getProperty("talentforge.m5v2.screenshots") != null,
        )
        outputDir.mkdirs()
    }

    private fun readyStateOf(classToken: String): TalentTreeUiState.Ready = runBlocking {
        val assets = ComposeResourceDatasetAssetSource()
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(assets)
                .document(DatasetId(GameId("forever"), "1.60.1.70009", ClassToken(classToken))),
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
            icons = TalentIconsLoader(assets).load(document, document.trees.single()).icons,
            descriptions = TalentDescriptionsLoader(assets).load(document, document.trees.single()).descriptions,
        )
    }

    /** A valid preset build, spent through the real engine (never hand-written). */
    private fun presetBuild(state: TalentTreeUiState.Ready, vararg ranks: Pair<String, Int>): BuildState {
        var session = TalentBuildEditingState.start(state.engine)
        for ((name, count) in ranks) {
            val nodeId = assertNotNull(state.engine.tree.nodes.firstOrNull { it.name == name }).id
            repeat(count) {
                val result = session.spend(state.engine, nodeId)
                assertTrue(result.changed, "preset spend on $name must succeed")
                session = result.editing
            }
        }
        return session.current
    }

    private fun compose(state: TalentTreeUiState.Ready, width: Dp, height: Dp, density: Float = 1f) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(state)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun select(name: String) {
        composeRule.onNodeWithContentDescription(name).performClick()
        composeRule.waitForIdle()
    }

    private fun shoot(name: String) {
        val image = composeRule.onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }

    /** Portrait: select, then expand the sheet by tapping the name (B3 03). */
    private fun expand(name: String) {
        select(name)
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun warriorImprovedRendRanks() {
        val warrior = readyStateOf("WARRIOR")
        for ((rank, file) in listOf(0 to "rank0", 1 to "partial", 3 to "max")) {
            compose(warrior.copy(buildState = presetBuild(warrior, "Improved Rend" to rank)), 360.dp, 760.dp)
            expand("Improved Rend")
            shoot("m5v2-desktop-warrior-improved-rend-$file.png")
        }
    }

    @Test
    fun warriorImprovedSlamAndWeaponmaster() {
        val warrior = readyStateOf("WARRIOR")
        val build = presetBuild(
            warrior,
            "Improved Heroic Strike" to 3,
            "Deflection" to 5,
            "Improved Rend" to 3,
            "Improved Charge" to 2,
            "Improved Tactical Mastery" to 5,
            "Improved Overpower" to 2,
            "Weaponmaster" to 1,
        )
        compose(warrior.copy(buildState = build), 360.dp, 760.dp)
        expand("Weaponmaster")
        shoot("m5v2-desktop-warrior-weaponmaster.png")
        compose(warrior.copy(buildState = build), 360.dp, 760.dp)
        expand("Improved Slam")
        shoot("m5v2-desktop-warrior-improved-slam.png")
    }

    @Test
    fun landscapeSideInspector() {
        val warrior = readyStateOf("WARRIOR")
        compose(warrior.copy(buildState = presetBuild(warrior, "Improved Rend" to 1)), 760.dp, 360.dp)
        select("Improved Rend")
        shoot("m5v2-desktop-warrior-landscape.png")
    }

    @Test
    fun partialAndOtherClasses() {
        for ((classToken, talent) in listOf(
            "MAGE" to "Arcane Blast",
            "ROGUE" to "Improved Expose Armor",
            "DRUID" to "Insect Swarm",
        )) {
            compose(readyStateOf(classToken), 360.dp, 760.dp)
            expand(talent)
            shoot("m5v2-desktop-${classToken.lowercase()}-${talent.lowercase().replace(' ', '-')}.png")
        }
    }
}
