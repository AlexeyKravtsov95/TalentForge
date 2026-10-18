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
    fun anImportedCodeOfAListedBuildIsDownloadedAndOfflineStaysTypedUnavailable() = OtaDevice(bundledBuilds = setOf(B_BUILD)).test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        assertIs<CheckOutcome.Activated>(device.check())
        val code = (io.github.alexeykravtsov95.talentforge.share.BuildShareCode.encode(device.saveBuild(A_BUILD, "MAGE").build)
            as io.github.alexeykravtsov95.talentforge.share.ShareCodeEncoding.Encoded).code
        fun controller(scope: kotlinx.coroutines.CoroutineScope) = io.github.alexeykravtsov95.talentforge.ui.share.BuildImportController(
            androidx.compose.runtime.mutableStateOf(code),
            SessionDatasets.of(device.runtime.snapshot.value).datasets,
            device.repository,
            scope,
            kotlinx.coroutines.Dispatchers.Default,
        ) { bundle -> (device.runtime.resolver.resolve(bundle) as? DatasetResolution.Available)?.let { SessionDatasets.of(it.snapshot).datasets } }
        runBlocking {
            device.server.networkDown = true
            val offline = controller(this)
            offline.submit()
            waitUntil { offline.phase !is io.github.alexeykravtsov95.talentforge.ui.share.ImportPhase.Checking }
            val problem = assertIs<io.github.alexeykravtsov95.talentforge.ui.share.ImportPhase.Problem>(offline.phase)
            val rejected = assertIs<io.github.alexeykravtsov95.talentforge.ui.share.ImportProblem.Rejected>(problem.problem)
            assertIs<io.github.alexeykravtsov95.talentforge.share.BuildImportFailure.ClientBuildUnavailable>(rejected.failure)
            device.server.networkDown = false
            val online = controller(this)
            online.submit()
            waitUntil { online.phase !is io.github.alexeykravtsov95.talentforge.ui.share.ImportPhase.Checking }
            assertIs<io.github.alexeykravtsov95.talentforge.ui.share.ImportPhase.Confirming>(online.phase, "previewed on its own build")
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timeout" }
            kotlinx.coroutines.delay(20)
        }
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
        val lease = assertNotNull(device.runtime.leases.tryAcquire(setOf(z.releaseId)))
        device.server.catalog = signedCatalog(3, B_ID, all)
        assertIs<CheckOutcome.Activated>(device.check()) // active B, previous A: z is neither
        assertNotNull(device.runtime.activation.intactRelease(z.releaseId), "z kept while leased")
        lease.release()
        device.server.catalog = signedCatalog(4, B_ID, all)
        assertIs<CheckOutcome.Current>(device.check())
        assertTrue(device.runtime.activation.intactRelease(z.releaseId) == null, "z deleted once the editor closed")
    }

    /**
     * PR #44 review P1-1: an editor that downloaded its historical build keeps that release
     * through the activation of a newer revision of the same build and the GC after it, and
     * reads it until it closes; then retention deletes it.
     */
    @Test
    fun anEditorKeepsTheRevisionItDownloadedWhenANewerRevisionOfTheSameBuildIsActivated() = OtaDevice().test { device ->
        val r2 = TestRelease.B.derive(releaseId = "$B_BUILD-r2")
        device.server.publish(TestRelease.A, TestRelease.B, r2)
        device.server.catalog = signedCatalog(1, A_ID, listOf(TestRelease.A.entry, TestRelease.B.entry))
        assertIs<CheckOutcome.Activated>(device.check()) // A-r1 active; B-r1 only listed
        val record = device.saveBuild(B_BUILD, "ROGUE")

        // the editor opens on the current session, which has no B, and downloads B-r1 on demand
        val access = EditorDatasetAccess.open(device.runtime, SessionDatasets.of(device.runtime.snapshot.value))
        assertTrue(!access.isAvailable(bundle(B_BUILD)))
        val ready = assertIs<EditorDatasetResolution.Ready>(runBlocking { access.resolve(bundle(B_BUILD)) {} })
        assertEquals(B_ID, ready.session.snapshot!!.source(bundle(B_BUILD))!!.releaseId)
        val editor = assertIs<EditorLoad.Ready>(runBlocking { open(device, ready.session, record.localId) }).session
        assertEquals(record.build, editor.record.build)

        // B-r2 is published and activated; retention runs after the check: B-r1 is neither
        // active, previous nor the newest revision of the saved build — only the lease keeps it
        val all = listOf(TestRelease.A.entry, TestRelease.B.entry, r2.entry)
        device.server.catalog = signedCatalog(2, r2.releaseId, all)
        assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(r2.releaseId, device.runtime.activation.currentState().active)
        assertNotNull(device.runtime.activation.intactRelease(B_ID), "B-r1 kept while the editor reads it")
        assertTrue(device.directory.resolve("v1/releases/$B_ID").isDirectory)
        runBlocking {
            val document = (
                ready.session.datasets.document(io.github.alexeykravtsov95.talentforge.data.DatasetId(GameId("forever"), B_BUILD, ClassToken("ROGUE")))
                    as io.github.alexeykravtsov95.talentforge.data.DatasetOutcome.Loaded
                ).value
            ready.session.icons.load(document, document.trees.single())
            ready.session.descriptions.load(document, document.trees.single())
        }
        assertNotNull(device.runtime.activation.intactRelease(B_ID), "every read of the open editor found its files")

        // the editor closes: the next GC deletes B-r1
        access.onForgotten()
        device.server.catalog = signedCatalog(3, r2.releaseId, all)
        assertIs<CheckOutcome.Current>(device.check())
        assertTrue(device.runtime.activation.intactRelease(B_ID) == null, "B-r1 deleted once the editor closed")
        assertTrue(!device.directory.resolve("v1/releases/$B_ID").exists())
    }

    @Test
    fun anEditorNeverLeasesASnapshotWhoseReleaseRetentionAlreadyDeleted() = OtaDevice().test { device ->
        val z = TestRelease.A.derive(releaseId = "1.60.1.70374-r2")
        device.server.publish(TestRelease.A, TestRelease.B, z)
        val all = listOf(TestRelease.A.entry, z.entry, TestRelease.B.entry)
        device.server.catalog = signedCatalog(1, z.releaseId, all)
        assertIs<CheckOutcome.Activated>(device.check())
        // a session published while z was active, not yet opened by any editor
        val stale = SessionDatasets.of(device.runtime.snapshot.value)
        assertTrue(z.releaseId in stale.snapshot!!.releaseIds)
        for ((sequence, active) in listOf(2L to A_ID, 3L to B_ID)) {
            device.server.catalog = signedCatalog(sequence, active, all)
            assertIs<CheckOutcome.Activated>(device.check())
        }
        assertTrue(device.runtime.activation.intactRelease(z.releaseId) == null, "z deleted: nothing leased it")
        assertEquals(null, device.runtime.activation.lease(stale.snapshot!!))
        // the editor opens on the current snapshot instead, leased
        val access = EditorDatasetAccess.open(device.runtime, stale)
        assertTrue(access.session !== stale)
        assertTrue(z.releaseId !in access.session.snapshot!!.releaseIds)
        assertEquals(access.session.snapshot!!.releaseIds, device.runtime.leases.leased())
        access.onForgotten()
        assertEquals(emptySet(), device.runtime.leases.leased())
    }

    @Test
    fun retentionClaimsOnlyUnleasedReleasesAndAClaimedReleaseCannotBeLeased() {
        val leases = SnapshotLeases()
        val held = assertNotNull(leases.tryAcquire(setOf("x")))
        assertEquals(setOf("y"), leases.claimForDeletion(setOf("x", "y")), "a leased release is never claimed")
        assertEquals(null, leases.tryAcquire(setOf("y", "w")), "nothing is leased while being deleted")
        assertEquals(setOf("x"), leases.leased())
        leases.deletionFinished(setOf("y"))
        val reinstalled = assertNotNull(leases.tryAcquire(setOf("y")))
        held.release()
        held.release() // idempotent
        assertEquals(setOf("y"), leases.leased())
        reinstalled.release()
        assertEquals(emptySet(), leases.leased())
    }

    @Test
    fun aLeaseHandedToAClosedHolderIsReleasedAtOnce() {
        val leases = SnapshotLeases()
        val holder = DatasetLeaseHolder()
        holder.hold(assertNotNull(leases.tryAcquire(setOf("a"))))
        holder.hold(assertNotNull(leases.tryAcquire(setOf("b"))))
        assertEquals(setOf("b"), leases.leased(), "moving to another snapshot releases the previous lease")
        holder.onForgotten()
        assertEquals(emptySet(), leases.leased())
        holder.hold(assertNotNull(leases.tryAcquire(setOf("c")))) // a download that finished after the screen closed
        assertEquals(emptySet(), leases.leased())
    }
}
