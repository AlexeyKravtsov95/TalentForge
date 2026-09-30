package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * Produces M4.3 visual-evidence screenshots of the real interactive screen
 * (real dataset, real engine mutations through the editing session, real
 * renderer): the rank-action states, the unlocked deeper talent, the
 * undo/redo header and the recoverable-reset feedback.
 *
 * Skipped unless the caller opts in, because it is PR evidence, not a
 * regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeM43ScreenshotTest*" \
 *     -Dtalentforge.m43.screenshots=<output-dir>
 * ```
 */
class TalentTreeM43ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var readyState: TalentTreeUiState.Ready

    private val outputDir: File
        get() = File(System.getProperty("talentforge.m43.screenshots")!!)

    @BeforeTest
    fun setUp() {
        assumeTrue(
            "screenshot capture not requested",
            System.getProperty("talentforge.m43.screenshots") != null,
        )
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
    }

    @Test
    fun interactiveEditingStates() {
        outputDir.mkdirs()

        // portrait 360×760 (B3 03/04): empty, available, partial, maxed
        composeAndSelect(width = 360.dp, height = 760.dp, select = null)
        shoot("m43-portrait-empty.png")
        composeAndSelect(width = 360.dp, height = 760.dp, select = "Improved Rend")
        shoot("m43-portrait-available.png")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        shoot("m43-portrait-partial.png")
        repeat(2) {
            composeRule.onNodeWithText("+").performClick()
            composeRule.waitForIdle()
        }
        shoot("m43-portrait-maxed.png")

        // a newly unlocked deeper talent: a real 10-point build makes Deep
        // Wounds available (both its gate and prerequisite met)
        composeAndSelect(
            width = 360.dp,
            height = 760.dp,
            preset = presetBuild("Improved Rend" to 3, "Deflection" to 5, "Improved Charge" to 2),
            select = "Deep Wounds",
        )
        shoot("m43-portrait-unlocked-deeper.png")

        // undo/redo enabled in the header after one spend
        composeAndSelect(width = 360.dp, height = 760.dp, select = "Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        shoot("m43-portrait-undo-redo-enabled.png")

        // reset feedback (UX/UI spec E.3): points clear, snackbar offers Undo
        composeAndSelect(width = 360.dp, height = 760.dp, select = "Improved Rend")
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("open build actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset talents").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(300) // let the snackbar settle in
        composeRule.waitForIdle()
        shoot("m43-portrait-reset-feedback.png")

        // landscape 760×360 (B3 05): partial talent, side inspector, undo/redo
        composeAndSelect(
            width = 760.dp,
            height = 360.dp,
            preset = presetBuild("Improved Rend" to 1),
            select = "Improved Rend",
        )
        shoot("m43-landscape-side-inspector.png")

        // tablet 840×600 (UX/UI spec 13): interactive persistent inspector
        composeAndSelect(
            width = 840.dp,
            height = 600.dp,
            preset = presetBuild("Improved Rend" to 2),
            select = "Improved Rend",
        )
        shoot("m43-tablet-inspector.png")
    }

    /** A valid preset build, spent through the real engine (never hand-written). */
    private fun presetBuild(vararg ranks: Pair<String, Int>): BuildState {
        var session = TalentBuildEditingState.start(readyState.engine)
        for ((name, count) in ranks) {
            val nodeId = readyState.engine.tree.nodes.first { it.name == name }.id
            repeat(count) {
                val result = session.spend(readyState.engine, nodeId)
                assertTrue(result.changed, "preset spend on $name must succeed")
                session = result.editing
            }
        }
        return session.current
    }

    private fun composeAndSelect(
        width: Dp,
        height: Dp,
        select: String?,
        preset: BuildState? = null,
    ) {
        val state = preset?.let { readyState.copy(buildState = it) } ?: readyState
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(state)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        if (select != null) {
            composeRule.onNodeWithContentDescription(select).performClick()
            composeRule.waitForIdle()
        }
    }

    private fun shoot(name: String) {
        val image = composeRule.onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }
}
