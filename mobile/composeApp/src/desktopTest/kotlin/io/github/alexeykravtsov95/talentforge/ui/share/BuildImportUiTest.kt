package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.share.BuildShareCode
import io.github.alexeykravtsov95.talentforge.share.ShareCodeDecoding
import io.github.alexeykravtsov95.talentforge.share.ShareCodeEncoding
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.AppDestination
import io.github.alexeykravtsov95.talentforge.ui.EditorOrigin
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.decodeDestinations
import io.github.alexeykravtsov95.talentforge.ui.encodeDestination
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.DRUID_FULL_70009
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.MAGE_EMPTY
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.WARRIOR_13_OF_51
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M5.SHARE.2 import through the REAL app root: Home and All Builds entry
 * points, the one import screen, the confirmation surface, the single write,
 * the editor it opens and the way back — with real bundled datasets, the
 * real engine and an in-memory storage.
 */
class BuildImportUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private val storage = FakeBuildLibraryStorage()
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val sharing = RecordingBuildCodeSharing()

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun composeApp() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        App(storage = storage, clock = clock, ids = ids, sharing = sharing)
                    }
                }
            }
        }
    }

    private fun awaitText(text: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitGone(text: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun records(): List<LocalBuildRecord> =
        assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(
            LocalBuildRepository(FakeBuildLibraryStorage(storage.payload), FixedClock(9L), SequentialIds()).load(),
        ).value

    private fun decoded(code: String): BuildFile = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build

    private fun seed(code: String, name: String): LocalBuildRecord {
        val record = assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository().createDraft(decoded(code))).value
        return assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository().renameBuild(record.localId, name)).value
    }

    private fun openImportFromHome() {
        awaitText("Import build")
        composeRule.onNodeWithText("Import build").performClick()
        awaitText("Paste the code here")
    }

    private fun openAllBuilds(count: Int) {
        awaitText("All builds $count")
        composeRule.onNodeWithText("All builds $count").performClick()
        awaitText("Builds $count")
    }

    private fun enterCode(code: String) {
        composeRule.onNodeWithContentDescription("build code").performTextInput(code)
    }

    private fun continueToPreview() {
        composeRule.onNodeWithText("Continue").performClick()
        awaitText("Import this build?")
    }

    private fun systemBack(): Boolean {
        var handled = false
        composeRule.runOnIdle { handled = DesktopBackDispatcher.dispatch() }
        composeRule.waitForIdle()
        return handled
    }

    // ------------------------------------------------------------------ Home

    @Test
    fun anEmptyLibraryImportsFromHomeAndBackReturnsHome() {
        composeApp()
        awaitText("No builds yet")
        openImportFromHome()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled() // blank input

        enterCode(WARRIOR_13_OF_51)
        continueToPreview()
        composeRule.onNodeWithText("Warrior").assertExists()
        composeRule.onNodeWithText("Forever · talent data 1.60.1.70374").assertExists()
        composeRule.onNodeWithText("Points: 13 / 51").assertExists()
        composeRule.onNodeWithText(
            "Importing adds a new, independent build to this device. Your other builds stay unchanged.",
        ).assertExists()
        assertEquals(null, storage.payload, "the preview never saves")

        composeRule.onNodeWithText("Import").performClick()
        awaitText("Warrior · Forever") // the editor of the new record
        composeRule.onNodeWithText("13 / 51").assertExists()

        val imported = records().single()
        assertEquals(decoded(WARRIOR_13_OF_51), imported.build)
        assertEquals(null, imported.name)

        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE") // Home, now with the imported build
    }

    @Test
    fun backFromTheImportScreenReturnsHomeWithoutWriting() {
        composeApp()
        openImportFromHome()
        enterCode(WARRIOR_13_OF_51)

        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("No builds yet")
        assertEquals(null, storage.payload)
    }

    @Test
    fun cancelAndSystemBackCloseThePreviewWithoutWriting() {
        composeApp()
        openImportFromHome()
        enterCode(WARRIOR_13_OF_51)
        continueToPreview()

        composeRule.onNodeWithText("Cancel").performClick()
        awaitGone("Import this build?")
        composeRule.onNodeWithText(WARRIOR_13_OF_51).assertExists() // the input is kept

        continueToPreview()
        assertTrue(systemBack())
        awaitGone("Import this build?")
        composeRule.onNodeWithText("Continue").assertExists() // still on the import screen
        composeRule.onNodeWithText(WARRIOR_13_OF_51).assertExists()

        assertEquals(null, storage.payload)
        assertEquals(0, storage.writeCount)
    }

    // ------------------------------------------------------------------ All Builds

    @Test
    fun importFromAllBuildsReturnsToAllBuildsAndAddsOneRecord() {
        seed(MAGE_EMPTY, "Frost")
        composeApp()
        openAllBuilds(1)
        composeRule.onNodeWithText("Import build").performScrollTo().performClick()
        awaitText("Paste the code here")

        // the explicit Paste button
        sharing.clipboard = ClipboardRead.Text(DRUID_FULL_70009)
        composeRule.onNodeWithText("Paste").performClick()
        awaitText(DRUID_FULL_70009)
        continueToPreview()
        // an older client build is shown — and kept — exactly
        composeRule.onNodeWithText("Forever · talent data 1.60.1.70009").assertExists()
        composeRule.onNodeWithText("Points: 51 / 51").assertExists()
        composeRule.onNodeWithText("Import").performClick()
        awaitText("Druid · Forever")

        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("Builds 2") // the editor returns to where the import was opened
        val all = records()
        assertEquals(2, all.size)
        assertEquals("1.60.1.70009", all.single { it.name == null }.build.clientBuild)
        assertEquals(decoded(MAGE_EMPTY), all.single { it.name == "Frost" }.build, "the existing build is untouched")
    }

    @Test
    fun backFromTheImportScreenReturnsToAllBuilds() {
        seed(MAGE_EMPTY, "Frost")
        composeApp()
        openAllBuilds(1)
        composeRule.onNodeWithText("Import build").performScrollTo().performClick()
        awaitText("Paste the code here")

        assertTrue(systemBack())
        awaitText("Builds 1")
    }

    @Test
    fun theEmptyAllBuildsStateStillOffersImport() {
        seed(MAGE_EMPTY, "Frost")
        composeApp()
        openAllBuilds(1)
        composeRule.onNodeWithContentDescription("build actions for Frost").performClick()
        awaitText("Delete")
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("Delete \"Frost\"?")
        composeRule.onNodeWithText("Delete").performClick()
        awaitText("No saved builds yet.")

        composeRule.onNodeWithText("Import build").performScrollTo().performClick()
        awaitText("Paste the code here")
        enterCode(MAGE_EMPTY)
        continueToPreview()
        composeRule.onNodeWithText("Import").performClick()
        awaitText("Mage · Forever")
        assertEquals(1, records().size)
    }

    // ------------------------------------------------------------------ problems

    @Test
    fun invalidDamagedAndUnshippedCodesShowTheirOwnMessages() {
        composeApp()
        openImportFromHome()

        enterCode("hello there")
        composeRule.onNodeWithText("Continue").performClick()
        awaitText("This isn't a TalentForge build code")

        composeRule.onNodeWithText("Clear").performClick()
        val corrupted = MAGE_EMPTY.substring(0, 20) + (if (MAGE_EMPTY[20] == 'A') 'B' else 'A') + MAGE_EMPTY.substring(21)
        enterCode(corrupted)
        // editing cleared the previous message
        composeRule.onNodeWithText("This isn't a TalentForge build code").assertDoesNotExist()
        composeRule.onNodeWithText("Continue").performClick()
        awaitText("This code is damaged or incomplete. Copy it again from the original message.")

        composeRule.onNodeWithText("Clear").performClick()
        val unshipped = assertIs<ShareCodeEncoding.Encoded>(
            BuildShareCode.encode(decoded(MAGE_EMPTY).copy(clientBuild = "1.60.1.69977")),
        ).code
        enterCode(unshipped)
        composeRule.onNodeWithText("Continue").performClick()
        awaitText("This build uses talent data 1.60.1.69977, which this version of TalentForge doesn't include.")

        composeRule.onNodeWithText("Clear").performClick()
        enterCode("TF2" + MAGE_EMPTY.drop(3))
        composeRule.onNodeWithText("Continue").performClick()
        awaitText("This code was made by a newer version of TalentForge. Update the app to import it.")

        sharing.clipboard = ClipboardRead.Empty
        composeRule.onNodeWithText("Paste").performClick()
        awaitText("The clipboard has no text to paste")

        assertEquals(null, storage.payload, "no rejected code ever writes")
    }

    @Test
    fun aRepeatedImportTapCreatesExactlyOneRecord() {
        composeApp()
        openImportFromHome()
        enterCode(WARRIOR_13_OF_51)
        continueToPreview()

        storage.writeDelayMillis = 1_500
        composeRule.onNodeWithText("Import").performClick()
        awaitText("Importing…")
        composeRule.onNodeWithText("Import").assertIsNotEnabled()
        composeRule.onNodeWithText("Import").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(systemBack()) // held while the record is written

        awaitText("Warrior · Forever")
        assertEquals(1, records().size)
    }

    @Test
    fun aFailedWriteKeepsThePreviewAndRetrySucceeds() {
        composeApp()
        openImportFromHome()
        enterCode(WARRIOR_13_OF_51)
        continueToPreview()

        storage.failWrites = true
        composeRule.onNodeWithText("Import").performClick()
        awaitText("Couldn't save the build on this device. Try again.")
        composeRule.onNodeWithText("Import this build?").assertExists()
        assertEquals(null, storage.payload)

        storage.failWrites = false
        composeRule.onNodeWithText("Import").performClick()
        awaitText("Warrior · Forever")
        assertEquals(1, records().size)
    }

    // ------------------------------------------------------------------ localization

    @Test
    fun theImportFlowIsLocalizedInRussian() {
        uiLanguage.language = AppLanguage.RUSSIAN
        composeApp()
        awaitText("Импортировать билд")
        composeRule.onNodeWithText("Импортировать билд").performClick()
        awaitText("Вставьте код сюда")
        composeRule.onNodeWithText("Вставить").assertExists()
        composeRule.onNodeWithText("Очистить").assertExists()

        composeRule.onNodeWithContentDescription("код билда").performTextInput("hello")
        composeRule.onNodeWithText("Продолжить").performClick()
        awaitText("Это не код билда TalentForge")

        composeRule.onNodeWithText("Очистить").performClick()
        composeRule.onNodeWithContentDescription("код билда").performTextInput(WARRIOR_13_OF_51)
        composeRule.onNodeWithText("Продолжить").performClick()
        awaitText("Импортировать этот билд?")
        composeRule.onNodeWithText("Воин").assertExists()
        composeRule.onNodeWithText("Forever · данные талантов 1.60.1.70374").assertExists()
        composeRule.onNodeWithText("Очки: 13 / 51").assertExists()
        composeRule.onNodeWithText("Отмена").assertExists()
        composeRule.onNodeWithText("Импортировать").performClick()
        awaitText("Воин · Forever")
        assertEquals(1, records().size)
    }

    // ------------------------------------------------------------------ navigation state

    @Test
    fun theImportDestinationSurvivesStateRestoration() {
        val stack = listOf(AppDestination.Home, AppDestination.AllBuilds, AppDestination.ImportBuild)
        assertEquals(stack, decodeDestinations(stack.map(::encodeDestination)))
        assertEquals("import-build", encodeDestination(AppDestination.ImportBuild))
        val opened = listOf(AppDestination.Home, AppDestination.Editor("local-7", EditorOrigin.Imported))
        assertEquals(opened, decodeDestinations(opened.map(::encodeDestination)))
    }
}
