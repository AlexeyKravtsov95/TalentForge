package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * M5.UI.TABLET.1 PR evidence from the real tablet editor (desktop-rendered,
 * product dataset, real icons and Russian overlay): the whole-tree canvas
 * with its three specialization trees, in English and Russian, on 840, 1024
 * and 1280 dp wide tablets in both orientations, at 200 % text, with the
 * persistent inspector showing a selection, and zoomed out / in.
 *
 * Only the public screen API is used, so the same test renders the editor
 * before and after the change. Skipped unless requested — it is evidence,
 * not a regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTabletSectionHeadersScreenshotTest*" \
 *     -Dtalentforge.m5tablet1.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class TalentTabletSectionHeadersScreenshotTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun onTablet(
        state: TalentTreeUiState.Ready,
        width: Int,
        height: Int,
        language: AppLanguage,
        fontScale: Float = 1f,
        viewportState: TalentTreeViewportState = TalentTreeViewportState(initial = null),
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        uiLanguage.language = language
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalAppLanguage provides language,
                ) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        TalentTreeScreen(state, viewportState = viewportState)
                    }
                }
            }
        }
        settle()
        block()
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(800)
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    private fun ComposeUiTest.select(name: String) {
        onNodeWithContentDescription(name).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test
    fun tabletSpecializationTrees() {
        val warrior = withSectionPoints(localizedProductState("WARRIOR"), 13, 5, 0)
        val paladin = withSectionPoints(localizedProductState("PALADIN"), 11, 0, 9)

        onTablet(warrior, 1280, 800, AppLanguage.ENGLISH) { shoot("tablet1-1280x800-warrior-en.png") }
        onTablet(warrior, 1280, 800, AppLanguage.RUSSIAN) { shoot("tablet1-1280x800-warrior-ru.png") }
        onTablet(warrior, 1280, 800, AppLanguage.ENGLISH) {
            select(WarriorText.DEEP_WOUNDS_EN)
            shoot("tablet1-1280x800-warrior-en-inspector.png")
        }
        onTablet(paladin, 1024, 768, AppLanguage.ENGLISH) { shoot("tablet1-1024x768-paladin-en.png") }
        onTablet(paladin, 1024, 768, AppLanguage.RUSSIAN) { shoot("tablet1-1024x768-paladin-ru.png") }
        onTablet(warrior, 840, 600, AppLanguage.RUSSIAN, fontScale = 2f) {
            shoot("tablet1-840x600-warrior-ru-font200.png")
        }
        onTablet(paladin, 1024, 1366, AppLanguage.ENGLISH) { shoot("tablet1-1024x1366-portrait-paladin-en.png") }
        onTablet(warrior, 1024, 1366, AppLanguage.RUSSIAN, fontScale = 2f) {
            shoot("tablet1-1024x1366-portrait-warrior-ru-font200.png")
        }

        val zoomedOut = TalentTreeViewportState(initial = null)
        onTablet(warrior, 1280, 800, AppLanguage.ENGLISH, viewportState = zoomedOut) {
            zoomedOut.applyUserTransform(zoomedOut.viewport!!.zoomAt(Offset(0f, 0f), TalentTreeCanvasMetrics.ZOOM_MIN))
            settle()
            shoot("tablet1-1280x800-warrior-en-zoom-min.png")
        }
        val zoomedIn = TalentTreeViewportState(initial = null)
        onTablet(paladin, 1280, 800, AppLanguage.RUSSIAN, viewportState = zoomedIn) {
            zoomedIn.applyUserTransform(zoomedIn.viewport!!.zoomAt(Offset(450f, 120f), TalentTreeCanvasMetrics.ZOOM_MAX))
            settle()
            shoot("tablet1-1280x800-paladin-ru-zoom-max.png")
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5tablet1.screenshots"
    }
}

/**
 * [state] with [points] spent in each section (by tab position), each rank spent
 * through the real engine in the section's reading order — never a hand-written build.
 */
internal fun withSectionPoints(state: TalentTreeUiState.Ready, vararg points: Int): TalentTreeUiState.Ready {
    val sections = buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned
    var session = TalentBuildEditingState.start(state.engine)
    for ((ordinal, target) in points.withIndex()) {
        var spent = 0
        var progressed = true
        while (spent < target && progressed) {
            progressed = false
            for (nodeId in sections.sections[ordinal].readingOrderNodeIds) {
                if (spent >= target) break
                val result = session.spend(state.engine, nodeId)
                if (result.changed) {
                    session = result.editing
                    spent += 1
                    progressed = true
                }
            }
        }
        check(spent == target) { "could spend only $spent of $target points in section $ordinal" }
    }
    return state.copy(buildState = session.current)
}
