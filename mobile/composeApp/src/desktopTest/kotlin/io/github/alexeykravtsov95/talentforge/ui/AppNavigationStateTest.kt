package io.github.alexeykravtsov95.talentforge.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The M5.1 navigation state must survive configuration restoration (review
 * fix): the destination stack is `rememberSaveable` with an explicit
 * plain-value saver — navigation facts only (Home / NewBuild / AllBuilds /
 * Editor(localId, origin)), never editor/build state. A configuration change
 * (Activity recreation) therefore restores the exact destination, and Back
 * semantics are identical because the whole stack restores.
 *
 * Compose's `StateRestorationTester` requires the `ComposeUiTest` interface,
 * which the CMP 1.10.3 desktop JUnit4 rule does not implement — per the
 * review's fallback this suite pins the saver's plain-value codec (exactly
 * what `DestinationStackSaver`'s save/restore delegate to); the production
 * wiring is `rememberSaveable(stateSaver = DestinationStackSaver)` in [App].
 */
class AppNavigationStateTest {

    private fun roundTrip(stack: List<AppDestination>): List<AppDestination>? =
        decodeDestinations(stack.map(::encodeDestination))

    // ------------------------------------------------------------------ round trips

    @Test
    fun everyDestinationRoundTripsIncludingAllEditorOrigins() {
        val stack = listOf(
            AppDestination.Home,
            AppDestination.AllBuilds,
            AppDestination.Editor("local-1", EditorOrigin.FromAllBuilds),
            AppDestination.Editor("local-2", EditorOrigin.FromHome),
            AppDestination.Editor("local-3", EditorOrigin.CreatedNew),
            AppDestination.NewBuild,
        )

        assertEquals(stack, roundTrip(stack))
    }

    @Test
    fun theHomeOnlyStackRoundTrips() {
        assertEquals(listOf(AppDestination.Home), roundTrip(listOf(AppDestination.Home)))
    }

    // ------------------------------------------------------------------ fail-closed decoding

    @Test
    fun corruptSavedStateFailsClosedToHome() {
        // a malformed entry must never restore a wrong screen — the saver
        // falls back to the initial stack (Home only)
        val corruptInputs = listOf(
            listOf("home", "editor", "no-origin"),
            emptyList(),
            listOf("bogus"),
            listOf("editor", "", "FromHome"),
            listOf("home", "editor", "local-1", "NotAnOrigin"),
            listOf("editor", "local-1", "FromHome", "trailing"),
        )
        for (values in corruptInputs) {
            assertNull(decodeDestinations(values), "corrupt input must not decode: $values")
        }
        assertEquals(listOf(AppDestination.Home), DestinationStackSaver.restore(listOf("home", "editor", "no-origin")))
    }
}
