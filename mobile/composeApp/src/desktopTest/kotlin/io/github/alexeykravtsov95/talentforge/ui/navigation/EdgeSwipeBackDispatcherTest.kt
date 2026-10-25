package io.github.alexeykravtsov95.talentforge.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The edge-swipe routing rules the iOS host relies on (see [EdgeSwipeBackDispatcher]). */
class EdgeSwipeBackDispatcherTest {

    private class Recording(val name: String, val log: MutableList<String>) : EdgeSwipeBackDispatcher.Handler() {
        override fun onStarted() {
            log += "$name:start"
        }

        override fun onProgressed(progress: Float) {
            log += "$name:$progress"
        }

        override fun onCancelled() {
            log += "$name:cancel"
        }

        override fun onCompleted() {
            log += "$name:complete"
        }
    }

    private val log = mutableListOf<String>()
    private val dispatcher = EdgeSwipeBackDispatcher()

    private fun handler(name: String, enabled: Boolean = true) =
        Recording(name, log).also {
            it.enabled = enabled
            dispatcher.register(it)
        }

    @Test
    fun withNoEnabledHandlerNothingTakesTheGesture() {
        handler("page", enabled = false)
        assertFalse(dispatcher.hasEnabledHandler)
        assertFalse(dispatcher.start())
        dispatcher.progress(0.5f)
        assertFalse(dispatcher.complete())
        assertEquals(emptyList(), log)
    }

    @Test
    fun theNewestEnabledHandlerTakesTheWholeGesture() {
        handler("page")
        handler("sheet")
        handler("dialog", enabled = false)

        assertTrue(dispatcher.start())
        dispatcher.progress(0.25f)
        dispatcher.complete()
        assertEquals(listOf("sheet:start", "sheet:0.25", "sheet:complete"), log)
    }

    @Test
    fun theHandlerThatStartedKeepsTheGestureEvenIfANewerOneAppears() {
        handler("page")
        dispatcher.start()
        handler("sheet")
        dispatcher.progress(0.5f)
        dispatcher.cancel()
        assertEquals(listOf("page:start", "page:0.5", "page:cancel"), log)
    }

    @Test
    fun oneGestureCompletesAtMostOnce() {
        handler("page")
        dispatcher.start()
        dispatcher.complete()
        // stray events after the terminal one reach nobody
        dispatcher.cancel()
        dispatcher.progress(0.9f)
        assertEquals(listOf("page:start", "page:complete"), log)
    }

    @Test
    fun aCompletionWithoutAStartIsAnInstantBackToTheNewestEnabledHandler() {
        handler("page")
        handler("sheet", enabled = false)
        assertTrue(dispatcher.complete())
        assertEquals(listOf("page:complete"), log)
    }

    @Test
    fun aHandlerLeavingMidGestureIsCancelledAndTheGestureEnds() {
        handler("below")
        val page = handler("page")
        dispatcher.start()
        dispatcher.unregister(page)
        dispatcher.progress(0.5f)
        assertEquals(listOf("page:start", "page:cancel"), log, "the gesture ended with the handler")

        // the next gesture goes to whoever is newest now
        dispatcher.start()
        dispatcher.complete()
        assertEquals(listOf("page:start", "page:cancel", "below:start", "below:complete"), log)
    }

    @Test
    fun progressIsClampedToTheWidth() {
        handler("page")
        dispatcher.start()
        dispatcher.progress(1.4f)
        dispatcher.progress(-0.2f)
        assertEquals(listOf("page:start", "page:1.0", "page:0.0"), log)
    }

    @Test
    fun theHostIsToldWhenAnEnabledHandlerAppearsOrGoes() {
        val seen = mutableListOf<Boolean>()
        dispatcher.onHasEnabledHandlerChanged = { seen += it }
        val page = handler("page", enabled = false)
        page.enabled = true
        page.enabled = true
        val sheet = handler("sheet")
        page.enabled = false
        dispatcher.unregister(page)
        assertEquals(listOf(false, true), seen, "only real changes are reported; the sheet keeps it enabled")
        dispatcher.unregister(sheet)
        assertEquals(listOf(false, true, false), seen)
    }
}
