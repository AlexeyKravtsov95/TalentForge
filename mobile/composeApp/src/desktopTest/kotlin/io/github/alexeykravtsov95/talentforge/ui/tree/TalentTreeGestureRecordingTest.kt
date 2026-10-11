package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue

/**
 * PR #19 evidence: frame-by-frame renders of the REAL screen (desktop, product
 * Warrior, 360 × 800 dp at density 2) while the gestures are injected — a
 * FIT swipe Tree 1 → Tree 2 → Tree 1, then the bottom inspector
 * swiped up and down. Frames are numbered PNGs (assembled into a GIF for the
 * PR). Desktop-rendered: it shows the app's own behaviour, not Android's
 * system gesture handling, which needs a device.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentTreeGestureRecordingTest*" \
 *     -Dtalentforge.pr19.recording=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class TalentTreeGestureRecordingTest {

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("recording not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private var frame = 0

    private fun ComposeUiTest.shoot(hold: Int = 1) {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        repeat(hold) { ImageIO.write(image, "png", File(outputDir, "frame-%04d.png".format(frame++))) }
    }

    private fun ComposeUiTest.advance(ms: Long) {
        mainClock.advanceTimeBy(ms)
        waitForIdle()
    }

    /** A drag in [steps] injected moves, one frame per move, released at the end. */
    private fun ComposeUiTest.recordedDrag(start: Offset, delta: Offset, steps: Int = 14) {
        onRoot().performTouchInput { down(start * DENSITY) }
        repeat(steps) {
            onRoot().performTouchInput { moveBy(delta * DENSITY / steps.toFloat(), delayMillis = 16) }
            shoot()
        }
        onRoot().performTouchInput { up() }
        repeat(6) {
            advance(40)
            shoot()
        }
    }

    @Test
    fun sectionSwipesAndBottomSheetSwipes() {
        val warrior = productState("WARRIOR")
        runDesktopComposeUiTest(width = (360 * DENSITY).toInt(), height = (800 * DENSITY).toInt()) {
            mainClock.autoAdvance = false
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(DENSITY)) {
                        Box(Modifier.size(360.dp, 800.dp)) {
                            TalentTreeScreen(
                                warrior,
                                onBack = {},
                                sectionCameras = TalentTreeSectionCameras("recording", warrior.model.treeId),
                            )
                        }
                    }
                }
            }
            advance(900)
            shoot(hold = 8)
            // at FIT, Tree 1 → Tree 2: an ordinary swipe left from inside the canvas (no pan to an edge)
            recordedDrag(start = Offset(240f, 420f), delta = Offset(-140f, 0f))
            shoot(hold = 6)
            // Tree 2 → Tree 1: swipe right
            recordedDrag(start = Offset(120f, 420f), delta = Offset(140f, 0f))
            shoot(hold = 6)
            // select a talent: the compact strip appears
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            repeat(6) {
                advance(50)
                shoot()
            }
            shoot(hold = 6)
            // swipe up on the strip: the sheet expands
            val strip = onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).fetchSemanticsNode().boundsInRoot
            recordedDrag(start = Offset(strip.center.x, strip.center.y) / DENSITY, delta = Offset(0f, -90f), steps = 8)
            shoot(hold = 10)
            // swipe down on the sheet header: back to the strip
            val header = onNodeWithTag(TALENT_INSPECTOR_SHEET_HEADER_TAG).fetchSemanticsNode().boundsInRoot
            recordedDrag(start = Offset(header.center.x, header.center.y) / DENSITY, delta = Offset(0f, 90f), steps = 8)
            shoot(hold = 10)
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.pr19.recording"
        const val DENSITY = 2f
    }
}
