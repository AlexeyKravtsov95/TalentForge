package io.github.alexeykravtsov95.talentforge.data.builds

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5.2a build management over the M5.1 library: rename / duplicate / delete —
 * the metadata contracts (a rename touches nothing but the name), the copy
 * policy (fresh id, full payload, independent record) and the fail-closed
 * guarantees (a failed operation never mutates the stored payload).
 */
class LocalBuildRepositoryManagementTest {

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

    private class Harness(
        val repo: LocalBuildRepository,
        val storage: InMemoryBuildLibraryStorage = InMemoryBuildLibraryStorage(),
        val clock: FixedBuildClock = FixedBuildClock(),
    )

    private fun repository(
        storage: InMemoryBuildLibraryStorage = InMemoryBuildLibraryStorage(),
        clock: FixedBuildClock = FixedBuildClock(),
    ): Harness = Harness(LocalBuildRepository(storage, clock, SequentialBuildIds()), storage, clock)

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    private fun failure(outcome: BuildLibraryOutcome<*>): BuildLibraryError =
        assertIs<BuildLibraryOutcome.Failed>(outcome).error

    private fun records(harness: Harness): List<LocalBuildRecord> = loaded(harness.repo.load())

    private fun namedDraft(harness: Harness, name: String): LocalBuildRecord {
        val created = loaded(harness.repo.createDraft(buildFile()))
        return loaded(harness.repo.renameBuild(created.localId, name))
    }

    // ------------------------------------------------------------------ rename

    @Test
    fun renamingADraftNamesTheRecord() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))

        val renamed = loaded(harness.repo.renameBuild(created.localId, "Raid build"))

        assertEquals("Raid build", renamed.name)
        assertEquals("Raid build", records(harness).single().name)
    }

    @Test
    fun renameTrimsSurroundingWhitespace() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))

        val renamed = loaded(harness.repo.renameBuild(created.localId, "  Raid build \t"))

        assertEquals("Raid build", renamed.name)
    }

    @Test
    fun aBlankNameIsATypedValidationFailureThatNeverTouchesStorage() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))
        val payloadBefore = harness.storage.payload
        val writesBefore = harness.storage.writeCount
        harness.storage.failReads = true // validation must not even need a read

        val error = failure(harness.repo.renameBuild(created.localId, "   "))

        val invalid = assertIs<BuildLibraryError.InvalidBuildName>(error)
        assertIs<BuildLibraryError.InvalidBuildName.Reason.Blank>(invalid.reason)
        assertEquals(LocalBuildRepository.MAX_BUILD_NAME_LENGTH, invalid.maxLength)
        assertEquals(payloadBefore, harness.storage.payload)
        assertEquals(writesBefore, harness.storage.writeCount)
    }

    @Test
    fun aNameOverTheLimitIsATypedValidationFailure() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))
        val tooLong = "x".repeat(LocalBuildRepository.MAX_BUILD_NAME_LENGTH + 1)

        val error = failure(harness.repo.renameBuild(created.localId, tooLong))

        val invalid = assertIs<BuildLibraryError.InvalidBuildName>(error)
        val tooLongReason = assertIs<BuildLibraryError.InvalidBuildName.Reason.TooLong>(invalid.reason)
        assertEquals(LocalBuildRepository.MAX_BUILD_NAME_LENGTH + 1, tooLongReason.length)
        assertNull(records(harness).single().name)
        assertEquals(1, harness.storage.writeCount) // only the createDraft write
    }

    @Test
    fun theInclusiveLengthEdgeStillRenames() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile()))
        val exactly = "x".repeat(LocalBuildRepository.MAX_BUILD_NAME_LENGTH)

        val renamed = loaded(harness.repo.renameBuild(created.localId, exactly))

        assertEquals(exactly, renamed.name)
    }

    @Test
    fun renamingToTheCurrentNameIsASuccessfulNoOpWithoutWrite() {
        val harness = repository()
        val named = namedDraft(harness, "Same")
        val payloadBefore = harness.storage.payload
        val writesBefore = harness.storage.writeCount

        val outcome = loaded(harness.repo.renameBuild(named.localId, " Same "))

        assertEquals(named, outcome, "trimming to the current name changes nothing")
        assertEquals(payloadBefore, harness.storage.payload)
        assertEquals(writesBefore, harness.storage.writeCount)
    }

    @Test
    fun renamePreservesIdentityPayloadAndEveryTimestamp() {
        val harness = repository()
        val created = loaded(harness.repo.createDraft(buildFile(ranks = arrayOf(Triple(101, 1001, 2)))))
        val opened = loaded(harness.repo.open(created.localId))
        harness.clock.advanceBy(123)

        val renamed = loaded(harness.repo.renameBuild(opened.localId, "Renamed"))

        assertEquals(opened.localId, renamed.localId)
        assertEquals(opened.build, renamed.build)
        assertEquals(opened.createdAt, renamed.createdAt)
        assertEquals(opened.updatedAt, renamed.updatedAt)
        assertEquals(opened.lastOpenedAt, renamed.lastOpenedAt)
        // the stored record agrees, not just the returned one
        val stored = records(harness).single()
        assertEquals("Renamed", stored.name)
        assertEquals(opened.updatedAt, stored.updatedAt)
    }

    @Test
    fun renameDoesNotTouchNeighbouringRecords() {
        val harness = repository()
        val first = loaded(harness.repo.createDraft(buildFile("WARRIOR")))
        harness.clock.advanceBy(10)
        val second = loaded(harness.repo.createDraft(buildFile("MAGE")))

        loaded(harness.repo.renameBuild(first.localId, "Renamed"))

        val byId = records(harness).associateBy { it.localId }
        assertEquals(second, byId.getValue(second.localId))
        assertEquals("Renamed", byId.getValue(first.localId).name)
    }

    @Test
    fun renameOfAnUnknownRecordFailsTyped() {
        val harness = repository()

        assertEquals(
            "missing",
            assertIs<BuildLibraryError.UnknownRecord>(failure(harness.repo.renameBuild("missing", "Name"))).localId,
        )
    }

    @Test
    fun renameReadAndWriteFailuresAreTypedAndLeaveTheLibraryIntact() {
        val readHarness = repository(storage = InMemoryBuildLibraryStorage(failReads = true))
        assertIs<BuildLibraryError.StorageRead>(failure(readHarness.repo.renameBuild("any", "Name")))

        val harness = repository()
        val named = namedDraft(harness, "Before")
        val payloadBefore = harness.storage.payload
        harness.storage.failWrites = true

        assertIs<BuildLibraryError.StorageWrite>(failure(harness.repo.renameBuild(named.localId, "After")))
        assertEquals(payloadBefore, harness.storage.payload)
        assertEquals("Before", records(harness).single().name)
    }

    // ------------------------------------------------------------------ duplicate

    @Test
    fun duplicateCopiesTheFullPayloadUnderAFreshId() {
        val harness = repository()
        val original = loaded(harness.repo.createDraft(buildFile(ranks = arrayOf(Triple(101, 1001, 2)))))
        loaded(harness.repo.renameBuild(original.localId, "Raid build"))
        harness.clock.advanceBy(99)

        val copy = loaded(harness.repo.duplicateBuild(original.localId))

        val storedCopy = records(harness).first { it.localId == copy.localId }
        assertEquals("Raid build (copy)", copy.name)
        assertEquals(original.build, copy.build)
        assertEquals(copy, storedCopy)
    }

    @Test
    fun duplicateOfADraftIsNamedDraftCopy() {
        val harness = repository()
        val draft = loaded(harness.repo.createDraft(buildFile()))

        val copy = loaded(harness.repo.duplicateBuild(draft.localId))

        assertEquals("Draft (copy)", copy.name)
    }

    @Test
    fun duplicateTakesFreshTimestampsAndHasNeverBeenOpened() {
        val harness = repository()
        val original = loaded(harness.repo.createDraft(buildFile()))
        loaded(harness.repo.open(original.localId))
        harness.clock.advanceBy(500)

        val copy = loaded(harness.repo.duplicateBuild(original.localId))

        assertEquals(1_500L, copy.createdAt)
        assertEquals(1_500L, copy.updatedAt)
        assertNull(copy.lastOpenedAt)
    }

    @Test
    fun theDuplicateLeadsTheListAsTheNewestEditedBuild() {
        val harness = repository()
        val first = loaded(harness.repo.createDraft(buildFile("WARRIOR")))
        harness.clock.advanceBy(10)
        val second = loaded(harness.repo.createDraft(buildFile("MAGE")))
        harness.clock.advanceBy(10)

        val copy = loaded(harness.repo.duplicateBuild(first.localId))

        assertEquals(
            listOf(copy.localId, second.localId, first.localId),
            records(harness).map { it.localId },
        )
    }

    @Test
    fun duplicateRegeneratesACollidingOrBlankIdAndKeepsTheOriginal() {
        val storage = InMemoryBuildLibraryStorage()
        val repo = LocalBuildRepository(storage, FixedBuildClock(), SequentialBuildIds())
        val original = loaded(repo.createDraft(buildFile())) // "id-1"
        val scripted = LocalBuildRepository(storage, FixedBuildClock(), ScriptedBuildIds("id-1", "", "fresh"))

        val copy = loaded(scripted.duplicateBuild(original.localId))

        assertEquals("fresh", copy.localId, "a colliding and a blank candidate are skipped, not normalized")
        assertEquals(setOf(original.localId, "fresh"), loaded(scripted.load()).map { it.localId }.toSet())
    }

    @Test
    fun duplicateFailsTypedAfterTheBoundedCollisionPolicyAndNeverWrites() {
        val harness = repository()
        val original = loaded(harness.repo.createDraft(buildFile()))
        val payloadBefore = harness.storage.payload
        val stuck = LocalBuildRepository(harness.storage, FixedBuildClock(), ScriptedBuildIds(original.localId))

        val error = failure(stuck.duplicateBuild(original.localId))

        val collision = assertIs<BuildLibraryError.IdCollision>(error)
        assertEquals(8, collision.attempts)
        assertEquals(payloadBefore, harness.storage.payload)
    }

    @Test
    fun duplicatesAreIndependentRecordsAfterFurtherEdits() {
        val harness = repository()
        val original = loaded(harness.repo.createDraft(buildFile("WARRIOR")))
        val copy = loaded(harness.repo.duplicateBuild(original.localId))

        // editing the original never reaches the copy…
        harness.clock.advanceBy(10)
        loaded(harness.repo.saveBuild(original.localId, buildFile("WARRIOR", ranks = arrayOf(Triple(9, 99, 3)))))
        assertEquals(copy, records(harness).associateBy { it.localId }.getValue(copy.localId))

        // …and editing the copy never reaches the original
        harness.clock.advanceBy(10)
        val originalBeforeCopyEdits = records(harness).associateBy { it.localId }.getValue(original.localId)
        loaded(harness.repo.renameBuild(copy.localId, "Renamed copy"))
        loaded(harness.repo.saveBuild(copy.localId, buildFile("MAGE")))
        assertEquals(originalBeforeCopyEdits, records(harness).associateBy { it.localId }.getValue(original.localId))
    }

    @Test
    fun duplicateRespectsTheNameLimitWhileKeepingTheSuffix() {
        val harness = repository()
        val maxName = "y".repeat(LocalBuildRepository.MAX_BUILD_NAME_LENGTH)
        val original = namedDraft(harness, maxName)

        val copy = loaded(harness.repo.duplicateBuild(original.localId))

        assertNotNull(copy.name)
        assertEquals(LocalBuildRepository.MAX_BUILD_NAME_LENGTH, copy.name.length)
        assertTrue(copy.name.endsWith(" (copy)"))
    }

    @Test
    fun duplicateOfADuplicateAppendsTheSuffixOncePerCopy() {
        val harness = repository()
        val original = namedDraft(harness, "Build")

        val firstCopy = loaded(harness.repo.duplicateBuild(original.localId))
        val secondCopy = loaded(harness.repo.duplicateBuild(firstCopy.localId))

        // the copy's name derives from the source's CURRENT name: a copy of a
        // copy accumulates one suffix per copy (documented policy, not a bug)
        assertEquals("Build (copy)", firstCopy.name)
        assertEquals("Build (copy) (copy)", secondCopy.name)
    }

    @Test
    fun repeatedDuplicationStaysWithinTheLimitWithTheSuffixAlwaysKept() {
        val harness = repository()
        var current = namedDraft(harness, "y".repeat(LocalBuildRepository.MAX_BUILD_NAME_LENGTH))

        repeat(5) {
            current = loaded(harness.repo.duplicateBuild(current.localId))
            assertNotNull(current.name)
            assertEquals(LocalBuildRepository.MAX_BUILD_NAME_LENGTH, current.name.length)
            assertTrue(current.name.endsWith(" (copy)"))
        }
    }

    @Test
    fun duplicateNamesAreAllowedBecauseIdentityIsTheLocalId() {
        val harness = repository()
        val original = loaded(harness.repo.createDraft(buildFile()))

        val firstCopy = loaded(harness.repo.duplicateBuild(original.localId))
        val secondCopy = loaded(harness.repo.duplicateBuild(original.localId))

        assertEquals(firstCopy.name, secondCopy.name)
        val ids = records(harness).map { it.localId }
        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun duplicateOfAnUnknownRecordFailsTyped() {
        val harness = repository()

        assertEquals(
            "missing",
            assertIs<BuildLibraryError.UnknownRecord>(failure(harness.repo.duplicateBuild("missing"))).localId,
        )
    }

    @Test
    fun duplicateReadAndWriteFailuresAreTypedAndLeaveTheLibraryIntact() {
        val readHarness = repository(storage = InMemoryBuildLibraryStorage(failReads = true))
        assertIs<BuildLibraryError.StorageRead>(failure(readHarness.repo.duplicateBuild("any")))

        val harness = repository()
        val original = loaded(harness.repo.createDraft(buildFile()))
        val payloadBefore = harness.storage.payload
        harness.storage.failWrites = true

        assertIs<BuildLibraryError.StorageWrite>(failure(harness.repo.duplicateBuild(original.localId)))
        assertEquals(payloadBefore, harness.storage.payload)
        assertEquals(listOf(original.localId), records(harness).map { it.localId })
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun deleteRemovesOnlyTheNamedRecord() {
        val harness = repository()
        val first = loaded(harness.repo.createDraft(buildFile("WARRIOR")))
        harness.clock.advanceBy(10)
        val second = loaded(harness.repo.createDraft(buildFile("MAGE")))

        loaded(harness.repo.deleteBuild(first.localId))

        assertEquals(listOf(second), records(harness))
    }

    @Test
    fun deletingTheLastRecordLeavesAnEmptyLibraryNotAFailure() {
        val harness = repository()
        val only = loaded(harness.repo.createDraft(buildFile()))

        loaded(harness.repo.deleteBuild(only.localId))

        assertTrue(records(harness).isEmpty())
        // a later management action on the deleted id fails typed instead of resurrecting
        assertIs<BuildLibraryError.UnknownRecord>(failure(harness.repo.duplicateBuild(only.localId)))
    }

    @Test
    fun deleteOfAnUnknownRecordFailsTyped() {
        val harness = repository()

        assertEquals(
            "missing",
            assertIs<BuildLibraryError.UnknownRecord>(failure(harness.repo.deleteBuild("missing"))).localId,
        )
    }

    @Test
    fun deleteReadAndWriteFailuresAreTypedAndNeverLookSuccessful() {
        val readHarness = repository(storage = InMemoryBuildLibraryStorage(failReads = true))
        assertIs<BuildLibraryError.StorageRead>(failure(readHarness.repo.deleteBuild("any")))

        val harness = repository()
        val only = loaded(harness.repo.createDraft(buildFile()))
        val payloadBefore = harness.storage.payload
        harness.storage.failWrites = true

        assertIs<BuildLibraryError.StorageWrite>(failure(harness.repo.deleteBuild(only.localId)))
        assertEquals(payloadBefore, harness.storage.payload)
        assertEquals(listOf(only.localId), records(harness).map { it.localId })
    }

    // ------------------------------------------------------------------ restart

    @Test
    fun aFreshRepositoryOverTheSameStorageObservesRenameDuplicateAndDelete() {
        val storage = InMemoryBuildLibraryStorage()
        val first = LocalBuildRepository(storage, FixedBuildClock(100L), SequentialBuildIds())
        val created = loaded(first.createDraft(buildFile()))
        loaded(first.renameBuild(created.localId, "Renamed"))
        val copy = loaded(first.duplicateBuild(created.localId))

        // "relaunch": a second instance over the SAME serialized storage
        val relaunched = LocalBuildRepository(storage, FixedBuildClock(900L), SequentialBuildIds())
        loaded(relaunched.deleteBuild(created.localId))

        val afterRestart = loaded(relaunched.load())
        assertEquals(listOf("Renamed (copy)"), afterRestart.map { it.name })
        assertEquals(listOf(copy.localId), afterRestart.map { it.localId })
    }
}
