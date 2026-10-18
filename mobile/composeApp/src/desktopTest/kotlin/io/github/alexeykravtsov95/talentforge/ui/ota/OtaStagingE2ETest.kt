package io.github.alexeykravtsov95.talentforge.ui.ota

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.ota.ActivationDecision
import io.github.alexeykravtsov95.talentforge.data.ota.CanonicalBase64
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.StagingPolicy
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import io.github.alexeykravtsov95.talentforge.ui.library.loadNewBuildChoices
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * Publisher → client staging E2E over HTTPS (M5.OTA.4a, opt-in). The stand is made by the real
 * M5.OTA.2 publisher CLI with an ephemeral `tf-staging-local-*` key (`scripts/ota_staging_stand.py
 * prepare`, outside the repository); this test serves its exact bytes over HTTPS with the stand's
 * own certificate and runs the real client: the configuration through [OtaConfig.resolve] (the
 * path an app build takes, not `forTesting`), [PlatformBlobFetcher] with explicit trust in the
 * stand's CA only ([pinnedTrustSocketFactory]; certificate and hostname verification on),
 * `java.security` signatures, the `java.nio` store and the app's repositories as validator.
 *
 * ```
 * python scripts/ota_staging_stand.py prepare <out>
 * ./gradlew :composeApp:desktopTest --tests "*OtaStagingE2ETest*" -Dtalentforge.ota.staging=<out>
 * ```
 * `scripts/ota_integration_check.sh` does both. Each scenario appends its result line to
 * `<out>/kotlin-e2e-results.txt`.
 */
class OtaStagingE2ETest {
    private val out: File? = System.getProperty(PROPERTY)?.let(::File)

    @Before
    fun requested() = assumeTrue("staging E2E not requested (-D$PROPERTY=<stand>)", out != null)

    private val stand: File get() = out!!

    private val report: JsonObject by lazy { Json.parseToJsonElement(stand.resolve("report.json").readText()).jsonObject }

    private fun reportString(vararg path: String): String {
        var node: JsonObject = report
        for (key in path.dropLast(1)) node = node.getValue(key).jsonObject
        return node.getValue(path.last()).jsonPrimitive.content
    }

    private val publishedAt: UtcInstant by lazy { UtcInstant.parseOrNull(reportString("now"))!! }

    private fun snapshotField(snapshot: String, field: String): String = report.getValue("snapshots").jsonArray
        .map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == snapshot }.getValue(field).jsonPrimitive.content

    private val stagingKeys: List<OtaKeySetting> by lazy {
        Json.parseToJsonElement(stand.resolve(reportString("keyring")).readText()).jsonObject.getValue("keys").jsonArray.map {
            val key = it.jsonObject
            OtaKeySetting(key.getValue("keyId").jsonPrimitive.content, key.getValue("algorithm").jsonPrimitive.content, key.getValue("publicKeySpki").jsonPrimitive.content)
        }
    }

    private val caPem: String by lazy { stand.resolve(reportString("tls", "ca")).readText() }

    private fun publishedManifest(releaseId: String): ByteArray = stand.resolve(reportString("manifests", releaseId, "path")).readBytes()

    private fun manifestHex(releaseId: String): String = reportString("manifests", releaseId, "sha256").removePrefix("sha256:")

    /** path → SHA-256 hex of every file of a published release (from the publisher's manifest bytes). */
    private fun releaseFiles(releaseId: String): Map<String, String> =
        Json.parseToJsonElement(publishedManifest(releaseId).decodeToString()).jsonObject.getValue("files").jsonArray.associate {
            val file = it.jsonObject
            file.getValue("path").jsonPrimitive.content to file.getValue("sha256").jsonPrimitive.content.removePrefix("sha256:")
        }

    // ------------------------------------------------------------------ the stand over HTTPS

    /** What the origin does instead of the normal response to one request. */
    sealed interface Fault {
        data class Status(val code: Int, val location: String? = null) : Fault

        /** Announces the full length, sends at most [keepBytes], then drops the connection (lost network). */
        data class Truncate(val keepBytes: Int) : Fault

        /** Waits before the normal response (a read timeout on the client). */
        data class Stall(val millis: Long) : Fault

        /** Serves other bytes under the same name (corruption in transit or at rest). */
        class Replace(val body: ByteArray) : Fault
    }

    /**
     * `stores/<snapshot>` of the stand served like the static origin of contract §1, over HTTPS
     * with the stand's server certificate: the catalog path and blob paths only, 404 otherwise,
     * never a redirect unless a [Fault] says so. Every request path is logged.
     */
    private class StandOrigin(private val stand: File) : AutoCloseable {
        @Volatile
        var snapshot: String = "1-a-staged"

        @Volatile
        var fault: ((path: String, count: Int) -> Fault?)? = null

        val requests = CopyOnWriteArrayList<String>()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        private val pool = Executors.newFixedThreadPool(8)
        private val server: HttpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverContext(stand))
            executor = pool
            createContext("/") { exchange -> exchange.use { handle(it) } }
            start()
        }
        val base: String = "https://127.0.0.1:${server.address.port}"

        fun blobRequests(): List<String> = requests.filter { it.startsWith(BLOB_PREFIX) }.map { it.removePrefix(BLOB_PREFIX) }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.rawPath.removePrefix("/")
            requests += path
            val count = counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            val file = if (exchange.requestMethod == "GET" && (path == CATALOG_PATH || BLOB_PATH.matches(path))) {
                stand.resolve("stores/$snapshot/$path").takeIf { it.isFile }
            } else {
                null
            }
            when (val fault = fault?.invoke(path, count)) {
                is Fault.Status -> {
                    fault.location?.let { exchange.responseHeaders.add("Location", it) }
                    exchange.sendResponseHeaders(fault.code, -1)
                }
                is Fault.Truncate -> {
                    val body = file?.readBytes() ?: return exchange.sendResponseHeaders(404, -1)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    try {
                        exchange.responseBody.write(body, 0, fault.keepBytes.coerceAtMost(body.size - 1))
                        exchange.responseBody.flush()
                        exchange.responseBody.close() // fewer bytes than announced: the connection is dropped
                    } catch (_: IOException) {
                    }
                }
                is Fault.Stall -> {
                    Thread.sleep(fault.millis)
                    serve(exchange, file?.readBytes())
                }
                is Fault.Replace -> serve(exchange, fault.body)
                null -> serve(exchange, file?.readBytes())
            }
        }

        private fun serve(exchange: HttpExchange, body: ByteArray?) {
            if (body == null) return exchange.sendResponseHeaders(404, -1)
            exchange.sendResponseHeaders(200, body.size.toLong())
            try {
                exchange.responseBody.use { it.write(body) }
            } catch (_: IOException) {
                // the client gave up (timeout, cancelled attempt)
            }
        }

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }

        companion object {
            /** The stand's server key and chain (`tls/server.key`, `tls/server.pem`), never committed. */
            fun serverContext(stand: File): SSLContext {
                val keyBase64 = stand.resolve("tls/server.key").readLines().filterNot { it.startsWith("-----") }.joinToString("")
                val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyBase64)))
                val chain = stand.resolve("tls/server.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it).toTypedArray() }
                val password = CharArray(0)
                val keys = KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry("server", key, password, chain)
                }
                val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }.keyManagers
                return SSLContext.getInstance("TLS").apply { init(managers, null, null) }
            }
        }
    }

    // ------------------------------------------------------------------ the device

    /** The staging configuration exactly as an app build resolves it (contract §8, client design §8). */
    private fun stagingConfig(baseUrl: String): OtaConfig =
        assertIs<OtaConfig.Resolution.Enabled>(OtaConfig.resolve(OtaPlatformSettings(enabled = true, channel = OtaChannel.STAGING, baseUrl = baseUrl, keys = stagingKeys))).config

    private fun fetcher(trustStand: Boolean = true, readTimeoutMillis: Int = 30_000) = PlatformBlobFetcher(
        userAgent = "TalentForge/staging-e2e",
        readTimeoutMillis = readTimeoutMillis,
        sslSocketFactory = if (trustStand) pinnedTrustSocketFactory(caPem) else null,
    )

    private fun device(
        origin: StandOrigin,
        bundled: Set<String> = setOf(A_BUILD),
        storeWrapper: (OtaFileStore) -> OtaFileStore = { it },
        fetcher: PlatformBlobFetcher = fetcher(),
    ) = OtaDevice(bundledBuilds = bundled, config = stagingConfig(origin.base), storeWrapper = storeWrapper, fetcher = fetcher).also {
        it.clock.now = publishedAt.plusSeconds(60)
    }

    private fun OtaDevice.installedManifest(releaseId: String): ByteArray = directory.resolve("v1/releases/$releaseId/release.json").readBytes()

    private fun OtaDevice.active(): String? = runtime.activation.currentState().active

    /** SHA-256 hex of the files verified into `staging/<releaseId>` (the resume set). */
    private fun OtaDevice.stagedHashes(releaseId: String): Set<String> {
        val root = directory.resolve("v1/staging/$releaseId")
        val files = releaseFiles(releaseId)
        return root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .mapNotNull { files[it.relativeTo(root).invariantSeparatorsPath] }.toSet()
    }

    private fun failure(outcome: CheckOutcome): OtaFailure = assertIs<CheckOutcome.Failed>(outcome).failure

    private fun opensOnItsOwnBuild(device: OtaDevice, session: SessionDatasets, record: io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord) {
        val opened = runBlocking { loadEditorSession(device.repository, session.datasets, record.localId, session.icons, session.descriptions, session.localization) }
        assertEquals(record.build, assertIs<EditorLoad.Ready>(opened).session.record.build, "the saved build opens unchanged on ${record.build.clientBuild}")
    }

    /** The invariant of every negative scenario (contract §11): the library and the active dataset are untouched. */
    private fun assertNothingChanged(device: OtaDevice, library: String?, active: String?, product: String, label: String) {
        assertEquals(library, device.library.payload, "$label: the saved-build library is unchanged")
        assertEquals(active, device.active(), "$label: the active release is unchanged")
        assertEquals(product, device.runtime.snapshot.value.product.clientBuild, "$label: new builds stay on $product")
    }

    private fun record(line: String) {
        println("[staging-e2e] $line")
        stand.resolve("kotlin-e2e-results.txt").appendText("$line\n")
    }

    private fun bundle(build: String) = DatasetBundleId(GameId("forever"), build)

    // ------------------------------------------------------------------ scenarios

    @Test
    fun theThreeKeyClassesStaySeparated() {
        assertTrue(stagingKeys.isNotEmpty() && stagingKeys.all { it.keyId.startsWith("tf-staging-local-") })
        // the stand's staging key is refused by a production configuration ...
        val asProduction = stagingKeys + stagingKeys.map { it.copy(keyId = it.keyId + "b") }
        assertEquals(
            OtaConfig.Resolution.Disabled(OtaConfig.DisabledReason.KEY_NOT_FOR_CHANNEL),
            OtaConfig.resolve(OtaPlatformSettings(true, OtaChannel.PRODUCTION, "https://ota.example.org", asProduction)),
        )
        // ... the fixtures' test- key ids by every app configuration, staging included ...
        val fixtureIds = OtaTestRepo.keyring.keyIds.map { id -> OtaKeySetting(id, "ecdsa-p256-sha256", stagingKeys.single().publicKeySpki) }
        assertEquals(
            OtaConfig.Resolution.Disabled(OtaConfig.DisabledReason.TEST_KEY_IN_APP_KEYRING),
            OtaConfig.resolve(OtaPlatformSettings(true, OtaChannel.STAGING, "https://127.0.0.1:8443", fixtureIds)),
        )
        // ... and no plaintext origin can be configured
        assertEquals(
            OtaConfig.Resolution.Disabled(OtaConfig.DisabledReason.BAD_BASE_URL),
            OtaConfig.resolve(OtaPlatformSettings(true, OtaChannel.STAGING, "http://127.0.0.1:8443", stagingKeys)),
        )
        record("PASSED key classes: the tf-staging-local key is refused in production (KEY_NOT_FOR_CHANNEL), test- keys in staging (TEST_KEY_IN_APP_KEYRING), http:// (BAD_BASE_URL)")
    }

    @Test
    fun aToBRollbackAndAnOfflineColdStartOnThePublishersBytes() = StandOrigin(stand).use { origin ->
        device(origin).test { device ->
            val a = reportString("releases", "a")
            val b = reportString("releases", "b")

            // 1-a-staged: A, the bundled product build, installed from the bundle and activated
            origin.snapshot = "1-a-staged"
            assertEquals(a, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertContentEquals(publishedManifest(a), device.installedManifest(a), "the publisher's exact manifest bytes")
            assertEquals(listOf(manifestHex(a)), origin.blobRequests(), "every file of A is reused from the bundle")
            val savedOnA = device.saveBuild(A_BUILD, "PRIEST")

            // 2-b-staged: B downloaded over HTTPS, verified, validated, activated - no app update
            origin.snapshot = "2-b-staged"
            origin.requests.clear()
            device.bundleSource.reads.clear()
            val activatedB = assertIs<CheckOutcome.Activated>(device.check())
            assertEquals(b, activatedB.releaseId)
            assertContentEquals(publishedManifest(b), device.installedManifest(b))
            assertTrue(origin.requests.all { it == CATALOG_PATH || it.startsWith(BLOB_PREFIX) }, "only contract paths: ${origin.requests.toSet()}")
            assertTrue(activatedB.report.downloaded > 300, "B came over the network: ${activatedB.report}")
            assertEquals(setOf(A_BUILD), device.bundledBuilds, "the bundle is unchanged")
            assertTrue(device.bundleSource.reads.none { "/$B_BUILD/" in it }, "no B file comes from the bundle")
            val session = SessionDatasets.of(device.runtime.snapshot.value)
            assertEquals(B_BUILD, session.product.clientBuild)
            assertNotNull(runBlocking { loadNewBuildChoices(session.datasets, session.product) })
            val draft = runBlocking { createClassDraft(device.repository, session.datasets, ClassToken("DRUID"), session.product) }
            assertEquals(B_BUILD, assertIs<DraftCreation.Created>(draft).record.build.clientBuild)
            opensOnItsOwnBuild(device, session, savedOnA)
            record(
                "PASSED A -> B: ${activatedB.report.downloaded} files / ${activatedB.report.downloadedBytes} bytes over HTTPS, " +
                    "${activatedB.report.copiedFromBundle} reused from the bundle; the publisher's manifest bytes installed; new builds on B; the saved A build opens on A",
            )

            // the same catalog again: nothing downloaded
            origin.requests.clear()
            assertEquals(ActivationDecision.NO_CHANGE, assertIs<CheckOutcome.Current>(device.check()).decision)
            assertEquals(listOf(CATALOG_PATH), origin.requests.toList())
            record("PASSED same catalog again: NO_CHANGE, 1 request (the catalog), 0 blobs")

            // 3-rollback-a: a new sequence names A; A is still installed, only the catalog is fetched
            origin.snapshot = "3-rollback-a"
            origin.requests.clear()
            assertEquals(a, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertEquals(listOf(CATALOG_PATH), origin.requests.toList())
            val state = device.runtime.activation.currentState()
            assertEquals(a, state.active)
            assertEquals(b, state.previous)
            assertEquals(3L, state.catalog?.sequence)
            assertEquals(A_BUILD, device.runtime.snapshot.value.product.clientBuild)
            opensOnItsOwnBuild(device, SessionDatasets.of(device.runtime.snapshot.value), savedOnA)
            record("PASSED rollback B -> A: sequence 3, A active, B kept as previous, 1 request, 0 blobs")

            // 4-level-2: the active release needs a newer app - nothing downloaded, A stays
            origin.snapshot = "4-level-2"
            origin.requests.clear()
            val newer = assertIs<CheckOutcome.NeedsNewerApp>(device.check())
            assertEquals(reportString("releases", "bLevel2") to 2, newer.releaseId to newer.minDataLevel)
            assertEquals(listOf(CATALOG_PATH), origin.requests.toList())
            assertEquals(a, device.active())
            record("PASSED minDataLevel 2 active: KEEP_CURRENT_INCOMPATIBLE (NeedsNewerApp), 0 blobs, A stays active")

            // offline: the origin is gone; a cold start recovers the same state without the network
            val library = device.library.payload
            origin.close()
            val restarted = device.restart()
            assertTrue(restarted.recovery.stateReadable)
            assertEquals(a, restarted.activation.currentState().active)
            assertEquals(A_BUILD, restarted.snapshot.value.product.clientBuild)
            opensOnItsOwnBuild(device, SessionDatasets.of(restarted.snapshot.value), savedOnA)
            assertIs<OtaFailure.Network>(failure(device.check()))
            assertNothingChanged(device, library, a, A_BUILD, "offline")
            record("PASSED offline cold start: state recovered from disk (A active), saved build opens, the check fails as Network, nothing changes")
        }
    }

    @Test
    fun aConnectionLostMidDownloadResumesWithTheVerifiedFiles() = StandOrigin(stand).use { origin ->
        device(origin).test { device ->
            origin.snapshot = "2-b-staged"
            val library = device.library.payload
            val served = AtomicInteger()
            origin.fault = { path, _ -> if (path.startsWith(BLOB_PREFIX) && served.incrementAndGet() > 150) Fault.Truncate(keepBytes = 100) else null }
            assertIs<OtaFailure.Network>(failure(device.check()))
            assertNothingChanged(device, library, null, A_BUILD, "lost connection")
            val staged = device.stagedHashes(B_ID)
            assertTrue(staged.size >= 100, "verified files kept for resume: ${staged.size}")
            assertTrue(device.directory.walkTopDown().none { it.name.endsWith(".part") }, "no .part left behind")

            origin.fault = null
            origin.requests.clear()
            val resumed = assertIs<CheckOutcome.Activated>(device.check())
            assertEquals(B_ID, resumed.releaseId)
            assertTrue(origin.blobRequests().none { it in staged }, "no verified file is downloaded again")
            record("PASSED lost connection: attempt 1 ends Network with ${staged.size} verified files kept; resume downloaded ${resumed.report.downloaded}, reused ${resumed.report.reusedStaged} staged files")
        }
    }

    @Test
    fun aCorruptBlobManifestOrSignatureChangesNothing() = StandOrigin(stand).use { origin ->
        origin.snapshot = "2-b-staged"
        val envelope = Json.parseToJsonElement(stand.resolve("stores/2-b-staged/$CATALOG_PATH").readText()).jsonObject
        val signature = envelope.getValue("signatures").jsonArray.single().jsonObject
        val der = CanonicalBase64.decodeOrNull(signature.getValue("sig").jsonPrimitive.content)!!
        val tamperedDer = der.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val tamperedSignature = JsonObject(signature + ("sig" to JsonPrimitive(CanonicalBase64.encode(tamperedDer))))
        val badSignature = JsonObject(envelope + ("signatures" to JsonArray(listOf(tamperedSignature)))).toString().encodeToByteArray()
        val manifest = publishedManifest(B_ID)
        val talentHex = releaseFiles(B_ID).getValue("dataset/forever/$B_BUILD/druid.json")
        val talent = stand.resolve("stores/2-b-staged/$BLOB_PREFIX$talentHex").readBytes()
        fun flipped(bytes: ByteArray, at: Int) = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x20).toByte() }
        val cases: List<Triple<String, (String) -> Fault?, KClass<out OtaFailure>>> = listOf(
            Triple("signature", { path -> if (path == CATALOG_PATH) Fault.Replace(badSignature) else null }, OtaFailure.SignatureInvalid::class),
            Triple("release manifest", { path -> if (path == BLOB_PREFIX + manifestHex(B_ID)) Fault.Replace(flipped(manifest, 100)) else null }, OtaFailure.ManifestHashMismatch::class),
            Triple("blob", { path -> if (path == BLOB_PREFIX + talentHex) Fault.Replace(flipped(talent, 10)) else null }, OtaFailure.BlobHashMismatch::class),
        )
        for ((what, fault, expected) in cases) {
            device(origin).test { device ->
                val saved = device.saveBuild(A_BUILD, "ROGUE")
                val library = device.library.payload
                origin.fault = { path, _ -> fault(path) }
                val failure = failure(device.check())
                assertTrue(expected.isInstance(failure), "$what: expected ${expected.simpleName}, got $failure")
                assertNothingChanged(device, library, null, A_BUILD, what)
                assertTrue(!device.directory.resolve("v1/releases/$B_ID").exists(), "$what: nothing installed")
                if (what == "signature") assertNull(device.runtime.activation.currentState().catalog, "an unverified catalog is never accepted")
                opensOnItsOwnBuild(device, SessionDatasets.of(device.runtime.snapshot.value), saved)
                // with the corruption gone the next check installs B
                origin.fault = null
                assertEquals(B_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId, "$what: retried")
                record("PASSED corrupt $what: ${failure::class.simpleName}, library and active dataset unchanged, the saved build opens; the next clean check installs B")
            }
        }
    }

    @Test
    fun anExpiredCatalogIsRefused() = StandOrigin(stand).use { origin ->
        device(origin).test { device ->
            origin.snapshot = "2-b-staged"
            device.clock.now = UtcInstant.parseOrNull(snapshotField("2-b-staged", "expiresAt"))!!.plusSeconds(1)
            val library = device.library.payload
            assertIs<OtaFailure.CatalogExpired>(failure(device.check()))
            assertEquals(listOf(CATALOG_PATH), origin.requests.toList(), "nothing else is fetched")
            assertNothingChanged(device, library, null, A_BUILD, "expired")
            record("PASSED expired catalog (device clock = expiresAt + 1 s): CATALOG_EXPIRED, 1 request, nothing changes")
        }
    }

    @Test
    fun anEditorOpenDuringActivationKeepsReadingItsOwnRelease() = StandOrigin(stand).use { origin ->
        device(origin).test { device ->
            val before = device.runtime.snapshot.value
            val recorder = RecordingAssetSource(RoutingDatasetAssetSource(before))
            val sa = SessionDatasets(before.product, recorder, before)
            val draft = assertIs<DraftCreation.Created>(runBlocking { createClassDraft(device.repository, sa.datasets, ClassToken("WARRIOR"), sa.product) }).record
            val editor = assertIs<EditorLoad.Ready>(runBlocking { loadEditorSession(device.repository, sa.datasets, draft.localId, sa.icons, sa.descriptions, sa.localization) }).session
            assertEquals(A_BUILD, editor.record.build.clientBuild)

            origin.snapshot = "2-b-staged"
            assertEquals(B_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)

            recorder.reads.clear()
            runBlocking {
                val document = (sa.datasets.document(DatasetId(GameId("forever"), A_BUILD, ClassToken("WARRIOR"))) as DatasetOutcome.Loaded).value
                sa.icons.load(document, document.trees.single())
                sa.descriptions.load(document, document.trees.single())
            }
            assertTrue(recorder.reads.isNotEmpty() && recorder.reads.all { "/$A_BUILD/" in it }, "the open editor reads only A: ${recorder.reads.filterNot { "/$A_BUILD/" in it }}")
            assertEquals(B_BUILD, SessionDatasets.of(device.runtime.snapshot.value).product.clientBuild, "a screen opened afterwards is on B")
            record("PASSED editor open during activation: keeps reading A (${recorder.reads.size} asset reads after the switch), new screens on B")
        }
    }

    @Test
    fun aHistoricalBuildIsDownloadedOnDemandAndNeverActivated() = StandOrigin(stand).use { origin ->
        device(origin, bundled = setOf(B_BUILD)).test { device ->
            origin.snapshot = "2-b-staged" // lists A and B; B is this device's bundled product
            assertEquals(B_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            val savedOnA = device.saveBuild(A_BUILD, "MAGE")
            val library = device.library.payload
            val resolver = device.runtime.resolver
            assertTrue(!resolver.isAvailable(bundle(A_BUILD)))

            origin.fault = { _, _ -> Fault.Truncate(0) } // every connection drops
            val offline = assertIs<DatasetResolution.Unavailable>(runBlocking { resolver.resolve(bundle(A_BUILD)) })
            assertEquals(DatasetUnavailableReason.Offline, offline.reason)
            assertEquals(library, device.library.payload)

            origin.fault = null
            origin.requests.clear()
            val available = assertIs<DatasetResolution.Available>(runBlocking { resolver.resolve(bundle(A_BUILD)) })
            assertTrue(manifestHex(A_ID) in origin.blobRequests(), "A was downloaded from the stand")
            assertEquals(B_ID, device.active(), "downloaded, not activated")
            assertEquals(library, device.library.payload, "the download changes no record")
            opensOnItsOwnBuild(device, SessionDatasets.of(available.snapshot), savedOnA)
            available.lease.release()
            record("PASSED on-demand historical download: offline -> typed Offline; online -> A downloaded (${origin.blobRequests().size} blobs), B stays active, the A build opens on A")
        }
    }

    @Test
    fun notEnoughSpaceChangesNothing() = StandOrigin(stand).use { origin ->
        var space: SpaceLimitedStore? = null
        device(origin, storeWrapper = { SpaceLimitedStore(it).also { s -> space = s } }).test { device ->
            origin.snapshot = "2-b-staged"
            space!!.free = StagingPolicy.SPACE_MARGIN_BYTES + 1_000
            val library = device.library.payload
            assertIs<OtaFailure.InsufficientStorage>(failure(device.check()))
            assertEquals(listOf(manifestHex(B_ID)), origin.blobRequests(), "only the manifest, before the space check")
            assertNothingChanged(device, library, null, A_BUILD, "no space")

            space!!.free = Long.MAX_VALUE
            space!!.failPartWritesAfter = 60 // ENOSPC half-way
            assertIs<OtaFailure.InsufficientStorage>(failure(device.check()))
            assertNothingChanged(device, library, null, A_BUILD, "ENOSPC")
            assertTrue(device.directory.walkTopDown().none { it.name.endsWith(".part") }, "the .part in flight is deleted")
            space!!.failPartWritesAfter = Int.MAX_VALUE
            assertEquals(B_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            record("PASSED insufficient storage: before the first file and ENOSPC half-way both end InsufficientStorage with nothing changed; with space the next check installs B")
        }
    }

    @Test
    fun processDeathDuringTheInstallRecoversAndResumes() = StandOrigin(stand).use { origin ->
        origin.snapshot = "2-b-staged"
        // (a) killed half-way through the downloads
        val parts = AtomicInteger()
        val midDownload = { store: OtaFileStore -> CrashingStore(store, crashOnPath = { op, path -> op == "write" && path.endsWith(".part") && parts.incrementAndGet() == 120 }) }
        device(origin, storeWrapper = midDownload).test { device ->
            val library = device.library.payload
            (device.store as CrashingStore).arm()
            try {
                device.check()
                fail("the kill point was not reached")
            } catch (_: SimulatedCrash) {
            }
            val restarted = device.restart { it }
            assertTrue(restarted.recovery.stateReadable)
            assertNothingChanged(device, library, null, A_BUILD, "killed mid-download")
            assertTrue(device.directory.walkTopDown().none { it.name.endsWith(".part") }, "recover() deletes .part files")
            val staged = device.stagedHashes(B_ID)
            origin.requests.clear()
            assertEquals(B_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertTrue(origin.blobRequests().none { it in staged }, "the verified files survive the process")
            record("PASSED process death mid-download: recovered on the old dataset, ${staged.size} verified files kept, resumed to B")
        }
        // (b) killed right after staging/<B> became releases/<B>, before the state names it
        val afterRename = { store: OtaFileStore -> CrashingStore(store, after = true, crashOnPath = { op, path -> op == "rename" && path.endsWith("v1/releases/$B_ID") }) }
        device(origin, storeWrapper = afterRename).test { device ->
            val library = device.library.payload
            (device.store as CrashingStore).arm()
            try {
                device.check()
                fail("the kill point was not reached")
            } catch (_: SimulatedCrash) {
            }
            device.restart { it }
            assertEquals(library, device.library.payload)
            assertEquals(A_BUILD, device.runtime.snapshot.value.product.clientBuild, "not active before the state says so")
            origin.requests.clear()
            assertEquals(B_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertTrue(origin.blobRequests().isEmpty(), "the installed release is adopted, nothing downloaded: ${origin.blobRequests()}")
            record("PASSED process death after the install rename: the release is adopted by recovery and activated with 0 blob requests")
        }
    }

    @Test
    fun theTransportRefusesRedirectsErrorsTimeoutsUntrustedCertificatesAndPlaintext() = StandOrigin(stand).use { origin ->
        StandOrigin(stand).use { elsewhere ->
            origin.snapshot = "2-b-staged"
            val cases: List<Triple<String, (String) -> Fault, OtaFailure>> = listOf(
                Triple("404", { _ -> Fault.Status(404) }, OtaFailure.Http(404)),
                Triple("500", { _ -> Fault.Status(500) }, OtaFailure.Http(500)),
                Triple("redirect", { path -> Fault.Status(302, "${elsewhere.base}/$path") }, OtaFailure.Http(302)),
            )
            for ((name, fault, expected) in cases) {
                device(origin).test { device ->
                    val library = device.library.payload
                    origin.fault = { path, _ -> if (path == CATALOG_PATH) fault(path) else null }
                    assertEquals(expected, failure(device.check()), name)
                    assertNothingChanged(device, library, null, A_BUILD, name)
                }
            }
            assertTrue(elsewhere.requests.isEmpty(), "a redirect is never followed")
            // a stalled response hits the read timeout
            origin.fault = { path, _ -> if (path == CATALOG_PATH) Fault.Stall(3_000) else null }
            device(origin, fetcher = fetcher(readTimeoutMillis = 500)).test { device ->
                assertIs<OtaFailure.Network>(failure(device.check()))
                assertNothingChanged(device, device.library.payload, null, A_BUILD, "timeout")
            }
            origin.fault = null
            // the platform's roots do not trust the stand: TLS verification is not weakened
            origin.requests.clear()
            device(origin, fetcher = fetcher(trustStand = false)).test { device ->
                assertIs<OtaFailure.Network>(failure(device.check()))
                assertTrue(origin.requests.isEmpty(), "no HTTP request without a verified TLS session")
            }
            // plaintext is refused before any connection
            val plain = runBlocking { fetcher().fetch("http://127.0.0.1:${origin.base.substringAfterLast(':')}/$CATALOG_PATH", 1024) }
            assertEquals(FetchResult.NetworkError("refusing a non-HTTPS URL", retryable = false), plain)
            assertTrue(origin.requests.isEmpty())
            record("PASSED transport: 404/500/302 -> Http(status) with nothing changed, the redirect target never contacted, read timeout -> Network, untrusted certificate -> Network with no request, http:// refused")
        }
    }

    /** Free space and `ENOSPC` under test control (client design §4.4). */
    private class SpaceLimitedStore(private val delegate: OtaFileStore) : OtaFileStore by delegate {
        @Volatile
        var free: Long = Long.MAX_VALUE

        @Volatile
        var failPartWritesAfter: Int = Int.MAX_VALUE
        private val partWrites = AtomicInteger()

        override fun freeSpaceBytes(): Long = free

        override fun writeSynced(path: String, bytes: ByteArray) {
            if (path.endsWith(".part") && partWrites.incrementAndGet() > failPartWritesAfter) {
                partWrites.set(0)
                delegate.writeSynced(path, bytes.copyOf(bytes.size / 2)) // half written, then ENOSPC
                throw OtaIoException("ENOSPC (test)", noSpace = true)
            }
            delegate.writeSynced(path, bytes)
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.ota.staging"
        const val CATALOG_PATH = "v1/channels/staging/catalog.json"
        const val BLOB_PREFIX = "v1/blobs/sha256/"
        val BLOB_PATH = Regex("v1/blobs/sha256/[0-9a-f]{64}")
    }
}
