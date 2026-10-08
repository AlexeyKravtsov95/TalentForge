package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.allocation.toBuildState
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentBuildEditingState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * M5.1 build opening against the REAL bundled Forever documents: the generic
 * editor path opens real drafts of several classes, all nine manifest tokens
 * resolve, and every mismatch (wrong tree, unavailable client build, invalid
 * allocations) fails closed with typed failures — never a migration, clamp,
 * rank deletion or silent baseline fallback.
 */
class BuildOpeningTest {

    private val game = GameId("forever")
    private val clientBuild = "1.60.1.70009"

    private lateinit var datasets: BundledTalentDatasetRepository
    private lateinit var storage: FakeBuildLibraryStorage
    private lateinit var repository: LocalBuildRepository
    private lateinit var clock: FixedClock

    @BeforeTest
    fun setUp() {
        datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
        storage = FakeBuildLibraryStorage()
        clock = FixedClock()
        repository = LocalBuildRepository(storage, clock, SequentialIds())
    }

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    private fun <T> loadedDocument(outcome: DatasetOutcome<T>): T =
        assertIs<DatasetOutcome.Loaded<T>>(outcome).value

    private fun warriorDocument(): TalentDataDocument = runBlocking {
        loadedDocument(datasets.document(DatasetId(game, clientBuild, ClassToken("WARRIOR"))))
    }

    private fun warriorEngine(): TalentAllocationEngine = TalentAllocationEngine(warriorDocument())

    /** A real, engine-spent Warrior build (never hand-written ranks). */
    private fun spentWarriorState(engine: TalentAllocationEngine, ranks: Map<String, Int>): BuildState {
        var state = engine.emptyState()
        for ((name, count) in ranks) {
            val nodeId = engine.tree.nodes.first { it.name == name }.id
            repeat(count) {
                val result = engine.spend(state, nodeId)
                assertTrue(result.allowed, "preset spend on $name must succeed")
                state = result.state
            }
        }
        return state
    }

    private fun draftOf(token: String): String {
        val created = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken(token)) },
        )
        return created.record.localId
    }

    private fun warriorFileOf(state: BuildState): BuildFile = buildFileOf(
        state = state,
        game = game,
        clientBuild = clientBuild,
        classToken = ClassToken("WARRIOR"),
    )

    // ------------------------------------------------------------------ opening

    @Test
    fun realEmptyWarriorDraftOpens() {
        val localId = draftOf("WARRIOR")

        val session = runBlocking { loadEditorSession(repository, datasets, localId) }

        val ready = assertIs<EditorLoad.Ready>(session)
        assertEquals("Warrior", ready.session.classDisplayName)
        assertTrue(ready.session.buildState.allocations.isEmpty())
        assertEquals(ready.session.engine.emptyState(), ready.session.buildState)
    }

    @Test
    fun realEditedWarriorBuildOpensWithExactRanks() {
        val engine = warriorEngine()
        val state = spentWarriorState(engine, mapOf("Improved Rend" to 3, "Deflection" to 2))
        val record = loaded(repository.createDraft(warriorFileOf(state)))

        val session = runBlocking { loadEditorSession(repository, datasets, record.localId) }

        val ready = assertIs<EditorLoad.Ready>(session)
        assertEquals(state, ready.session.buildState)
        assertEquals(5, ready.session.engine.spentPoints(ready.session.buildState))
    }

    @Test
    fun anotherRealBundledClassOpensThroughTheSameGenericPath() {
        val localId = draftOf("MAGE")

        val session = runBlocking { loadEditorSession(repository, datasets, localId) }

        val ready = assertIs<EditorLoad.Ready>(session)
        assertEquals("Mage", ready.session.classDisplayName)
        assertEquals(1112, ready.session.buildState.treeId)
    }

    @Test
    fun allNineManifestClassTokensResolveTheirRealDocumentsAndOpen() {
        // drafts are created on the product dataset (M5.DATA)
        val manifest = runBlocking { loadedDocument(datasets.manifest(PRODUCT_BUNDLE)) }
        assertEquals(9, manifest.classes.size)

        for (entry in manifest.classes) {
            val document = runBlocking {
                loadedDocument(datasets.document(DatasetId(game, PRODUCT_BUNDLE.clientBuild, entry.token)))
            }
            assertEquals(entry.token, document.classInfo.token)
            assertTrue(document.classInfo.name.isNotBlank())

            val localId = draftOf(entry.token.value)
            val session = runBlocking { loadEditorSession(repository, datasets, localId) }
            assertIs<EditorLoad.Ready>(session, "class ${entry.token} must open through the generic path")
        }
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    fun wrongTreeIdFailsClosedWithoutRepair() {
        val localId = draftOf("WARRIOR")
        val record = loaded(repository.open(localId))
        loaded(repository.saveBuild(localId, record.build.copy(treeId = 999_999)))
        val payloadBefore = storage.payload

        val session = runBlocking { loadEditorSession(repository, datasets, localId) }

        val failure = assertIs<EditorLoad.Failed>(session).failure
        val tree = assertIs<EditorFailure.TreeUnavailable>(failure)
        assertEquals(999_999, tree.buildTreeId)
        // no migration: the stored payload still holds the wrong tree
        assertEquals(payloadBefore, storage.payload)
    }

    @Test
    fun unavailableClientBuildFailsClosedWithoutBaselineFallback() {
        val localId = draftOf("WARRIOR")
        val record = loaded(repository.open(localId))
        loaded(repository.saveBuild(localId, record.build.copy(clientBuild = "9.9.9.99999")))

        val session = runBlocking { loadEditorSession(repository, datasets, localId) }

        assertIs<EditorFailure.Dataset>(assertIs<EditorLoad.Failed>(session).failure)
    }

    @Test
    fun invalidSavedAllocationsFailEngineValidationUnchanged() {
        val engine = warriorEngine()
        val valid = spentWarriorState(engine, mapOf("Improved Rend" to 3))
        // hand-corrupt to an invalid state (rank far out of bounds): this is
        // the fail-closed import path under test, not a build construction
        val broken = valid.withAllocation(
            nodeId = valid.allocations.first().nodeId,
            entryId = valid.allocations.first().entryId,
            rank = 99,
        )
        val record = loaded(repository.createDraft(warriorFileOf(broken)))

        val session = runBlocking { loadEditorSession(repository, datasets, record.localId) }

        val invalid = assertIs<EditorFailure.InvalidBuild>(assertIs<EditorLoad.Failed>(session).failure)
        assertTrue(invalid.reasonCodes.isNotEmpty())
        // no clamping / rank deletion: the stored build still has rank 99
        val stored: LocalBuildRecord = loaded(repository.load()).single()
        assertEquals(99, stored.build.allocations.single().rank)
    }

    @Test
    fun unknownRecordFailsClosed() {
        val session = runBlocking { loadEditorSession(repository, datasets, "no-such-build") }

        assertIs<EditorFailure.Record>(assertIs<EditorLoad.Failed>(session).failure)
    }

    // ------------------------------------------------------------------ session semantics

    @Test
    fun reopenedBuildStartsWithEmptyUndoRedoHistory() {
        val state = spentWarriorState(warriorEngine(), mapOf("Improved Rend" to 2))
        val record = loaded(repository.createDraft(warriorFileOf(state)))

        val session = runBlocking { loadEditorSession(repository, datasets, record.localId) }
        val ready = assertIs<EditorLoad.Ready>(session)

        val editing = TalentBuildEditingState(ready.session.buildState)

        assertEquals(false, editing.canUndo)
        assertEquals(false, editing.canRedo)
    }

    @Test
    fun editingThenAutosavingUpdatesTheSameRecord() {
        val localId = draftOf("WARRIOR")
        val opened = loaded(repository.open(localId))
        clock.advanceBy(10)
        val engine = warriorEngine()
        val edited = engine.spend(
            engine.emptyState(),
            engine.tree.nodes.first { it.name == "Improved Rend" }.id,
        ).state

        val saved = loaded(repository.saveBuild(localId, warriorFileOf(edited)))

        val records = loaded(repository.load())
        assertEquals(1, records.size, "autosave must not duplicate the record")
        assertEquals(localId, saved.localId)
        assertEquals(opened.createdAt, saved.createdAt)
        assertEquals(edited, records.single().build.toBuildState())
    }
}
