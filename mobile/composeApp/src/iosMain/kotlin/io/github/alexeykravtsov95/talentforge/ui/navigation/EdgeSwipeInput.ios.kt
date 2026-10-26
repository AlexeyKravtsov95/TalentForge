package io.github.alexeykravtsov95.talentforge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.backhandler.LocalCompatNavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo

/**
 * iOS: the Compose host's own screen-edge pan recognizer (`ComposeUIViewController`) drives
 * [dispatcher]. The host turns the left-edge pan into navigation events — started, the
 * finger's progress, then completed (fast enough to the right, or past 30 % of the width) or
 * cancelled — and enables its recognizer only while an enabled handler exists, so Home (no
 * enabled handler) has no swipe at all and touches elsewhere are never delayed by it. One
 * bridge handler forwards those events; the app's handlers are routed by [dispatcher].
 *
 * The host's dispatcher is reached through Compose UI's own composition local: the public
 * `LocalNavigationEventDispatcherOwner` lives in navigationevent-compose, which Compose
 * Multiplatform 1.10 does not ship (its builds need Compose runtime 1.11).
 */
@OptIn(InternalComposeUiApi::class)
@Composable
internal actual fun PlatformEdgeSwipeInput(dispatcher: EdgeSwipeBackDispatcher) {
    val host = LocalCompatNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    val bridge = remember(dispatcher) {
        object : NavigationEventHandler<NavigationEventInfo.None>(
            initialInfo = NavigationEventInfo.None,
            isBackEnabled = false,
        ) {
            override fun onBackStarted(event: NavigationEvent) {
                dispatcher.start()
            }

            override fun onBackProgressed(event: NavigationEvent) {
                dispatcher.progress(event.progress)
            }

            override fun onBackCancelled() {
                dispatcher.cancel()
            }

            override fun onBackCompleted() {
                dispatcher.complete()
            }
        }
    }
    DisposableEffect(host, bridge) {
        host.addHandler(bridge)
        dispatcher.onHasEnabledHandlerChanged = { enabled -> bridge.isBackEnabled = enabled }
        onDispose {
            dispatcher.onHasEnabledHandlerChanged = null
            bridge.remove()
        }
    }
}
