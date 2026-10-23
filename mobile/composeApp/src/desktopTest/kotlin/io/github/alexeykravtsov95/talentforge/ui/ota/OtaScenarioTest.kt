package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.ota.ActivationDecision
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogRelease
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaErrorCode
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileEntry
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * L2 client scenarios S1–S14 and the additional tests of test-plan §3, against the committed
 * fixtures, an in-memory origin with a request log, a temp-dir OTA store and a fixed clock.
 * Every negative scenario asserts the saved-build library is byte-identical afterwards.
 */
class OtaScenarioTest {

    private fun catalog(name: String) = OtaTestRepo.fixture("catalogs/$name.json")

    private fun failure(outcome: CheckOutcome): OtaFailure = assertIs<CheckOutcome.Failed>(outcome).failure

    private fun code(outcome: CheckOutcome): OtaErrorCode = (failure(outcome) as OtaFailure.Contract).code

    private fun stagedPaths(device: OtaDevice, releaseId: String): Set<String> {
        val root = device.directory.resolve("v1/staging/$releaseId")
        return root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it == "attempt.json" || it == "release.json" || it.endsWith(".part") }.toSet()
    }

    private fun partFiles(device: OtaDevice) = device.directory.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()

    /** A device that already activated B from the bundle-A start (S1). */
    private fun deviceWithB(bundled: Set<String> = setOf(A_BUILD)): OtaDevice {
        val device = OtaDevice(bundledBuilds = bundled)
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        assertIs<CheckOutcome.Activated>(device.check())
        device.server.requests.clear()
        return device
    }

    // ------------------------------------------------------------------ S1

    @Test
    fun s1_aToBWithoutAnAppUpdate() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        val before = device.runtime.snapshot.value
        assertEquals(A_BUILD, before.product.clientBuild)

        val outcome = assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(B_ID, outcome.releaseId)
        assertEquals(TestRelease.B.manifest.files.size, outcome.report.downloaded, "nothing reusable: every file downloaded")
        val after = device.runtime.snapshot.value
        assertEquals(B_BUILD, after.product.clientBuild)
        assertEquals(B_ID, after.productReleaseId)
        assertEquals(B_ID, device.runtime.activation.currentState().active)

        // a NEW draft is created on B; the bundled A is untouched and still answers for A
        val session = SessionDatasets.of(after)
        val created = runBlocking { createClassDraft(device.repository, session.datasets, ClassToken("DRUID"), session.product) }
        assertEquals(B_BUILD, assertIs<DraftCreation.Created>(created).record.build.clientBuild)
        assertEquals(null, after.source(DatasetBundleId(GameId("forever"), A_BUILD))!!.releaseId)
        assertEquals(B_BUILD, device.runtime.status.value.productBuild)
        assertEquals(B_BUILD, device.runtime.status.value.updatedTo)
    }

    // ------------------------------------------------------------------ S4

    @Test
    fun s4_noInternetLeavesEverythingAsItWas() = deviceWithB().test { device ->
        val library = device.library.payload
        device.server.networkDown = true
        device.restart()
        assertEquals(B_BUILD, device.runtime.snapshot.value.product.clientBuild, "the app starts on the last active release")
        val tree = device.tree()
        assertIs<OtaFailure.Network>(failure(device.check()))
        assertEquals(tree, device.tree())
        assertEquals(B_ID, device.runtime.activation.currentState().active)
        assertEquals(FIXTURE_NOW.plusSeconds(3600), device.runtime.coordinator.nextAutomaticCheckAt(), "backoff 1 h")
        assertIs<OtaFailure.Network>(failure(device.check()))
        assertEquals(FIXTURE_NOW.plusSeconds(7200), device.runtime.coordinator.nextAutomaticCheckAt(), "backoff 2 h")
        assertEquals(library, device.library.payload)
    }

    @Test
    fun s4_aColdStartWithoutAnyStateNeverTouchesTheNetwork() = OtaDevice().test { device ->
        device.server.networkDown = true
        device.restart()
        assertEquals(A_BUILD, device.runtime.snapshot.value.product.clientBuild)
        assertTrue(device.server.requests.isEmpty(), "no network on the startup path")
    }

    // ------------------------------------------------------------------ S5

    @Test
    fun s5a_aTruncatedBlobIsDiscardedAndTheStagingKept() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        val icon = TestRelease.B.manifest.files.first { it.path.endsWith(".png") }
        device.server.fault = { url, _ ->
            if (url.endsWith(icon.sha256Hex)) FetchResult.Body(device.server.blobs.getValue(icon.sha256Hex).copyOf(100), false) else null
        }
        val library = device.library.payload
        assertEquals(OtaErrorCode.BLOB_SIZE_MISMATCH, code(device.check()))
        assertTrue(partFiles(device).isEmpty(), ".part discarded")
        assertTrue(device.directory.resolve("v1/staging/$B_ID/attempt.json").isFile, "staging kept for the resume")
        assertNull(device.runtime.activation.currentState().active)
        assertEquals(A_BUILD, device.runtime.snapshot.value.product.clientBuild)
        assertEquals(library, device.library.payload)
    }

    @Test
    fun s5b_aResumedAttemptRequestsOnlyTheMissingBlobs() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        val files = TestRelease.B.manifest.files
        val k = files.size / 2
        // first attempt: the manifest and exactly k file blobs go through, every further one hangs (the app is killed)
        val answered = AtomicInteger()
        val hung = AtomicInteger()
        device.server.beforeResponse = { url ->
            if ("/blobs/" in url && answered.incrementAndGet() > k + 1) {
                hung.incrementAndGet()
                awaitCancellation()
            }
        }
        device.runtime.coordinator.onAppStarted()
        // quiescent: the k answered files are staged and every download slot is parked on a hung blob
        val slots = minOf(device.config.parallelism, files.size - k)
        waitUntil { stagedPaths(device, B_ID).size == k && hung.get() == slots }
        assertNull(device.runtime.activation.currentState().active, "nothing is activated before full verification")
        assertEquals(A_BUILD, device.runtime.snapshot.value.product.clientBuild)
        val library = device.library.payload
        // the process dies while a blob is being written: a .part is left behind. It is placed after
        // the old runtime is gone — its cooperative cancellation cleanup deletes .part files itself.
        // The hang is lifted only after the kill: while the parent cancels its children one by one,
        // a queued download can take a permit a cancelled one released before its own cancellation;
        // with the hang still in place it parks and is cancelled, instead of staging one more file
        // (the in-memory fetch never suspends, so nothing else would stop it).
        device.kill()
        device.server.beforeResponse = null
        val inFlight = device.directory.resolve("v1/staging/$B_ID/${files.last().path}.part")
        inFlight.parentFile.mkdirs()
        inFlight.writeBytes(ByteArray(10))
        device.coldStart()
        val report = device.runtime.recovery
        assertEquals(1, report.deletedPartFiles, "recover() deletes only the .part")
        assertEquals(listOf(B_ID), report.keptStaging)
        val staged = stagedPaths(device, B_ID)
        assertEquals(k, staged.size, "exactly the k answered files stay staged")

        device.server.requests.clear()
        val outcome = assertIs<CheckOutcome.Activated>(device.check())
        val expected = files.filter { it.path !in staged }.map { it.sha256Hex }.toSet()
        assertEquals(expected, device.server.blobRequests().toSet(), "only the missing blobs are requested")
        assertEquals(staged.size, outcome.report.reusedStaged)
        assertEquals(false, outcome.report.manifestDownloaded, "the staged manifest is reused")
        assertEquals(B_ID, device.runtime.activation.currentState().active)
        assertEquals(library, device.library.payload)
    }

    @Test
    fun s5c_aCorruptedStagedFileIsDeletedAndRequestedAgain() = OtaDevice(
        config = OtaConfig.forTesting(OtaTestRepo.keyring, baseUrl = "https://ota.test", parallelism = 1),
    ).test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        val files = TestRelease.B.manifest.files
        // the last file whose blob no other path shares (two paths may share one blob, contract §5)
        val cut = files.last { file -> files.count { it.sha256Hex == file.sha256Hex } == 1 }
        device.server.fault = { url, _ -> if (url.endsWith(cut.sha256Hex)) FetchResult.NetworkError("cut") else null }
        assertIs<OtaFailure.Network>(failure(device.check()))
        val staged = stagedPaths(device, B_ID)
        assertTrue(cut.path !in staged && staged.isNotEmpty(), "the cut file is not staged; the verified ones are")
        val victim = files.first { it.path in staged && it.sha256Hex != cut.sha256Hex }
        val victimFile = device.directory.resolve("v1/staging/$B_ID/${victim.path}")
        victimFile.writeBytes(victimFile.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
        device.server.fault = null
        device.restart()
        device.server.requests.clear()
        assertIs<CheckOutcome.Activated>(device.check())
        val expected = files.filter { it.path !in staged }.map { it.sha256Hex }.toSet() + victim.sha256Hex
        assertEquals(expected, device.server.blobRequests().toSet(), "only the unstaged files and the corrupted one are requested")
    }

    // ------------------------------------------------------------------ S5b (catalog pins other bytes)

    @Test
    fun s5b_aCatalogPinningOtherBytesRestartsTheAttemptFromNothing() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        val files = TestRelease.B.manifest.files
        device.server.fault = { url, _ -> if (url.endsWith(files.last().sha256Hex)) FetchResult.NetworkError("cut") else null }
        assertIs<OtaFailure.Network>(failure(device.check()))
        assertTrue(stagedPaths(device, B_ID).isNotEmpty())
        // the same release id, other manifest bytes (same files): an append-only violation or local corruption
        val other = TestRelease.B.derive()
        assertTrue(!other.manifestBytes.contentEquals(TestRelease.B.manifestBytes))
        device.server.publish(other)
        device.server.catalog = signedCatalog(3, B_ID, listOf(TestRelease.A.entry, other.entry))
        device.server.fault = null
        device.server.requests.clear()
        val outcome = assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(0, outcome.report.reusedStaged, "the old staging was deleted")
        assertTrue(outcome.report.manifestDownloaded)
        assertEquals(files.size, outcome.report.downloaded)
        assertEquals(other.entry.manifestSha256, device.runtime.activation.currentState().installedRelease(B_ID)!!.manifestSha256)
    }

    // ------------------------------------------------------------------ S6

    @Test
    fun s6_aBitFlippedBlobIsDiscardedAndRetried() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        val icon = TestRelease.B.manifest.files.first { it.path.endsWith(".png") }
        val flipped = OtaTestRepo.fixture("blobs/icon-132089-bitflip.png")
        device.server.fault = { url, _ -> if (url.endsWith(icon.sha256Hex)) FetchResult.Body(flipped.copyOf(icon.byteLength.toInt()), false) else null }
        val library = device.library.payload
        assertEquals(OtaErrorCode.BLOB_HASH_MISMATCH, code(device.check()))
        assertTrue(icon.path !in stagedPaths(device, B_ID), "the bad blob is not staged")
        assertTrue(stagedPaths(device, B_ID).isNotEmpty(), "verified staged files are kept")
        assertTrue(device.runtime.activation.currentState().rejected.isEmpty(), "a transient failure is not a rejection")
        assertEquals(FIXTURE_NOW.plusSeconds(3600), device.runtime.coordinator.nextAutomaticCheckAt())
        assertNull(device.runtime.activation.currentState().active)
        device.server.fault = null
        device.server.requests.clear()
        assertIs<CheckOutcome.Activated>(device.check(), "retried")
        assertEquals(library, device.library.payload)
    }

    // ------------------------------------------------------------------ S7 / S13 / S14 (catalog rejections)

    @Test
    fun s7_s13_s14_aRejectedCatalogChangesNothingAndRequestsNoBlob() = deviceWithB().test { device ->
        val library = device.library.payload
        val seen = device.runtime.activation.currentState().catalog
        val expected = mapOf(
            "catalog-tampered-payload" to OtaErrorCode.SIGNATURE_INVALID,
            "catalog-signature-by-wrong-key" to OtaErrorCode.SIGNATURE_INVALID,
            "catalog-untrusted-signer" to OtaErrorCode.UNTRUSTED_SIGNER,
            "catalog-wrong-payload-type" to OtaErrorCode.PAYLOAD_TYPE_MISMATCH,
            "catalog-envelope-duplicate-key" to OtaErrorCode.ENVELOPE_INVALID,
            "catalog-duplicate-escaped-key" to OtaErrorCode.PAYLOAD_INVALID,
            "catalog-duplicate-nested-key" to OtaErrorCode.PAYLOAD_INVALID,
            "catalog-over-size-limit" to OtaErrorCode.CATALOG_TOO_LARGE,
            "catalog-staging-on-production" to OtaErrorCode.CHANNEL_MISMATCH,
            "catalog-schema-version-2" to OtaErrorCode.UNSUPPORTED_SCHEMA_VERSION,
            "catalog-unknown-field" to OtaErrorCode.PAYLOAD_INVALID,
            "catalog-sequence-regression" to OtaErrorCode.SEQUENCE_REGRESSION,
        )
        val tree = device.tree()
        for ((name, code) in expected) {
            device.server.catalog = catalog(name)
            device.server.requests.clear()
            assertEquals(code, code(device.check()), name)
            assertTrue(device.server.blobRequests().isEmpty(), "$name: no blob requested")
            assertEquals(seen, device.runtime.activation.currentState().catalog, "$name: lastSeen unchanged")
            assertEquals(tree, device.tree(), "$name: nothing on disk changed")
        }
        // S14: an endless response is cut at 1 MiB + 1 byte by the fetcher and never parsed
        device.server.catalog = ByteArray(5 * 1024 * 1024) { '{'.code.toByte() }
        assertEquals(OtaErrorCode.CATALOG_TOO_LARGE, code(device.check()))
        assertEquals(B_ID, device.runtime.activation.currentState().active)
        assertEquals(library, device.library.payload)
    }

    // ------------------------------------------------------------------ S8

    private fun caseEntry(id: String): CatalogRelease {
        val case = Json.parseToJsonElement(OtaTestRepo.fixtures.resolve("cases.json").readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
        val e = case.getValue("catalogEntry").jsonObject
        return CatalogRelease(
            e.getValue("releaseId").jsonPrimitive.content, e.getValue("clientBuild").jsonPrimitive.content, e.getValue("minDataLevel").jsonPrimitive.int,
            e.getValue("manifestSha256").jsonPrimitive.content, e.getValue("manifestByteLength").jsonPrimitive.long,
            e.getValue("totalByteLength").jsonPrimitive.long, e.getValue("publishedAt").jsonPrimitive.content,
        )
    }

    private fun publishInvalidRelease(device: OtaDevice, manifestFile: String, overrides: Map<String, String> = emptyMap()) {
        val bytes = OtaTestRepo.fixture(manifestFile)
        device.server.blobs[sha256Hex(bytes)] = bytes
        val files = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("files").jsonArray
        for (file in files) {
            val path = file.jsonObject.getValue("path").jsonPrimitive.content
            val override = overrides[path]
            val source = if (override != null) OtaTestRepo.fixtures.resolve(override) else OtaTestRepo.repositoryFile(path)
            if (source.isFile) device.server.blobs[sha256Hex(source.readBytes())] = source.readBytes()
        }
    }

    @Test
    fun s8_releasesRejectedForWhatTheyAreAreRecordedAndNeverRetried() {
        val cases = listOf(
            Triple("release-unknown-field", "releases/invalid/release-unknown-field.release.json", emptyMap<String, String>()) to OtaErrorCode.MANIFEST_INVALID,
            Triple("release-schema-version-2", "releases/invalid/release-schema-version-2.release.json", emptyMap<String, String>()) to OtaErrorCode.UNSUPPORTED_SCHEMA_VERSION,
            Triple("release-unknown-role", "releases/invalid/release-unknown-role.release.json", emptyMap<String, String>()) to OtaErrorCode.MANIFEST_INVALID,
            Triple(
                "release-new-mechanic",
                "releases/invalid/release-new-mechanic.release.json",
                mapOf("dataset/forever/1.60.1.70450/paladin.json" to "blobs/release-new-mechanic--paladin.json"),
            ) to OtaErrorCode.CONTENT_INVALID,
        )
        for ((case, expected) in cases) {
            OtaDevice().use { device ->
                val (id, file, overrides) = case
                val entry = caseEntry(id)
                publishInvalidRelease(device, file, overrides)
                device.server.catalog = signedCatalog(2, entry.releaseId, listOf(entry))
                val library = device.library.payload
                assertEquals(expected, code(device.check()), id)
                val rejected = device.runtime.activation.currentState().rejected.single()
                assertEquals(entry.manifestSha256, rejected.manifestSha256)
                assertEquals(expected, rejected.code)
                assertTrue(!device.directory.resolve("v1/staging/${entry.releaseId}").exists(), "$id: staging deleted")
                // the same manifest bytes are never tried again — not even downloaded
                device.server.requests.clear()
                assertEquals(expected, code(device.check()), "$id: second check")
                assertTrue(device.server.blobRequests().isEmpty(), "$id: nothing requested again")
                // a restart keeps the record
                device.restart()
                assertEquals(rejected, device.runtime.activation.currentState().rejected.single())
                assertNull(device.runtime.activation.currentState().active)
                assertEquals(library, device.library.payload)
            }
        }
    }

    @Test
    fun s8_anActiveReleaseForANewerAppIsNeverDownloaded() = deviceWithB().test { device ->
        device.server.catalog = catalog("catalog-active-needs-newer-app")
        val outcome = assertIs<CheckOutcome.NeedsNewerApp>(device.check())
        assertEquals(2, outcome.minDataLevel)
        assertTrue(device.server.blobRequests().isEmpty())
        assertEquals(B_ID, device.runtime.activation.currentState().active)
    }

    // ------------------------------------------------------------------ S9

    private class SpaceStore(private val delegate: OtaFileStore) : OtaFileStore by delegate {
        @Volatile
        var free: Long = Long.MAX_VALUE

        @Volatile
        var failPartWritesAfter: Int = Int.MAX_VALUE
        private var partWrites = 0

        override fun freeSpaceBytes(): Long = free

        override fun writeSynced(path: String, bytes: ByteArray) {
            if (path.endsWith(".part") && ++partWrites > failPartWritesAfter) {
                delegate.writeSynced(path, bytes.copyOf(bytes.size / 2)) // half written, then ENOSPC
                throw OtaIoException("ENOSPC (test)", noSpace = true)
            }
            delegate.writeSynced(path, bytes)
        }
    }

    @Test
    fun s9_notEnoughSpaceBeforeTheFirstDownload() {
        var store: SpaceStore? = null
        OtaDevice(storeWrapper = { SpaceStore(it).also { s -> store = s } }).use { device ->
            device.server.publish(TestRelease.A, TestRelease.B)
            device.server.catalog = catalog("catalog-b-active")
            store!!.free = io.github.alexeykravtsov95.talentforge.data.ota.StagingPolicy.SPACE_MARGIN_BYTES + 1000 // above the margin, below remaining + margin
            val library = device.library.payload
            assertIs<OtaFailure.InsufficientStorage>(failure(device.check()))
            assertEquals(listOf(TestRelease.B.entry.manifestSha256.removePrefix("sha256:")), device.server.blobRequests(), "only the manifest")
            assertTrue(device.directory.resolve("v1/staging/$B_ID/release.json").isFile, "staging kept")
            assertNull(device.runtime.activation.currentState().active)
            assertEquals(library, device.library.payload)
        }
    }

    @Test
    fun s9_enospcHalfWayDeletesThePartAndKeepsVerifiedFiles() {
        var store: SpaceStore? = null
        OtaDevice(storeWrapper = { SpaceStore(it).also { s -> store = s } }).use { device ->
            device.server.publish(TestRelease.A, TestRelease.B)
            device.server.catalog = catalog("catalog-b-active")
            store!!.failPartWritesAfter = 40
            assertIs<OtaFailure.InsufficientStorage>(failure(device.check()))
            assertTrue(partFiles(device).isEmpty(), "the .part in flight is deleted")
            assertTrue(stagedPaths(device, B_ID).size >= 40 - device.config.parallelism, "verified staged files kept")
            assertNull(device.runtime.activation.currentState().active)
        }
    }

    @Test
    fun s9_belowTheMarginTheStagingIsDeleted() {
        var store: SpaceStore? = null
        OtaDevice(storeWrapper = { SpaceStore(it).also { s -> store = s } }).use { device ->
            device.server.publish(TestRelease.A, TestRelease.B)
            device.server.catalog = catalog("catalog-b-active")
            store!!.free = 1024
            assertIs<OtaFailure.InsufficientStorage>(failure(device.check()))
            assertTrue(!device.directory.resolve("v1/staging/$B_ID").exists(), "staging deleted below the margin")
            assertNull(device.runtime.activation.currentState().active)
        }
    }

    // ------------------------------------------------------------------ S11

    @Test
    fun s11_rollbackToAKeepsBAndRejectsAReplayedOldCatalog() = deviceWithB().test { device ->
        val bBuild = device.saveBuild(B_BUILD)
        val library = device.library.payload
        device.server.catalog = catalog("catalog-rollback-to-a")
        val outcome = assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(A_ID, outcome.releaseId)
        assertEquals(TestRelease.A.manifest.files.size, outcome.report.copiedFromBundle + outcome.report.copiedFromInstalled, "A's files come from the bundle (or B's identical icons) after re-hashing")
        assertEquals(0, outcome.report.downloaded)
        assertEquals(1, device.server.blobRequests().size, "only A's manifest is downloaded")
        assertEquals(A_BUILD, device.runtime.snapshot.value.product.clientBuild)
        val state = device.runtime.activation.currentState()
        assertEquals(A_ID, state.active)
        assertEquals(B_ID, state.previous)
        assertNotNull(device.runtime.activation.intactRelease(B_ID), "B kept: previous and referenced by a saved build")
        assertNotNull(device.runtime.snapshot.value.source(DatasetBundleId(GameId("forever"), B_BUILD)))
        // the old sequence-1 catalog is a replay
        device.server.catalog = catalog("catalog-a-first-publication")
        assertEquals(OtaErrorCode.SEQUENCE_REGRESSION, code(device.check()))
        assertEquals(A_ID, device.runtime.activation.currentState().active)
        assertEquals(library, device.library.payload)
        assertEquals(B_BUILD, device.repository.load().let { (it as io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome.Loaded).value.single { r -> r.localId == bBuild.localId }.build.clientBuild })
    }

    @Test
    fun s11_aRollbackBelowTheBundledProductKeepsTheBundle() = OtaDevice(bundledBuilds = setOf(A_BUILD, B_BUILD)).test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-rollback-below-bundled-floor")
        val outcome = assertIs<CheckOutcome.Current>(device.check())
        assertEquals(ActivationDecision.KEEP_BUNDLED_FLOOR, outcome.decision)
        assertTrue(device.server.blobRequests().isEmpty())
        assertEquals(B_BUILD, device.runtime.snapshot.value.product.clientBuild)
        assertNull(device.runtime.snapshot.value.productReleaseId)
    }

    // ------------------------------------------------------------------ S12

    @Test
    fun s12_theSameCatalogAgainDownloadsNothing() = deviceWithB().test { device ->
        device.server.catalog = catalog("catalog-b-active-refetched")
        val tree = device.tree()
        val outcome = assertIs<CheckOutcome.Current>(device.check())
        assertEquals(ActivationDecision.NO_CHANGE, outcome.decision)
        assertTrue(device.server.blobRequests().isEmpty())
        assertEquals(tree, device.tree())
    }

    @Test
    fun s12_aReleaseEqualToTheBundleIsCopiedFromTheBundle() = OtaDevice(bundledBuilds = setOf(A_BUILD, B_BUILD), bundledProduct = A_BUILD).test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        val outcome = assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(TestRelease.B.manifest.files.size, outcome.report.copiedFromBundle)
        assertEquals(0, outcome.report.downloaded)
        assertEquals(1, device.server.blobRequests().size, "only the manifest")
    }

    // ------------------------------------------------------------------ test-plan §3

    @Test
    fun anUnreadableStateRunsOnTheBundleDeletesNothingAndIsRebuiltByTheNextCatalog() = deviceWithB().test { device ->
        device.directory.resolve("v1/state.json").writeText("garbage")
        val staging = device.directory.resolve("v1/staging/1.60.1.70450-r9")
        staging.mkdirs()
        staging.resolve("attempt.json").writeBytes(io.github.alexeykravtsov95.talentforge.data.ota.InstallAttemptCodec.encode(
            io.github.alexeykravtsov95.talentforge.data.ota.InstallAttempt("1.60.1.70450-r9", "sha256:" + "0".repeat(64), 10, FIXTURE_NOW, FIXTURE_NOW),
        ))
        device.restart()
        assertEquals(false, device.runtime.recovery.stateReadable)
        assertEquals(A_BUILD, device.runtime.snapshot.value.product.clientBuild, "bundled data")
        assertTrue(device.directory.resolve("v1/releases/$B_ID/release.json").isFile, "no release directory deleted")
        assertTrue(staging.isDirectory, "staging kept")
        device.server.requests.clear()
        device.server.catalog = catalog("catalog-b-active")
        assertIs<CheckOutcome.Activated>(device.check())
        assertTrue(device.server.blobRequests().isEmpty(), "B adopted from disk, not downloaded")
        assertEquals(B_ID, device.runtime.activation.currentState().active)
    }

    @Test
    fun recoveryDeletesOnlyIdentifiableStagingGarbage() = OtaDevice().test { device ->
        fun stage(name: String, attempt: ByteArray?) = device.directory.resolve("v1/staging/$name").also {
            it.mkdirs()
            it.resolve("some.json").writeText("{}")
            if (attempt != null) it.resolve("attempt.json").writeBytes(attempt)
        }
        fun attempt(id: String) = io.github.alexeykravtsov95.talentforge.data.ota.InstallAttemptCodec.encode(
            io.github.alexeykravtsov95.talentforge.data.ota.InstallAttempt(id, "sha256:" + "1".repeat(64), 10, FIXTURE_NOW, FIXTURE_NOW),
        )
        stage("not-an-id", attempt("1.60.1.70450-r1"))
        stage("1.60.1.70450-r2", null)
        stage("1.60.1.70450-r3", attempt("1.60.1.70450-r4"))
        val good = stage("1.60.1.70450-r5", attempt("1.60.1.70450-r5"))
        device.directory.resolve("v1/trash/x.0").mkdirs()
        device.directory.resolve("v1/state.json.tmp").writeText("partial")
        device.restart()
        assertEquals(listOf("1.60.1.70450-r5"), device.runtime.recovery.keptStaging)
        assertEquals(3, device.runtime.recovery.deletedStaging.size)
        assertTrue(good.resolve("some.json").isFile)
        assertTrue(!device.directory.resolve("v1/trash").exists())
        assertTrue(!device.directory.resolve("v1/state.json.tmp").exists())
    }

    @Test
    fun aCorruptFileOfTheActiveReleaseQuarantinesItAndTheNextCheckReinstallsIt() = deviceWithB().test { device ->
        val snapshot = device.runtime.snapshot.value
        val doc = TestRelease.B.manifest.files.first { it.path.endsWith("/druid.json") }
        val file = device.directory.resolve("v1/releases/$B_ID/${doc.path}")
        file.writeBytes(file.readBytes().also { it[10] = (it[10].toInt() xor 1).toByte() })
        val read = runBlocking { SessionDatasets.of(snapshot).datasets.document(io.github.alexeykravtsov95.talentforge.data.DatasetId(GameId("forever"), B_BUILD, ClassToken("DRUID"))) }
        assertIs<io.github.alexeykravtsov95.talentforge.data.DatasetOutcome.Failed>(read, "the open session gets the typed failure")
        waitUntil { device.runtime.activation.intactRelease(B_ID) == null }
        assertTrue(device.directory.resolve("v1/quarantine").listFiles()!!.any { it.name.startsWith(B_ID) })
        assertEquals(A_BUILD, device.runtime.snapshot.value.product.clientBuild, "new sessions use the bundle")
        device.server.catalog = catalog("catalog-b-active")
        assertIs<CheckOutcome.Activated>(device.check(), "the next check re-installs it")
        assertEquals(B_BUILD, device.runtime.snapshot.value.product.clientBuild)
    }

    @Test
    fun anAppUpdateWhoseBundleIsNewerWinsAtOnceAndKeepsTheOldRelease() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-a-first-publication")
        assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(A_ID, device.runtime.activation.currentState().active)
        // the "new app" bundles B: same OTA directory, a newer bundled product
        val updated = OtaDevice(bundledBuilds = setOf(A_BUILD, B_BUILD))
        try {
            device.directory.copyRecursively(updated.directory, overwrite = true)
            val runtime = updated.restart()
            assertEquals(B_BUILD, runtime.snapshot.value.product.clientBuild, "the bundled product wins at once")
            assertNotNull(runtime.activation.intactRelease(A_ID), "A stays installed")
        } finally {
            updated.close()
        }
    }

    @Test
    fun twoTriggersAtOnceRunOneAttempt() = OtaDevice().test { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
        val outcomes = runBlocking {
            listOf(async { device.runtime.coordinator.checkNow() }, async { device.runtime.coordinator.checkNow() }).awaitAll()
        }
        assertEquals(1, device.server.catalogRequests())
        assertEquals(outcomes[0], outcomes[1])
    }

    private fun retentionDevice(libraryUnreadable: Boolean): OtaDevice {
        // installed {X, Z, active B, previous A}: X = the newest revision of 70450, a build a saved
        // build references and this device does not bundle; Z = a revision of the bundled 70374
        // that nothing references
        val device = OtaDevice(bundledBuilds = setOf(A_BUILD))
        val x = TestRelease.B.derive(releaseId = "1.60.1.70450-r7")
        val z = TestRelease.A.derive(releaseId = "1.60.1.70374-r2")
        device.server.publish(TestRelease.A, TestRelease.B, x, z)
        device.saveBuild(B_BUILD)
        device.saveBuild(A_BUILD)
        val all = listOf(TestRelease.A.entry, z.entry, TestRelease.B.entry, x.entry)
        device.library.failReads = libraryUnreadable
        for ((sequence, active) in listOf(1L to z, 2L to x, 3L to TestRelease.A, 4L to TestRelease.B)) {
            device.server.catalog = signedCatalog(sequence, active.releaseId, all)
            assertIs<CheckOutcome.Activated>(device.check(), active.releaseId)
        }
        return device
    }

    @Test
    fun retentionDeletesOnlyTheUnreferencedRelease() = retentionDevice(libraryUnreadable = false).test { device ->
        val state = device.runtime.activation.currentState()
        assertEquals(B_ID, state.active)
        assertEquals(A_ID, state.previous)
        assertEquals(setOf(A_ID, B_ID, "1.60.1.70450-r7"), state.installed.map { it.releaseId }.toSet())
        assertTrue(!device.directory.resolve("v1/releases/1.60.1.70374-r2").exists(), "Z deleted")
        assertTrue(device.directory.resolve("v1/releases/1.60.1.70450-r7").isDirectory, "X kept for the saved build")
        assertTrue(!device.directory.resolve("v1/trash").exists())
    }

    @Test
    fun withAnUnreadableLibraryRetentionDeletesNoInstalledRelease() = retentionDevice(libraryUnreadable = true).test { device ->
        assertEquals(4, device.runtime.activation.currentState().installed.size)
        assertTrue(device.directory.resolve("v1/releases/1.60.1.70374-r2").isDirectory, "Z kept: the library could not be read")
    }

    private fun waitUntil(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in time" }
            Thread.sleep(20)
        }
    }

    @Suppress("unused")
    private fun File.entries(): List<OtaFileEntry> = listFiles().orEmpty().map { OtaFileEntry(it.name, it.isDirectory) }
}
