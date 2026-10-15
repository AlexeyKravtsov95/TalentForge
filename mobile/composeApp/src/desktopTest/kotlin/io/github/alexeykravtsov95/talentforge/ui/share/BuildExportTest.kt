package io.github.alexeykravtsov95.talentforge.ui.share

import io.github.alexeykravtsov95.talentforge.allocation.toBuildState
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.share.BuildShareCode
import io.github.alexeykravtsov95.talentforge.share.ShareCodeDecoding
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.DRUID_FULL_70009
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.MAGE_EMPTY
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.WARRIOR_13_OF_51
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The pure export functions behind the editor's and All Builds' Share. */
class BuildExportTest {

    private val storage = FakeBuildLibraryStorage()
    private val clock = FixedClock()
    private val repository = LocalBuildRepository(storage, clock, SequentialIds())

    private fun decoded(code: String): BuildFile = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build

    private fun create(build: BuildFile): LocalBuildRecord =
        assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.createDraft(build)).value

    @Test
    fun emptyAndFullBuildsExportTheirCanonicalCodes() {
        assertEquals(BuildExport.Ready(MAGE_EMPTY), exportBuildFile(decoded(MAGE_EMPTY)))
        assertEquals(BuildExport.Ready(DRUID_FULL_70009), exportBuildFile(decoded(DRUID_FULL_70009)))
        // allocation order of the input does not matter: one build, one code
        val druid = decoded(DRUID_FULL_70009)
        assertEquals(
            BuildExport.Ready(DRUID_FULL_70009),
            exportBuildFile(druid.copy(allocations = druid.allocations.reversed())),
        )
    }

    @Test
    fun theEditorExportsTheGivenStateUnderTheRecordsIdentityOnly() {
        val warrior = decoded(WARRIOR_13_OF_51)
        // the identity record still holds an EMPTY build (a lagging autosave):
        // its allocations must not leak into the export
        val staleIdentity = warrior.copy(allocations = emptyList())

        assertEquals(
            BuildExport.Ready(WARRIOR_13_OF_51),
            exportEditorState(warrior.toBuildState(), identity = staleIdentity),
        )
        assertEquals(
            exportBuildFile(staleIdentity),
            exportEditorState(staleIdentity.toBuildState(), identity = warrior),
        )
    }

    @Test
    fun allBuildsExportsTheLatestPersistedBuildWithoutTouchingTheLibrary() {
        val druid = decoded(DRUID_FULL_70009)
        val target = create(druid.copy(allocations = emptyList()))
        clock.advanceBy(1_000)
        create(decoded(MAGE_EMPTY))
        // the target was edited (autosaved) after it was created
        clock.advanceBy(1_000)
        repository.saveBuild(target.localId, druid)

        val payloadBefore = storage.payload
        val writesBefore = storage.writeCount
        clock.advanceBy(60_000)

        assertEquals(BuildExport.Ready(DRUID_FULL_70009), exportPersistedBuild(repository, target.localId))
        assertEquals(payloadBefore, storage.payload, "no timestamp, name, order or payload change")
        assertEquals(writesBefore, storage.writeCount, "sharing never writes (no `open`)")
    }

    @Test
    fun aMissingRecordOrAnUnreadableLibraryIsTyped() {
        create(decoded(MAGE_EMPTY))
        assertEquals(
            BuildExport.Failed(BuildExportFailure.Library(BuildLibraryError.UnknownRecord("gone"))),
            exportPersistedBuild(repository, "gone"),
        )

        storage.failReads = true
        val failed = assertIs<BuildExport.Failed>(exportPersistedBuild(repository, "local-1"))
        assertIs<BuildLibraryError.StorageRead>(assertIs<BuildExportFailure.Library>(failed.failure).error)
    }
}
