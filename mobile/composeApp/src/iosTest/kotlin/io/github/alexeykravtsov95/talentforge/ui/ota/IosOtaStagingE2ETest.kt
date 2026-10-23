@file:OptIn(ExperimentalForeignApi::class)

package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.ota.ActivationDecision
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.StagingPolicy
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import io.github.alexeykravtsov95.talentforge.ui.library.loadNewBuildChoices
import kotlin.concurrent.AtomicInt
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSURLErrorDomain
import platform.posix.SIGKILL
import platform.posix.getpid
import platform.posix.kill

/**
 * M5.IOS.2 — the OTA staging E2E **on the iOS Simulator**: the production iOS client stack
 * (`NSURLSession` with App Transport Security defaults and the system trust store,
 * Security.framework ECDSA, the Application Support store, `NSUserDefaults` saved builds, the app's
 * Compose resources as the bundle) against the real publisher's releases served over HTTPS by
 * `scripts/ios_ota_e2e.py`, which installs its ephemeral CA as a root of the test simulator — TLS
 * is verified, never skipped. 70374 (A) is the bundle of an app version that predates 70450 (B), so
 * A → B is a real update downloaded over the network, not a same-version re-activation.
 *
 * Every phase runs in its own simulator process (`xcrun simctl spawn`), started by the script; a
 * `…Phase1` leaves its OTA directory and saved builds for the `…Phase2` that cold-starts on them,
 * and `terminatePhase1KilledMidDownload` ends its own process by `SIGKILL` mid-download, the way
 * iOS terminates a suspended app. The class is excluded from `iosSimulatorArm64Test` (it needs
 * the origin) and fails, never skips, without `TF_IOS_OTA_E2E`.
 *
 * The overriding rule of every negative case (test-plan §1): the saved-build library is unchanged
 * and the dataset that was active stays usable.
 */
class IosOtaStagingE2ETest {
    private val e2e by lazy { E2E() }
    private val stand get() = e2e.stand

    private fun bundle(build: String) = DatasetBundleId(GameId("forever"), build)

    private fun failure(outcome: CheckOutcome): OtaFailure = assertIs<CheckOutcome.Failed>(outcome, "expected a failed check, got $outcome").failure

    /** The outcome as an activation; any other outcome fails with its failure named (CI diagnosis). */
    private fun activated(outcome: CheckOutcome, label: String = "check"): CheckOutcome.Activated =
        outcome as? CheckOutcome.Activated ?: fail("$label: expected an activation, got $outcome")

    private fun opensOnItsOwnBuild(device: IosOtaDevice, session: SessionDatasets, record: LocalBuildRecord) {
        val opened = runBlocking { loadEditorSession(device.repository, session.datasets, record.localId, session.icons, session.descriptions, session.localization) }
        assertEquals(record.build, assertIs<EditorLoad.Ready>(opened, "${record.build.clientBuild} build").session.record.build, "the saved build opens unchanged on ${record.build.clientBuild}")
    }

    /** The invariant of every negative scenario: the library and the active dataset are untouched. */
    private fun assertNothingChanged(device: IosOtaDevice, library: String?, active: String?, product: String, label: String) {
        assertEquals(library, device.library.read(), "$label: the saved-build library is unchanged")
        assertEquals(active, device.active(), "$label: the active release is unchanged")
        assertEquals(product, device.product(), "$label: new builds stay on $product")
        assertEquals(emptyList(), device.partFiles(), "$label: no partial file is left")
    }

    // ------------------------------------------------------------------ configuration

    @Test
    fun theAppConfigurationRefusesProductionTestAndPlaintextSettings() {
        val keys = stand.stagingKeys
        assertTrue(keys.isNotEmpty() && keys.all { it.keyId.startsWith("tf-staging-local-") })
        // this test binary is built without OTA settings: the kill switch, exactly the shipped OTA OFF app
        assertEquals(OtaConfig.Resolution.Disabled(OtaConfig.DisabledReason.NOT_ENABLED), OtaConfig.resolve(GeneratedOtaSettings.iosProduction))
        assertEquals(OtaConfig.Resolution.Disabled(OtaConfig.DisabledReason.NOT_ENABLED), OtaConfig.resolve(GeneratedOtaSettings.iosStaging))
        assertNull(IosOta.runtime(), "OTA OFF: no runtime, so no request is possible")
        // the staging key is refused by a production configuration, test- keys by every one, http:// always
        assertEquals(
            OtaConfig.Resolution.Disabled(OtaConfig.DisabledReason.KEY_NOT_FOR_CHANNEL),
            OtaConfig.resolve(OtaPlatformSettings(true, OtaChannel.PRODUCTION, "https://ota.mythweld.com", keys + keys.map { it.copy(keyId = it.keyId + "b") })),
        )
        assertEquals(
            OtaConfig.Resolution.Disabled(OtaConfig.DisabledReason.TEST_KEY_IN_APP_KEYRING),
            OtaConfig.resolve(OtaPlatformSettings(true, OtaChannel.STAGING, e2e.settings.origin, keys.map { it.copy(keyId = "test-primary") })),
        )
        val plaintext = e2e.settings.origin.replace("https://", "http://")
        assertEquals(
            OtaConfig.Resolution.Disabled(OtaConfig.DisabledReason.BAD_BASE_URL),
            OtaConfig.resolve(OtaPlatformSettings(true, OtaChannel.STAGING, plaintext, keys)),
        )
        val plain = runBlocking { PlatformBlobFetcher("TalentForge/ios-e2e").fetch("$plaintext/s/x/$E2E_CATALOG_PATH", 1024) }
        assertEquals(FetchResult.NetworkError("refusing a non-HTTPS URL", retryable = false), plain)
        e2e.result("01-key-classes", "PASSED kill switch: the binary without OTA settings resolves iosProduction and iosStaging to NOT_ENABLED and IosOta.runtime() = null; the stand key ${stand.keyId} is refused in production (KEY_NOT_FOR_CHANNEL), test- keys in staging (TEST_KEY_IN_APP_KEYRING), http:// (BAD_BASE_URL, and the fetcher refuses it before connecting)")
    }

    // ------------------------------------------------------------------ 70374 -> 70450

    @Test
    fun aToBOverHttpsKeepsTheOpenEditorAndTheSavedBuilds() {
        val device = e2e.device("update")
        try {
            // the app starts on its bundled data, with no network request before a check
            assertEquals(E2E_A_BUILD, device.product())
            assertNull(device.active())
            assertEquals(emptyList(), device.fetcher.requests(), "no network on the startup path")

            device.serve("1-a-staged")
            assertEquals(E2E_A_ID, activated(device.check()).releaseId)
            assertEquals(listOf(stand.manifestHex(E2E_A_ID)), device.blobRequests(), "every file of A is reused from the bundle")
            val savedOnA = device.saveBuild(E2E_A_BUILD, "PRIEST")

            // an editor opened on A before B arrives
            val before = device.runtime.snapshot.value
            val recorder = RecordingAssetSource(RoutingDatasetAssetSource(before))
            val onA = SessionDatasets(before.product, recorder, before)
            val draftA = assertIs<DraftCreation.Created>(runBlocking { createClassDraft(device.repository, onA.datasets, ClassToken("WARRIOR"), onA.product) }).record
            val editor = assertIs<EditorLoad.Ready>(runBlocking { loadEditorSession(device.repository, onA.datasets, draftA.localId, onA.icons, onA.descriptions, onA.localization) }).session
            assertEquals(E2E_A_BUILD, editor.record.build.clientBuild)
            val library = device.library.read()

            // B: downloaded over HTTPS, verified with Security.framework, installed atomically, activated
            device.serve("2-b-staged")
            device.fetcher.clear()
            val activated = activated(device.check())
            assertEquals(E2E_B_ID, activated.releaseId)
            assertContentEquals(stand.manifest(E2E_B_ID), device.installedManifest(E2E_B_ID), "the publisher's exact manifest bytes")
            assertTrue(device.fetcher.requests().all { it.endsWith(E2E_CATALOG_PATH) || E2E_BLOB_PREFIX in it }, "only contract paths")
            assertTrue(device.fetcher.requests().all { it.startsWith("https://") }, "HTTPS only")
            assertTrue(activated.report.downloaded > 300, "B came over the network: ${activated.report}")
            assertEquals(library, device.library.read(), "the activation writes no saved build")
            assertEquals(E2E_B_ID, device.active())

            // a new editor uses B
            val onB = SessionDatasets.of(device.runtime.snapshot.value)
            assertEquals(E2E_B_BUILD, onB.product.clientBuild)
            assertNotNull(runBlocking { loadNewBuildChoices(onB.datasets, onB.product) })
            val draftB = assertIs<DraftCreation.Created>(runBlocking { createClassDraft(device.repository, onB.datasets, ClassToken("DRUID"), onB.product) }).record
            assertEquals(E2E_B_BUILD, draftB.build.clientBuild)

            // the editor opened before keeps its A snapshot
            recorder.clear()
            runBlocking {
                val document = (onA.datasets.document(DatasetId(GameId("forever"), E2E_A_BUILD, ClassToken("WARRIOR"))) as DatasetOutcome.Loaded).value
                onA.icons.load(document, document.trees.single())
                onA.descriptions.load(document, document.trees.single())
            }
            val reads = recorder.reads()
            assertTrue(reads.isNotEmpty() && reads.all { "/$E2E_A_BUILD/" in it }, "the open editor reads only A: ${reads.filterNot { "/$E2E_A_BUILD/" in it }}")

            // saved builds open on their own build
            opensOnItsOwnBuild(device, onB, savedOnA)
            opensOnItsOwnBuild(device, onB, draftA)

            // the same catalog again: nothing downloaded
            device.fetcher.clear()
            assertEquals(ActivationDecision.NO_CHANGE, assertIs<CheckOutcome.Current>(device.check()).decision)
            assertEquals(1, device.fetcher.requests().size)
            e2e.result(
                "02-update",
                "PASSED 70374 -> 70450 over HTTPS (NSURLSession, Security.framework): A from the bundle (only its manifest fetched); B ${activated.report.downloaded} files / " +
                    "${activated.report.downloadedBytes} bytes downloaded, ${activated.report.copiedFromBundle} from the bundle, the publisher's manifest bytes installed; " +
                    "a new editor and a new DRUID build use 70450; the editor opened before reads only 70374 (${reads.size} reads after the switch); " +
                    "saved 70374 builds open on 70374; the library is unchanged by the update; the same catalog again: NO_CHANGE, 1 request",
            )
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------ relaunch

    @Test
    fun relaunchPhase1InstallsB() {
        val device = e2e.device("relaunch")
        try {
            val savedOnA = device.saveBuild(E2E_A_BUILD, "MAGE")
            device.serve("2-b-staged")
            assertEquals(E2E_B_ID, activated(device.check()).releaseId)
            val savedOnB = device.saveBuild(E2E_B_BUILD, "HUNTER")
            phaseNote("relaunch", savedOnA.localId, savedOnB.localId, device.library.read()!!)
            e2e.result("03-relaunch", "PASSED relaunch phase 1 (process 1): B installed and active; saved builds on 70374 and 70450; the process exits")
        } finally {
            device.close()
        }
    }

    @Test
    fun relaunchPhase2ColdStartsOfflineOnB() {
        val note = readPhaseNote("relaunch")
        val device = e2e.device("relaunch", fresh = false)
        try {
            device.serve("2-b-staged", "drop") // the network is gone
            assertTrue(device.runtime.recovery.stateReadable)
            assertEquals(E2E_B_ID, device.active(), "the update survives the relaunch")
            assertEquals(E2E_B_BUILD, device.product(), "new builds use 70450 without any request")
            assertEquals(emptyList(), device.fetcher.requests(), "the cold start makes no request")
            assertEquals(note.library, device.library.read(), "the saved builds survive the relaunch byte for byte")
            val session = SessionDatasets.of(device.runtime.snapshot.value)
            for (id in listOf(note.a, note.b)) opensOnItsOwnBuild(device, session, device.recordOf(id))
            val opened = device.library.read() // opening a build records lastOpenedAt: the user's own change
            assertIs<OtaFailure.Network>(failure(device.check()))
            assertNothingChanged(device, opened, E2E_B_ID, E2E_B_BUILD, "offline relaunch")
            e2e.result("04-relaunch", "PASSED relaunch phase 2 (a new process, offline): B active from disk with 0 requests at startup, both saved builds open on their own build, a check fails as Network and changes nothing")
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------ termination

    @Test
    fun terminatePhase1KilledMidDownload() {
        val writes = AtomicInt(0)
        val killed = "${e2e.settings.work}/killed/terminatePhase1KilledMidDownload"
        var library: String? = null
        val device = e2e.device("terminate", storeWrapper = { store ->
            KillingStore(store) { path ->
                if (path.endsWith(".part") && writes.incrementAndGet() == 150) {
                    phaseNote("terminate", "", "", library!!)
                    HostFiles.write(killed, "SIGKILL before the 150th file".encodeToByteArray())
                    kill(getpid(), SIGKILL) // what iOS does to a suspended app: no cleanup runs
                }
            }
        })
        device.saveBuild(E2E_A_BUILD, "ROGUE")
        library = device.library.read()
        device.serve("2-b-staged", "slow:10")
        device.check()
        fail("the process was not killed mid-download")
    }

    @Test
    fun terminatePhase2ColdStartResumes() {
        val note = readPhaseNote("terminate")
        val device = e2e.device("terminate", fresh = false)
        try {
            val recovery = device.runtime.recovery
            assertTrue(recovery.stateReadable)
            assertEquals(listOf(E2E_B_ID), recovery.keptStaging, "the verified files of B are kept for the resume")
            assertNothingChanged(device, note.library, null, E2E_A_BUILD, "killed mid-download")
            val staged = device.stagedHashes(E2E_B_ID)
            assertTrue(staged.size >= 100, "verified files survive the kill: ${staged.size}")
            device.serve("2-b-staged")
            val resumed = activated(device.check())
            assertEquals(E2E_B_ID, resumed.releaseId)
            assertTrue(device.blobRequests().none { it in staged }, "no verified file is downloaded again")
            assertEquals(note.library, device.library.read())
            e2e.result("06-terminate", "PASSED SIGKILL mid-download, then a cold start in a new process: recover() deleted ${recovery.deletedPartFiles} partial file(s) and kept ${staged.size} verified files; nothing was active and new builds stayed on 70374; the resume downloaded ${resumed.report.downloaded} files, reused ${resumed.report.reusedStaged}, and activated B; the library is unchanged")
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------ suspension

    @Test
    fun backgroundCancelsTheAttemptAndForegroundResumesIt() {
        val device = e2e.device("suspend")
        try {
            device.saveBuild(E2E_A_BUILD, "PALADIN")
            val library = device.library.read()
            device.serve("2-b-staged", "slow:10")
            device.runtime.coordinator.onAppStarted() // the automatic check after the first frame
            device.waitUntil("60 verified files of B") { device.stagedHashes(E2E_B_ID).size >= 60 }
            device.runtime.coordinator.onBackground() // the app leaves the foreground (ON_STOP)
            device.waitUntil("the attempt to stop") { !device.runtime.status.value.checking }
            device.waitUntil("no partial file") { device.partFiles().isEmpty() }
            assertNothingChanged(device, library, null, E2E_A_BUILD, "background")
            val requestsAtStop = device.fetcher.requests().size
            runBlocking { delay(2_000) }
            assertEquals(requestsAtStop, device.fetcher.requests().size, "no request after the app left the foreground")
            val staged = device.stagedHashes(E2E_B_ID)

            device.serve("2-b-staged")
            device.fetcher.clear()
            device.runtime.coordinator.onForeground()
            device.waitUntil("B to be activated", detail = { "(last outcome: ${device.runtime.status.value.lastOutcome})" }) {
                device.runtime.status.value.lastOutcome is CheckOutcome.Activated
            }
            assertEquals(E2E_B_ID, device.active())
            assertTrue(device.blobRequests().none { it in staged }, "the resume after the foreground downloads no verified file again")
            assertEquals(library, device.library.read())
            e2e.result("07-suspension", "PASSED background mid-download (onBackground, as ON_STOP calls it): the attempt is cancelled cooperatively, no request in the 2 s after it, no partial file, ${staged.size} verified files kept, nothing activated; foreground (onForeground) resumed and activated B without downloading a verified file again")
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------ transport

    @Test
    fun transportFailuresChangeNothing() {
        val talent = stand.releaseFiles(E2E_B_ID).getValue("dataset/forever/$E2E_B_BUILD/druid.json")
        val cases: List<Triple<String, String, (OtaFailure) -> Boolean>> = listOf(
            Triple("catalog 404", "catalog-status:404", { it == OtaFailure.Http(404) }),
            Triple("catalog 500", "catalog-status:500", { it == OtaFailure.Http(500) }),
            Triple("catalog 503", "catalog-status:503", { it == OtaFailure.Http(503) }),
            Triple("catalog redirect", "catalog-redirect", { it == OtaFailure.Http(302) }),
            Triple("catalog connection dropped", "catalog-drop", { it is OtaFailure.Network }),
            Triple("manifest missing on the CDN", "manifest-status:404", { it == OtaFailure.Http(404) }),
            Triple("a file missing on the CDN", "blob-404:$talent", { it == OtaFailure.Http(404) }),
            Triple("every connection dropped", "drop", { it is OtaFailure.Network }),
        )
        val lines = mutableListOf<String>()
        for ((index, case) in cases.withIndex()) {
            val (name, fault, expected) = case
            val device = e2e.device("transport-$index")
            try {
                device.saveBuild(E2E_A_BUILD, "WARLOCK")
                val library = device.library.read()
                device.serve("2-b-staged", fault)
                val failure = failure(device.check())
                assertTrue(expected(failure), "$name: got $failure")
                assertNothingChanged(device, library, null, E2E_A_BUILD, name)
                assertFalse(HostFiles.exists("${device.root}/v1/releases/$E2E_B_ID"), "$name: nothing installed")
                lines += "$name -> $failure"
            } finally {
                device.close()
            }
        }
        // a connection lost in the middle of the files: verified files are kept, the resume needs only the rest
        val lost = e2e.device("transport-lost")
        try {
            val library = lost.library.read()
            lost.serve("2-b-staged", "blob-drop-after:150")
            assertIs<OtaFailure.Network>(failure(lost.check()))
            assertNothingChanged(lost, library, null, E2E_A_BUILD, "lost connection")
            val staged = lost.stagedHashes(E2E_B_ID)
            assertTrue(staged.size >= 100, "verified files kept for resume: ${staged.size}")
            lost.serve("2-b-staged")
            lost.fetcher.clear()
            val resumed = activated(lost.check())
            assertTrue(lost.blobRequests().none { it in staged }, "no verified file is downloaded again")
            lines += "connection lost after 150 files -> Network, ${staged.size} verified files kept; the resume downloaded ${resumed.report.downloaded}, reused ${resumed.report.reusedStaged}"
        } finally {
            lost.close()
        }
        // no internet: the origin is down / the name does not resolve
        for ((name, origin) in listOf("origin down (connection refused)" to e2e.settings.closedOrigin, "no DNS answer" to e2e.settings.unresolvableOrigin)) {
            val device = e2e.device("transport-${name.substringBefore(' ')}", origin = origin)
            try {
                assertIs<OtaFailure.Network>(failure(device.check()), name)
                assertNothingChanged(device, null, null, E2E_A_BUILD, name)
                lines += "$name -> Network"
            } finally {
                device.close()
            }
        }
        e2e.result("08-transport", "PASSED transport (library, active release and product unchanged in every case; nothing installed): " + lines.joinToString("; "))
    }

    @Test
    fun rejectedCertificatesChangeNothing() {
        val lines = mutableListOf<String>()
        for ((name, origin) in listOf(
            "untrusted CA" to e2e.settings.untrustedOrigin,
            "expired certificate" to e2e.settings.expiredOrigin,
            "certificate for another host" to e2e.settings.wrongHostOrigin,
        )) {
            val direct = runBlocking { PlatformBlobFetcher("TalentForge/ios-e2e").fetch("$origin/s/tls/$E2E_CATALOG_PATH", 1L shl 20) }
            val error = assertIs<FetchResult.NetworkError>(direct, name)
            assertEquals(NSURLErrorDomain, error.detail, "$name: an NSURLErrorDomain failure")
            assertFalse(error.retryable, "$name: a rejected certificate is never repeated")
            val device = e2e.device("tls-${name.substringBefore(' ')}", origin = origin)
            try {
                device.saveBuild(E2E_A_BUILD, "SHAMAN")
                val library = device.library.read()
                assertIs<OtaFailure.Network>(failure(device.check()), name)
                assertEquals(1, device.fetcher.requests().size, "$name: not repeated")
                assertNothingChanged(device, library, null, E2E_A_BUILD, name)
                lines += "$name -> NetworkError(NSURLErrorDomain, retryable=false), 1 attempt"
            } finally {
                device.close()
            }
        }
        e2e.result("09-tls", "PASSED TLS (ATS defaults, the simulator's trust store with only the run's CA added): " + lines.joinToString("; "))
    }

    // ------------------------------------------------------------------ integrity

    @Test
    fun signatureManifestAndFileFailuresChangeNothing() {
        val talent = stand.releaseFiles(E2E_B_ID).getValue("dataset/forever/$E2E_B_BUILD/druid.json")
        val catalogFaults = setOf("bad-signature", "payload-flip", "foreign-key", "test-key")
        val cases: List<Triple<String, String, KClass<out OtaFailure>>> = listOf(
            Triple("bad signature", "bad-signature", OtaFailure.SignatureInvalid::class),
            Triple("tampered catalog payload", "payload-flip", OtaFailure.SignatureInvalid::class),
            Triple("catalog signed by a foreign key", "foreign-key", OtaFailure.UntrustedSigner::class),
            Triple("catalog signed by the fixtures' test- key", "test-key", OtaFailure.UntrustedSigner::class),
            Triple("corrupt release manifest", "manifest-flip", OtaFailure.ManifestHashMismatch::class),
            Triple("SHA-256 mismatch of a file", "blob-flip:$talent", OtaFailure.BlobHashMismatch::class),
            Triple("incomplete file", "blob-short:$talent", OtaFailure.BlobSizeMismatch::class),
        )
        val lines = mutableListOf<String>()
        for ((index, case) in cases.withIndex()) {
            val (name, fault, expected) = case
            val device = e2e.device("integrity-$index")
            try {
                val saved = device.saveBuild(E2E_A_BUILD, "DRUID")
                val library = device.library.read()
                device.serve("2-b-staged", fault)
                val failure = failure(device.check())
                assertTrue(expected.isInstance(failure), "$name: expected ${expected.simpleName}, got $failure")
                assertNothingChanged(device, library, null, E2E_A_BUILD, name)
                assertFalse(HostFiles.exists("${device.root}/v1/releases/$E2E_B_ID"), "$name: nothing installed")
                if (fault in catalogFaults) {
                    assertNull(device.runtime.activation.currentState().catalog, "$name: an unverified catalog is never accepted")
                    assertTrue(device.blobRequests().isEmpty(), "$name: no file is requested")
                }
                opensOnItsOwnBuild(device, SessionDatasets.of(device.runtime.snapshot.value), saved)
                device.serve("2-b-staged")
                assertEquals(E2E_B_ID, activated(device.check()).releaseId, "$name: the next clean check installs B")
                lines += "$name -> ${failure::class.simpleName}"
            } finally {
                device.close()
            }
        }
        val expired = e2e.device("integrity-expired")
        try {
            expired.now = stand.expiresAt("2-b-staged").plusSeconds(1)
            expired.serve("2-b-staged")
            assertIs<OtaFailure.CatalogExpired>(failure(expired.check()))
            assertEquals(1, expired.fetcher.requests().size, "nothing else is fetched")
            assertNothingChanged(expired, null, null, E2E_A_BUILD, "expired")
            lines += "catalog past expiresAt -> CatalogExpired, 1 request"
        } finally {
            expired.close()
        }
        e2e.result("10-integrity", "PASSED integrity (library, active release and product unchanged, the saved build opens; the next clean check installs B): " + lines.joinToString("; "))
    }

    // ------------------------------------------------------------------ storage

    @Test
    fun insufficientStorageChangesNothing() {
        var space: SpaceLimitedStore? = null
        val device = e2e.device("space", storeWrapper = { SpaceLimitedStore(it).also { limited -> space = limited } })
        try {
            device.saveBuild(E2E_A_BUILD, "WARRIOR")
            val library = device.library.read()
            device.serve("2-b-staged")
            space!!.free = StagingPolicy.SPACE_MARGIN_BYTES + 1_000
            assertIs<OtaFailure.InsufficientStorage>(failure(device.check()))
            assertEquals(listOf(stand.manifestHex(E2E_B_ID)), device.blobRequests(), "only the manifest, before the space check")
            assertNothingChanged(device, library, null, E2E_A_BUILD, "no space")
            space!!.free = Long.MAX_VALUE
            space!!.failPartWritesAfter = 60 // ENOSPC half-way
            assertIs<OtaFailure.InsufficientStorage>(failure(device.check()))
            assertNothingChanged(device, library, null, E2E_A_BUILD, "ENOSPC")
            space!!.failPartWritesAfter = Int.MAX_VALUE
            assertEquals(E2E_B_ID, activated(device.check()).releaseId)
            assertEquals(library, device.library.read())
            e2e.result("11-disk-space", "PASSED insufficient storage (free space and ENOSPC of the Application Support store under test control): before the first file and ENOSPC half-way both end InsufficientStorage, no partial file, nothing changes; with space the next check installs B")
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------ rollback, data level

    @Test
    fun rollbackAndAnIncompatibleDataLevel() {
        val device = e2e.device("rollback")
        try {
            device.serve("2-b-staged")
            assertEquals(E2E_B_ID, activated(device.check()).releaseId)
            val savedOnB = device.saveBuild(E2E_B_BUILD, "PRIEST")
            val saved = device.library.read()

            device.serve("3-rollback-a")
            device.fetcher.clear()
            assertEquals(E2E_A_ID, activated(device.check()).releaseId)
            assertTrue(device.blobRequests().all { it == stand.manifestHex(E2E_A_ID) }, "A comes from the bundle: ${device.blobRequests()}")
            val state = device.runtime.activation.currentState()
            assertEquals(E2E_A_ID, state.active)
            assertEquals(E2E_B_ID, state.previous)
            assertEquals(3L, state.catalog?.sequence)
            assertEquals(E2E_A_BUILD, device.product())
            assertNotNull(device.runtime.activation.intactRelease(E2E_B_ID), "B is kept: a saved build uses it")
            assertEquals(saved, device.library.read(), "the rollback writes no saved build")
            opensOnItsOwnBuild(device, SessionDatasets.of(device.runtime.snapshot.value), savedOnB)
            val library = device.library.read() // opening a build records lastOpenedAt: the user's own change

            device.serve("4-level-2")
            device.fetcher.clear()
            val newer = assertIs<CheckOutcome.NeedsNewerApp>(device.check())
            assertEquals(2, newer.minDataLevel)
            assertEquals(1, device.fetcher.requests().size, "nothing downloaded for an incompatible release")
            assertEquals(E2E_A_ID, device.active())

            device.serve("2-b-staged") // an older catalog served again
            assertIs<OtaFailure.SequenceRegression>(failure(device.check()))
            assertNothingChanged(device, library, E2E_A_ID, E2E_A_BUILD, "sequence regression")
            e2e.result("12-rollback", "PASSED rollback B -> A (sequence 3): A active from the bundle (no talent file fetched), B kept as previous and the saved 70450 build opens on 70450; minDataLevel 2 -> NeedsNewerApp(${newer.releaseId}), 1 request, A stays; the old sequence 2 again -> SequenceRegression, nothing changes")
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------ corrupted store

    @Test
    fun aCorruptedStoreRecovers() {
        val device = e2e.device("corrupt")
        try {
            device.serve("2-b-staged")
            assertEquals(E2E_B_ID, activated(device.check()).releaseId)
            val saved = device.saveBuild(E2E_A_BUILD, "HUNTER")
            var library = device.library.read()

            // (a) an unreadable state file: bundled data, no release deleted, the next catalog rebuilds the state
            HostFiles.write("${device.root}/v1/state.json", "{ not json".encodeToByteArray())
            val restarted = device.restart()
            assertFalse(restarted.recovery.stateReadable)
            assertEquals(E2E_A_BUILD, device.product(), "an unreadable state starts on the bundle")
            assertTrue(HostFiles.exists("${device.root}/v1/releases/$E2E_B_ID/release.json"), "no release is deleted")
            assertEquals(library, device.library.read())
            opensOnItsOwnBuild(device, SessionDatasets.of(device.runtime.snapshot.value), saved)
            library = device.library.read() // opening a build records lastOpenedAt: the user's own change
            device.fetcher.clear()
            assertEquals(E2E_B_ID, activated(device.check()).releaseId)
            val talentRequests = device.blobRequests().filter { it != stand.manifestHex(E2E_B_ID) }
            assertEquals(emptyList(), talentRequests, "the installed release is reused, not downloaded")

            // (b) a corrupt file of the active release: the reader gets a typed failure, the release is quarantined
            val file = "${device.root}/v1/releases/$E2E_B_ID/dataset/forever/$E2E_B_BUILD/druid.json"
            HostFiles.write(file, HostFiles.read(file).also { it[10] = (it[10].toInt() xor 1).toByte() })
            val read = runBlocking { SessionDatasets.of(device.runtime.snapshot.value).datasets.document(DatasetId(GameId("forever"), E2E_B_BUILD, ClassToken("DRUID"))) }
            assertIs<DatasetOutcome.Failed>(read, "the open session gets the typed failure")
            device.waitUntil("B to be quarantined") { device.runtime.activation.intactRelease(E2E_B_ID) == null }
            assertEquals(E2E_A_BUILD, device.product(), "new sessions fall back to the bundle")
            assertEquals(library, device.library.read())
            device.fetcher.clear()
            assertEquals(E2E_B_ID, activated(device.check()).releaseId, "the next check re-installs B")
            assertEquals(E2E_B_BUILD, device.product())
            assertEquals(library, device.library.read())
            e2e.result("13-corrupted-store", "PASSED corrupted OTA store: garbage state.json -> cold start on the bundle, no release deleted, the next check takes B back with 0 talent files fetched; a flipped byte in an installed file -> DatasetOutcome.Failed for the reader, B quarantined, new sessions on 70374, the next check re-installs B (${device.blobRequests().size} files fetched); the library is unchanged throughout")
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------ historical builds

    @Test
    fun aSavedBuildOfAnUnbundledBuildDownloadsItsData() {
        val device = e2e.device("historical", bundled = setOf(E2E_B_BUILD))
        try {
            device.serve("2-b-staged") // lists A and B; B is this app's bundled product
            assertEquals(E2E_B_ID, activated(device.check()).releaseId)
            val savedOnA = device.saveBuild(E2E_A_BUILD, "MAGE")
            val library = device.library.read()
            val resolver = device.runtime.resolver
            assertFalse(resolver.isAvailable(bundle(E2E_A_BUILD)))

            device.serve("2-b-staged", "drop")
            val offline = assertIs<DatasetResolution.Unavailable>(runBlocking { resolver.resolve(bundle(E2E_A_BUILD)) })
            assertEquals(DatasetUnavailableReason.Offline, offline.reason)
            assertEquals(library, device.library.read())

            device.serve("2-b-staged")
            device.fetcher.clear()
            val available = assertIs<DatasetResolution.Available>(runBlocking { resolver.resolve(bundle(E2E_A_BUILD)) })
            assertTrue(stand.manifestHex(E2E_A_ID) in device.blobRequests(), "A was downloaded")
            assertEquals(E2E_B_ID, device.active(), "downloaded, not activated")
            assertEquals(library, device.library.read(), "the download changes no record")
            opensOnItsOwnBuild(device, SessionDatasets.of(available.snapshot), savedOnA)
            available.lease.release()
            e2e.result("14-historical", "PASSED a saved build of 70374 on an app that bundles only 70450: offline -> Unavailable(Offline), record unchanged; online -> 70374 downloaded (${device.blobRequests().size} files), 70450 stays active, the build opens on 70374 with its record unchanged")
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    private class PhaseNote(val a: String, val b: String, val library: String)

    private fun phaseNote(name: String, a: String, b: String, library: String) {
        val json = buildJsonObject {
            put("a", JsonPrimitive(a))
            put("b", JsonPrimitive(b))
            put("library", JsonPrimitive(library))
        }
        HostFiles.write("${e2e.settings.work}/devices/$name/phase-note.json", json.toString().encodeToByteArray())
    }

    private fun readPhaseNote(name: String): PhaseNote {
        val json = Json.parseToJsonElement(HostFiles.read("${e2e.settings.work}/devices/$name/phase-note.json").decodeToString()).jsonObject
        return PhaseNote(json.getValue("a").jsonPrimitive.content, json.getValue("b").jsonPrimitive.content, json.getValue("library").jsonPrimitive.content)
    }

    private fun IosOtaDevice.recordOf(localId: String): LocalBuildRecord =
        assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository.load()).value.single { it.localId == localId }

    /** Records every asset path a session reads (which build answered). */
    private class RecordingAssetSource(private val delegate: DatasetAssetSource) : DatasetAssetSource {
        private val lock = Mutex()
        private val paths = ArrayList<String>()

        override suspend fun read(path: String): ByteArray {
            lock.withLock { paths += path }
            return delegate.read(path)
        }

        fun reads(): List<String> = runBlocking { lock.withLock { paths.toList() } }

        fun clear() = runBlocking { lock.withLock { paths.clear() } }
    }

    /** Calls [beforeWrite] before every write: the kill point of a phase. */
    private class KillingStore(private val delegate: OtaFileStore, private val beforeWrite: (String) -> Unit) : OtaFileStore by delegate {
        override fun writeSynced(path: String, bytes: ByteArray) {
            beforeWrite(path)
            delegate.writeSynced(path, bytes)
        }
    }

    /** Free space and `ENOSPC` under test control (client design §4.4). */
    private class SpaceLimitedStore(private val delegate: OtaFileStore) : OtaFileStore by delegate {
        var free: Long = Long.MAX_VALUE
        var failPartWritesAfter: Int = Int.MAX_VALUE
        private val partWrites = AtomicInt(0)

        override fun freeSpaceBytes(): Long = free

        override fun writeSynced(path: String, bytes: ByteArray) {
            if (path.endsWith(".part") && partWrites.incrementAndGet() > failPartWritesAfter) {
                partWrites.value = 0
                delegate.writeSynced(path, bytes.copyOf(bytes.size / 2)) // half written, then ENOSPC
                throw OtaIoException("ENOSPC (test)", noSpace = true)
            }
            delegate.writeSynced(path, bytes)
        }
    }
}
