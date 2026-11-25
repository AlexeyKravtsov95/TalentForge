package io.github.alexeykravtsov95.talentforge.ui.builds

import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.ota.A_BUILD
import io.github.alexeykravtsov95.talentforge.ui.ota.B_BUILD
import io.github.alexeykravtsov95.talentforge.ui.ota.CheckOutcome
import io.github.alexeykravtsov95.talentforge.ui.ota.OtaDevice
import io.github.alexeykravtsov95.talentforge.ui.ota.SessionDatasets
import io.github.alexeykravtsov95.talentforge.ui.ota.TestRelease
import io.github.alexeykravtsov95.talentforge.ui.ota.signedCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest

class BuildMigrationControllerTest {
    @Test
    fun previewReadsOnlyAndCommitAtomicallyAddsOneIndependentNamedRecord() = withDevice { device, _, controller ->
        val original = device.namedBuild()
        val writes = device.library.writeCount
        val bytes = device.library.payload
        val preview = assertIs<MigrationPreview.Ready>(controller.preview(original.localId))
        assertEquals("Warrior PvP — 70450", preview.proposedName("Warrior build"))
        assertEquals(bytes, device.library.payload)
        assertEquals(writes, device.library.writeCount)

        val copy = assertIs<MigrationWrite.Saved>(controller.confirm(preview, " Fury on new patch ")).copy
        assertEquals(writes + 1, device.library.writeCount)
        assertEquals("Fury on new patch", copy.name)
        assertEquals(B_BUILD, copy.build.clientBuild)
        assertEquals(original.build.allocations, copy.build.allocations)
        assertNotEquals(original.localId, copy.localId)
        assertFalse(copy.isFavorite)

        val reopenedRepository = LocalBuildRepository(device.library, FixedClock(), SequentialIds())
        assertEquals(setOf(original, copy), reopenedRepository.records().toSet())
    }

    @Test
    fun failedAtomicWriteLeavesLibraryIntactAndCanBeRetried() = withDevice { device, _, controller ->
        val original = device.namedBuild()
        val preview = assertIs<MigrationPreview.Ready>(controller.preview(original.localId))
        val before = device.library.payload
        device.library.failWrites = true
        val failure = assertIs<MigrationWrite.Failed>(controller.confirm(preview, "Copy"))
        assertIs<BuildLibraryError.StorageWrite>(assertIs<MigrationProblem.Library>(failure.problem).error)
        assertEquals(before, device.library.payload)
        assertEquals(listOf(original), device.repository.records())
        device.library.failWrites = false
        assertIs<MigrationWrite.Saved>(controller.confirm(preview, "Copy"))
        assertEquals(2, device.repository.records().size)
    }

    @Test
    fun editedSourceAndUnsuccessfulAutosaveBothPreventStaleCopy() = withDevice { device, autosave, controller ->
        val original = device.namedBuild()
        val preview = assertIs<MigrationPreview.Ready>(controller.preview(original.localId))
        val edited = original.build.copy(allocations = emptyList())
        val before = device.library.payload

        device.library.failWrites = true
        autosave.submit(original.localId, edited)
        assertEquals(MigrationWrite.Failed(MigrationProblem.PendingSaveFailed), controller.confirm(preview, "Copy"))
        assertEquals(before, device.library.payload)
        assertEquals(MigrationPreview.Failed(MigrationProblem.PendingSaveFailed), controller.preview(original.localId))

        device.library.failWrites = false
        autosave.submit(original.localId, edited)
        assertEquals(MigrationWrite.Failed(MigrationProblem.Stale), controller.confirm(preview, "Copy"))
        assertEquals(original.copy(build = edited), device.repository.records().single())

        val reviewed = assertIs<MigrationPreview.Ready>(controller.preview(original.localId))
        assertEquals(0, reviewed.plan.transferredPoints)
    }

    @Test
    fun previewWaitsForAlreadySubmittedAutosaveAndAnalyzesTheSavedRanks() = withDevice { device, autosave, controller ->
        val original = device.namedBuild()
        device.library.writeDelayMillis = 35
        val writes = device.library.writeCount
        val edited = original.build.copy(allocations = emptyList())
        autosave.submit(original.localId, edited)
        val preview = assertIs<MigrationPreview.Ready>(controller.preview(original.localId))
        assertEquals(edited, preview.record.build)
        assertEquals(writes + 1, device.library.writeCount)
        assertIs<MigrationWrite.Saved>(controller.confirm(preview, "Copy"))
        assertEquals(writes + 2, device.library.writeCount)
    }

    @Test
    fun interveningSourceMetadataChangeRequiresNewPreview() = withDevice { device, _, controller ->
        val original = device.namedBuild()
        val preview = assertIs<MigrationPreview.Ready>(controller.preview(original.localId))
        val renamed = assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(
            device.repository.renameBuild(original.localId, "Renamed"),
        ).value
        val before = device.library.payload
        assertEquals(MigrationWrite.Failed(MigrationProblem.Stale), controller.confirm(preview, "Copy"))
        assertEquals(before, device.library.payload)
        assertEquals(listOf(renamed), device.repository.records())
    }

    @Test
    fun aNewActiveOtaRevisionRequiresReviewEvenAtTheSameClientBuild() = withDevice { device, _, controller ->
        val original = device.namedBuild()
        val preview = assertIs<MigrationPreview.Ready>(controller.preview(original.localId))
        val revision = TestRelease.B.derive(releaseId = "$B_BUILD-r2")
        device.server.publish(revision)
        device.server.catalog = signedCatalog(1, revision.releaseId, listOf(revision.entry))
        assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(B_BUILD, device.runtime.snapshot.value.product.clientBuild)
        val before = device.library.payload
        assertEquals(MigrationWrite.Failed(MigrationProblem.Stale), controller.confirm(preview, "Copy"))
        assertEquals(before, device.library.payload)
        val reviewed = assertIs<MigrationPreview.Ready>(controller.preview(original.localId))
        assertIs<MigrationWrite.Saved>(controller.confirm(reviewed, "Reviewed"))
        assertEquals(original, device.repository.records().single { it.localId == original.localId })
    }

    private fun OtaDevice.namedBuild(): LocalBuildRecord {
        val original = saveSpentBuild(A_BUILD, "WARRIOR", 5)
        repository.renameBuild(original.localId, "Warrior PvP")
        return assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.setFavorite(original.localId, true)).value
    }

    private fun LocalBuildRepository.records() =
        assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(load()).value

    private fun withDevice(
        test: suspend (OtaDevice, BuildAutosaveCoordinator, BuildMigrationController) -> Unit,
    ) = runTest {
        OtaDevice(bundledBuilds = setOf(A_BUILD, B_BUILD), bundledProduct = B_BUILD).use { device ->
            val autosave = BuildAutosaveCoordinator(device.repository, backgroundScope)
            val controller = BuildMigrationController(
                device.repository, autosave, SessionDatasets.bundled(device.bundleSource), device.runtime.activation,
            )
            test(device, autosave, controller)
        }
    }
}
