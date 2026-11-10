package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

@OptIn(ExperimentalTestApi::class)
class TalentRequirementConnectorUiTest {
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun druidDecorationCannotCoverTheUnmetRequiredEdgeDashGaps() = runDesktopComposeUiTest(width = 320, height = 240) {
        val ready = productState("DRUID")
        var shownBuild by mutableStateOf(ready.buildState)
        val cameraState = TalentTreeViewportState(initial = null)
        val pair = setOf(104927, 104928)
        val nodes = ready.model.nodes.filter { it.id in pair }
        val model = ready.model.copy(
            nodes = nodes,
            edges = ready.model.edges.filter { it.fromNodeId in pair && it.toNodeId in pair },
            bounds = TalentTreeBounds(
                nodes.minOf { it.centerX } - 24f, nodes.minOf { it.centerY } - 24f,
                nodes.maxOf { it.centerX } + 24f, nodes.maxOf { it.centerY } + 24f,
            ),
        )
        assertTrue(model.edges.size == 2, "both real opposite-direction edges are present")
        var renderedModel by mutableStateOf(model, neverEqualPolicy())
        var guide by mutableStateOf<TalentShowGuide?>(null)
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(320.dp, 240.dp)) {
                        TalentTreeCanvas(
                            model = renderedModel,
                            presentation = buildTalentTreePresentation(ready.engine, shownBuild),
                            viewportState = cameraState,
                            layoutClass = TalentTreeLayoutClass.PORTRAIT,
                            accent = ready.classAccent,
                            selectedNodeId = null,
                            showGuide = guide,
                            guideToken = 0,
                            onNodeSelected = {},
                            onSelectionCleared = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
        val camera = cameraState.viewport!!
        // Real type 3 edge 124675: Majesty -> Splendor. Reverse type 0 edge 124666
        // has the same geometry, but must not make an unmet requirement look solid.
        val from = ready.model.nodes.single { it.id == 104927 }
        val to = ready.model.nodes.single { it.id == 104928 }
        val x = camera.worldToScreen(Offset(from.centerX, from.centerY)).x.roundToInt()
        val top = camera.worldToScreen(Offset(from.centerX, from.centerY + 36f)).y.roundToInt()
        val bottom = camera.worldToScreen(Offset(to.centerX, to.centerY - 28f)).y.roundToInt()
        val image = onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val gaps = (top..bottom).count { y ->
            val line = pixels[x, y]
            val background = pixels[x + 8, y]
            abs(line.red - background.red) + abs(line.green - background.green) + abs(line.blue - background.blue) < 0.01f
        }
        System.getenv("TALENTFORGE_REQUIREMENTS_SCREENSHOTS")?.let { dir ->
            ImageIO.write(image.toAwtImage(), "png", File(dir, "druid-required-dashes.png"))
        }
        assertTrue(gaps >= 2, "the real unmet gameplay connector must retain visible dash gaps; found $gaps")

        var editing = TalentBuildEditingState.start(ready.engine)
        val section = ready.engine.tree.sections.single { from.id in it.nodeIds }
        while (editing.current.rankOf(from.id) < from.maxRank) {
            var progressed = false
            for (id in section.nodeIds - to.id) {
                if (editing.current.rankOf(from.id) == from.maxRank) break
                val result = editing.spend(ready.engine, id)
                if (result.changed) {
                    editing = result.editing
                    progressed = true
                }
            }
            assertTrue(progressed, "the real prerequisite must be purchasable")
        }
        runOnIdle { shownBuild = editing.current }
        waitForIdle()
        val completedImage = onRoot().captureToImage()
        val completed = completedImage.toPixelMap()
        val solid = (top..bottom).count { y ->
            val line = completed[x, y]
            val background = completed[x + 8, y]
            abs(line.red - background.red) + abs(line.green - background.green) + abs(line.blue - background.blue) > 0.01f
        }
        assertTrue(solid == bottom - top + 1, "completed prerequisite must render as solid")
        System.getenv("TALENTFORGE_REQUIREMENTS_SCREENSHOTS")?.let { dir ->
            ImageIO.write(completedImage.toAwtImage(), "png", File(dir, "druid-completed-solid.png"))
        }

        // A fresh, structurally equal projection must still highlight the exact Show path.
        runOnIdle {
            renderedModel = model.copy(edges = model.edges.map { it.copy() })
            guide = TalentShowGuide(originNodeId = to.id, targetNodeId = from.id)
        }
        waitForIdle()
        val guided = onRoot().captureToImage().toPixelMap()
        assertTrue(guided[x, (top + bottom) / 2].red > 0.6f, "Show path remains bright after an equal model replacement")
    }
}
