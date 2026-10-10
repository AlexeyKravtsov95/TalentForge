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
 * and restored). M4.2 adds the inspector-driven Show focus request
 * ([requestFocus]): a one-shot ask that the canvas animates to (never a
 * viewport reset — normal pan/zoom continues from the result).
 *
 * M5.UX.1 adds the per-section camera behaviour of 1A v2 S3:
 * - [fitPolicy] decides the first camera: [TalentTreeFitPolicy.WHOLE_TREE]
 *   keeps the M4 rule (initialized exactly once, never reset — the
 *   landscape/tablet whole-tree canvas), [TalentTreeFitPolicy.SECTION] fits
 *   one section and re-fits when the canvas the camera was made for changes
 *   size (1A v2 S5: "rotation keeps the section and selection, the camera is
 *   recomputed to fit");
 * - user gestures go through [applyUserTransform]: clamped to the content
 *   (the 12 dp field), they mark the camera [userMoved] and interrupt any
 *   running camera animation ([gestureEpoch]);
 * - [requestFit] asks the canvas to animate back to [fitViewport] (FIT);
 *   [isAwayFromFit] is when the FIT control is offered.
 */
class TalentTreeViewportState(
    initial: TalentTreeViewport?,
    val fitPolicy: TalentTreeFitPolicy = TalentTreeFitPolicy.WHOLE_TREE,
    initialUserMoved: Boolean = false,
    initialFittedCanvasSize: Size = Size.Zero,
) {

    /** Current transform; `null` only before the first initialization. */
    var viewport: TalentTreeViewport? by mutableStateOf(initial)
        private set

    /**
     * Whether the user moved this camera by a gesture since its last fit
     * (1A v2 S3 tab memory: "Camera + userMoved"). FIT, a re-fit and the
     * first initialization clear it; Show focus never sets it.
     */
    var userMoved: Boolean by mutableStateOf(initialUserMoved && initial != null)
        private set

    /** The canvas size (dp) the current camera was fitted for; zero before the first fit. */
    var fittedCanvasSize: Size = initialFittedCanvasSize
        private set

    /**
     * World-dp bounds of the content this camera shows (the whole tree or one
     * section); set on initialization, drives the clamp and the fit.
     */
    var contentBounds: TalentTreeBounds? by mutableStateOf(null)
        private set

    /**
     * Height (dp) of the canvas bottom an overlaid inspector covers; the
     * clamp lets content move above it (set by the screen, never saved).
     */
    var coveredBottomDp: Float by mutableStateOf(0f)

    /**
     * Incremented by every user gesture step: a running camera animation
     * (Show focus, FIT) stops as soon as it changes, so animations never
     * block or fight new gestures (1A v2 S4: "the gesture interrupts the animation").
     */
    var gestureEpoch: Int = 0
        private set

    /** Monotonic FIT request token observed by the canvas (0 = none yet). */
    var fitRequest: Int by mutableStateOf(0)
        private set

    /** Measured canvas size in dp; [Size.Zero] until laid out. */
    var canvasSize: Size by mutableStateOf(Size.Zero)
        private set

    /**
     * The presentation layout class of the current arrangement (set by the
     * screen each composition; drives the initial scale and the screen-space
     * touch-target floor). Not saved: it is recomputed from the constraints
     * after a configuration change, and the initialized transform is kept.
     */
    var layoutClass: TalentTreeLayoutClass by mutableStateOf(TalentTreeLayoutClass.PORTRAIT)

    /**
     * Whether the canvas is at least as wide as it is tall — the M4.1
     * predicate kept for the portrait/landscape distinction.
     */
    val isLandscape: Boolean
        get() = canvasSize.width >= canvasSize.height

    /**
     * Binds the content [bounds] and applies the first deterministic camera.
     * [TalentTreeFitPolicy.WHOLE_TREE]: exactly once, later calls only
     * refresh the bounds. [TalentTreeFitPolicy.SECTION]: also re-fits when the
     * canvas size differs from the size the camera was fitted for (a
     * restored camera of another orientation, a resized window).
     */
    fun initializeOnce(bounds: TalentTreeBounds) {
        contentBounds = bounds
        if (canvasSize == Size.Zero) return
        val current = viewport
        when (fitPolicy) {
            TalentTreeFitPolicy.WHOLE_TREE -> if (current == null) {
                viewport = TalentTreeViewport.initialFor(bounds, canvasSize, layoutClass)
                fittedCanvasSize = canvasSize
            }

            TalentTreeFitPolicy.SECTION ->
                if (current == null || !sameSize(fittedCanvasSize, canvasSize)) {
                    viewport = TalentTreeViewport.sectionFitFor(bounds, canvasSize, layoutClass)
                    fittedCanvasSize = canvasSize
                    userMoved = false
                }
        }
    }

    /**
     * Prepares a camera that is not on screen yet (a cross-section Show
     * target) for a canvas of [size]: binds the bounds and fits it exactly
     * like [initializeOnce] would on its first layout.
     */
    fun prepare(bounds: TalentTreeBounds, size: Size, layoutClass: TalentTreeLayoutClass) {
        if (size.width.isFinite() && size.height.isFinite() && size.width > 0f && size.height > 0f) {
            canvasSize = size
        }
        this.layoutClass = layoutClass
        initializeOnce(bounds)
    }

    /** The camera FIT returns to: the policy's first camera for the current canvas. */
    fun fitViewport(): TalentTreeViewport? {
        val bounds = contentBounds ?: return null
        if (canvasSize == Size.Zero) return null
        return when (fitPolicy) {
            TalentTreeFitPolicy.WHOLE_TREE -> TalentTreeViewport.initialFor(bounds, canvasSize, layoutClass)
            TalentTreeFitPolicy.SECTION -> TalentTreeViewport.sectionFitFor(bounds, canvasSize, layoutClass)
        }
    }

    /**
     * 1A v2 S3: FIT "is shown when userMoved and the camera differs from the fit" — the user
     * moved the camera and it is no longer (close to) the fit.
     */
    val isAwayFromFit: Boolean
        get() {
            if (!userMoved) return false
            val current = viewport ?: return false
            val fit = fitViewport() ?: return false
            return !current.isCloseTo(fit)
        }

    /** Asks the canvas to animate back to [fitViewport] (FIT, or a re-tap of the active tab). */
    fun requestFit() {
        fitRequest += 1
    }

    /** Ends a FIT: the camera is the fit again and no longer user-moved. */
    fun completeFit(fit: TalentTreeViewport) {
        viewport = fit
        userMoved = false
        fittedCanvasSize = canvasSize
    }

    /**
     * The gesture entry point: applies [transform] clamped to the content
     * (1A v2 S3 bounds, relative to the current camera so nothing ever
     * jumps), marks the camera user-moved and interrupts running camera
     * animations.
     */
    fun applyUserTransform(transform: TalentTreeViewport) {
        val bounds = contentBounds
        val clamped = if (bounds != null && canvasSize != Size.Zero) {
            transform.clampedTo(bounds, canvasSize, coveredBottomDp, reference = viewport)
        } else {
            transform
        }
        gestureEpoch += 1
        viewport = clamped
        userMoved = true
    }

    /**
     * Clamps a programmatic camera target (Show centring) to the content
     * bounds, relative to the current camera: 1A v2 04: the target "is centred
     * within the content".
     */
    fun clampTarget(target: TalentTreeViewport): TalentTreeViewport {
        val bounds = contentBounds ?: return target
        if (canvasSize == Size.Zero) return target
        return target.clampedTo(bounds, canvasSize, coveredBottomDp, reference = viewport)
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

    /**
     * Applies a programmatic transform as is (camera animations, tests) —
     * never clamped and never marked user-moved; gestures use
     * [applyUserTransform].
     */
    fun applyTransform(transform: TalentTreeViewport) {
        viewport = transform
    }

    // ------------------------------------------------------------------ Show focus (M4.2)

    /**
     * One-shot focus request of the inspector-driven Show action: make [world]
     * (the prerequisite node's real canvas position) visible inside
     * [visibleSize] — the canvas area not covered by the inspector.
     */
    fun requestFocus(
        world: Offset,
        visibleSize: Size,
        mode: TalentTreeFocusMode = TalentTreeFocusMode.CENTRE,
    ) {
        if (!world.x.isFinite() || !world.y.isFinite()) return
        if (visibleSize.width.isFinite() && visibleSize.height.isFinite() &&
            (visibleSize.width <= 0f || visibleSize.height <= 0f)
        ) {
            return
        }
        focusRequest = TalentTreeFocusRequest(
            world = world,
            visibleSize = visibleSize,
            id = nextFocusId++,
            mode = mode,
        )
    }

    /** Current pending focus request; consumed by the canvas animation. */
    var focusRequest: TalentTreeFocusRequest? by mutableStateOf(null)
        private set

    private var nextFocusId: Int = 0

    private var handledFocusId: Int = -1

    private var handledFitRequest: Int = 0

    /**
     * Marks [request] as handled; `false` if it already was. A request is
     * animated exactly once, even when the canvas showing this camera leaves
     * and re-enters composition (a tab switch) — a restored camera is never
     * moved again by an old request.
     */
    fun consumeFocusRequest(request: TalentTreeFocusRequest): Boolean {
        if (request.id == handledFocusId) return false
        handledFocusId = request.id
        return true
    }

    /** Marks the latest FIT request as handled; `false` when there is none pending. */
    fun consumeFitRequest(): Boolean {
        if (fitRequest == handledFitRequest) return false
        handledFitRequest = fitRequest
        return true
    }

    companion object {
        private const val SAVED_UNINITIALIZED = 0f
        private const val SAVED_INITIALIZED = 1f

        /** Saved floats of an initialized camera: flag, scale, x, y, userMoved, fitted w/h. */
        internal const val SAVED_SIZE = 7

        /** Saves only plain floats: the camera, the user-moved flag and the fitted canvas size. */
        internal fun encode(state: TalentTreeViewportState): List<Float> =
            state.viewport?.let {
                listOf(
                    SAVED_INITIALIZED,
                    it.scale,
                    it.offset.x,
                    it.offset.y,
                    if (state.userMoved) 1f else 0f,
                    state.fittedCanvasSize.width,
                    state.fittedCanvasSize.height,
                )
            } ?: listOf(SAVED_UNINITIALIZED)

        /**
         * Total fail-closed decoding: anything malformed (wrong length,
         * non-finite or non-positive scale, non-finite offsets or sizes)
         * restores an uninitialized camera — a corrupt save can never throw
         * or restore a broken transform.
         */
        internal fun decode(values: List<Float>, fitPolicy: TalentTreeFitPolicy): TalentTreeViewportState {
            val fresh = TalentTreeViewportState(initial = null, fitPolicy = fitPolicy)
            if (values.size != SAVED_SIZE || values[0] != SAVED_INITIALIZED) return fresh
            if (values.any { !it.isFinite() }) return fresh
            val scale = values[1]
            if (scale <= 0f) return fresh
            val userMoved = when (values[4]) {
                0f -> false
                1f -> true
                else -> return fresh
            }
            val fitted = Size(values[5], values[6])
            if (fitted.width < 0f || fitted.height < 0f) return fresh
            return TalentTreeViewportState(
                initial = TalentTreeViewport(scale, Offset(values[2], values[3])),
                fitPolicy = fitPolicy,
                initialUserMoved = userMoved,
                initialFittedCanvasSize = fitted,
            )
        }

        val Saver = listSaver<TalentTreeViewportState, Float>(
            save = { state -> encode(state) },
            restore = { values -> decode(values, TalentTreeFitPolicy.WHOLE_TREE) },
        )

        private fun sameSize(a: Size, b: Size): Boolean =
            kotlin.math.abs(a.width - b.width) < 0.5f && kotlin.math.abs(a.height - b.height) < 0.5f
    }
}

/**
 * How a camera chooses its first transform and FIT target (M5.UX.1):
 * - [WHOLE_TREE] — the M4 whole-tree canvas (landscape/tablet, and the
 *   fail-safe portrait fallback when sections cannot be resolved): the
 *   layout-class initial scale, initialized once, never reset;
 * - [SECTION] — one section's camera (1A v2 S3): fit to the visible width,
 *   re-fitted when the canvas size changes.
 */
enum class TalentTreeFitPolicy { WHOLE_TREE, SECTION }

/**
 * How a focus request resolves the target transform:
 * - [CENTRE] — B3 07 (Show): "otherwise the target is centred";
 * - [REVEAL] — B3 08 (inspector layout): "pans the canvas the minimum amount
 *   needed", each axis moving only when out of the visible region.
 */
enum class TalentTreeFocusMode { CENTRE, REVEAL }

/** Immutable payload of [TalentTreeViewportState.requestFocus]; [id] changes per request. */
data class TalentTreeFocusRequest(
    val world: Offset,
    val visibleSize: Size,
    val id: Int,
    val mode: TalentTreeFocusMode = TalentTreeFocusMode.CENTRE,
)

/** Creates a [TalentTreeViewportState] whose transform survives recomposition and rotation. */
@Composable
fun rememberTalentTreeViewportState(): TalentTreeViewportState =
    rememberSaveable(saver = TalentTreeViewportState.Saver) {
        TalentTreeViewportState(initial = null)
    }
