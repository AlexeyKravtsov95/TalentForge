package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.ota.ActivationDecision
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * Historical patch replay, client half (M5.OTA.AUTO.2, opt-in): the app as it was **before**
 * 1.60.1.70450 meets the staging publication of 70450.
 *
 * The released app bundles 70450 as `PRODUCT_BUNDLE`, so it cannot show this update. The device
 * here is the app before the patch: 70374 is its only bundled build and its product (no 70450
 * file can be read locally), OTA is enabled for the staging channel only, resolved by
 * [OtaConfig.resolve] like an app build. Everything else is the real client: the
 * `HttpURLConnection` fetcher over HTTPS trusting the stand's CA only, `java.security`
 * signatures, the `java.nio` store, the app's repositories and editor sessions. The origin
 * serves the exact bytes the real publisher staged with the stand's `tf-staging-local-*` key.
 *
 * `scripts/ota_historical_replay.py run <out>` makes `<out>/stand` (and `<out>/prepare`, the
 * preparation of 70450) and runs this test with `-Dtalentforge.ota.replay=<out>`. Each test
 * writes its evidence to `<out>/client/<test>.json`: what came over the network, from the
 * installed 70374 release (the client's cache) or from the bundle.
 */
class OtaHistoricalReplayTest {
    private val out: File? = System.getProperty(PROPERTY)?.let(::File)

    @Before
    fun requested() = assumeTrue("historical replay not requested (-D$PROPERTY=<replay out>)", out != null)

    private val stand: StagingStand by lazy { StagingStand(out!!.resolve("stand")) }

    /** The app before the patch: 70374 bundled and the product, the staging channel, real adapters. */
    private fun historicalDevice(origin: StandOrigin) =
        OtaDevice(bundledBuilds = setOf(A_BUILD), config = stand.config(origin.base), fetcher = stand.fetcher()).also {
            it.clock.now = stand.publishedAt.plusSeconds(60)
        }

    private fun releaseFiles(releaseId: String): Map<String, String> = StagingStand.releaseFiles(stand.publishedManifest(releaseId))

    private fun OtaDevice.active(): String? = runtime.activation.currentState().active

    private fun OtaDevice.product(): String = runtime.snapshot.value.product.clientBuild

    private fun bundle(build: String) = DatasetBundleId(GameId("forever"), build)

    private fun opensOnItsOwnBuild(device: OtaDevice, record: LocalBuildRecord) {
        val session = SessionDatasets.of(device.runtime.snapshot.value)
        val opened = runBlocking { loadEditorSession(device.repository, session.datasets, record.localId, session.icons, session.descriptions, session.localization) }
        assertEquals(record.build, assertIs<EditorLoad.Ready>(opened).session.record.build, "the saved build opens unchanged on ${record.build.clientBuild}")
    }

    /** SHA-256 hex of the files verified into `staging/<releaseId>` (the resume set). */
    private fun OtaDevice.stagedHashes(releaseId: String): Set<String> {
        val root = directory.resolve("v1/staging/$releaseId")
        val files = releaseFiles(releaseId)
        return root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .mapNotNull { files[it.relativeTo(root).invariantSeparatorsPath] }.toSet()
    }

    private fun evidence(test: String, facts: Map<String, Any?>) {
        fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v) })
            is Iterable<*> -> JsonArray(value.map(::json))
            else -> JsonPrimitive(value.toString())
        }
        val dir = out!!.resolve("client").apply { mkdirs() }
        dir.resolve("$test.json").writeText(json(facts).toString() + "\n")
        println("[historical-replay] $test: $facts")
    }

    @Test
    fun theClientMovesFrom70374To70450() = StandOrigin(stand.dir).use { origin ->
        historicalDevice(origin).test { device ->
            assertEquals(A_ID to B_ID, stand.reportString("releases", "a") to stand.reportString("releases", "b"))

            // the app before the patch: 70374 is the product, 70450 cannot be read locally
            assertEquals(A_BUILD, device.product(), "before any catalog the app runs on 70374")
            assertNull(device.active())
            assertFalse(device.runtime.resolver.isAvailable(bundle(B_BUILD)), "70450 is not available locally")
            assertFailsWith<AssetNotFoundException> { runBlocking { device.bundleSource.read("dataset/forever/$B_BUILD/druid.json") } }

            // staging carries 70374 only: activated from the bundle, only its manifest is fetched
            origin.snapshot = "1-a-staged"
            assertEquals(A_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertEquals(listOf(stand.manifestHex(A_ID)), origin.blobRequests(), "every 70374 file comes from the bundle")
            assertEquals(A_BUILD, device.product())
            origin.requests.clear()
            assertEquals(ActivationDecision.NO_CHANGE, assertIs<CheckOutcome.Current>(device.check()).decision, "a check without a new update")
            assertEquals(listOf(STAND_CATALOG_PATH), origin.requests.toList(), "a check without a new update fetches the catalog only")

            val savedOnA = device.saveBuild(A_BUILD, "PRIEST")
            val before = device.runtime.snapshot.value
            val recorder = RecordingAssetSource(RoutingDatasetAssetSource(before))
            val sa = SessionDatasets(before.product, recorder, before)
            val draftOnA = assertIs<DraftCreation.Created>(runBlocking { createClassDraft(device.repository, sa.datasets, ClassToken("MAGE"), sa.product) }).record
            val editorOnA = assertIs<EditorLoad.Ready>(runBlocking { loadEditorSession(device.repository, sa.datasets, draftOnA.localId, sa.icons, sa.descriptions, sa.localization) }).session
            assertEquals(A_BUILD, editorOnA.record.build.clientBuild)
            val library = device.library.payload

            // the staging catalog now names 70450
            origin.snapshot = "2-b-staged"
            origin.requests.clear()
            device.bundleSource.reads.clear()
            val activated = assertIs<CheckOutcome.Activated>(device.check())
            assertEquals(B_ID, activated.releaseId)
            assertEquals(B_ID, device.active())
            assertEquals(B_BUILD, device.product())
            assertEquals(library, device.library.payload, "the activation changes no saved build")

            // signature and integrity: the publisher's manifest bytes, every installed file re-hashed against its pin
            assertContentEquals(stand.publishedManifest(B_ID), device.directory.resolve("v1/releases/$B_ID/release.json").readBytes())
            val files = releaseFiles(B_ID)
            val corrupt = files.filter { (path, hex) -> sha256Hex(device.directory.resolve("v1/releases/$B_ID/$path").readBytes()) != hex }
            assertTrue(corrupt.isEmpty(), "installed files match the signed manifest: $corrupt")

            // what came over the network: exactly the files the installed 70374 release does not hold
            val aHashes = releaseFiles(A_ID).values.toSet()
            val fromNetwork = files.filterValues { it !in aHashes }
            val fromInstalled = files.filterValues { it in aHashes }
            assertTrue(origin.requests.all { it == STAND_CATALOG_PATH || it.startsWith(STAND_BLOB_PREFIX) }, "only contract paths: ${origin.requests.toSet()}")
            assertTrue(origin.base.startsWith("https://"))
            assertEquals(fromNetwork.values.toSet() + stand.manifestHex(B_ID), origin.blobRequests().toSet(), "the blobs fetched over HTTPS")
            assertEquals(fromNetwork.size, activated.report.downloaded)
            assertEquals(fromInstalled.size, activated.report.copiedFromInstalled)
            assertEquals(0, activated.report.copiedFromBundle, "70450 is not bundled")
            assertTrue(device.bundleSource.reads.none { "/$B_BUILD/" in it }, "no 70450 file comes from the bundle")
            assertTrue(fromNetwork.keys.any { it.startsWith("dataset/") }, "talent data came over the network")
            val blobRequests = origin.blobRequests().size

            // the prepared candidate reached the device unchanged (when the preparation ran)
            val prepared = out!!.resolve("prepare/out/ota/$B_ID/release.json").takeIf { it.isFile }
            if (prepared != null) assertEquals(StagingStand.releaseFiles(prepared.readBytes()), files, "the device installed the prepared files")

            // a new editor uses 70450, read from the downloaded release
            val after = device.runtime.snapshot.value
            val newRecorder = RecordingAssetSource(RoutingDatasetAssetSource(after))
            val sb = SessionDatasets(after.product, newRecorder, after)
            val draftOnB = assertIs<DraftCreation.Created>(runBlocking { createClassDraft(device.repository, sb.datasets, ClassToken("MAGE"), sb.product) }).record
            assertEquals(B_BUILD, draftOnB.build.clientBuild)
            assertIs<EditorLoad.Ready>(runBlocking { loadEditorSession(device.repository, sb.datasets, draftOnB.localId, sb.icons, sb.descriptions, sb.localization) })
            assertTrue(newRecorder.reads.isNotEmpty() && newRecorder.reads.all { "/$B_BUILD/" in it }, "the new editor reads 70450 only")
            assertTrue(device.bundleSource.reads.none { "/$B_BUILD/" in it })
            val mageB = device.directory.resolve("v1/releases/$B_ID/dataset/forever/$B_BUILD/mage.json").readBytes()
            assertFalse(mageB.contentEquals(OtaTestRepo.repositoryFile("dataset/forever/$A_BUILD/mage.json").readBytes()), "the mage tree of 70450 is not 70374's")

            // the editor opened before the switch keeps reading 70374
            recorder.reads.clear()
            runBlocking {
                val document = (sa.datasets.document(DatasetId(GameId("forever"), A_BUILD, ClassToken("MAGE"))) as DatasetOutcome.Loaded).value
                sa.icons.load(document, document.trees.single())
                sa.descriptions.load(document, document.trees.single())
            }
            assertTrue(recorder.reads.isNotEmpty() && recorder.reads.all { "/$A_BUILD/" in it }, "the open editor reads only 70374")
            opensOnItsOwnBuild(device, savedOnA)

            // a restart keeps the update; the next check downloads nothing
            val libraryAfter = device.library.payload
            val restarted = device.restart()
            assertTrue(restarted.recovery.stateReadable)
            assertEquals(B_ID, device.active())
            assertEquals(B_BUILD, device.product(), "70450 survives the restart")
            opensOnItsOwnBuild(device, savedOnA)
            origin.requests.clear()
            assertEquals(ActivationDecision.NO_CHANGE, assertIs<CheckOutcome.Current>(device.check()).decision)
            assertTrue(origin.blobRequests().isEmpty(), "nothing is downloaded again after the restart")
            assertEquals(libraryAfter, device.library.payload)

            evidence(
                "theClientMovesFrom70374To70450",
                mapOf(
                    "origin" to origin.base,
                    "keyId" to stand.reportString("keyId"),
                    "releaseFiles" to files.size,
                    "downloadedFiles" to activated.report.downloaded,
                    "downloadedBytes" to activated.report.downloadedBytes,
                    "copiedFromInstalled70374" to activated.report.copiedFromInstalled,
                    "copiedFromBundle" to activated.report.copiedFromBundle,
                    "manifestDownloaded" to activated.report.manifestDownloaded,
                    "blobRequests" to blobRequests,
                    "downloadedByRole" to fromNetwork.keys.groupingBy { it.substringBefore('/') }.eachCount(),
                    "copiedByRole" to fromInstalled.keys.groupingBy { it.substringBefore('/') }.eachCount(),
                    "downloadedPaths" to fromNetwork.keys.sorted(),
                    "copiedFromInstalledPaths" to fromInstalled.keys.sorted(),
                    "preparedReleaseCompared" to (prepared != null),
                    "activeAfterRestart" to device.active(),
                ),
            )
        }
    }

    @Test
    fun anInterruptedPatchDownloadResumesAfterARestart() = StandOrigin(stand.dir).use { origin ->
        historicalDevice(origin).test { device ->
            origin.snapshot = "1-a-staged"
            assertEquals(A_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            val saved = device.saveBuild(A_BUILD, "HUNTER")
            val library = device.library.payload

            // the connection drops after 120 blobs of 70450
            origin.snapshot = "2-b-staged"
            val served = AtomicInteger()
            origin.fault = { path, _ -> if (path.startsWith(STAND_BLOB_PREFIX) && served.incrementAndGet() > 120) StandFault.Truncate(keepBytes = 100) else null }
            assertIs<OtaFailure.Network>(assertIs<CheckOutcome.Failed>(device.check()).failure)
            assertEquals(A_ID, device.active())
            assertEquals(A_BUILD, device.product())
            assertEquals(library, device.library.payload)
            assertFalse(device.directory.resolve("v1/releases/$B_ID").exists(), "nothing installed")

            // the process dies; a cold start recovers on 70374 and keeps the verified files
            val restarted = device.restart()
            assertTrue(restarted.recovery.stateReadable)
            assertEquals(A_ID, device.active())
            assertEquals(A_BUILD, device.product())
            assertTrue(device.directory.walkTopDown().none { it.name.endsWith(".part") }, "no .part survives")
            val staged = device.stagedHashes(B_ID)
            assertTrue(staged.size >= 100, "verified files kept for the resume: ${staged.size}")
            assertEquals(library, device.library.payload, "the interruption and the restart change no saved build")
            opensOnItsOwnBuild(device, saved)
            val opened = device.library.payload // opening sets lastOpenedAt, the only change a run may show

            // the network is back: only the missing files are fetched
            origin.fault = null
            origin.requests.clear()
            val resumed = assertIs<CheckOutcome.Activated>(device.check())
            assertEquals(B_ID, resumed.releaseId)
            assertTrue(origin.blobRequests().none { it in staged }, "no verified file is downloaded again")
            assertEquals(B_BUILD, device.product())
            assertEquals(opened, device.library.payload)
            opensOnItsOwnBuild(device, saved)
            evidence(
                "anInterruptedPatchDownloadResumesAfterARestart",
                mapOf(
                    "firstAttempt" to "Network after 120 blobs",
                    "verifiedFilesKept" to staged.size,
                    "resumeReusedStaged" to resumed.report.reusedStaged,
                    "resumeDownloaded" to resumed.report.downloaded,
                    "resumeBlobRequests" to origin.blobRequests().size,
                ),
            )
        }
    }

    @Test
    fun noNetworkWhenThePatchAppearsKeeps70374() = StandOrigin(stand.dir).use { origin ->
        historicalDevice(origin).test { device ->
            origin.snapshot = "1-a-staged"
            assertEquals(A_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            val saved = device.saveBuild(A_BUILD, "SHAMAN")
            val library = device.library.payload
            val tree = device.tree()

            origin.snapshot = "2-b-staged"
            origin.fault = { _, _ -> StandFault.Truncate(0) } // every connection drops, the catalog's too
            assertIs<OtaFailure.Network>(assertIs<CheckOutcome.Failed>(device.check()).failure)
            assertEquals(A_ID, device.active())
            assertEquals(A_BUILD, device.product())
            assertEquals(1L, device.runtime.activation.currentState().catalog?.sequence, "the new catalog was never accepted")
            assertEquals(library, device.library.payload)
            assertEquals(tree, device.tree(), "the OTA store is byte-identical")
            opensOnItsOwnBuild(device, saved)

            origin.fault = null
            assertEquals(B_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId, "the next check with a network installs 70450")
            evidence("noNetworkWhenThePatchAppearsKeeps70374", mapOf("offlineFailure" to "Network", "storeUnchanged" to true, "thenActivated" to B_ID))
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.ota.replay"
    }
}
