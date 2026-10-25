package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The iOS left-edge swipe back, routed among the app's own handlers.
 *
 * The iOS host has no system Back button: its "system back" is the edge
 * swipe, and it must do what the visible back chevron does — pop the screen,
 * never just clear the editor's selection. So it is routed separately from
 * Android's system Back (whose 1A v2 S6 order stays as it is):
 *
 * - handlers stack in registration order and the most recently registered
 *   ENABLED one takes the gesture (the Android/desktop dispatcher rule), so a
 *   sheet, dialog or running operation still closes or holds it first;
 * - the handler that took the gesture's start keeps it until it completes or
 *   is cancelled — one gesture, one handler, at most one completion;
 * - a completion without a start (a hardware Esc on iPad) goes to the newest
 *   enabled handler, like an instant Back.
 *
 * The iOS actual of [PlatformEdgeSwipeInput] feeds this dispatcher from the
 * Compose host's screen-edge pan recognizer (which follows the finger and
 * decides completion vs cancellation); Android and desktop have no edge
 * swipe, so there nothing drives it. UI thread only.
 */
internal class EdgeSwipeBackDispatcher {

    /** One registered handler; a gesture reaches it only while [enabled]. */
    abstract class Handler {
        var enabled: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                dispatcher?.refresh()
            }

        internal var dispatcher: EdgeSwipeBackDispatcher? = null

        open fun onStarted() {}

        /** [progress] is the finger's share of the width, 0..1. */
        open fun onProgressed(progress: Float) {}

        open fun onCancelled() {}

        abstract fun onCompleted()
    }

    private val handlers = mutableListOf<Handler>()
    private var inProgress: Handler? = null

    /** Told whenever [hasEnabledHandler] changes: the iOS host enables its recognizer only then. */
    var onHasEnabledHandlerChanged: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(hasEnabledHandler)
        }

    var hasEnabledHandler: Boolean = false
        private set

    fun register(handler: Handler) {
        handler.dispatcher = this
        handlers += handler
        refresh()
    }

    fun unregister(handler: Handler) {
        handlers -= handler
        handler.dispatcher = null
        // a handler leaving mid-gesture (its screen disposed) is told the gesture is over
        if (inProgress === handler) {
            inProgress = null
            handler.onCancelled()
        }
        refresh()
    }

    /** A gesture began: the newest enabled handler takes it; `false` when none wants it. */
    fun start(): Boolean {
        if (inProgress != null) return true
        val target = newestEnabled() ?: return false
        inProgress = target
        target.onStarted()
        return true
    }

    fun progress(progress: Float) {
        inProgress?.onProgressed(progress.coerceIn(0f, 1f))
    }

    fun cancel() {
        val target = inProgress ?: return
        inProgress = null
        target.onCancelled()
    }

    /** The gesture completed (or an instant Back arrived); `false` when nobody took it. */
    fun complete(): Boolean {
        val target = inProgress ?: newestEnabled() ?: return false
        inProgress = null
        target.onCompleted()
        return true
    }

    private fun newestEnabled(): Handler? = handlers.lastOrNull { it.enabled }

    private fun refresh() {
        val now = handlers.any { it.enabled }
        if (now == hasEnabledHandler) return
        hasEnabledHandler = now
        onHasEnabledHandlerChanged?.invoke(now)
    }
}

/** The app's edge-swipe dispatcher, provided by [App]; tests may provide their own to drive gestures. */
internal val LocalEdgeSwipeBackDispatcher = staticCompositionLocalOf<EdgeSwipeBackDispatcher?> { null }

/** Connects the host's edge-swipe gesture to [dispatcher]: iOS only; nothing on hosts without one. */
@Composable
internal expect fun PlatformEdgeSwipeInput(dispatcher: EdgeSwipeBackDispatcher)

/**
 * Registers a handler of the edge-swipe back for as long as it is composed.
 * Registration order is composition order (as for [PlatformBackHandler]); the
 * callbacks are read when they run, never captured by an earlier composition.
 */
@Composable
internal fun EdgeSwipeBackHandler(
    enabled: Boolean,
    onStarted: () -> Unit = {},
    onProgressed: (Float) -> Unit = {},
    onCancelled: () -> Unit = {},
    onCompleted: () -> Unit,
) {
    val dispatcher = LocalEdgeSwipeBackDispatcher.current ?: return
    val currentOnStarted by rememberUpdatedState(onStarted)
    val currentOnProgressed by rememberUpdatedState(onProgressed)
    val currentOnCancelled by rememberUpdatedState(onCancelled)
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    val handler = remember {
        object : EdgeSwipeBackDispatcher.Handler() {
            override fun onStarted() = currentOnStarted()

            override fun onProgressed(progress: Float) = currentOnProgressed(progress)

            override fun onCancelled() = currentOnCancelled()

            override fun onCompleted() = currentOnCompleted()
        }
    }
    SideEffect { handler.enabled = enabled }
    DisposableEffect(dispatcher, handler) {
        handler.enabled = enabled
        dispatcher.register(handler)
        onDispose { dispatcher.unregister(handler) }
    }
}
