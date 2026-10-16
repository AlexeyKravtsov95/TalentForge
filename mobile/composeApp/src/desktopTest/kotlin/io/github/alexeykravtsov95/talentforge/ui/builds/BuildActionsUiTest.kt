package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryWriteException
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.share.BuildShareCode
import io.github.alexeykravtsov95.talentforge.share.ShareCodeDecoding
import io.github.alexeykravtsov95.talentforge.share.ShareCodeEncoding
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.DRUID_FULL_70009
import io.github.alexeykravtsov95.talentforge.ui.share.RecordingBuildCodeSharing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * M5.SHARE.2 — Rename and Share from all three places (Home's Recent rows,
 * the editor's ⋯ sheet, All Builds) against the REAL app root, bundled
 * datasets and engine, with a recording share adapter and a storage whose
 * writes can be held or failed:
 *
 * - a Recent row opens the editor, its ⋯ never does, and its sheet offers
 *   exactly Rename and Share;
 * - a rename is the same shared dialog and rule everywhere, metadata only
 *   (no allocation, `updatedAt` or order change), updates the editor title in
 *   place without restarting the session, waits for a pending autosave, is
 *   refused while the newest save failed, keeps the typed name on a failed
 *   write and writes once on a double tap;
 * - Share exports the selected record's latest persisted build (Home) or the
 *   editor's in-memory build right after Reset, writing nothing.
 */
class BuildActionsUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private val sharing = RecordingBuildCodeSharing()
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    /** Writes can be held (an operation stays in flight) or failed; completed writes are counted. */
    private class HoldableStorage : BuildLibraryStorage {
        @Volatile var payload: String? = null

        @Volatile var failWrites = false

        @Volatile var writes = 0

        @Volatile private var gate: CountDownLatch? = null

        fun hold() {
            gate = CountDownLatch(1)
        }

        fun release() {
            gate?.countDown()
            gate = null
        }

        override fun read(): String? = payload

        override fun write(payload: String) {
            gate?.await(20, TimeUnit.SECONDS)
            if (failWrites) throw BuildLibraryWriteException("injected write failure")
            this.payload = payload
            writes += 1
        }
    }

    private val storage = HoldableStorage()

    @AfterTest
    fun tearDown() = storage.release()

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun composeApp() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(393.dp, 852.dp)) {
                        App(storage = storage, clock = clock, ids = ids, sharing = sharing)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun awaitText(text: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitGone(text: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun awaitDescription(description: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }

    private fun has(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasDescription(description: String) =
        composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun systemBack(): Boolean {
        var handled = false
        composeRule.runOnIdle { handled = DesktopBackDispatcher.dispatch() }
        composeRule.waitForIdle()
        return handled
    }

    private fun decoded(code: String) = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build

    private fun encode(build: BuildFile) = assertIs<ShareCodeEncoding.Encoded>(BuildShareCode.encode(build)).code

    private fun seedWarrior(name: String?): LocalBuildRecord {
        val record = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository(), datasets, ClassToken("WARRIOR")) },
        ).record
        return if (name == null) record else rename(record, name)
    }

    private fun seed(build: BuildFile, name: String): LocalBuildRecord =
        rename(assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository().createDraft(build)).value, name)

    private fun rename(record: LocalBuildRecord, name: String): LocalBuildRecord =
        assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository().renameBuild(record.localId, name)).value

    /** Every persisted record, read independently of the app. */
    private fun persisted(): List<LocalBuildRecord> = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(
        LocalBuildRepository(FakeBuildLibraryStorage(storage.payload), FixedClock(9L), SequentialIds()).load(),
    ).value

    private fun persisted(localId: String): LocalBuildRecord = persisted().single { it.localId == localId }

    /** Two named builds: the returned first (a Recent row, "Feral") and second (the Continue card, "Frost"). */
    private fun seedTwo(): Pair<LocalBuildRecord, LocalBuildRecord> {
        val older = seed(decoded(DRUID_FULL_70009), "Feral")
        clock.advanceBy(60_000)
        val newer = seedWarrior("Frost")
        clock.advanceBy(60_000)
        return older to newer
    }

    private fun openRecentActions(title: String) {
        awaitDescription("build actions for $title")
        composeRule.onNodeWithContentDescription("build actions for $title").performClick()
        awaitText("Rename")
    }

    private fun renameField(): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription("build name")

    private fun fieldText(): String =
        renameField().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun typeName(name: String) {
        renameField().performTextReplacement(name)
        composeRule.waitForIdle()
    }

    private fun openEditorFromContinue() {
        awaitText("Continue editing")
        composeRule.onNodeWithText("Continue editing").performClick()
        awaitDescription("open build actions")
    }

    private fun openEditorActions() {
        composeRule.onNodeWithContentDescription("open build actions").performClick()
        awaitText("Reset talents")
    }

    private fun spend(times: Int) = repeat(times) { composeRule.onNodeWithText("+").performClick() }

    private fun top(text: String) = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    // ---------------------------------------------------------------- Home rows

    @Test
    fun aRecentRowOpensTheEditorButItsActionsButtonNeverDoes() {
        seedTwo()
        composeApp()
        awaitText("Feral")
        val writes = storage.writes

        openRecentActions("Feral")
        composeRule.waitForIdle()
        assertTrue(has("Share"), "the Recent sheet offers Share")
        assertTrue(!has("Duplicate") && !has("Delete"), "the Recent sheet holds exactly Rename and Share")
        assertTrue(!hasDescription("open build actions"), "⋯ must never open the editor")
        assertEquals(writes, storage.writes, "opening the sheet writes nothing")

        assertTrue(systemBack(), "Back closes the sheet first")
        awaitGone("Rename")
        assertTrue(has("CONTINUE"), "still on Home")

        composeRule.onNodeWithText("Feral").performClick()
        awaitDescription("open build actions")
    }

    @Test
    fun renamingFromRecentChangesOnlyTheNameAndKeepsTheOrder() {
        val (older, newer) = seedTwo()
        composeApp()
        openRecentActions("Feral")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        assertEquals("Feral", fieldText(), "the current name is pre-filled")

        typeName("Feral PvP")
        composeRule.onNodeWithText("Save").performClick()
        awaitText("Feral PvP")
        awaitGone("Rename build")

        assertEquals(older.copy(name = "Feral PvP"), persisted(older.localId), "only the name changed")
        assertEquals(newer, persisted(newer.localId), "the other build is untouched")
        assertEquals(2, persisted().size, "no record was created")
        // the order is unchanged: Frost is still the Continue card, Feral PvP a Recent row
        assertTrue(top("Frost") < top("Feral PvP"))

        composeRule.onNodeWithText("Feral PvP").performClick()
        awaitDescription("open build actions")
        assertTrue(has("Feral PvP"), "the reopened editor shows the new name")
    }

    @Test
    fun aFailedRenameKeepsTheTypedNameAndTheRetrySucceeds() {
        val (older, _) = seedTwo()
        composeApp()
        openRecentActions("Feral")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        typeName("Bear")

        storage.failWrites = true
        composeRule.onNodeWithText("Save").performClick()
        awaitText("Couldn't rename the build")
        assertEquals("Bear", fieldText(), "a failed save keeps the typed name")
        assertEquals("Feral", persisted(older.localId).name)

        storage.failWrites = false
        composeRule.onNodeWithText("Save").performClick()
        awaitGone("Rename build")
        awaitText("Bear")
        assertEquals("Bear", persisted(older.localId).name)
    }

    @Test
    fun aDoubleTapOnSaveRenamesExactlyOnce() {
        val (older, _) = seedTwo()
        composeApp()
        openRecentActions("Feral")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        typeName("Bear")
        val writes = storage.writes

        storage.hold()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Cancel").performClick() // ignored while the write runs
        assertTrue(has("Rename build"), "the dialog stays while the rename is written")
        storage.release()
        awaitGone("Rename build")

        assertEquals(writes + 1, storage.writes, "one rename, one write")
        assertEquals("Bear", persisted(older.localId).name)
        assertEquals(2, persisted().size)
    }

    @Test
    fun sharingFromRecentExportsThatRecordsLatestPersistedBuild() {
        val (older, _) = seedTwo()
        composeApp()
        openRecentActions("Feral")
        val writes = storage.writes

        composeRule.onNodeWithText("Share").performClick()
        awaitText("Copy code")
        composeRule.onNodeWithText("Copy code").performClick()
        composeRule.waitUntil(10_000) { sharing.copied.isNotEmpty() }

        assertEquals(listOf(encode(older.build)), sharing.copied, "the selected row's build, exactly")
        assertEquals(DRUID_FULL_70009, sharing.copied.single(), "the 70009 identity is kept")
        assertEquals(writes, storage.writes, "sharing writes nothing")
        assertTrue(systemBack())
        awaitGone("Copy code")
        assertTrue(has("CONTINUE") && !hasDescription("open build actions"), "no navigation happened")
    }

    @Test
    fun sharingARecentBuildThatVanishedSaysSoAndRefreshesHome() {
        val (older, _) = seedTwo()
        composeApp()
        openRecentActions("Feral")
        // the record disappears behind the open sheet
        assertIs<BuildLibraryOutcome.Loaded<Unit>>(repository().deleteBuild(older.localId))

        composeRule.onNodeWithText("Share").performClick()
        awaitText("This build no longer exists on this device")
        awaitGone("Feral")
        assertTrue(sharing.copied.isEmpty() && sharing.shared.isEmpty())
        assertEquals(1, persisted().size)
    }

    // -------------------------------------------------------------------- editor

    @Test
    fun theEditorMenuOffersRenameShareAndResetInThatOrder() {
        seedWarrior(null)
        composeApp()
        openEditorFromContinue()
        openEditorActions()

        assertTrue(top("Rename") < top("Share"))
        assertTrue(top("Share") < top("Reset talents"))
    }

    @Test
    fun renamingInTheEditorUpdatesTheTitleInPlaceAndEveryList() {
        val record = seedWarrior(null)
        composeApp()
        openEditorFromContinue()
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        spend(2)
        composeRule.waitUntil(10_000) { persisted(record.localId).build.allocations.singleOrNull()?.rank == 2 }
        val saved = persisted(record.localId)
        clock.advanceBy(60_000)

        openEditorActions()
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        assertEquals("", fieldText(), "a Draft starts with an empty field")
        typeName("Fury")
        composeRule.onNodeWithText("Save").performClick()
        awaitGone("Rename build")
        awaitText("Fury")

        // same session: the selection is still on screen and the history still undoes
        assertTrue(has("+"), "the selected talent's inspector is still open")
        assertEquals(saved.copy(name = "Fury"), persisted(record.localId), "allocation and updatedAt untouched")
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.waitUntil(10_000) { persisted(record.localId).build.allocations.singleOrNull()?.rank == 1 }
        assertEquals("Fury", persisted(record.localId).name, "the autosave after a rename keeps the name")

        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")
        awaitText("Fury")
        composeRule.onNodeWithText("All builds 1").performClick()
        awaitText("Builds 1")
        assertTrue(has("Fury"))
    }

    @Test
    fun backClosesTheEditorRenameDialogFirstAndKeepsTheSelection() {
        val record = seedWarrior(null)
        composeApp()
        openEditorFromContinue()
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        awaitText("+")
        openEditorActions()
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        typeName("Unsaved")

        assertTrue(systemBack(), "Back is consumed")
        awaitGone("Rename build")
        assertTrue(has("+"), "the dialog closed first: the talent is still selected")
        assertTrue(hasDescription("open build actions"), "still in the editor")
        assertNull(persisted(record.localId).name, "Back never renames")
    }

    @Test
    fun aRenameWaitsForAPendingAutosaveAndKeepsBothChanges() {
        val record = seedWarrior(null)
        composeApp()
        openEditorFromContinue()
        storage.hold() // the next autosave stays pending

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        spend(2)
        openEditorActions()
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        typeName("Pending")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()
        assertTrue(has("Rename build"), "the rename waits for the pending save")

        storage.release()
        awaitGone("Rename build")
        awaitText("Pending")
        val stored = persisted(record.localId)
        assertEquals("Pending", stored.name)
        assertEquals(2, stored.build.allocations.single().rank, "the pending allocation was not overwritten")
    }

    @Test
    fun aRenameIsRefusedWhileTheNewestSaveFailedAndWorksAfterRetry() {
        val record = seedWarrior(null)
        composeApp()
        openEditorFromContinue()
        storage.failWrites = true
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        spend(1)
        awaitText("Couldn't save your changes on this device")
        storage.failWrites = false

        openEditorActions()
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        typeName("Broken")
        composeRule.onNodeWithText("Save").performClick()
        awaitText("Your latest talent changes aren't saved yet. Close this, tap Retry to save them, then rename.")
        assertNull(persisted(record.localId).name, "nothing was renamed")
        assertTrue(persisted(record.localId).build.allocations.isEmpty())

        composeRule.onNodeWithText("Cancel").performClick()
        awaitGone("Rename build")
        assertTrue(has("−"), "the editor kept the unsaved rank")
        composeRule.onNodeWithText("Retry").performClick()
        awaitGone("Couldn't save your changes on this device")

        openEditorActions()
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        typeName("Fixed")
        composeRule.onNodeWithText("Save").performClick()
        awaitGone("Rename build")
        awaitText("Fixed")
        assertEquals("Fixed", persisted(record.localId).name)
        assertEquals(1, persisted(record.localId).build.allocations.single().rank)
    }

    @Test
    fun shareRightAfterResetExportsTheEmptyBuildWhileTheSaveIsPending() {
        val record = seedWarrior("Arms")
        composeApp()
        openEditorFromContinue()
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        spend(2)
        composeRule.waitUntil(10_000) { persisted(record.localId).build.allocations.singleOrNull()?.rank == 2 }

        storage.hold()
        openEditorActions()
        composeRule.onNodeWithText("Reset talents").performClick()
        awaitGone("Reset talents")
        openEditorActions()
        composeRule.onNodeWithText("Share").performClick()
        awaitText("Copy code")
        composeRule.onNodeWithText("Copy code").performClick()
        composeRule.waitUntil(10_000) { sharing.copied.isNotEmpty() }

        assertEquals(encode(record.build), sharing.copied.single(), "the reset (empty) build, not the stored one")
        assertEquals(2, persisted(record.localId).build.allocations.single().rank, "storage still lags behind")
        assertEquals("Arms", persisted(record.localId).name, "sharing never renames")
    }

    // ---------------------------------------------------------------- All Builds

    @Test
    fun allBuildsKeepsRenameDuplicateShareDeleteThroughTheSharedActions() {
        val (older, _) = seedTwo()
        composeApp()
        awaitText("All builds 2")
        composeRule.onNodeWithText("All builds 2").performClick()
        awaitDescription("build actions for Feral")
        composeRule.onNodeWithContentDescription("build actions for Feral").performClick()
        awaitText("Duplicate")
        assertTrue(has("Rename") && has("Share") && has("Delete"))

        composeRule.onNodeWithText("Share").performClick()
        awaitText("Copy code")
        composeRule.onNodeWithText("Copy code").performClick()
        composeRule.waitUntil(10_000) { sharing.copied.isNotEmpty() }
        assertEquals(DRUID_FULL_70009, sharing.copied.single())
        assertTrue(systemBack())
        awaitGone("Copy code")

        composeRule.onNodeWithContentDescription("build actions for Feral").performClick()
        awaitText("Rename")
        composeRule.onNodeWithText("Rename").performClick()
        awaitText("Rename build")
        assertEquals("Feral", fieldText())
        typeName("Moonkin")
        composeRule.onNodeWithText("Save").performClick()
        awaitGone("Rename build")
        awaitText("Moonkin")
        assertEquals(older.copy(name = "Moonkin"), persisted(older.localId))
    }

    // ---------------------------------------------------------------- Russian

    @Test
    fun everyActionsMenuIsLocalizedInRussian() {
        uiLanguage.language = AppLanguage.RUSSIAN
        seedTwo()
        composeApp()
        awaitDescription("действия: Feral")
        composeRule.onNodeWithContentDescription("действия: Feral").performClick()
        awaitText("Переименовать")
        assertTrue(has("Поделиться"))
        composeRule.onNodeWithText("Переименовать").performClick()
        awaitText("Новое название")
        composeRule.onNodeWithContentDescription("название билда").assertExists()
        composeRule.onNodeWithText("Отмена").performClick()
        awaitGone("Новое название")

        composeRule.onNodeWithText("Продолжить").performClick()
        awaitDescription("открыть действия с билдом")
        composeRule.onNodeWithContentDescription("открыть действия с билдом").performClick()
        awaitText("Сбросить таланты")
        assertTrue(has("Переименовать") && has("Поделиться"))
        assertTrue(top("Переименовать") < top("Поделиться"))
        assertTrue(top("Поделиться") < top("Сбросить таланты"))
    }
}
