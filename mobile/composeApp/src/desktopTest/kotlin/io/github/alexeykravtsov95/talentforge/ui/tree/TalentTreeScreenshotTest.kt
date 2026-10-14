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
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule

/**
 * Produces M4.1 visual-evidence screenshots of the real screen (real dataset,
 * real renderer) at phone portrait and landscape reference sizes.
 *
 * Skipped unless the caller opts in, because it is PR evidence, not a
 * regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeScreenshotTest*" \
 *     -Dtalentforge.m41.screenshots=<output-dir>
 * ```
 */
class TalentTreeScreenshotTest {

    /** M5.REL.2b: the UI copy follows the platform locale — pinned to English, whatever the JVM default. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var readyState: TalentTreeUiState.Ready

    private val outputDir: File
        get() = File(System.getProperty("talentforge.m41.screenshots")!!)

    @BeforeTest
    fun setUp() {
        assumeTrue(
            "screenshot capture not requested",
            System.getProperty("talentforge.m41.screenshots") != null,
        )
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
    }

    @Test
    fun portraitAndLandscapeWithAndWithoutSelection() {
        outputDir.mkdirs()

        capture("m41-portrait-idle.png", 360.dp, 760.dp, select = false)
        capture("m41-portrait-selected.png", 360.dp, 760.dp, select = true)
        capture("m41-landscape-idle.png", 760.dp, 360.dp, select = false)
        capture("m41-landscape-selected.png", 760.dp, 360.dp, select = true)
    }

    private fun capture(name: String, width: Dp, height: Dp, select: Boolean) {
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

        if (select) {
            composeRule.onNodeWithContentDescription("Improved Rend").performClick()
            composeRule.waitForIdle()
        }

        val image = composeRule.onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }
}
