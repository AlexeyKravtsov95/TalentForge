package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import org.junit.Rule

/**
 * M5.UX.2a PR evidence from the REAL screen (desktop-rendered, product
 * dataset, real icons and descriptions): the compact inspector in every state,
 * the content-sized expanded sheet with a short and a long description, and
 * the narrow header, at 320 × 568, 360 × 800 and 393 × 852 dp and at 100 %,
 * 130 % and 200 % font scale.
 *
 * Skipped unless requested — it is evidence, not a regression assertion (the
 * assertions live in `TalentInspectorAdaptiveUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeM5Ux2ScreenshotTest*" \
 *     -Dtalentforge.m5ux2.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class TalentTreeM5Ux2ScreenshotTest {

    /** M5.REL.2b: the UI copy follows the platform locale — pinned to English, whatever the JVM default. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun onPhone(
        state: TalentTreeUiState.Ready,
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = (width * DENSITY).toInt(), height = (height * DENSITY).toInt()) {
        val cameras = TalentTreeSectionCameras(sessionKey = "m5ux2-shots", treeId = state.model.treeId)
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(DENSITY, fontScale)) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        TalentTreeScreen(state, onBack = {}, sectionCameras = cameras)
                    }
                }
            }
        }
        settle()
        block()
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

    private fun ComposeUiTest.expand() {
        onNodeWithContentDescription(EditorCopy.English.expand).performClick()
        settle()
    }

    private fun ComposeUiTest.scrollDetail() {
        onNode(hasTestTag(TALENT_INSPECTOR_DETAIL_SCROLL_TAG)).performTouchInput { swipeUp() }
        settle()
    }

    @Test
    fun compactInspectorStatesOnTheSmallestPhone() {
        val warrior = productState("WARRIOR")
        for ((label, scenario) in compactScenarios(warrior)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                onPhone(warrior.copy(buildState = scenario.build), 320, 568, scale) {
                    select(scenario.nodeName)
                    shoot("m5ux2-320x568-${pct(scale)}-compact-$label.png")
                }
            }
        }
    }

    @Test
    fun compactInspectorOnRegularPhones() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        for ((w, h) in listOf(360 to 800, 393 to 852)) {
            for ((label, scenario) in scenarios) {
                onPhone(warrior.copy(buildState = scenario.build), w, h) {
                    select(scenario.nodeName)
                    shoot("m5ux2-${w}x$h-100-compact-$label.png")
                }
            }
        }
    }

    @Test
    fun expandedSheetShortAndLongDescriptions() {
        val warrior = productState("WARRIOR")
        val druid = productState("DRUID")
        val shortCase = scenarioOf(warrior, SHORT_DESCRIPTION_TALENT, spend = 1)
        val longCase = unlockedScenario(druid, LONG_DESCRIPTION_TALENT) // its + Rank / Max are live
        for ((w, h) in listOf(320 to 568, 360 to 800, 393 to 852)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                if (scale == 2f && w != 320) continue // 200 %: the critical small-phone cases only
                onPhone(warrior.copy(buildState = shortCase.build), w, h, scale) {
                    select(shortCase.nodeName)
                    expand()
                    shoot("m5ux2-${w}x$h-${pct(scale)}-expanded-short.png")
                }
                onPhone(druid.copy(buildState = longCase.build), w, h, scale) {
                    select(longCase.nodeName)
                    expand()
                    shoot("m5ux2-${w}x$h-${pct(scale)}-expanded-long.png")
                    scrollDetail()
                    shoot("m5ux2-${w}x$h-${pct(scale)}-expanded-long-scrolled.png")
                }
            }
        }
    }

    @Test
    fun headerOnNarrowAndRegularPhones() {
        val warrior = productState("WARRIOR")
        val build = scenarioOf(warrior, SHORT_DESCRIPTION_TALENT, spend = 3).build
        val titled = warrior.copy(buildState = build, buildTitle = "Arms PvP leveling build")
        for ((w, h) in listOf(320 to 568, 360 to 800, 393 to 852)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                onPhone(titled, w, h, scale) {
                    shoot("m5ux2-${w}x$h-${pct(scale)}-header.png")
                }
            }
        }
    }

    private fun pct(scale: Float): String = "${(scale * 100).toInt()}"

    private companion object {
        const val PROPERTY = "talentforge.m5ux2.screenshots"
        const val DENSITY = 2f
    }
}
