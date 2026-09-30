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
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * Produces M4.2 visual-evidence screenshots of the real screen (real dataset,
 * real engine projection, real renderer): the adaptive inspector states at the
 * B3 reference sizes plus the UX/UI spec tablet class.
 *
 * Skipped unless the caller opts in, because it is PR evidence, not a
 * regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeM42ScreenshotTest*" \
 *     -Dtalentforge.m42.screenshots=<output-dir>
 * ```
 */
class TalentTreeM42ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var readyState: TalentTreeUiState.Ready

    private val outputDir: File
        get() = File(System.getProperty("talentforge.m42.screenshots")!!)

    @BeforeTest
    fun setUp() {
        assumeTrue(
            "screenshot capture not requested",
            System.getProperty("talentforge.m42.screenshots") != null,
        )
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
    }

    @Test
    fun adaptiveInspectorStates() {
        outputDir.mkdirs()

        // portrait 360×760 (B3 03/04): available, prerequisite-locked, tier-locked
        capturePortrait(name = "m42-portrait-available.png", select = "Improved Rend")
        capturePortrait(name = "m42-portrait-prerequisite-locked.png", select = "Deep Wounds")
        capturePortrait(name = "m42-portrait-tier-locked.png", select = "Improved Charge")

        // expanded sheet (B3 04 08)
        composeAndSelect(width = 360.dp, height = 760.dp, select = "Deep Wounds")
        composeRule.onNodeWithText("Deep Wounds").performClick() // name area expands
        composeRule.waitForIdle()
        shoot("m42-portrait-expanded.png")

        // after Show (B3 05): guide + needed-for state line
        composeAndSelect(width = 360.dp, height = 760.dp, select = "Deep Wounds")
        composeRule.onNodeWithText("Show").performClick()
        composeRule.waitForIdle()
        shoot("m42-portrait-after-show.png")

        // landscape 760×360 (B3 05): side inspector with full detail
        composeAndSelect(width = 760.dp, height = 360.dp, select = "Improved Rend")
        shoot("m42-landscape-side-inspector.png")

        // tablet 840×600 (UX/UI spec 13): persistent inspector, empty prompt + selection
        composeAndSelect(width = 840.dp, height = 600.dp, select = null)
        shoot("m42-tablet-empty.png")
        composeAndSelect(width = 840.dp, height = 600.dp, select = "Improved Rend")
        shoot("m42-tablet-selected.png")
    }

    private fun composeAndSelect(width: Dp, height: Dp, select: String?) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(readyState)
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

    private fun capturePortrait(name: String, select: String) {
        composeAndSelect(width = 360.dp, height = 760.dp, select = select)
        shoot(name)
    }

    private fun shoot(name: String) {
        val image = composeRule.onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }
}
