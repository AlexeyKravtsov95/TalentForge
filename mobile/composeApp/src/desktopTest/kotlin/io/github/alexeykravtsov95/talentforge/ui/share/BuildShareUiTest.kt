package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.MAGE_EMPTY
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * M5.SHARE.2 export against the REAL app root, real bundled datasets and
 * engine, with an injected recording share adapter: what reaches the
 * platform is asserted byte for byte.
 *
 * The editor exports the session's in-memory build at the moment of the tap
 * — after rapid spend / refund / undo / redo, while an autosave is held
 * pending, and after an autosave failed — and All Builds exports the latest
 * persisted build without writing anything.
 */
class BuildShareUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private val sharing = RecordingBuildCodeSharing()
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    /** A storage whose writes can be held, so an autosave stays pending for as long as a test needs. */
    private class HoldableStorage : BuildLibraryStorage {
        @Volatile var payload: String? = null

        @Volatile var failWrites = false

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

    private fun has(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun seedWarriorDraft(): LocalBuildRecord = assertIs<DraftCreation.Created>(
        runBlocking { createClassDraft(repository(), datasets, ClassToken("WARRIOR")) },
    ).record

    private fun seed(build: BuildFile, name: String? = null): LocalBuildRecord {
        val record = assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository().createDraft(build)).value
        return if (name == null) {
            record
        } else {
            assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository().renameBuild(record.localId, name)).value
        }
    }

    private fun persisted(localId: String): BuildFile = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(
        LocalBuildRepository(FakeBuildLibraryStorage(storage.payload), FixedClock(9L), SequentialIds()).load(),
    ).value.single { it.localId == localId }.build

    private fun decoded(code: String) = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build

    private fun encode(build: BuildFile) = assertIs<ShareCodeEncoding.Encoded>(BuildShareCode.encode(build)).code

    private fun openEditorFromHome(meta: String, continueLabel: String = "Continue editing") {
        awaitText(continueLabel)
        composeRule.onNodeWithText(continueLabel).performClick()
        awaitText(meta)
    }

    private fun openShareSheet() {
        composeRule.onNodeWithContentDescription("open build actions").performClick()
        // the ⋯ item is the shared row-sheet label "Share" (the sheet itself is titled "Share build")
        awaitText("Share")
        composeRule.onNodeWithText("Share").performClick()
        awaitText("Copy code")
    }

    private fun copyCode(): String {
        val before = sharing.copied.size
        composeRule.onNodeWithText("Copy code").performClick()
        composeRule.waitUntil(10_000) { sharing.copied.size > before }
        awaitText("Code copied")
        return sharing.copied.last()
    }

    private fun spend(times: Int) = repeat(times) { composeRule.onNodeWithText("+").performClick() }

    private fun systemBack(): Boolean {
        var handled = false
        composeRule.runOnIdle { handled = DesktopBackDispatcher.dispatch() }
        composeRule.waitForIdle()
        return handled
    }

    // ------------------------------------------------------------------ editor

    @Test
    fun anEmptyBuildSharesItsExactCanonicalCode() {
        val record = seedWarriorDraft()
        composeApp()
        openEditorFromHome("Warrior · Forever")

        openShareSheet()
        val code = copyCode()

        assertTrue(record.build.allocations.isEmpty())
        assertEquals(encode(record.build), code)
        // the sheet shows the same exact code, without decoration
        composeRule.onNodeWithText(code).assertExists()
    }

    @Test
    fun aFullyAllocatedOlderBuildSharesItsOriginalCode() {
        seed(decoded(DRUID_FULL_70009))
        composeApp()
        openEditorFromHome("Druid · Forever")

        openShareSheet()
        composeRule.onNodeWithText("Share").performClick()
        composeRule.waitUntil(10_000) { sharing.shared.isNotEmpty() }

        assertEquals(listOf(DRUID_FULL_70009), sharing.shared, "the 70009 code round-trips byte for byte")
        // a chooser that opened is not a delivery: nothing is claimed
        composeRule.waitForIdle()
        assertTrue(!has("Code copied"))
    }

    @Test
    fun rapidSpendRefundUndoRedoThenShareExportsTheCurrentStateWhileAutosaveIsPending() {
        val record = seedWarriorDraft()
        composeApp()
        openEditorFromHome("Warrior · Forever")
        storage.hold() // every autosave from here on stays pending

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        spend(3)
        composeRule.onNodeWithText("−").performClick()
        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        composeRule.onNodeWithContentDescription("Redo talent change").performClick()
        // immediately: no wait for any save
        openShareSheet()
        val shared = decoded(copyCode())

        assertEquals(2, shared.allocations.single().rank, "+3, −1, undo, redo = 2 ranks")
        assertEquals(record.build.copy(allocations = shared.allocations), shared, "same identity, current ranks")
        assertTrue(persisted(record.localId).allocations.isEmpty(), "storage still holds the old build")

        // the save pipeline is untouched by sharing: once released, Back
        // flushes exactly the shared state to storage
        storage.release()
        assertTrue(systemBack())
        awaitGone("Copy code")
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")
        assertEquals(shared, persisted(record.localId))
    }

    @Test
    fun undoAndRedoBetweenSharesAlwaysExportTheStateOnScreen() {
        seedWarriorDraft()
        composeApp()
        openEditorFromHome("Warrior · Forever")
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        spend(2)

        openShareSheet()
        val two = decoded(copyCode())
        assertTrue(systemBack())
        awaitGone("Copy code")

        composeRule.onNodeWithContentDescription("Undo talent change").performClick()
        openShareSheet()
        val one = decoded(copyCode())
        assertTrue(systemBack())
        awaitGone("Copy code")

        composeRule.onNodeWithContentDescription("Redo talent change").performClick()
        openShareSheet()
        val twoAgain = decoded(copyCode())

        assertEquals(2, two.allocations.single().rank)
        assertEquals(1, one.allocations.single().rank, "no stale code after Undo")
        assertEquals(two, twoAgain)
        assertEquals(3, sharing.copied.size)
    }

    @Test
    fun aFailedAutosaveNeitherBlocksSharingNorIsMaskedByIt() {
        val record = seedWarriorDraft()
        composeApp()
        openEditorFromHome("Warrior · Forever")
        storage.failWrites = true

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        spend(1)
        awaitText("Couldn't save your changes on this device")

        openShareSheet()
        val shared = decoded(copyCode())
        assertEquals(1, shared.allocations.single().rank, "the player's current build is shared")
        assertTrue(persisted(record.localId).allocations.isEmpty())

        assertTrue(systemBack())
        awaitGone("Copy code")
        // the save failure is still reported — sharing is not saving
        composeRule.onNodeWithText("Couldn't save your changes on this device").assertExists()
    }

    @Test
    fun shareFailuresAreReportedAndCancellationIsSilent() {
        seedWarriorDraft()
        composeApp()
        openEditorFromHome("Warrior · Forever")
        openShareSheet()

        sharing.shareOutcome = TextShareOutcome.Cancelled
        composeRule.onNodeWithText("Share").performClick()
        composeRule.waitUntil(10_000) { sharing.shared.size == 1 }
        composeRule.waitForIdle()
        assertTrue(!has("Code copied") && !has("The share menu couldn't be opened. Copy the code instead."))

        sharing.shareOutcome = TextShareOutcome.Failed(PlatformShareFailure.NO_SHARE_TARGET)
        composeRule.onNodeWithText("Share").performClick()
        awaitText("No app on this device can share text. Copy the code instead.")

        sharing.shareOutcome = TextShareOutcome.Failed(PlatformShareFailure.PRESENTATION_UNAVAILABLE)
        composeRule.onNodeWithText("Share").performClick()
        awaitText("The share menu couldn't be opened. Copy the code instead.")

        sharing.copyOutcome = ClipboardWrite.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        composeRule.onNodeWithText("Copy code").performClick()
        awaitText("Couldn't copy the code to the clipboard")

        // desktop has no share sheet: its fallback copies and says so
        sharing.shareOutcome = TextShareOutcome.CopiedToClipboard
        composeRule.onNodeWithText("Share").performClick()
        awaitText("Code copied — this device has no share menu")

        // every dispatched text was the one exact code
        assertEquals(1, (sharing.shared + sharing.copied).toSet().size)
    }

    @Test
    fun backClosesTheSheetAndKeepsTheEditorState() {
        seedWarriorDraft()
        composeApp()
        openEditorFromHome("Warrior · Forever")
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        spend(1)

        openShareSheet()
        assertTrue(systemBack())
        awaitGone("Copy code")

        composeRule.onNodeWithText("Warrior · Forever").assertExists()
        composeRule.onNodeWithText("−").assertExists() // the selected talent still has its rank
    }

    // ------------------------------------------------------------------ All Builds

    @Test
    fun allBuildsSharesTheLatestPersistedBuildWithoutWriting() {
        val druid = decoded(DRUID_FULL_70009)
        val target = seed(druid.copy(allocations = emptyList()), name = "Feral")
        clock.advanceBy(1_000)
        seed(decoded(MAGE_EMPTY), name = "Frost")
        // edited after both were created: the newest persisted value is shared
        clock.advanceBy(1_000)
        repository().saveBuild(target.localId, druid)
        val payloadBefore = storage.payload

        composeApp()
        awaitText("All builds 2")
        composeRule.onNodeWithText("All builds 2").performClick()
        awaitText("Builds 2")
        composeRule.onNodeWithContentDescription("build actions for Feral").performClick()
        awaitText("Share")
        composeRule.onNodeWithText("Share").performClick()
        awaitText("Copy code")
        copyCode()

        assertEquals(listOf(DRUID_FULL_70009), sharing.copied)
        assertEquals(payloadBefore, storage.payload, "no open(), no timestamp, no reorder, no rewrite")
        assertTrue(systemBack())
        awaitGone("Copy code")
        composeRule.onNodeWithText("Builds 2").assertExists()
    }

    @Test
    fun aRecordThatVanishedBeforeSharingIsARecoverableNotice() {
        val gone = seed(decoded(MAGE_EMPTY), name = "Gone")
        clock.advanceBy(1_000)
        seed(decoded(DRUID_FULL_70009), name = "Kept")
        composeApp()
        awaitText("All builds 2")
        composeRule.onNodeWithText("All builds 2").performClick()
        awaitText("Builds 2")
        composeRule.onNodeWithContentDescription("build actions for Gone").performClick()
        awaitText("Share")
        // removed underneath before the tap
        repository().deleteBuild(gone.localId)

        composeRule.onNodeWithText("Share").performClick()
        awaitText("This build no longer exists on this device")
        composeRule.onNodeWithText("Retry").assertExists()
        assertTrue(sharing.shared.isEmpty() && sharing.copied.isEmpty())
        assertNotEquals(null, storage.payload)
    }

    @Test
    fun theShareSheetIsLocalizedInRussian() {
        uiLanguage.language = AppLanguage.RUSSIAN
        seedWarriorDraft()
        composeApp()
        openEditorFromHome("Воин · Forever", continueLabel = "Продолжить")

        composeRule.onNodeWithContentDescription("открыть действия с билдом").performClick()
        awaitText("Поделиться")
        composeRule.onNodeWithText("Поделиться").performClick()
        awaitText("Копировать код")
        composeRule.onNodeWithText("Поделиться").assertExists()
        composeRule.onNodeWithText("Копировать код").performClick()
        awaitText("Код скопирован")
        // the code itself is never translated or decorated
        assertTrue(sharing.copied.single().startsWith("TF1"))
        assertTrue(sharing.copied.single().none { it.isWhitespace() })
    }
}
