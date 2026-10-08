package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * THE M5.1 acceptance test: persistence across a fresh app instance. "App"
 * instance A creates a draft, spends real ranks and autosaves; every piece of
 * in-memory app state is then discarded. A second repository/editor load over
 * the SAME serialized storage (a fresh process would read exactly this
 * payload) resolves the same local build id against the real bundled dataset
 * and rebuilds the exact saved BuildState. Remembering an object proves
 * nothing — only the serialized payload crosses.
 */
class BuildRestartPersistenceTest {

    private lateinit var storage: FakeBuildLibraryStorage

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
    }

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    private fun <T> loadedDocument(outcome: DatasetOutcome<T>): T =
        assertIs<DatasetOutcome.Loaded<T>>(outcome).value

    @Test
    fun editedDraftSurvivesAFreshAppInstanceExactly() {
        // ---- instance A: create, edit through the real engine, autosave
        val clockA = FixedClock(startMillis = 5_000L)
        val repositoryA = LocalBuildRepository(storage, clockA, SequentialIds())
        val datasetsA = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

        val created = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repositoryA, datasetsA, ClassToken("WARRIOR")) },
        ).record
        val document: TalentDataDocument = runBlocking {
            // the draft's own dataset (the product dataset since M5.DATA)
            loadedDocument(datasetsA.document(DatasetId(created.build.game, created.build.clientBuild, created.build.classToken)))
        }
        val engine = TalentAllocationEngine(document)
        var state = engine.emptyState()
        val rend = engine.tree.nodes.first { it.name == "Improved Rend" }.id
        val deflection = engine.tree.nodes.first { it.name == "Deflection" }.id
        repeat(3) { state = engine.spend(state, rend).state }
        repeat(2) { state = engine.spend(state, deflection).state }
        clockA.advanceBy(50)
        val saved: LocalBuildRecord = loaded(
            repositoryA.saveBuild(
                created.localId,
                buildFileOf(
                    state = state,
                    game = document.game,
                    clientBuild = document.clientBuild,
                    classToken = document.classInfo.token,
                ),
            ),
        )

        // the ONLY thing that crosses the "restart": the serialized payload
        val persistedPayload = storage.payload
        assertTrue(!persistedPayload.isNullOrEmpty())

        // ---- instance B: fresh repository + fresh dataset repository over the same storage
        val storageB = FakeBuildLibraryStorage(payload = persistedPayload)
        val repositoryB = LocalBuildRepository(storageB, FixedClock(startMillis = 90_000L), SequentialIds())
        val datasetsB = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

        val reloaded: List<LocalBuildRecord> = loaded(runBlocking { repositoryB.load() })
        assertEquals(1, reloaded.size)

        val session = runBlocking { loadEditorSession(repositoryB, datasetsB, created.localId) }
        val ready = assertIs<EditorLoad.Ready>(session)

        assertEquals(created.localId, ready.session.record.localId)
        assertEquals(state, ready.session.buildState, "the reopened build must equal the saved state exactly")
        assertEquals(saved.updatedAt, ready.session.record.updatedAt)
        assertEquals(5, ready.session.engine.spentPoints(ready.session.buildState))
        // a draft stays a draft across restarts
        assertEquals(null, ready.session.record.name)
    }
}
