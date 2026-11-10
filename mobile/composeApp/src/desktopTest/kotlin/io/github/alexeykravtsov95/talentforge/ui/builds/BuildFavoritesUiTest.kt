package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.builds.*
import io.github.alexeykravtsov95.talentforge.ui.*
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Rule

class BuildFavoritesUiTest {
    @get:Rule val language = AppLanguageRule(AppLanguage.ENGLISH)
    @get:Rule val ui = createComposeRule()
    private val storage = FakeBuildLibraryStorage()
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val restart = mutableStateOf(0)
    private fun repository() = LocalBuildRepository(storage, clock, ids)
    private fun records() = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository().load()).value
    private fun await(text: String) = ui.waitUntil(10_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitStar(title: String) = ui.waitUntil(10_000) {
        ui.onAllNodesWithContentDescription("Remove from Favorites: $title").fetchSemanticsNodes().isNotEmpty()
    }
    private fun openLibrary() {
        await("All builds 2")
        ui.onNodeWithText("All builds 2").performScrollTo().performClick()
        await("Raid Arms")
    }
    private fun start() {
        ShellLibraryFixtures.persist(storage, clock, listOf(
            ShellLibraryFixtures.Build("WARRIOR", name = "Raid Arms"),
            ShellLibraryFixtures.Build("MAGE", name = "Frost"),
        ))
        // Use new ids beyond the seeded library if Duplicate is pressed.
        ids.next(); ids.next()
        ui.setContent {
            TalentForgeTheme {
                Box(Modifier.size(393.dp, 852.dp)) {
                    key(restart.value) { App(storage = storage, clock = clock, ids = ids) }
                }
            }
        }
        openLibrary()
    }
    private fun addArms() {
        ui.onNodeWithContentDescription("Add to Favorites: Raid Arms").performScrollTo().performClick()
        awaitStar("Raid Arms")
    }

    @Test fun renameAndDeleteWithSearchClassAndFavoritesActiveRefreshTheIntersection() {
        start()
        addArms()
        ui.onNodeWithText("Favorites").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Search builds").performScrollTo().performTextReplacement("Raid")
        ui.onNodeWithContentDescription("Filter by class: All classes").performScrollTo().performClick()
        ui.onNodeWithText("Warrior").performScrollTo().performClick()
        ui.onNodeWithText("Frost").assertDoesNotExist()
        ui.onNodeWithContentDescription("build actions for Raid Arms").performScrollTo().performClick()
        ui.onNodeWithText("Rename").performClick()
        ui.onNodeWithContentDescription("build name").performTextReplacement("Duel")
        ui.onNodeWithText("Save").performClick()
        await("No search results")
        ui.onNodeWithText("Favorites").performScrollTo().assertIsOn()
        ui.onNodeWithContentDescription("Search builds").performScrollTo().performTextReplacement("Duel")
        awaitStar("Duel")
        ui.onNodeWithContentDescription("build actions for Duel").performScrollTo().performClick()
        ui.onNodeWithText("Delete").performClick()
        ui.onNodeWithText("Delete").performClick()
        await("No favorites yet")
        assertEquals(listOf("Frost"), records().map { it.name })
        ui.onNodeWithText("Favorites").performScrollTo().assertIsOn()
        ui.onNodeWithContentDescription("Filter by class: Warrior").assertExists()
    }

    @Test fun failedFavoriteKeepsPreviousStateAndRetrySurvivesAnAppRestart() {
        start()
        val before = storage.payload
        storage.failWrites = true
        ui.onNodeWithContentDescription("Add to Favorites: Raid Arms").performScrollTo().performClick()
        await("Could not update favorites. Your build is unchanged.")
        assertEquals(before, storage.payload)
        ui.onNodeWithContentDescription("Add to Favorites: Raid Arms").assertIsOff()
        storage.failWrites = false
        ui.onNodeWithText("Retry").performClick()
        awaitStar("Raid Arms")
        val confirmed = records()
        val writes = storage.writeCount
        ui.runOnIdle { restart.value++ }
        openLibrary()
        awaitStar("Raid Arms")
        assertEquals(confirmed, records())
        assertEquals(writes, storage.writeCount)
    }

    @Test fun duplicatingAFavoriteDoesNotFavoriteTheCopyOrReorderTheOriginals() {
        start()
        val before = records()
        addArms()
        assertEquals(before.map { if (it.name == "Raid Arms") it.copy(isFavorite = true) else it }, records())
        ui.onNodeWithContentDescription("build actions for Raid Arms").performScrollTo().performClick()
        ui.onNodeWithText("Duplicate").performClick()
        await("Raid Arms (copy)")
        ui.onNodeWithContentDescription("Add to Favorites: Raid Arms (copy)").performScrollTo().assertIsOff()
        ui.onNodeWithContentDescription("Remove from Favorites: Raid Arms").performScrollTo().assertIsOn()
        ui.onNodeWithText("Favorites").performScrollTo().performClick()
        ui.onNodeWithText("Raid Arms (copy)").assertDoesNotExist()
        ui.onNodeWithText("Frost").assertDoesNotExist()
        ui.onNodeWithText("Raid Arms").assertExists()
    }
    @Test fun navigationWaitsForTheFavoriteWriteSoOpeningCannotOverwriteIt() {
        start()
        val before = records()
        storage.writeDelayMillis = 1500
        ui.onNodeWithContentDescription("Add to Favorites: Raid Arms").performScrollTo().performClick()
        ui.onNodeWithText("Raid Arms").assertIsNotEnabled()
        ui.onNodeWithContentDescription("build actions for Raid Arms").assertIsNotEnabled()
        ui.onNodeWithContentDescription("go back").assertIsNotEnabled()
        ui.onNodeWithText("+").assertIsNotEnabled()
        ui.onNodeWithText("Import build").assertIsNotEnabled()
        ui.runOnIdle { kotlin.test.assertTrue(DesktopBackDispatcher.dispatch()) }
        ui.onNodeWithText("Search builds").assertExists()
        awaitStar("Raid Arms")
        assertEquals(before.map { if (it.name == "Raid Arms") it.copy(isFavorite = true) else it }, records())
    }

}
