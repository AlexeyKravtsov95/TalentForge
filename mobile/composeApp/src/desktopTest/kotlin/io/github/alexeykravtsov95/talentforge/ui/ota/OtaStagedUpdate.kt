package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.allocation.toBuildState
import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.ota.ActivationDecision
import io.github.alexeykravtsov95.talentforge.data.ota.CanonicalBase64
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One staged data update, base → candidate, met by the app as it was **before** the update
 * (M5.OTA.AUTO.2 historical replay, M5.OTA.AUTO.4 candidate verification).
 *
 * The device: [base] is its only bundled build and its product; the candidate is neither in the
 * bundle nor in the installed OTA store; OTA is enabled for the staging channel only, resolved by
 * [OtaConfig.resolve] like an app build. Everything else is the real client: the
 * `HttpURLConnection` fetcher over HTTPS trusting the stand's CA only, `java.security`
 * signatures, the `java.nio` store, the app's build repository and editor sessions. The origin
 * serves the exact bytes the real publisher staged with the stand's `tf-staging-local-*` key.
 *
 * The pair is the one the stand published (`scripts/ota_staging_stand.py prepare --base
 * --candidate`, `report.json` `builds`). [dataRoot] holds the base's committed data the bundle is
 * read from: the checkout, or the data of the verified commit exported by
 * `scripts/ota_staging_verify.py`. Every flow returns its evidence; the caller writes it.
 */
class StagedUpdate(val stand: StagingStand, val dataRoot: File = OtaTestRepo.root) {
    val base: String = stand.reportString("builds", "a")
    val candidate: String = stand.reportString("builds", "b")
    val baseId: String = stand.reportString("releases", "a")
    val candidateId: String = stand.reportString("releases", "b")

    /** path → (SHA-256 hex, byte length) of a release the publisher staged. */
    fun releaseEntries(releaseId: String): Map<String, Pair<String, Long>> = StagingStand.releaseEntries(stand.publishedManifest(releaseId))

    /** The candidate's files the installed base release does not hold: what must come over the network. */
    val missingFromBase: Map<String, String> by lazy {
        val baseHashes = releaseEntries(baseId).values.map { it.first }.toSet()
        releaseEntries(candidateId).mapValues { it.value.first }.filterValues { it !in baseHashes }
    }

    /** The app before the update: [base] bundled and the product, the staging channel, the real adapters. */
    fun device(origin: StandOrigin): OtaDevice =
        OtaDevice(bundledBuilds = setOf(base), config = stand.config(origin.base), fetcher = stand.fetcher(), dataRoot = dataRoot).also {
            it.clock.now = stand.publishedAt.plusSeconds(60)
        }

    // ------------------------------------------------------------------ device facts

    fun OtaDevice.active(): String? = runtime.activation.currentState().active

    fun OtaDevice.product(): String = runtime.snapshot.value.product.clientBuild

    private fun bundle(build: String) = DatasetBundleId(GameId("forever"), build)

    /** path → (SHA-256 hex, byte length) of every file installed under `releases/<releaseId>` but its manifest. */
    fun OtaDevice.installedEntries(releaseId: String): Map<String, Pair<String, Long>> {
        val root = directory.resolve("v1/releases/$releaseId")
        return root.walkTopDown().filter { it.isFile && it != root.resolve("release.json") }.associate {
            val bytes = it.readBytes()
            it.relativeTo(root).invariantSeparatorsPath to (sha256Hex(bytes) to bytes.size.toLong())
        }
    }

    /** SHA-256 hex of the files verified into `staging/<releaseId>` (the resume set). */
    fun OtaDevice.stagedHashes(releaseId: String): Set<String> {
        val root = directory.resolve("v1/staging/$releaseId")
        val files = releaseEntries(releaseId).mapValues { it.value.first }
        return root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .mapNotNull { files[it.relativeTo(root).invariantSeparatorsPath] }.toSet()
    }

    // ------------------------------------------------------------------ saved builds

    /** Valid, non-empty builds saved on the base before the update: points spent by the real engine. */
    fun saveBaseBuilds(device: OtaDevice): List<LocalBuildRecord> = SAVED.map { (token, points) -> device.saveSpentBuild(base, token, points) }

    /**
     * A saved build opens on its own base data, unchanged: the same build file and allocations, points spent,
     * every asset read from the base. Opening touches `lastOpenedAt` only ([OtaDevice.libraryContent]).
     */
    fun opensUnchanged(device: OtaDevice, record: LocalBuildRecord) {
        val snapshot = device.runtime.snapshot.value
        val recorder = RecordingAssetSource(RoutingDatasetAssetSource(snapshot))
        val session = SessionDatasets(snapshot.product, recorder, snapshot)
        val opened = runBlocking { loadEditorSession(device.repository, session.datasets, record.localId, session.icons, session.descriptions, session.localization) }
        val editor = assertIs<EditorLoad.Ready>(opened, "the saved ${record.build.classToken.value} build opens").session
        assertEquals(record.build, editor.record.build, "the saved build file is unchanged")
        assertEquals(base, editor.record.build.clientBuild, "the saved build opens on $base")
        assertEquals(record.build.toBuildState(), editor.buildState, "the saved allocations are unchanged")
        assertTrue(editor.engine.spentPoints(editor.buildState) > 0, "the saved build is not empty")
        assertTrue(recorder.reads.isNotEmpty() && recorder.reads.all { "/$base/" in it }, "the saved build reads only $base: ${recorder.reads.filterNot { "/$base/" in it }}")
    }

    /** The invariant of a failed update: the saved builds, the active release and the product are untouched. */
    private fun assertNothingChanged(device: OtaDevice, library: String?, active: String?, label: String) {
        assertEquals(library, device.library.payload, "$label: the saved-build library is unchanged")
        assertEquals(active, device.active(), "$label: the active release is unchanged")
        assertEquals(base, device.product(), "$label: new builds stay on $base")
        assertFalse(device.directory.resolve("v1/releases/$candidateId").exists(), "$label: nothing installed")
    }

    // ------------------------------------------------------------------ the update

    /**
     * The whole update on one device: the app starts on the base, finds the candidate in the updated staging
     * catalog, downloads what the installed base lacks over HTTPS (signature and SHA-256 verified), activates it;
     * a new editor works on the candidate, an editor opened before keeps its base snapshot, the saved builds
     * open on the base unchanged; a cold start keeps the candidate and the next check downloads nothing.
     * [prepared]: a release manifest the installed files must also equal (the historical replay's preparation).
     */
    fun moveToCandidate(prepared: File? = null): Map<String, Any?> = StandOrigin(stand.dir).use { origin ->
        device(origin).use { device ->
            // the app before the update: the base is the product, the candidate is in neither the bundle nor the store
            assertEquals(base, device.product(), "before any catalog the app runs on $base")
            assertNull(device.active())
            assertFalse(device.runtime.resolver.isAvailable(bundle(candidate)), "$candidate is not available locally")
            assertFailsWith<AssetNotFoundException> { runBlocking { device.bundleSource.read("dataset/forever/$candidate/manifest.json") } }
            assertFalse(device.directory.resolve("v1/releases/$candidateId").exists(), "$candidate is not installed")

            // staging carries the base only: activated from the bundle, only its manifest is fetched
            origin.snapshot = "1-a-staged"
            assertEquals(baseId, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertEquals(listOf(stand.manifestHex(baseId)), origin.blobRequests(), "every $base file comes from the bundle")
            assertEquals(base, device.product())
            origin.requests.clear()
            assertEquals(ActivationDecision.NO_CHANGE, assertIs<CheckOutcome.Current>(device.check()).decision, "a check without a new update")
            assertEquals(listOf(STAND_CATALOG_PATH), origin.requests.toList(), "a check without a new update fetches the catalog only")

            val saved = saveBaseBuilds(device)
            val content = device.libraryContent()
            val before = device.runtime.snapshot.value
            val recorder = RecordingAssetSource(RoutingDatasetAssetSource(before))
            val sa = SessionDatasets(before.product, recorder, before)
            val draftOnBase = assertIs<DraftCreation.Created>(runBlocking { createClassDraft(device.repository, sa.datasets, ClassToken(EDITOR_CLASS), sa.product) }).record
            val editorOnBase = assertIs<EditorLoad.Ready>(runBlocking { loadEditorSession(device.repository, sa.datasets, draftOnBase.localId, sa.icons, sa.descriptions, sa.localization) }).session
            assertEquals(base, editorOnBase.record.build.clientBuild)
            val library = device.library.payload

            // the staging catalog now names the candidate
            origin.snapshot = "2-b-staged"
            origin.requests.clear()
            device.bundleSource.reads.clear()
            val activated = assertIs<CheckOutcome.Activated>(device.check())
            assertEquals(candidateId, activated.releaseId)
            assertEquals(candidateId, device.active())
            assertEquals(candidate, device.product())
            assertEquals(library, device.library.payload, "the activation changes no saved build")

            // the installed copy: the publisher's manifest bytes, every file with the pinned path, SHA-256 and size
            assertContentEquals(stand.publishedManifest(candidateId), device.directory.resolve("v1/releases/$candidateId/release.json").readBytes())
            val published = releaseEntries(candidateId)
            val installed = device.installedEntries(candidateId)
            assertEquals(published, installed, "the installed files are exactly the published ones (path, SHA-256, size)")

            // what came over the network: exactly the files the installed base release does not hold
            val fromInstalled = published.keys - missingFromBase.keys
            assertTrue(origin.requests.all { it == STAND_CATALOG_PATH || it.startsWith(STAND_BLOB_PREFIX) }, "only contract paths: ${origin.requests.toSet()}")
            assertTrue(origin.base.startsWith("https://"))
            assertEquals(missingFromBase.values.toSet() + stand.manifestHex(candidateId), origin.blobRequests().toSet(), "the blobs fetched over HTTPS")
            assertEquals(missingFromBase.size, activated.report.downloaded)
            assertEquals(fromInstalled.size, activated.report.copiedFromInstalled)
            assertEquals(0, activated.report.copiedFromBundle, "$candidate is not bundled")
            assertTrue(device.bundleSource.reads.none { "/$candidate/" in it }, "no $candidate file comes from the bundle")
            assertTrue(missingFromBase.keys.any { it.startsWith("dataset/") }, "talent data came over the network")
            val blobRequests = origin.blobRequests().size

            // the prepared candidate reached the device unchanged (when a preparation ran)
            if (prepared != null) assertEquals(StagingStand.releaseFiles(prepared.readBytes()), published.mapValues { it.value.first }, "the device installed the prepared files")

            // a new editor uses the candidate, read from the downloaded release
            val after = device.runtime.snapshot.value
            val newRecorder = RecordingAssetSource(RoutingDatasetAssetSource(after))
            val sb = SessionDatasets(after.product, newRecorder, after)
            val draftOnCandidate = assertIs<DraftCreation.Created>(runBlocking { createClassDraft(device.repository, sb.datasets, ClassToken(EDITOR_CLASS), sb.product) }).record
            assertEquals(candidate, draftOnCandidate.build.clientBuild)
            val newEditor = assertIs<EditorLoad.Ready>(runBlocking { loadEditorSession(device.repository, sb.datasets, draftOnCandidate.localId, sb.icons, sb.descriptions, sb.localization) }).session
            assertEquals(candidate, newEditor.engine.document.clientBuild, "the new editor's document is the candidate's")
            assertTrue(newRecorder.reads.isNotEmpty() && newRecorder.reads.all { "/$candidate/" in it }, "the new editor reads $candidate only")
            assertTrue(device.bundleSource.reads.none { "/$candidate/" in it })

            // the editor opened before the switch keeps reading the base
            recorder.reads.clear()
            runBlocking {
                val document = (sa.datasets.document(DatasetId(GameId("forever"), base, ClassToken(EDITOR_CLASS))) as DatasetOutcome.Loaded).value
                assertEquals(base, document.clientBuild)
                sa.icons.load(document, document.trees.single())
                sa.descriptions.load(document, document.trees.single())
            }
            assertTrue(recorder.reads.isNotEmpty() && recorder.reads.all { "/$base/" in it }, "the open editor reads only $base")
            assertEquals(base, editorOnBase.engine.document.clientBuild, "the open editor's document is still the base's")

            // the saved builds open on the base, unchanged
            saved.forEach { opensUnchanged(device, it) }
            val savedIds = saved.map(LocalBuildRecord::localId).toSet()
            assertEquals(content, device.libraryContent().filter { it.localId in savedIds }, "the saved builds are unchanged")

            // a cold start keeps the update; the next check downloads nothing
            val contentAfter = device.libraryContent()
            val restarted = device.restart()
            assertTrue(restarted.recovery.stateReadable)
            assertEquals(candidateId, device.active())
            assertEquals(candidate, device.product(), "$candidate survives the restart")
            assertEquals(installed, device.installedEntries(candidateId), "the installed files survive the restart")
            saved.forEach { opensUnchanged(device, it) }
            origin.requests.clear()
            assertEquals(ActivationDecision.NO_CHANGE, assertIs<CheckOutcome.Current>(device.check()).decision)
            assertEquals(listOf(STAND_CATALOG_PATH), origin.requests.toList(), "the check after the restart fetches the catalog only")
            assertEquals(contentAfter, device.libraryContent())

            mapOf(
                "base" to base,
                "candidate" to candidate,
                "baseRelease" to baseId,
                "candidateRelease" to candidateId,
                "commit" to stand.reportString("commit"),
                "origin" to origin.base,
                "keyId" to stand.reportString("keyId"),
                "releaseFiles" to published.size,
                "downloadedFiles" to activated.report.downloaded,
                "downloadedBytes" to activated.report.downloadedBytes,
                "copiedFromInstalledBase" to activated.report.copiedFromInstalled,
                "copiedFromBundle" to activated.report.copiedFromBundle,
                "manifestDownloaded" to activated.report.manifestDownloaded,
                "blobRequests" to blobRequests,
                "downloadedByRole" to missingFromBase.keys.groupingBy { it.substringBefore('/') }.eachCount(),
                "copiedByRole" to fromInstalled.groupingBy { it.substringBefore('/') }.eachCount(),
                "downloadedPaths" to missingFromBase.keys.sorted(),
                "installedFiles" to installed.toSortedMap().mapValues { (_, entry) -> listOf("sha256:${entry.first}", entry.second) },
                "savedBuilds" to saved.map { mapOf("class" to it.build.classToken.value, "clientBuild" to it.build.clientBuild, "allocations" to it.build.allocations.size) },
                "preparedReleaseCompared" to (prepared != null),
                "activeAfterRestart" to device.active(),
                "blobRequestsAfterRestart" to origin.blobRequests().size,
            )
        }
    }

    /**
     * The connection drops half-way through the candidate's downloads, then the process dies: the base stays
     * active, the saved builds unchanged; after a cold start the network is back and only the files that were
     * not verified yet are fetched.
     */
    fun interruptedDownloadResumesAfterARestart(): Map<String, Any?> = StandOrigin(stand.dir).use { origin ->
        device(origin).use { device ->
            val missing = missingFromBase.values.toSet()
            assertTrue(missing.size >= 2, "an interruption needs at least two files to download, $candidate has ${missing.size}")
            origin.snapshot = "1-a-staged"
            assertEquals(baseId, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            val saved = saveBaseBuilds(device)
            val library = device.library.payload
            val content = device.libraryContent()

            // the connection drops after the manifest and half of the missing files
            val cutAfter = 1 + missing.size / 2
            origin.snapshot = "2-b-staged"
            val served = AtomicInteger()
            origin.fault = { path, _ -> if (path.startsWith(STAND_BLOB_PREFIX) && served.incrementAndGet() > cutAfter) StandFault.Truncate(keepBytes = 100) else null }
            assertIs<OtaFailure.Network>(assertIs<CheckOutcome.Failed>(device.check()).failure)
            assertNothingChanged(device, library, baseId, "lost connection")

            // the process dies; a cold start recovers on the base and keeps the verified files
            val restarted = device.restart()
            assertTrue(restarted.recovery.stateReadable)
            assertNothingChanged(device, library, baseId, "cold start after the interruption")
            assertTrue(device.directory.walkTopDown().none { it.name.endsWith(".part") }, "no .part survives")
            // the staging directory holds the verified downloads and the files copied from the installed base
            val staged = device.stagedHashes(candidateId)
            assertTrue(staged.any { it in missing }, "verified downloads kept for the resume: ${staged.count { it in missing }} of ${staged.size}")
            saved.forEach { opensUnchanged(device, it) }
            assertEquals(content, device.libraryContent(), "the interruption and the restart change no saved build")

            // the network is back: only the missing files are fetched
            origin.fault = null
            origin.requests.clear()
            val resumed = assertIs<CheckOutcome.Activated>(device.check())
            assertEquals(candidateId, resumed.releaseId)
            assertTrue(origin.blobRequests().none { it in staged }, "no verified file is downloaded again")
            assertEquals(missing - staged, origin.blobRequests().toSet() - stand.manifestHex(candidateId), "the resume fetches exactly the rest")
            assertEquals(candidate, device.product())
            assertEquals(releaseEntries(candidateId), device.installedEntries(candidateId))
            saved.forEach { opensUnchanged(device, it) }
            assertEquals(content, device.libraryContent())
            mapOf(
                "base" to base,
                "candidate" to candidate,
                "firstAttempt" to "Network after $cutAfter blob responses",
                "filesToDownload" to missing.size,
                "verifiedFilesKept" to staged.size,
                "verifiedDownloadsKept" to staged.count { it in missing },
                "resumeReusedStaged" to resumed.report.reusedStaged,
                "resumeDownloaded" to resumed.report.downloaded,
                "resumeBlobRequests" to origin.blobRequests().size,
            )
        }
    }

    /** Every connection drops when the candidate appears: the base stays, the store is byte-identical, a later check installs it. */
    fun noNetworkKeepsTheBase(): Map<String, Any?> = StandOrigin(stand.dir).use { origin ->
        device(origin).use { device ->
            origin.snapshot = "1-a-staged"
            assertEquals(baseId, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            val saved = saveBaseBuilds(device)
            val library = device.library.payload
            val tree = device.tree()

            origin.snapshot = "2-b-staged"
            origin.fault = { _, _ -> StandFault.Truncate(0) } // every connection drops, the catalog's too
            assertIs<OtaFailure.Network>(assertIs<CheckOutcome.Failed>(device.check()).failure)
            assertNothingChanged(device, library, baseId, "offline")
            assertEquals(1L, device.runtime.activation.currentState().catalog?.sequence, "the new catalog was never accepted")
            assertEquals(tree, device.tree(), "the OTA store is byte-identical")
            saved.forEach { opensUnchanged(device, it) }

            origin.fault = null
            assertEquals(candidateId, assertIs<CheckOutcome.Activated>(device.check()).releaseId, "the next check with a network installs $candidate")
            mapOf("base" to base, "candidate" to candidate, "offlineFailure" to "Network", "storeUnchanged" to true, "thenActivated" to candidateId)
        }
    }

    /**
     * A corrupt publication of the candidate - its catalog signature, its release manifest or one of its files -
     * is refused with a typed failure and changes nothing; with the corruption gone the next check installs it.
     */
    fun corruptPublicationChangesNothing(what: Corruption): Map<String, Any?> = StandOrigin(stand.dir).use { origin ->
        val store = stand.dir.resolve("stores/2-b-staged")
        val fault: (String) -> StandFault?
        val expected: KClass<out OtaFailure>
        val target: String
        when (what) {
            Corruption.SIGNATURE -> {
                val envelope = Json.parseToJsonElement(store.resolve(STAND_CATALOG_PATH).readText()).jsonObject
                val signature = envelope.getValue("signatures").jsonArray.single().jsonObject
                val der = CanonicalBase64.decodeOrNull(signature.getValue("sig").jsonPrimitive.content)!!
                val tampered = der.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
                val badSignature = JsonObject(signature + ("sig" to JsonPrimitive(CanonicalBase64.encode(tampered))))
                val bad = JsonObject(envelope + ("signatures" to JsonArray(listOf(badSignature)))).toString().encodeToByteArray()
                fault = { path -> if (path == STAND_CATALOG_PATH) StandFault.Replace(bad) else null }
                expected = OtaFailure.SignatureInvalid::class
                target = STAND_CATALOG_PATH
            }
            Corruption.MANIFEST -> {
                val hex = stand.manifestHex(candidateId)
                val bad = flipped(stand.publishedManifest(candidateId), 100)
                fault = { path -> if (path == STAND_BLOB_PREFIX + hex) StandFault.Replace(bad) else null }
                expected = OtaFailure.ManifestHashMismatch::class
                target = "release.json"
            }
            Corruption.BLOB -> {
                val path = missingFromBase.keys.filter { it.startsWith("dataset/") }.minOrNull() ?: missingFromBase.keys.min()
                val hex = missingFromBase.getValue(path)
                val bad = flipped(store.resolve(STAND_BLOB_PREFIX + hex).readBytes(), 10)
                fault = { request -> if (request == STAND_BLOB_PREFIX + hex) StandFault.Replace(bad) else null }
                expected = OtaFailure.BlobHashMismatch::class
                target = path
            }
        }
        device(origin).use { device ->
            origin.snapshot = "1-a-staged"
            assertEquals(baseId, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            val saved = saveBaseBuilds(device)
            val library = device.library.payload
            val content = device.libraryContent()
            val sequence = device.runtime.activation.currentState().catalog?.sequence

            origin.snapshot = "2-b-staged"
            origin.fault = { path, _ -> fault(path) }
            val failure = assertIs<CheckOutcome.Failed>(device.check()).failure
            assertTrue(expected.isInstance(failure), "${what.label}: expected ${expected.simpleName}, got $failure")
            assertNothingChanged(device, library, baseId, what.label)
            if (what == Corruption.SIGNATURE) assertEquals(sequence, device.runtime.activation.currentState().catalog?.sequence, "an unverified catalog is never accepted")
            saved.forEach { opensUnchanged(device, it) }
            assertEquals(content, device.libraryContent(), "${what.label}: the saved builds are unchanged")

            // with the corruption gone the next check installs the candidate
            origin.fault = null
            assertEquals(candidateId, assertIs<CheckOutcome.Activated>(device.check()).releaseId, "${what.label}: retried")
            assertEquals(releaseEntries(candidateId), device.installedEntries(candidateId))
            saved.forEach { opensUnchanged(device, it) }
            mapOf(
                "base" to base,
                "candidate" to candidate,
                "corrupted" to what.label,
                "target" to target,
                "failure" to failure::class.simpleName,
                "nothingChanged" to true,
                "thenActivated" to candidateId,
            )
        }
    }

    enum class Corruption(val label: String) {
        SIGNATURE("catalog signature"),
        MANIFEST("release manifest"),
        BLOB("blob"),
    }

    companion object {
        /** The saved builds of every flow: classes and the points the real engine spends on them. */
        val SAVED: List<Pair<String, Int>> = listOf("MAGE" to 21, "PRIEST" to 11, "WARRIOR" to 5)

        /** The class of the editors opened around the activation (its tree changed between 70374 and 70450). */
        const val EDITOR_CLASS = "MAGE"

        private fun flipped(bytes: ByteArray, at: Int) = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x20).toByte() }

        /** Writes [facts] to `<dir>/<test>.json` (the orchestrator reads it back). */
        fun writeEvidence(dir: File, test: String, facts: Map<String, Any?>) {
            fun json(value: Any?): JsonElement = when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v) })
                is Iterable<*> -> JsonArray(value.map(::json))
                else -> JsonPrimitive(value.toString())
            }
            dir.mkdirs()
            dir.resolve("$test.json").writeText(json(facts).toString() + "\n")
            println("[staged-update] $test: ${facts.filterKeys { it != "installedFiles" }}")
        }
    }
}
