package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Desktop host: a real back dispatcher with Android's semantics — handlers
 * stack in registration order and the most recently registered ENABLED one
 * receives the Back (activity `OnBackPressedDispatcher` rule). The desktop
 * window maps Esc to it ([DesktopBackDispatcher.dispatch]); the visible header
 * chevron stays the primary back control. PR #19: this lets the editor's 1A v2
 * S6 Back order be exercised (and tested) off Android.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    val handler = remember { DesktopBackDispatcher.Handler(enabled) { currentOnBack() } }
    SideEffect { handler.enabled = enabled }
    DisposableEffect(handler) {
        DesktopBackDispatcher.register(handler)
        onDispose { DesktopBackDispatcher.unregister(handler) }
    }
}

/** The desktop host's back stack of [PlatformBackHandler]s (UI thread only). */
internal object DesktopBackDispatcher {

    class Handler(var enabled: Boolean, val onBack: () -> Unit)

    private val handlers = mutableListOf<Handler>()

    fun register(handler: Handler) {
        handlers += handler
    }

    fun unregister(handler: Handler) {
        handlers -= handler
    }

    /** Delivers one Back to the newest enabled handler; `false` when none wants it. */
    fun dispatch(): Boolean {
        val target = handlers.lastOrNull { it.enabled } ?: return false
        target.onBack()
        return true
    }
}
