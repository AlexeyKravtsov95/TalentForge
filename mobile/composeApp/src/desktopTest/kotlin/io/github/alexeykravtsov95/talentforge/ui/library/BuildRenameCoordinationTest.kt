package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryWriteException
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * M5.SHARE.2: the app's one rename path, [BuildAutosaveCoordinator.rename],
 * against the real repository. Every repository operation is an unlocked
 * read–modify–write of the whole library, so these pin the coordination:
 * a rename waits for the saves submitted before it, a save submitted while a
 * rename is being written waits for it (neither change is ever lost), a
 * rename is refused while the newest save of that build has failed, and a
 * rename is metadata only.
 */
class BuildRenameCoordinationTest {

    /** Storage whose next writes can be held at the door; counts completed writes. */
    private class GatedStorage : BuildLibraryStorage {
        @Volatile var payload: String? = null

        @Volatile var failWrites = false

        @Volatile var writes = 0

        @Volatile private var gate: CountDownLatch? = null

        @Volatile var entered = CountDownLatch(1)

        fun hold() {
            gate = CountDownLatch(1)
            entered = CountDownLatch(1)
        }

        fun release() {
            gate?.countDown()
            gate = null
        }

        override fun read(): String? = payload

        override fun write(payload: String) {
            entered.countDown()
            gate?.await(20, TimeUnit.SECONDS)
            if (failWrites) throw BuildLibraryWriteException("injected write failure")
            this.payload = payload
            writes += 1
        }
    }

    private val storage = GatedStorage()
    private val clock = FixedClock()
    private val repository = LocalBuildRepository(storage, clock, SequentialIds())

    private fun build(rank: Int?) = BuildFile(
        schemaVersion = 1,
        game = GameId("forever"),
        clientBuild = "1.60.1.70374",
        classToken = ClassToken("WARRIOR"),
        treeId = 1,
        allocations = if (rank == null) emptyList() else listOf(NodeAllocation(nodeId = 1, entryId = 1, rank = rank)),
    )

    private fun seed(): LocalBuildRecord =
        assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.createDraft(build(null))).value

    private fun stored(localId: String): LocalBuildRecord =
        assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository.load()).value.single { it.localId == localId }

    /** Runs [body] with a coordinator whose consumer lives in a child scope cancelled afterwards. */
    private fun withCoordinator(body: suspend CoroutineScope.(BuildAutosaveCoordinator) -> Unit) = runBlocking {
        val job = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + job)
        try {
            withTimeout(30_000) { scope.body(BuildAutosaveCoordinator(repository, scope)) }
        } finally {
            storage.release()
            job.cancel()
        }
    }

    @Test
    fun aRenameWaitsForThePendingSaveAndBothChangesArePersisted() {
        val record = seed()
        withCoordinator { autosave ->
            storage.hold()
            autosave.submit(record.localId, build(2))
            val rename = async { autosave.rename(record.localId, "  Arms  ") }
            while (storage.entered.count > 0) delay(10)
            delay(200)
            assertFalse(rename.isCompleted, "the rename must wait for the save submitted before it")
            storage.release()
            val outcome = assertIs<BuildRenameOutcome.Renamed>(rename.await())
            assertEquals("Arms", outcome.record.name, "the repository's trimming rule applies")
            assertEquals(build(2), outcome.record.build)
        }
        assertEquals("Arms", stored(record.localId).name)
        assertEquals(build(2), stored(record.localId).build)
    }

    @Test
    fun aSaveSubmittedWhileARenameIsWrittenNeverDropsTheName() {
        val record = seed()
        withCoordinator { autosave ->
            storage.hold()
            val rename = async { autosave.rename(record.localId, "Fury") }
            while (storage.entered.count > 0) delay(10) // the rename is inside its write
            autosave.submit(record.localId, build(3))
            val flush = async { autosave.flush() }
            delay(200)
            storage.release()
            assertIs<BuildRenameOutcome.Renamed>(rename.await())
            assertFalse(assertIs<BuildAutosaveCoordinator.SaveResult>(flush.await()).failed)
        }
        val stored = stored(record.localId)
        assertEquals("Fury", stored.name, "the later save re-read the renamed record")
        assertEquals(build(3), stored.build)
    }

    @Test
    fun aRenameIsRefusedWhileTheNewestSaveOfThatBuildFailed() {
        val record = seed()
        val writesBefore = storage.writes
        withCoordinator { autosave ->
            storage.failWrites = true
            autosave.submit(record.localId, build(1))
            assertTrue(assertIs<BuildAutosaveCoordinator.SaveResult>(autosave.flush()).failed)
            storage.failWrites = false

            assertEquals(BuildRenameOutcome.PendingSaveFailed, autosave.rename(record.localId, "Arms"))
            assertEquals(writesBefore, storage.writes, "a refused rename writes nothing")

            // the save is retried and succeeds: the rename goes through
            autosave.submit(record.localId, build(1))
            assertIs<BuildRenameOutcome.Renamed>(autosave.rename(record.localId, "Arms"))
        }
        assertEquals("Arms", stored(record.localId).name)
        assertEquals(build(1), stored(record.localId).build)
    }

    @Test
    fun anotherBuildsFailedSaveDoesNotBlockARename() {
        val failing = seed()
        val other = seed()
        withCoordinator { autosave ->
            storage.failWrites = true
            autosave.submit(failing.localId, build(1))
            autosave.flush()
            storage.failWrites = false
            assertIs<BuildRenameOutcome.Renamed>(autosave.rename(other.localId, "Other"))
        }
        assertEquals("Other", stored(other.localId).name)
    }

    @Test
    fun aRenameIsMetadataOnly() {
        val record = seed()
        repository.open(record.localId)
        val before = stored(record.localId)
        clock.advanceBy(60_000)
        withCoordinator { autosave -> assertIs<BuildRenameOutcome.Renamed>(autosave.rename(record.localId, "Arms")) }
        val after = stored(record.localId)
        assertEquals(
            before.copy(name = "Arms"),
            after,
            "only the name changes — updatedAt, build, createdAt and lastOpenedAt stay",
        )
    }

    @Test
    fun renameFailuresStayTyped() {
        val record = seed()
        withCoordinator { autosave ->
            val blank = assertIs<BuildRenameOutcome.Failed>(autosave.rename(record.localId, "   "))
            assertIs<BuildLibraryError.InvalidBuildName>(blank.error)
            val missing = assertIs<BuildRenameOutcome.Failed>(autosave.rename("local-404", "Arms"))
            assertIs<BuildLibraryError.UnknownRecord>(missing.error)
            storage.failWrites = true
            val write = assertIs<BuildRenameOutcome.Failed>(autosave.rename(record.localId, "Arms"))
            assertIs<BuildLibraryError.StorageWrite>(write.error)
        }
        assertNull(stored(record.localId).name)
    }
}
