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
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * M6.LIB.2: the app's one favorite path, [BuildAutosaveCoordinator.setFavorite],
 * against the real repository. Every repository operation is an unlocked
 * read–modify–write of the whole library, so these pin the coordination:
 * a favorite waits for the saves submitted before it, a save submitted while a
 * favorite is being written waits for it (neither change is ever lost), a
 * favorite is refused while the newest save of that build has failed, and a
 * favorite is metadata only.
 */
class BuildFavoriteCoordinationTest {

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
    fun aFavoriteWaitsForThePendingSaveAndBothChangesArePersisted() {
        val record = seed()
        withCoordinator { autosave ->
            storage.hold()
            autosave.submit(record.localId, build(2))
            val favorite = async { autosave.setFavorite(record.localId, true) }
            while (storage.entered.count > 0) delay(10)
            delay(200)
            assertFalse(favorite.isCompleted, "the favorite must wait for the save submitted before it")
            storage.release()
            val outcome = assertIs<BuildFavoriteOutcome.Saved>(favorite.await())
            assertEquals(true, outcome.record.isFavorite, "the repository's metadata-only rule applies")
            assertEquals(build(2), outcome.record.build)
        }
        assertEquals(true, stored(record.localId).isFavorite)
        assertEquals(build(2), stored(record.localId).build)
    }

    @Test
    fun aSaveSubmittedWhileAFavoriteIsWrittenNeverDropsTheFavorite() {
        val record = seed()
        withCoordinator { autosave ->
            storage.hold()
            val favorite = async { autosave.setFavorite(record.localId, true) }
            while (storage.entered.count > 0) delay(10) // the favorite is inside its write
            autosave.submit(record.localId, build(3))
            val flush = async { autosave.flush() }
            delay(200)
            storage.release()
            assertIs<BuildFavoriteOutcome.Saved>(favorite.await())
            assertFalse(assertIs<BuildAutosaveCoordinator.SaveResult>(flush.await()).failed)
        }
        val stored = stored(record.localId)
        assertEquals(true, stored.isFavorite, "the later save re-read the favorited record")
        assertEquals(build(3), stored.build)
    }

    @Test
    fun aFavoriteIsRefusedWhileTheNewestSaveOfThatBuildFailed() {
        val record = seed()
        val writesBefore = storage.writes
        withCoordinator { autosave ->
            storage.failWrites = true
            autosave.submit(record.localId, build(1))
            assertTrue(assertIs<BuildAutosaveCoordinator.SaveResult>(autosave.flush()).failed)
            storage.failWrites = false

            assertEquals(BuildFavoriteOutcome.PendingSaveFailed, autosave.setFavorite(record.localId, true))
            assertEquals(writesBefore, storage.writes, "a refused favorite writes nothing")

            // the save is retried and succeeds: the favorite goes through
            autosave.submit(record.localId, build(1))
            assertIs<BuildFavoriteOutcome.Saved>(autosave.setFavorite(record.localId, true))
        }
        assertEquals(true, stored(record.localId).isFavorite)
        assertEquals(build(1), stored(record.localId).build)
    }

    @Test
    fun anotherBuildsFailedSaveDoesNotBlockAFavorite() {
        val failing = seed()
        val other = seed()
        withCoordinator { autosave ->
            storage.failWrites = true
            autosave.submit(failing.localId, build(1))
            autosave.flush()
            storage.failWrites = false
            assertIs<BuildFavoriteOutcome.Saved>(autosave.setFavorite(other.localId, true))
        }
        assertEquals(true, stored(other.localId).isFavorite)
    }

    @Test
    fun aFavoriteIsMetadataOnly() {
        val record = seed()
        repository.open(record.localId)
        val before = stored(record.localId)
        clock.advanceBy(60_000)
        withCoordinator { autosave -> assertIs<BuildFavoriteOutcome.Saved>(autosave.setFavorite(record.localId, true)) }
        val after = stored(record.localId)
        assertEquals(
            before.copy(isFavorite = true),
            after,
            "only the favorite state changes — updatedAt, build, createdAt and lastOpenedAt stay",
        )
    }

    @Test
    fun favoriteFailuresStayTyped() {
        val record = seed()
        withCoordinator { autosave ->
            val missing = assertIs<BuildFavoriteOutcome.Failed>(autosave.setFavorite("local-404", true))
            assertIs<BuildLibraryError.UnknownRecord>(missing.error)
            storage.failWrites = true
            val write = assertIs<BuildFavoriteOutcome.Failed>(autosave.setFavorite(record.localId, true))
            assertIs<BuildLibraryError.StorageWrite>(write.error)
        }
        assertFalse(stored(record.localId).isFavorite)
    }
}
