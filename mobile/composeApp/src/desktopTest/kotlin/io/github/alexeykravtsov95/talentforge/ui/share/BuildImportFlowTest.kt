package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.runtime.mutableStateOf
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.share.BuildImportFailure
import io.github.alexeykravtsov95.talentforge.share.BuildShareCode
import io.github.alexeykravtsov95.talentforge.share.ShareCodeDecodeError
import io.github.alexeykravtsov95.talentforge.share.ShareCodeDecoding
import io.github.alexeykravtsov95.talentforge.share.ShareCodeEncoding
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The M5.SHARE.2 import flow without UI: [BuildImportController] over the
 * REAL bundled datasets and engine (through `prepareBuildImport`) and the
 * real [LocalBuildRepository] on an in-memory storage. The controller runs on
 * its own single "main" thread, exactly as Compose click handlers would call
 * it; validation and the write hop to `Dispatchers.Default`.
 */
class BuildImportFlowTest {

    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val storage = FakeBuildLibraryStorage()
    private val repository = LocalBuildRepository(storage, FixedClock(), SequentialIds())
    private val bundled = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        main.close()
    }

    private fun controller(datasets: TalentDatasetRepository = bundled) =
        BuildImportController(mutableStateOf(""), datasets, repository, scope, Dispatchers.Default)

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun BuildImportController.awaitPhase(predicate: (ImportPhase) -> Boolean): ImportPhase = runBlocking {
        withTimeout(20_000) {
            var current = withContext(main) { phase }
            while (!predicate(current)) {
                delay(10)
                current = withContext(main) { phase }
            }
            current
        }
    }

    private fun BuildImportController.check(code: String): ImportPhase {
        onMain {
            onInputChange(code)
            submit()
        }
        return awaitPhase { it !is ImportPhase.Checking }
    }

    private fun records(): List<LocalBuildRecord> =
        assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository.load()).value

    private fun BuildImportController.rejection(code: String): BuildImportFailure =
        assertIs<ImportProblem.Rejected>(assertIs<ImportPhase.Problem>(check(code)).problem).failure

    // ------------------------------------------------------------------ preview

    @Test
    fun aValidCodeOpensThePreviewWithoutWritingAnything() {
        val flow = controller()
        val confirming = assertIs<ImportPhase.Confirming>(flow.check(WARRIOR_13_OF_51))

        assertEquals(WARRIOR_13_OF_51, confirming.code)
        assertEquals(ClassToken("WARRIOR"), confirming.preview.classToken)
        assertEquals("1.60.1.70374", confirming.preview.clientBuild)
        assertEquals(13, confirming.preview.spentPoints)
        assertEquals(51, confirming.preview.pointBudget)
        assertEquals(0, storage.writeCount, "a preview must never write")
        assertEquals(null, storage.payload)
    }

    @Test
    fun theInputReachesValidationExactlyAsEntered() {
        val flow = controller()
        // a lower-cased code is NOT silently upper-cased: the decoder rejects it
        val failure = assertIs<BuildImportFailure.InvalidCode>(flow.rejection(MAGE_EMPTY.lowercase()))
        assertIs<ShareCodeDecodeError.NotAShareCode>(failure.error)
        assertEquals(MAGE_EMPTY.lowercase(), flow.text, "the field keeps the text verbatim")

        // surrounding whitespace is the decoder's business; the field keeps it
        val padded = "  \n$MAGE_EMPTY\n "
        assertIs<ImportPhase.Confirming>(flow.check(padded))
        assertEquals(padded, flow.text)
    }

    @Test
    fun editingTheInputAfterAPreviewInvalidatesIt() {
        val flow = controller()
        assertIs<ImportPhase.Confirming>(flow.check(WARRIOR_13_OF_51))

        onMain { flow.onInputChange(WARRIOR_13_OF_51 + "A") }
        assertEquals(ImportPhase.Editing, onMain { flow.phase })

        // a confirm now has nothing to confirm: no write, no record
        var imported: String? = null
        onMain { flow.confirm { imported = it } }
        Thread.sleep(200)
        assertEquals(null, imported)
        assertEquals(0, storage.writeCount)
    }

    @Test
    fun aStaleValidationResultIsDroppedWhenTheInputChangedMeanwhile() {
        val gate = CompletableDeferred<Unit>()
        val gated = object : TalentDatasetRepository {
            override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> =
                bundled.manifest(bundle)

            override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> {
                gate.await()
                return bundled.document(id)
            }
        }
        val flow = controller(gated)
        onMain {
            flow.onInputChange(WARRIOR_13_OF_51)
            flow.submit()
        }
        assertIs<ImportPhase.Checking>(onMain { flow.phase })
        // the player edits while the check runs, then the old check finishes
        onMain { flow.onInputChange(MAGE_EMPTY) }
        gate.complete(Unit)
        Thread.sleep(500)
        assertEquals(ImportPhase.Editing, onMain { flow.phase }, "the stale preview must never appear")
        assertEquals(MAGE_EMPTY, flow.text)
    }

    // ------------------------------------------------------------------ confirm

    @Test
    fun confirmingCreatesExactlyOneIndependentRecordOfTheValidatedBuild() {
        val existing = assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(
            repository.createDraft(decoded(MAGE_EMPTY)),
        ).value
        val flow = controller()
        val confirming = assertIs<ImportPhase.Confirming>(flow.check(WARRIOR_13_OF_51))

        var imported: String? = null
        onMain {
            flow.confirm { imported = it }
            flow.confirm { imported = "second" } // a repeated tap while saving is ignored
        }
        val done = assertIs<ImportPhase.Imported>(flow.awaitPhase { it is ImportPhase.Imported })

        assertEquals(done.localId, imported)
        val all = records()
        assertEquals(2, all.size, "exactly one new record")
        val created = all.single { it.localId == done.localId }
        assertEquals(confirming.preview.build, created.build, "the validated build, unchanged")
        assertEquals(null, created.name, "an import is a Draft")
        assertEquals(existing, all.single { it.localId == existing.localId }, "existing builds are untouched")
    }

    @Test
    fun anOlderClientBuildKeepsItsExactIdentity() {
        val flow = controller()
        val confirming = assertIs<ImportPhase.Confirming>(flow.check(DRUID_FULL_70009))
        assertEquals("1.60.1.70009", confirming.preview.clientBuild)
        assertEquals(51, confirming.preview.spentPoints)

        onMain { flow.confirm { } }
        val done = assertIs<ImportPhase.Imported>(flow.awaitPhase { it is ImportPhase.Imported })
        val record = records().single { it.localId == done.localId }
        assertEquals("1.60.1.70009", record.build.clientBuild, "never migrated to the product bundle")
        assertEquals(decoded(DRUID_FULL_70009), record.build)
        assertEquals(3, record.build.allocations.single { it.nodeId == 104951 }.rank, "King of the Jungle 3/3 survives")
    }

    @Test
    fun cancellingThePreviewWritesNothing() {
        val flow = controller()
        assertIs<ImportPhase.Confirming>(flow.check(WARRIOR_13_OF_51))

        onMain { flow.dismissPreview() }

        assertEquals(ImportPhase.Editing, onMain { flow.phase })
        assertEquals(WARRIOR_13_OF_51, flow.text, "the input stays for another try")
        assertEquals(0, storage.writeCount)
        assertEquals(null, storage.payload)
    }

    @Test
    fun aFailedWriteKeepsThePreviewAndRetryCreatesOneRecord() {
        val flow = controller()
        assertIs<ImportPhase.Confirming>(flow.check(WARRIOR_13_OF_51))

        storage.failWrites = true
        var imported: String? = null
        onMain { flow.confirm { imported = it } }
        val failed = assertIs<ImportPhase.Confirming>(
            flow.awaitPhase { it is ImportPhase.Confirming && !it.saving && it.saveError != null },
        )
        assertIs<BuildLibraryError.StorageWrite>(failed.saveError)
        assertEquals(null, imported, "never report success before storage confirms it")
        assertEquals(null, storage.payload)

        storage.failWrites = false
        onMain { flow.confirm { imported = it } }
        val done = assertIs<ImportPhase.Imported>(flow.awaitPhase { it is ImportPhase.Imported })
        assertEquals(done.localId, imported)
        assertEquals(1, records().size)
    }

    @Test
    fun theInputIsFrozenWhileTheWriteRuns() {
        storage.writeDelayMillis = 600
        val flow = controller()
        assertIs<ImportPhase.Confirming>(flow.check(WARRIOR_13_OF_51))
        onMain {
            flow.confirm { }
            flow.onInputChange("TF1")
            flow.clear()
            flow.dismissPreview()
        }
        assertEquals(WARRIOR_13_OF_51, flow.text)
        assertIs<ImportPhase.Imported>(flow.awaitPhase { it is ImportPhase.Imported })
        assertEquals(1, records().size)
    }

    // ------------------------------------------------------------------ input rules

    @Test
    fun blankInputCannotContinue() {
        val flow = controller()
        assertFalse(flow.canContinue)
        onMain { flow.onInputChange(" \n\t ") }
        assertFalse(flow.canContinue)
        onMain { flow.submit() }
        assertEquals(ImportProblem.EmptyInput, assertIs<ImportPhase.Problem>(onMain { flow.phase }).problem)
        assertEquals(ImportMessage.EmptyInput, importMessageOf(ImportProblem.EmptyInput))
    }

    @Test
    fun textOverTheInputLimitIsRefusedNotTruncated() {
        val flow = controller()
        onMain { flow.onInputChange(MAGE_EMPTY) }
        onMain { flow.onInputChange("A".repeat(BuildShareCode.MAX_INPUT_LENGTH + 1)) }

        assertEquals(MAGE_EMPTY, flow.text, "the field keeps its previous text")
        assertEquals(ImportProblem.InputTooLong, assertIs<ImportPhase.Problem>(onMain { flow.phase }).problem)
        // exactly at the limit is accepted (the decoder then judges it)
        onMain { flow.onInputChange("A".repeat(BuildShareCode.MAX_INPUT_LENGTH)) }
        assertEquals(BuildShareCode.MAX_INPUT_LENGTH, flow.text.length)
    }

    @Test
    fun pasteReplacesTheFieldOrReportsTypedClipboardProblems() {
        val flow = controller()
        onMain { flow.onPasted(ClipboardRead.Text(WARRIOR_13_OF_51)) }
        assertEquals(WARRIOR_13_OF_51, flow.text)

        onMain { flow.onPasted(ClipboardRead.Empty) }
        assertEquals(ImportProblem.ClipboardEmpty, assertIs<ImportPhase.Problem>(onMain { flow.phase }).problem)
        onMain { flow.onPasted(ClipboardRead.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)) }
        assertEquals(
            ImportMessage.ClipboardUnavailable,
            importMessageOf(assertIs<ImportPhase.Problem>(onMain { flow.phase }).problem),
        )
        assertEquals(WARRIOR_13_OF_51, flow.text, "a failed paste never clears the field")
    }

    // ------------------------------------------------------------------ problem categories

    @Test
    fun everyRejectionMapsToItsPlayerFacingCategory() {
        val flow = controller()

        fun messageOf(code: String) = importMessageOf(ImportProblem.Rejected(flow.rejection(code)))

        assertEquals(ImportMessage.NotACode, messageOf("hello"))
        assertEquals(ImportMessage.UnsupportedVersion, messageOf("TF2" + MAGE_EMPTY.drop(3)))

        val corrupted = MAGE_EMPTY.substring(0, 20) + (if (MAGE_EMPTY[20] == 'A') 'B' else 'A') + MAGE_EMPTY.substring(21)
        val checksum = assertIs<BuildImportFailure.InvalidCode>(flow.rejection(corrupted))
        assertIs<ShareCodeDecodeError.ChecksumMismatch>(checksum.error)
        assertEquals(ImportMessage.Damaged, messageOf(corrupted))
        assertEquals(ImportMessage.Damaged, messageOf(MAGE_EMPTY.dropLast(6)))

        val unshipped = encode(decoded(MAGE_EMPTY).copy(clientBuild = "1.60.1.69977"))
        assertIs<BuildImportFailure.ClientBuildUnavailable>(flow.rejection(unshipped))
        assertEquals(ImportMessage.ClientBuildUnavailable("1.60.1.69977"), messageOf(unshipped))

        val noSuchClass = encode(decoded(MAGE_EMPTY).copy(classToken = ClassToken("DEATHKNIGHT")))
        assertIs<BuildImportFailure.ClassUnavailable>(flow.rejection(noSuchClass))
        assertEquals(ImportMessage.ClassUnavailable, messageOf(noSuchClass))

        // the 70009 Druid (King of the Jungle 3/3) is not a valid plan on 70245
        val wrongData = encode(decoded(DRUID_FULL_70009).copy(clientBuild = "1.60.1.70245"))
        val invalid = flow.rejection(wrongData)
        assertTrue(
            invalid is BuildImportFailure.InvalidAllocation || invalid is BuildImportFailure.TreeMismatch,
            "$invalid",
        )
        assertEquals(ImportMessage.InvalidAllocation, messageOf(wrongData))
    }

    @Test
    fun aBrokenDatasetIsADatasetProblemNotAnInvalidCode() {
        val broken = object : TalentDatasetRepository {
            override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> =
                bundled.manifest(bundle)

            override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> =
                DatasetOutcome.Failed(DatasetLoadError.InvalidJson(path = "x.json", detail = "injected"))
        }
        val flow = controller(broken)
        val failure = assertIs<BuildImportFailure.DatasetRejected>(flow.rejection(WARRIOR_13_OF_51))
        assertEquals(ImportMessage.DatasetProblem, importMessageOf(ImportProblem.Rejected(failure)))
        assertEquals(0, storage.writeCount)
    }

    private fun decoded(code: String): BuildFile = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build

    private fun encode(build: BuildFile): String = assertIs<ShareCodeEncoding.Encoded>(BuildShareCode.encode(build)).code

    companion object {
        /** docs/build-sharing.md §3: empty Mage, 1.60.1.70374. */
        const val MAGE_EMPTY = "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT08KA18X2XG200AYM31R0"

        /** docs/build-sharing.md §3: Warrior 13/51, 1.60.1.70374. */
        const val WARRIOR_13_OF_51 =
            "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT0ENT1A994JKTJVM40HMNV0TH96601088G40820411008206N3QGF90ABEE1AA"

        /** docs/build-sharing.md §3: full Druid, 1.60.1.70009 (King of the Jungle 3/3). */
        const val DRUID_FULL_70009 =
            "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1G60WGAH2JAN4M9G882VDV61P3H8C020RE0M0GA083041020R1080G208204102081080G2082041G20G2080G2081041G20HYGMB54"
    }
}
