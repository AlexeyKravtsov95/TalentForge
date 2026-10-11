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
import androidx.compose.ui.test.onNodeWithText
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
 * Warrior, 360 × 800 dp at density 2) while the gestures are injected — the
 * PR #19 motion tour: swipes Tree 1 → 2 → 3 and back, tab taps, a cancelled
 * swipe, a zoomed Tree 1 that keeps its camera through a round trip, and the
 * inspector sheet sliding up and down. Frames are numbered PNGs (assembled
 * into a GIF for the PR). Desktop-rendered: it shows the app's own behaviour, not Android's
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

    /** A drag in [steps] injected moves, one frame per move, released at the end, then the settle. */
    private fun ComposeUiTest.recordedDrag(start: Offset, delta: Offset, steps: Int = 14, moveMillis: Long = 16) {
        onRoot().performTouchInput { down(start * DENSITY) }
        repeat(steps) {
            onRoot().performTouchInput { moveBy(delta * DENSITY / steps.toFloat(), delayMillis = moveMillis) }
            advance(moveMillis)
            shoot()
        }
        onRoot().performTouchInput { up() }
        settleFrames()
    }

    /** ≈ 320 ms of frames: a page transition or the sheet settling. */
    private fun ComposeUiTest.settleFrames() {
        repeat(10) {
            advance(32)
            shoot()
        }
    }

    @Test
    fun motionTour() {
        val warrior = productState("WARRIOR")
        val cameras = TalentTreeSectionCameras("recording", warrior.model.treeId)
        runDesktopComposeUiTest(width = (360 * DENSITY).toInt(), height = (800 * DENSITY).toInt()) {
            mainClock.autoAdvance = false
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(DENSITY)) {
                        Box(Modifier.size(360.dp, 800.dp)) {
                            TalentTreeScreen(warrior, onBack = {}, sectionCameras = cameras)
                        }
                    }
                }
            }
            advance(900)
            shoot(hold = 8)
            // Tree 1 → Tree 2 → Tree 3: ordinary swipes left, both pages follow the finger
            recordedDrag(start = Offset(250f, 420f), delta = Offset(-140f, 0f))
            shoot(hold = 4)
            recordedDrag(start = Offset(250f, 420f), delta = Offset(-140f, 0f))
            shoot(hold = 4)
            // and back: Tree 3 → Tree 2 → Tree 1
            recordedDrag(start = Offset(110f, 420f), delta = Offset(140f, 0f))
            shoot(hold = 4)
            recordedDrag(start = Offset(110f, 420f), delta = Offset(140f, 0f))
            shoot(hold = 6)
            // tab taps: the same transition, 260 ms ease-out, from the tab's side
            onNodeWithText("Tree 3").performClick()
            settleFrames()
            shoot(hold = 4)
            onNodeWithText("Tree 1").performClick()
            settleFrames()
            shoot(hold = 6)
            // a cancelled swipe: 40 dp slowly, Tree 2 peeks in and eases back out
            recordedDrag(start = Offset(220f, 420f), delta = Offset(-40f, 0f), steps = 8, moveMillis = 60)
            shoot(hold = 6)
            // Tree 1 zoomed by a pinch, then Tree 2 and back: Tree 1 comes back with its zoom
            onRoot().performTouchInput {
                down(0, Offset(150f, 360f) * DENSITY)
                down(1, Offset(210f, 360f) * DENSITY)
            }
            repeat(8) {
                onRoot().performTouchInput {
                    updatePointerBy(0, Offset(-7f, -4f) * DENSITY)
                    updatePointerBy(1, Offset(7f, 4f) * DENSITY)
                    move(delayMillis = 16)
                }
                advance(16)
                shoot()
            }
            onRoot().performTouchInput {
                up(0)
                up(1)
            }
            advance(200)
            shoot(hold = 6)
            onNodeWithText("Tree 2").performClick()
            settleFrames()
            shoot(hold = 4)
            onNodeWithText("Tree 1").performClick()
            settleFrames()
            shoot(hold = 8)
            onNodeWithContentDescription("Fit tree").performClick()
            settleFrames()
            // select a talent: the compact strip appears
            onNodeWithContentDescription(SHORT_DESCRIPTION_TALENT).performClick()
            settleFrames()
            shoot(hold = 4)
            // swipe up on the strip: the sheet slides up; swipe down on its header: back to the strip
            val strip = onNodeWithTag(TALENT_INSPECTOR_COMPACT_TAG).fetchSemanticsNode().boundsInRoot
            recordedDrag(start = Offset(strip.center.x, strip.center.y) / DENSITY, delta = Offset(0f, -90f), steps = 6)
            shoot(hold = 8)
            val header = onNodeWithTag(TALENT_INSPECTOR_SHEET_HEADER_TAG).fetchSemanticsNode().boundsInRoot
            recordedDrag(start = Offset(header.center.x, header.center.y) / DENSITY, delta = Offset(0f, 90f), steps = 6)
            shoot(hold = 8)
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.pr19.recording"
        const val DENSITY = 2f
    }
}
