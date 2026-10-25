package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.motion.prefersReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Test tag of the page shown beneath the swiped one while an edge swipe is in progress. */
const val BACK_SWIPE_BENEATH_TAG = "back-swipe-beneath"

/**
 * A stack of pages whose top one the iOS left-edge swipe takes back
 * ([EdgeSwipeBackDispatcher]); everywhere else — and whenever nothing swipes —
 * it shows exactly the top page, as before.
 *
 * Levels `0..depth` are the pages, `depth` the one shown; level 0 is the root,
 * which never swipes. While a swipe is in progress the top page follows the
 * finger and the page beneath is composed under it (dimmed, out of the
 * accessibility tree, untouchable: the host cancels touches during the
 * gesture) — but only once [onPrepareBack] has confirmed it may be left (the
 * app: the autosave flush succeeded, so the page beneath reads what storage
 * holds). A released swipe the host judged complete slides the page out and
 * then calls [onBack] for the level it started on, which does the real back
 * (the app: the same one-at-a-time, flush-first pop as the chevron) and
 * answers whether it went back; a cancelled swipe, or a back that did not
 * happen (the newest save failed), slides the page back in: the page and its
 * state were never left. The page beneath is keyed by its level, so the one
 * that was shown under the finger is the one that stays.
 *
 * One gesture is at most one [onBack]: the handler is off while a swipe
 * settles. With reduced motion the finger still moves the page (direct
 * manipulation), but the page beneath does not parallax and the settle jumps
 * to its end state.
 */
@Composable
internal fun BackSwipeStack(
    depth: Int,
    enabled: Boolean,
    onPrepareBack: suspend () -> Boolean,
    onBack: suspend (level: Int) -> Boolean,
    modifier: Modifier = Modifier,
    page: @Composable (level: Int) -> Unit,
) {
    val reducedMotion = prefersReducedMotion()
    val currentPrepare by rememberUpdatedState(onPrepareBack)
    val currentOnBack by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(SwipePhase.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }
    // the level the gesture started on, and whether the page beneath it may be shown
    var swipedLevel by remember { mutableStateOf<Int?>(null) }
    var beneathShown by remember { mutableStateOf(false) }
    var prepare by remember { mutableStateOf<Job?>(null) }
    // a gesture taken while another one settles: it ends without doing anything
    var ignoring by remember { mutableStateOf(false) }

    suspend fun moveTo(target: Float) {
        if (reducedMotion) {
            progress = target
        } else {
            animate(progress, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                progress = value
            }
        }
    }

    fun settle(complete: Boolean) {
        val level = swipedLevel ?: depth
        phase = SwipePhase.Settling
        prepare?.cancel()
        scope.launch {
            try {
                var wentBack = false
                if (complete) {
                    if (beneathShown) moveTo(1f)
                    wentBack = currentOnBack(level)
                }
                if (!wentBack) moveTo(0f)
            } finally {
                // when it went back this lands in the same frame as the removed page
                progress = 0f
                beneathShown = false
                swipedLevel = null
                phase = SwipePhase.Idle
            }
        }
    }

    EdgeSwipeBackHandler(
        enabled = enabled && depth > 0 && phase != SwipePhase.Settling,
        onStarted = {
            // the handler is switched off only by the next composition: a gesture that
            // starts before it, while another one settles, is taken and ignored to its end
            if (phase != SwipePhase.Idle) {
                ignoring = true
                return@EdgeSwipeBackHandler
            }
            phase = SwipePhase.Dragging
            swipedLevel = depth
            progress = 0f
            prepare = scope.launch {
                if (currentPrepare() && phase == SwipePhase.Dragging) beneathShown = true
            }
        },
        onProgressed = { if (!ignoring && phase == SwipePhase.Dragging) progress = it },
        onCancelled = {
            if (ignoring) ignoring = false else if (phase == SwipePhase.Dragging) settle(complete = false)
        },
        onCompleted = {
            if (ignoring) {
                ignoring = false
                return@EdgeSwipeBackHandler
            }
            when (phase) {
                SwipePhase.Dragging -> settle(complete = true)
                // an instant Back (a hardware Esc): no finger to follow
                SwipePhase.Idle -> {
                    swipedLevel = depth
                    settle(complete = true)
                }
                SwipePhase.Settling -> Unit
            }
        },
    )

    val colors = talentForgeColors()
    val beneath = swipedLevel?.takeIf { beneathShown && it == depth && it > 0 }?.minus(1)
    Box(modifier.fillMaxSize().background(colors.background)) {
        for (level in listOfNotNull(beneath, depth)) {
            key(level) {
                val top = level == depth
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = when {
                                top -> progress * size.width
                                reducedMotion -> 0f
                                else -> (progress - 1f) * size.width * BENEATH_PARALLAX
                            }
                        }
                        .then(
                            if (top) {
                                Modifier.drawWithContent {
                                    drawContent()
                                    if (progress > 0f) {
                                        val shadow = EDGE_SHADOW_WIDTH.toPx()
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                listOf(Color.Transparent, colors.scrim),
                                                startX = -shadow,
                                                endX = 0f,
                                            ),
                                            topLeft = Offset(-shadow, 0f),
                                            size = Size(shadow, size.height),
                                        )
                                    }
                                }
                            } else {
                                Modifier
                                    .testTag(BACK_SWIPE_BENEATH_TAG)
                                    .clearAndSetSemantics {}
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(colors.scrim, alpha = (1f - progress) * BENEATH_DIM)
                                    }
                            },
                        ),
                ) {
                    page(level)
                }
            }
        }
    }
}

private enum class SwipePhase { Idle, Dragging, Settling }

/** How far the page beneath trails behind (iOS-like parallax), as a share of the width. */
private const val BENEATH_PARALLAX = 0.3f

/** The page beneath starts this dimmed (times the scrim's own alpha) and brightens as the top page leaves. */
private const val BENEATH_DIM = 0.6f

private val EDGE_SHADOW_WIDTH = 12.dp
