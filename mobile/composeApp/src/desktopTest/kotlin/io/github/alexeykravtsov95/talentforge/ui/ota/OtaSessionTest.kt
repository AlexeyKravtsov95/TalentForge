package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogRelease
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import io.github.alexeykravtsov95.talentforge.ui.library.loadNewBuildChoices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Sessions are pinned (client design §4.5, §5): an open editor keeps its snapshot through an
 * activation, saved builds open only on their own client build, historical builds are
 * downloaded on demand (never activated, never substituted), and leased releases survive GC.
 */
class OtaSessionTest {

    private fun catalog(name: String) = OtaTestRepo.fixture("catalogs/$name.json")

    private fun bundle(build: String) = DatasetBundleId(GameId("forever"), build)

    /** Session collaborators over a snapshot, recording every asset path they read. */
    private class RecordedSession(snapshot: io.github.alexeykravtsov95.talentforge.data.ota.DatasetSnapshot) {
        val recorder = RecordingAssetSource(RoutingDatasetAssetSource(snapshot))
        val session = SessionDatasets(snapshot.product, recorder, snapshot)
    }

    private suspend fun open(device: OtaDevice, session: SessionDatasets, localId: String) =
        loadEditorSession(device.repository, session.datasets, localId, session.icons, session.descriptions, session.localization)

    @Test
    fun s2_anEditorOpenDuringActivationKeepsReadingItsOwnRelease() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        val sa = RecordedSession(device.runtime.snapshot.value)
        val draft = runBlocking { createClassDraft(device.repository, sa.session.datasets, ClassToken("WARRIOR"), sa.session.product) }
        val record = assertIs<DraftCreation.Created>(draft).record
        assertEquals(A_BUILD, record.build.clientBuild)
        val editor = assertIs<EditorLoad.Ready>(runBlocking { open(device, sa.session, record.localId) }).session

        // B is activated while the editor is open
        device.server.catalog = catalog("catalog-b-active")
        assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(B_BUILD, device.runtime.snapshot.value.product.clientBuild)

        // the open session keeps loading icons and descriptions — from A only
        sa.recorder.reads.clear()
        val tree = editor.model
        runBlocking {
            val document = (sa.session.datasets.document(io.github.alexeykravtsov95.talentforge.data.DatasetId(GameId("forever"), A_BUILD, ClassToken("WARRIOR"))) as io.github.alexeykravtsov95.talentforge.data.DatasetOutcome.Loaded).value
            sa.session.icons.load(document, document.trees.single())
            sa.session.descriptions.load(document, document.trees.single())
        }
        assertTrue(sa.recorder.reads.isNotEmpty())
        assertTrue(sa.recorder.reads.all { "/$A_BUILD/" in it }, "every asset of the open editor comes from A: ${sa.recorder.reads.filterNot { "/$A_BUILD/" in it }}")
        assertNotNull(tree)
        // saving keeps the record on A, unchanged in identity
        val saved = device.repository.saveBuild(record.localId, buildFileOf(editor.buildState, record.build.game, record.build.clientBuild, record.build.classToken))
        assertEquals(A_BUILD, assertIs<BuildLibraryOutcome.Loaded<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord>>(saved).value.build.clientBuild)
        // a New Build screen opened afterwards is on B
        val sb = SessionDatasets.of(device.runtime.snapshot.value)
        assertEquals(bundle(B_BUILD), sb.product)
        assertNotNull(runBlocking { loadNewBuildChoices(sb.datasets, sb.product) })
        val newDraft = runBlocking { createClassDraft(device.repository, sb.datasets, ClassToken("MAGE"), sb.product) }
        assertEquals(B_BUILD, assertIs<DraftCreation.Created>(newDraft).record.build.clientBuild)
    }

    @Test
    fun s3_aSavedABuildOpensOnAAfterBIsActive() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        val record = device.saveBuild(A_BUILD, "PRIEST")
        device.server.catalog = catalog("catalog-b-active")
        assertIs<CheckOutcome.Activated>(device.check())
        val sb = RecordedSession(device.runtime.snapshot.value)
        val opened = assertIs<EditorLoad.Ready>(runBlocking { open(device, sb.session, record.localId) }).session
        assertEquals(record.build, opened.record.build, "allocations and identity unchanged")
        assertTrue(sb.recorder.reads.all { "/$A_BUILD/" in it }, "no B file read: ${sb.recorder.reads.filterNot { "/$A_BUILD/" in it }}")
    }

    @Test
    fun s3b_aHistoricalBuildIsUnavailableOfflineAndDownloadedOnDemandOnline() = OtaDevice(bundledBuilds = setOf(B_BUILD)).test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active") // lists A and B; B is the bundled product
        assertIs<CheckOutcome.Activated>(device.check())
        val record = device.saveBuild(A_BUILD, "ROGUE")
        val library = device.library.payload
        val resolver = device.runtime.resolver
        assertTrue(!resolver.isAvailable(bundle(A_BUILD)))

        device.server.networkDown = true
        val offline = assertIs<DatasetResolution.Unavailable>(runBlocking { resolver.resolve(bundle(A_BUILD)) })
        assertEquals(DatasetUnavailableReason.Offline, offline.reason)
        assertEquals(library, device.library.payload, "the record is untouched")

        device.server.networkDown = false
        val progress = mutableListOf<InstallProgress>()
        val available = assertIs<DatasetResolution.Available>(runBlocking { resolver.resolve(bundle(A_BUILD)) { progress += it } })
        assertTrue(progress.isNotEmpty() && progress.last().filesDone == progress.last().filesTotal)
        assertEquals(B_ID, device.runtime.activation.currentState().active, "downloaded, not activated")
        assertEquals(B_BUILD, available.snapshot.product.clientBuild)
        val session = SessionDatasets.of(available.snapshot)
        val opened = assertIs<EditorLoad.Ready>(runBlocking { open(device, session, record.localId) }).session
        assertEquals(A_BUILD, opened.record.build.clientBuild)
        assertEquals(record.build, opened.record.build)
    }

    @Test
    fun unlistedAndTooNewBuildsAreTypedUnavailable() = OtaDevice().test { device ->
        val future = CatalogRelease("1.60.1.79999-r1", "1.60.1.79999", 2, "sha256:" + "c".repeat(64), 100, 1000, "2026-10-17T23:30:00Z")
        device.server.publish(TestRelease.A)
        device.server.catalog = signedCatalog(5, A_ID, listOf(TestRelease.A.entry, future))
        assertIs<CheckOutcome.Activated>(device.check())
        val resolver = device.runtime.resolver
        val unlisted = assertIs<DatasetResolution.Unavailable>(runBlocking { resolver.resolve(bundle("1.60.1.70245")) })
        assertEquals(DatasetUnavailableReason.NotPublished, unlisted.reason)
        val tooNew = assertIs<DatasetResolution.Unavailable>(runBlocking { resolver.resolve(bundle("1.60.1.79999")) })
        assertEquals(DatasetUnavailableReason.NeedsNewerApp(2), tooNew.reason)
        assertTrue(device.server.blobRequests().none { it == "c".repeat(64) }, "nothing downloaded for a newer app")
    }

    @Test
    fun aLeasedReleaseSurvivesGarbageCollectionUntilTheEditorCloses() = OtaDevice().test { device ->
        val z = TestRelease.A.derive(releaseId = "1.60.1.70374-r2")
        device.server.publish(TestRelease.A, TestRelease.B, z)
        val all = listOf(TestRelease.A.entry, z.entry, TestRelease.B.entry)
        for ((sequence, active) in listOf(1L to z, 2L to TestRelease.A)) {
            device.server.catalog = signedCatalog(sequence, active.releaseId, all)
            assertIs<CheckOutcome.Activated>(device.check())
        }
        // an editor holds a snapshot that reads z: its lease protects z from retention
        val lease = device.runtime.leases.acquire(setOf(z.releaseId))
        device.server.catalog = signedCatalog(3, B_ID, all)
        assertIs<CheckOutcome.Activated>(device.check()) // active B, previous A: z is neither
        assertNotNull(device.runtime.activation.intactRelease(z.releaseId), "z kept while leased")
        lease.release()
        device.server.catalog = signedCatalog(4, B_ID, all)
        assertIs<CheckOutcome.Current>(device.check())
        assertTrue(device.runtime.activation.intactRelease(z.releaseId) == null, "z deleted once the editor closed")
    }
}
