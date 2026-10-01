package io.github.alexeykravtsov95.talentforge.data.builds

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5.1 local build library: the versioned private envelope and the repository
 * over it — creation, autosave, open/touch, deterministic ordering and the
 * fail-closed storage behaviour (a corrupt or unreadable library is a typed
 * failure and is never silently replaced).
 */
class LocalBuildRepositoryTest {

    private val game = GameId("forever")
    private val clientBuild = "1.60.1.70009"

    private fun buildFile(
        classToken: String = "WARRIOR",
        treeId: Int = 1117,
        vararg ranks: Triple<Int, Int, Int>,
    ) = BuildFile(
        game = game,
        clientBuild = clientBuild,
        classToken = ClassToken(classToken),
        treeId = treeId,
        allocations = ranks.map { (nodeId, entryId, rank) ->
            NodeAllocation(nodeId = nodeId, entryId = entryId, rank = rank)
        },
    )

    private fun record(
        localId: String,
        createdAt: Long,
        updatedAt: Long,
        build: BuildFile = buildFile(),
    ) = LocalBuildRecord(
        localId = localId,
        name = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastOpenedAt = null,
        build = build,
    )

    private class Harness(
        val repo: LocalBuildRepository,
        val storage: InMemoryBuildLibraryStorage = InMemoryBuildLibraryStorage(),
        val clock: FixedBuildClock = FixedBuildClock(),
    )

    private fun repository(
        storage: InMemoryBuildLibraryStorage = InMemoryBuildLibraryStorage(),
        clock: FixedBuildClock = FixedBuildClock(),
    ): Harness {
        val ids = SequentialBuildIds()
        // keep the generator visible in failures by seeding one record name-free
        return Harness(LocalBuildRepository(storage, clock, ids), storage, clock)
    }

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    private fun failure(outcome: BuildLibraryOutcome<*>): BuildLibraryError =
        assertIs<BuildLibraryOutcome.Failed>(outcome).error

    private fun loadedRecords(outcome: BuildLibraryOutcome<List<LocalBuildRecord>>) = loaded(outcome)

    private fun repoOver(payload: String?): List<LocalBuildRecord> =
        loadedRecords(
            LocalBuildRepository(InMemoryBuildLibraryStorage(payload), FixedBuildClock(), SequentialBuildIds()).load(),
        )

    private fun failureOver(payload: String?): BuildLibraryError =
        failure(
            LocalBuildRepository(InMemoryBuildLibraryStorage(payload), FixedBuildClock(), SequentialBuildIds()).load(),
        )

    // ------------------------------------------------------------------ empty / create

    @Test
    fun noPayloadIsAnEmptyLibraryNotAFailure() {
        val harness = repository()

        assertTrue(loadedRecords(harness.repo.load()).isEmpty())
    }

    @Test
    fun createDraftPersistsARealBuildFile() {
        val harness = repository()
        val file = buildFile(ranks = arrayOf(Triple(101, 1001, 2)))

        val created = loaded(harness.repo.createDraft(file))

        assertEquals(file, created.build)
        assertEquals(listOf(created), loadedRecords(harness.repo.load()))
    }

    @Test
    fun createdLocalIdIsTheGeneratorValueAndSurvivesReload() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))

        assertEquals("id-1", created.localId)
        assertEquals("id-1", loadedRecords(harness.repo.load()).single().localId)
    }

    @Test
    fun createdDraftNameIsNullAndRoundTripsAsDraft() {
        val harness = repository()
        harness.repo.createDraft(buildFile())

        assertNull(loadedRecords(harness.repo.load()).single().name)
    }

    @Test
    fun timestampsComeFromTheInjectedClock() {
        val harness = repository()
        harness.clock.advanceBy(41)

        val created = loaded(harness.repo.createDraft(buildFile()))

        assertEquals(1_041L, created.createdAt)
        assertEquals(1_041L, created.updatedAt)
        assertNull(created.lastOpenedAt)
    }

    // ------------------------------------------------------------------ multiple records / ordering

    @Test
    fun multipleRecordsRoundTrip() {
        val harness = repository()
        val first = loaded(harness.repo.createDraft(buildFile("WARRIOR")))
        val second = loaded(harness.repo.createDraft(buildFile("MAGE")))

        val records = loadedRecords(harness.repo.load())
        assertEquals(setOf(first.localId, second.localId), records.map { it.localId }.toSet())
    }

    @Test
    fun orderingIsMostRecentlyEditedFirstAndDeterministic() {
        val harness = repository()
        val a = loaded(harness.repo.createDraft(buildFile("WARRIOR")))
        harness.clock.advanceBy(10)
        val b = loaded(harness.repo.createDraft(buildFile("MAGE")))
        harness.clock.advanceBy(10)
        val c = loaded(harness.repo.createDraft(buildFile("ROGUE")))

        // edit A last: it must lead; C (edited later than B) follows; B is last
        harness.clock.advanceBy(10)
        loaded(harness.repo.saveBuild(a.localId, buildFile(ranks = arrayOf(Triple(1, 2, 1)))))

        assertEquals(
            listOf(a.localId, c.localId, b.localId),
            loadedRecords(harness.repo.load()).map { it.localId },
        )
    }

    @Test
    fun equalTimestampsStillOrderByLocalIdDeterministically() {
        val payload = LocalBuildLibraryCodec.encode(
            listOf(record("id-9", 5L, 5L), record("id-2", 5L, 5L), record("id-7", 5L, 5L)),
        )

        assertEquals(
            listOf("id-2", "id-7", "id-9"),
            repoOver(payload).map { it.localId },
        )
    }

    @Test
    fun encodingIsDeterministicRegardlessOfInMemoryOrder() {
        val a = record("id-1", 10L, 10L, buildFile("WARRIOR"))
        val b = record("id-2", 20L, 20L, buildFile("MAGE"))

        assertEquals(
            LocalBuildLibraryCodec.encode(listOf(a, b)),
            LocalBuildLibraryCodec.encode(listOf(b, a)),
        )
    }

    // ------------------------------------------------------------------ update / autosave / open

    @Test
    fun updateReplacesOnlyTheIntendedBuild() {
        val harness = repository()
        val warrior = loaded(harness.repo.createDraft(buildFile("WARRIOR")))
        val mage = loaded(harness.repo.createDraft(buildFile("MAGE")))
        val edited = buildFile(ranks = arrayOf(Triple(900, 9001, 3)))

        loaded(harness.repo.saveBuild(warrior.localId, edited))

        val records = loadedRecords(harness.repo.load()).associateBy { it.localId }
        assertEquals(edited, records.getValue(warrior.localId).build)
        assertEquals(mage.build, records.getValue(mage.localId).build)
    }

    @Test
    fun autosavePreservesLocalIdCreatedAtNameAndLastOpenedAt() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))
        val opened = loaded(harness.repo.open(created.localId))
        harness.clock.advanceBy(77)

        val saved = loaded(harness.repo.saveBuild(created.localId, buildFile(treeId = 1117)))

        assertEquals(created.localId, saved.localId)
        assertEquals(created.createdAt, saved.createdAt)
        assertEquals(created.name, saved.name)
        assertEquals(opened.lastOpenedAt, saved.lastOpenedAt)
    }

    @Test
    fun autosaveAdvancesUpdatedAt() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))
        harness.clock.advanceBy(33)

        assertEquals(created.updatedAt + 33, loaded(harness.repo.saveBuild(created.localId, buildFile())).updatedAt)
    }

    @Test
    fun openAdvancesOnlyLastOpenedAt() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))
        harness.clock.advanceBy(55)

        val opened = loaded(harness.repo.open(created.localId))

        assertEquals(created.updatedAt + 55, opened.lastOpenedAt)
        assertEquals(created.updatedAt, opened.updatedAt)
        assertEquals(created.build, opened.build)
    }

    // ------------------------------------------------------------------ fail-closed storage

    @Test
    fun malformedJsonIsATypedFailureAndIsNeverOverwritten() {
        val storage = InMemoryBuildLibraryStorage(payload = "{ not json")
        val harness = repository(storage = storage)

        assertIs<BuildLibraryError.Malformed>(failure(harness.repo.load()))

        // an operation over corrupt storage must fail, not "repair" by writing
        assertIs<BuildLibraryError.Malformed>(failure(harness.repo.createDraft(buildFile())))
        assertEquals("{ not json", storage.payload)
        assertEquals(0, storage.writeCount)
    }

    @Test
    fun unsupportedStorageVersionIsATypedFailure() {
        val error = failureOver("""{"storageVersion":2,"builds":[]}""")

        val versionError = assertIs<BuildLibraryError.UnsupportedStorageVersion>(error)
        assertEquals(2, versionError.actual)
        assertEquals(1, versionError.supported)
    }

    @Test
    fun storageReadFailureIsATypedFailure() {
        val harness = repository(storage = InMemoryBuildLibraryStorage(failReads = true))

        assertIs<BuildLibraryError.StorageRead>(failure(harness.repo.load()))
    }

    @Test
    fun storageWriteFailureIsATypedFailure() {
        val harness = repository(storage = InMemoryBuildLibraryStorage(failWrites = true))

        assertIs<BuildLibraryError.StorageWrite>(failure(harness.repo.createDraft(buildFile())))
    }

    @Test
    fun failedWriteDoesNotMutateTheLastPersistedPayload() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))
        val payloadBefore = harness.storage.payload
        harness.storage.failWrites = true
        harness.clock.advanceBy(11)

        assertIs<BuildLibraryError.StorageWrite>(failure(harness.repo.saveBuild(created.localId, buildFile())))
        assertEquals(payloadBefore, harness.storage.payload)
    }

    @Test
    fun unknownRecordIsATypedFailure() {
        val harness = repository()

        assertEquals("missing", assertIs<BuildLibraryError.UnknownRecord>(failure(harness.repo.saveBuild("missing", buildFile()))).localId)
    }

    // ------------------------------------------------------------------ deliberate envelope policy

    @Test
    fun unknownEnvelopeFieldsAreRejectedWithinAStorageVersion() {
        // Documented policy: drift within storageVersion 1 is a bug and fails;
        // schema evolution requires a storageVersion bump (plus migration later).
        val payload = LocalBuildLibraryCodec.encode(listOf(record("id-1", 1L, 1L)))
            .replace("\"storageVersion\":1", "\"storageVersion\":1,\"futureField\":7")

        assertIs<BuildLibraryError.Malformed>(failureOver(payload))
    }

    @Test
    fun quotedNumericLiteralsAreRejectedLikePublicContracts() {
        val payload = LocalBuildLibraryCodec.encode(listOf(record("id-1", 1000L, 1000L)))
            .replace("\"createdAt\":1000", "\"createdAt\":\"1000\"")

        assertIs<BuildLibraryError.Malformed>(failureOver(payload))
    }

    @Test
    fun duplicateLocalIdsAreMalformed() {
        // the writer refuses duplicate ids (see the write-boundary test below),
        // so a duplicate payload can only arrive as hand-crafted/corrupted
        // storage — exactly the case the reader must still reject
        val one = record("id-1", 1L, 1L)
        val single = LocalBuildLibraryCodec.encode(listOf(one))
        val recordJson = single.substringAfter("[").substringBeforeLast("]")
        val duplicated = single.replace("[$recordJson]", "[$recordJson,$recordJson]")

        assertIs<BuildLibraryError.Malformed>(failureOver(duplicated))
    }

    @Test
    fun persistedBlankNamesAreNormalizedToDraft() {
        val payload = LocalBuildLibraryCodec.encode(listOf(record("id-1", 1L, 1L)))
            .replace("\"name\":null", "\"name\":\"  \"")

        assertNull(repoOver(payload).single().name)
    }

    @Test
    fun embeddedBuildFileWithWrongSchemaVersionIsMalformed() {
        val payload = LocalBuildLibraryCodec.encode(listOf(record("id-1", 1L, 1L)))
            .replace("\"schemaVersion\":1,\"game\"", "\"schemaVersion\":9,\"game\"")

        assertIs<BuildLibraryError.Malformed>(failureOver(payload))
    }

    @Test
    fun aValidLibraryReloadsAcrossFreshRepositoryInstances() {
        val storage = InMemoryBuildLibraryStorage()
        val first = LocalBuildRepository(storage, FixedBuildClock(100L), SequentialBuildIds())
        val created = loaded(first.createDraft(buildFile()))

        // a second instance over the SAME serialized storage observes the build
        val second = LocalBuildRepository(storage, FixedBuildClock(200L), SequentialBuildIds())

        val reloaded = loadedRecords(second.load()).single()
        assertEquals(created.localId, reloaded.localId)
        assertEquals(created.build, reloaded.build)
        assertNotEquals(0, storage.writeCount)
    }

    // ------------------------------------------------------------------ id-collision policy

    @Test
    fun createDraftRegeneratesACollidingIdAndKeepsTheOriginalRecord() {
        val existing = record("same", 1L, 1L)
        val storage = InMemoryBuildLibraryStorage(LocalBuildLibraryCodec.encode(listOf(existing)))
        val repo = LocalBuildRepository(storage, FixedBuildClock(), ScriptedBuildIds("same", "fresh"))

        val created = loaded(repo.createDraft(buildFile("MAGE")))

        assertEquals("fresh", created.localId)
        val records = loadedRecords(repo.load())
        assertEquals(setOf("same", "fresh"), records.map { it.localId }.toSet())
        assertEquals(existing, records.first { it.localId == "same" })
    }

    @Test
    fun createDraftFailsTypedAfterTheBoundedCollisionPolicyAndNeverWrites() {
        val existing = record("same", 1L, 1L)
        val storage = InMemoryBuildLibraryStorage(LocalBuildLibraryCodec.encode(listOf(existing)))
        val payloadBefore = storage.payload
        val repo = LocalBuildRepository(storage, FixedBuildClock(), ScriptedBuildIds("same"))

        val error = failure(repo.createDraft(buildFile()))

        val collision = assertIs<BuildLibraryError.IdCollision>(error)
        assertEquals(8, collision.attempts)
        assertEquals(payloadBefore, storage.payload)
        assertEquals(0, storage.writeCount)
    }

    @Test
    fun theLibraryReloadsAfterEverySuccessfulCreateEvenWithOccasionalCollisions() {
        val storage = InMemoryBuildLibraryStorage()
        val repo = LocalBuildRepository(storage, FixedBuildClock(), ScriptedBuildIds("a", "b", "b", "c"))

        val first = loaded(repo.createDraft(buildFile("WARRIOR")))
        assertEquals(listOf(first), loadedRecords(repo.load()))
        val second = loaded(repo.createDraft(buildFile("MAGE")))
        assertEquals(2, loadedRecords(repo.load()).size)
        val third = loaded(repo.createDraft(buildFile("ROGUE")))

        val ids = loadedRecords(repo.load()).map { it.localId }
        assertEquals(listOf(first.localId, second.localId, third.localId).toSet(), ids.toSet())
        assertEquals(3, ids.size)
    }

    @Test
    fun theWriteBoundaryRefusesDuplicateIdsTheReaderWouldReject() {
        val one = record("id-1", 1L, 1L)

        val error = assertFailsWith<LocalBuildLibraryFormatException.Malformed> {
            LocalBuildLibraryCodec.encode(listOf(one, one.copy(createdAt = 2L, updatedAt = 2L)))
        }

        assertTrue(error.message!!.contains("duplicate localId id-1"))
    }

    @Test
    fun randomIdsKeepTheirFullRandomPart() {
        val clock = FixedBuildClock(1_000L)
        val generated = List(200) { RandomLocalBuildIdGenerator(clock).next() }

        // time-ordered prefix from the injected clock (1000 ms = "rs" base 36),
        // then the untouched random part — no entropy-discarding transform
        assertTrue(generated.all { it.startsWith("rs-") })
        val suffixes = generated.map { it.substringAfter('-') }
        assertTrue(suffixes.all { it.isNotEmpty() })
        assertEquals(200, suffixes.toSet().size)
    }
}
