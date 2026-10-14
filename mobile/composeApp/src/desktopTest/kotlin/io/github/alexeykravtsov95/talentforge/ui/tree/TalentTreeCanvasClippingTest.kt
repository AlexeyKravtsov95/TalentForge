package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule

/**
 * M4.2 review regression: the Talent Tree canvas is a real viewport — after a
 * far pan, nothing it draws (connectors, the transformed node layer,
 * selection rings, pulses, rank chips) may escape its own bounds into the
 * header above or a neighbouring side panel.
 *
 * Pixel-based on purpose: clipping is a rendering property, invisible to the
 * semantics tree. The header and panel bands are painted pure distinct
 * colours; any escaped canvas pixel would change them.
 */
class TalentTreeCanvasClippingTest {

    /** M5.REL.2b: the UI copy follows the platform locale — pinned to English, whatever the JVM default. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var model: TalentTreeUiModel
    private lateinit var presentation: TalentTreePresentation
    private lateinit var viewportState: TalentTreeViewportState

    private val headerColor = Color(0xFF001133)
    private val panelColor = Color(0xFF003311)
    private val panelWidthDp = 100
    private val headerHeightDp = 44

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 760
    }

    @BeforeTest
    fun loadRealWarriorModel() {
        val ready = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
        model = ready.model
        presentation = buildTalentTreePresentation(ready.engine, ready.buildState)
        viewportState = TalentTreeViewportState(initial = null)
    }

    @Test
    fun farPanKeepsEveryCanvasPixelInsideTheCanvasBounds() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(WIDTH.dp, HEIGHT.dp)) {
                        Column(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(headerHeightDp.dp)
                                    .background(headerColor),
                            )
                            Row(Modifier.fillMaxSize()) {
                                TalentTreeCanvas(
                                    model = model,
                                    presentation = presentation,
                                    viewportState = viewportState,
                                    layoutClass = TalentTreeLayoutClass.PORTRAIT,
                                    accent = io.github.alexeykravtsov95.talentforge.ui.theme.WarriorClassAccent.color,
                                    selectedNodeId = null,
                                    showGuide = null,
                                    guideToken = 0,
                                    purchaseNodeId = null,
                                    purchaseToken = 0,
                                    onNodeSelected = {},
                                    onSelectionCleared = {},
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    Modifier
                                        .width(panelWidthDp.dp)
                                        .fillMaxHeight()
                                        .background(panelColor),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Pan far up and right: without clipping, upper/right tree content
        // would render into the header band and the side-panel band.
        val initial = viewportState.viewport
        viewportState.applyTransform(
            TalentTreeViewport(
                scale = initial?.scale ?: 1f,
                offset = Offset((initial?.offset?.x ?: 0f) + 400f, (initial?.offset?.y ?: 0f) - 600f),
            ),
        )
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(400L)

        val image = composeRule.onRoot().captureToImage().toAwtImage()
        val canvasRight = WIDTH - panelWidthDp

        for (x in 0 until WIDTH) {
            for (y in 0 until HEIGHT) {
                val pixel = image.getRGB(x, y)
                if (y < headerHeightDp && x < canvasRight) {
                    assertTrue(
                        pixel == headerColor.toArgb(),
                        "canvas content escaped into the header at ($x,$y): 0x${pixel.toString(16)}",
                    )
                }
                if (x >= canvasRight && y >= headerHeightDp) {
                    assertTrue(
                        pixel == panelColor.toArgb(),
                        "canvas content escaped into the side panel at ($x,$y): 0x${pixel.toString(16)}",
                    )
                }
            }
        }
    }
}
