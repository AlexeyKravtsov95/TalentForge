package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * State holder for the tree canvas viewport: the current transform plus the
 * measured canvas size.
 *
 * The transform survives recomposition and configuration change (it is saved
 * and restored), and once initialized it is never reset — pan/zoom state made
 * by the user persists. Initialization happens exactly once, when both the
 * tree bounds and a non-empty canvas size are known.
 */
class TalentTreeViewportState(initial: TalentTreeViewport?) {

    /** Current transform; `null` only before the first initialization. */
    var viewport: TalentTreeViewport? by mutableStateOf(initial)
        private set

    /** Measured canvas size in dp; [Size.Zero] until laid out. */
    var canvasSize: Size by mutableStateOf(Size.Zero)
        private set

    /** Applies the first deterministic transform for [bounds]; later calls are no-ops. */
    fun initializeOnce(bounds: TalentTreeBounds) {
        if (viewport != null || canvasSize == Size.Zero) return
        val landscape = canvasSize.width >= canvasSize.height
        viewport = TalentTreeViewport.initialFor(bounds, canvasSize, landscape)
    }

    /** Called from the canvas's size change callback (input in px). */
    fun onCanvasSizeChanged(sizePx: Size, density: Float) {
        canvasSize = if (density > 0f && sizePx.width.isFinite() && sizePx.height.isFinite()) {
            Size(sizePx.width / density, sizePx.height / density)
        } else {
            Size.Zero
        }
    }

    fun panBy(deltaDp: Offset) {
        viewport = (viewport ?: return).panBy(deltaDp)
    }

    fun zoomAt(focalDp: Offset, targetScale: Float) {
        viewport = (viewport ?: return).zoomAt(focalDp, targetScale)
    }

    /** Applies a transform computed from the current one (gesture handler entry point). */
    fun applyTransform(transform: TalentTreeViewport) {
        viewport = transform
    }

    companion object {
        private const val SAVED_UNINITIALIZED = 0f
        private const val SAVED_INITIALIZED = 1f

        val Saver = listSaver<TalentTreeViewportState, Float>(
            save = { state ->
                state.viewport?.let { listOf(SAVED_INITIALIZED, it.scale, it.offset.x, it.offset.y) }
                    ?: listOf(SAVED_UNINITIALIZED)
            },
            restore = { values ->
                TalentTreeViewportState(
                    if (values.first() == SAVED_INITIALIZED) {
                        TalentTreeViewport(values[1], Offset(values[2], values[3]))
                    } else {
                        null
                    },
                )
            },
        )
    }
}

/** Creates a [TalentTreeViewportState] whose transform survives recomposition and rotation. */
@Composable
fun rememberTalentTreeViewportState(): TalentTreeViewportState =
    rememberSaveable(saver = TalentTreeViewportState.Saver) {
        TalentTreeViewportState(initial = null)
    }
