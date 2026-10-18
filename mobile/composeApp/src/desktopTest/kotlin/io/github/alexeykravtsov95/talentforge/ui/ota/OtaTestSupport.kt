package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.CanonicalBase64
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogRelease
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaClock
import io.github.alexeykravtsov95.talentforge.data.ota.OtaContract
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.OtaKeyring
import io.github.alexeykravtsov95.talentforge.data.ota.OtaReleaseManifest
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseVerifier
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import io.github.alexeykravtsov95.talentforge.data.ota.preAuthenticationEncoding
import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import io.github.alexeykravtsov95.talentforge.data.sha256Ref
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** `use` for test bodies: always returns Unit (JUnit test methods must be void). */
fun <T : AutoCloseable> T.test(block: (T) -> Unit) {
    use(block)
}

/** The repository checkout and the committed OTA fixtures (synced mirror, `--check` guarded). */
object OtaTestRepo {
    val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !(dir.resolve("pyproject.toml").isFile && dir.resolve("data/normalized").isDirectory)) dir = dir.parentFile
        requireNotNull(dir) { "repository root not found" }
    }

    val fixtures: File get() = root.resolve("mobile/shared/src/jvmTest/resources/ota/fixtures/v1")

    fun fixture(relative: String): ByteArray = fixtures.resolve(relative).readBytes()

    /** A release path's committed file (contract §12). */
    fun repositoryFile(releasePath: String): File {
        val head = releasePath.substringBefore('/')
        val directory = when (head) {
            "dataset" -> "data/normalized"
            "visuals" -> "data/visuals"
            "tooltips" -> "data/tooltips"
            "localization" -> "data/localization"
            else -> error("not a release path: $releasePath")
        }
        return root.resolve("$directory/${releasePath.substringAfter('/')}")
    }

    val keyring: OtaKeyring by lazy {
        val keys = Json.parseToJsonElement(fixtures.resolve("keyring.json").readText()).jsonObject.getValue("keys").jsonArray
        OtaKeyring.of(keys.map { it.jsonObject }.map {
            OtaKeyring.Entry(it.getValue("keyId").jsonPrimitive.content, it.getValue("algorithm").jsonPrimitive.content, it.getValue("publicKeySpki").jsonPrimitive.content)
        })
    }
}

const val A_BUILD = "1.60.1.70374"
const val B_BUILD = "1.60.1.70450"
const val A_ID = "1.60.1.70374-r1"
const val B_ID = "1.60.1.70450-r1"
val FIXTURE_NOW: UtcInstant = UtcInstant.parseOrNull("2026-10-18T12:00:00Z")!!

/**
 * The TEST private keys of the fixtures, derived exactly like `scripts/generate_ota_fixtures.py`
 * (`d = SHA-256("talentforge-ota-v1-test-key:<name>") mod (n − 1) + 1`) — public by construction;
 * they exist so tests can sign the catalogs a client-only scenario needs.
 */
object TestKeys {
    private val ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    fun privateKey(name: String): PrivateKey {
        val seed = MessageDigest.getInstance("SHA-256").digest("talentforge-ota-v1-test-key:$name".encodeToByteArray())
        val d = BigInteger(1, seed).mod(ORDER.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params))
    }

    fun sign(name: String, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey(name)); update(message) }.sign()
}

/** One release the fake origin can serve: the exact manifest bytes, its catalog entry, its files. */
class TestRelease(val manifestBytes: ByteArray, val files: Map<String, ByteArray>) {
    val json: JsonObject = Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
    val releaseId: String = json.getValue("releaseId").jsonPrimitive.content
    val clientBuild: String = json.getValue("clientBuild").jsonPrimitive.content
    val manifest: OtaReleaseManifest = requireNotNull(
        ReleaseVerifier.readInstalledManifest(manifestBytes, sha256Ref(manifestBytes), releaseId, "forever"),
    ) { "fixture manifest does not parse" }
    val entry: CatalogRelease = CatalogRelease(
        releaseId = releaseId,
        clientBuild = clientBuild,
        minDataLevel = json.getValue("minDataLevel").jsonPrimitive.int,
        manifestSha256 = sha256Ref(manifestBytes),
        manifestByteLength = manifestBytes.size.toLong(),
        totalByteLength = json.getValue("totalByteLength").jsonPrimitive.long,
        publishedAt = "2026-10-17T23:30:00Z",
    )

    /** Blob address (64 hex) → bytes: the manifest and every file. */
    fun blobs(): Map<String, ByteArray> =
        files.values.associateBy { sha256Hex(it) } + (sha256Hex(manifestBytes) to manifestBytes)

    /** A new release from this one: other id/level, some files replaced (sizes and hashes re-pinned). */
    fun derive(
        releaseId: String = this.releaseId,
        minDataLevel: Int? = null,
        overrides: Map<String, ByteArray> = emptyMap(),
    ): TestRelease {
        val newFiles = files + overrides
        val fileEntries = json.getValue("files").jsonArray.map { element ->
            val file = element.jsonObject
            val path = file.getValue("path").jsonPrimitive.content
            val bytes = newFiles.getValue(path)
            JsonObject(file + mapOf("sha256" to JsonPrimitive(sha256Ref(bytes)), "byteLength" to JsonPrimitive(bytes.size)))
        }
        val derived = JsonObject(
            json + mapOf(
                "releaseId" to JsonPrimitive(releaseId),
                "minDataLevel" to JsonPrimitive(minDataLevel ?: json.getValue("minDataLevel").jsonPrimitive.int),
                "files" to JsonArray(fileEntries),
                "totalByteLength" to JsonPrimitive(newFiles.values.sumOf { it.size.toLong() }),
            ),
        )
        return TestRelease((PRETTY.encodeToString(JsonObject.serializer(), derived) + "\n").encodeToByteArray(), newFiles)
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        /** A committed fixture release whose files are the repository's committed data. */
        fun fixture(relative: String): TestRelease {
            val bytes = OtaTestRepo.fixture(relative)
            val files = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("files").jsonArray.associate {
                val path = it.jsonObject.getValue("path").jsonPrimitive.content
                path to OtaTestRepo.repositoryFile(path).readBytes()
            }
            return TestRelease(bytes, files)
        }

        val A: TestRelease by lazy { fixture("releases/$A_ID.release.json") }
        val B: TestRelease by lazy { fixture("releases/$B_ID.release.json") }
    }
}

/** A signed `ota-envelope.v1` catalog built from fixture releases with a TEST key. */
fun signedCatalog(
    sequence: Long,
    active: String,
    releases: List<CatalogRelease>,
    issuedAt: String = "2026-10-18T00:00:00Z",
    expiresAt: String = "2026-12-02T00:00:00Z",
    channel: String = "production",
    signer: String = "test-primary",
): ByteArray {
    val payload = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "game" to JsonPrimitive("forever"),
                "channel" to JsonPrimitive(channel),
                "sequence" to JsonPrimitive(sequence),
                "issuedAt" to JsonPrimitive(issuedAt),
                "expiresAt" to JsonPrimitive(expiresAt),
                "activeReleaseId" to JsonPrimitive(active),
                "releases" to JsonArray(releases.map {
                    JsonObject(
                        mapOf(
                            "releaseId" to JsonPrimitive(it.releaseId),
                            "clientBuild" to JsonPrimitive(it.clientBuild),
                            "minDataLevel" to JsonPrimitive(it.minDataLevel),
                            "manifestSha256" to JsonPrimitive(it.manifestSha256),
                            "manifestByteLength" to JsonPrimitive(it.manifestByteLength),
                            "totalByteLength" to JsonPrimitive(it.totalByteLength),
                            "publishedAt" to JsonPrimitive(it.publishedAt),
                        ),
                    )
                }),
            ),
        ),
    ).encodeToByteArray()
    val signature = TestKeys.sign(signer, preAuthenticationEncoding(OtaContract.CATALOG_PAYLOAD_TYPE, payload))
    return Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "payloadType" to JsonPrimitive(OtaContract.CATALOG_PAYLOAD_TYPE),
                "payload" to JsonPrimitive(CanonicalBase64.encode(payload)),
                "signatures" to JsonArray(listOf(JsonObject(mapOf("keyId" to JsonPrimitive(signer), "sig" to JsonPrimitive(CanonicalBase64.encode(signature)))))),
            ),
        ),
    ).encodeToByteArray()
}

/**
 * The static origin in memory (test-plan L2): the catalog of the channel and content-addressed
 * blobs, with a request log and fault injection. Byte limits behave like the platform fetchers:
 * at most `maxBytes + 1` bytes are returned and `exceededLimit` is set.
 */
class FakeBlobServer(private val config: OtaConfig) : BlobFetcher {
    @Volatile
    var catalog: ByteArray? = null
    val blobs = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<String>()

    @Volatile
    var networkDown = false

    /** Returns a result for a URL to override the normal response (bit flips, truncation, failures). */
    @Volatile
    var fault: ((url: String, count: Int) -> FetchResult?)? = null

    /** Called before every response (e.g. to suspend a download so it can be killed). */
    @Volatile
    var beforeResponse: (suspend (url: String) -> Unit)? = null

    fun publish(vararg releases: TestRelease) {
        for (release in releases) blobs.putAll(release.blobs())
    }

    fun blobRequests(): List<String> = requests.filter { "/v1/blobs/sha256/" in it }.map { it.substringAfterLast('/') }

    fun catalogRequests(): Int = requests.count { it == config.catalogUrl }

    override suspend fun fetch(url: String, maxBytes: Long): FetchResult {
        requests += url
        beforeResponse?.invoke(url)
        if (networkDown) return FetchResult.NetworkError("network down (test)")
        fault?.invoke(url, requests.count { it == url })?.let { return it }
        val body = when {
            url == config.catalogUrl -> catalog
            url.startsWith(config.baseUrl + "/v1/blobs/sha256/") -> blobs[url.substringAfterLast('/')]
            else -> null
        } ?: return FetchResult.HttpStatus(404)
        return limited(body, maxBytes)
    }

    companion object {
        fun limited(body: ByteArray, maxBytes: Long): FetchResult.Body =
            if (body.size > maxBytes) FetchResult.Body(body.copyOf((maxBytes + 1).toInt()), exceededLimit = true) else FetchResult.Body(body, false)
    }
}

/** The app bundle as committed data of [builds] (the bundled resources are byte-identical mirrors of it), with a read log. */
class RepoBundleSource(private val builds: Set<String>) : DatasetAssetSource {
    val reads = CopyOnWriteArrayList<String>()

    override suspend fun read(path: String): ByteArray {
        val bundle = RoutingDatasetAssetSource.bundleOf(path) ?: throw AssetNotFoundException(path)
        if (bundle.clientBuild !in builds) throw AssetNotFoundException(path)
        val file = OtaTestRepo.repositoryFile(path)
        if (!file.isFile) throw AssetNotFoundException(path)
        reads += path
        return file.readBytes()
    }
}

class TestOtaClock(var now: UtcInstant = FIXTURE_NOW) : OtaClock {
    override fun now(): UtcInstant = now
}

/** Records every asset path a session reads (S2/S3: which build answered). */
class RecordingAssetSource(private val delegate: DatasetAssetSource) : DatasetAssetSource {
    val reads = CopyOnWriteArrayList<String>()

    override suspend fun read(path: String): ByteArray {
        reads += path
        return delegate.read(path)
    }
}

/**
 * One device for the L2 tests: an OTA store in a temp directory, a bundle, a library, a clock
 * and the fake origin. [restart] simulates process death: the runtime's coroutines are
 * cancelled, every in-memory object is dropped, and a new runtime recovers from the same
 * directory.
 */
class OtaDevice(
    val bundledBuilds: Set<String> = setOf(A_BUILD),
    val bundledProduct: String = bundledBuilds.maxWith(compareBy { io.github.alexeykravtsov95.talentforge.data.ota.ClientBuildVersion.parse(it) }),
    val config: OtaConfig = OtaConfig.forTesting(OtaTestRepo.keyring, baseUrl = "https://ota.test"),
    val storeWrapper: (OtaFileStore) -> OtaFileStore = { it },
    /** Another origin than the in-memory [server] (the publisher interop check serves a real store over HTTP). */
    private val fetcher: BlobFetcher? = null,
) : AutoCloseable {
    val directory: File = Files.createTempDirectory("tf-ota").toFile()
    val server = FakeBlobServer(config)
    val clock = TestOtaClock()
    val library = FakeBuildLibraryStorage()
    val bundleSource = RepoBundleSource(bundledBuilds)
    private val libraryClock = FixedClock()
    private val ids = SequentialIds()
    val repository = LocalBuildRepository(library, libraryClock, ids)
    val baseStore: OtaFileStore = PlatformOtaFileStore(directory)
    var store: OtaFileStore = storeWrapper(baseStore)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var runtime: OtaRuntime = open()
        private set

    val bundle: BundleInventory
        get() = BundleInventory(DatasetBundleId(GameId("forever"), bundledProduct), bundledBuilds, bundleSource)

    private fun open(): OtaRuntime = OtaRuntime.open(
        config = config,
        store = store,
        fetcher = fetcher ?: server,
        signatures = PlatformSignatureVerifier(),
        clock = clock,
        bundle = bundle,
        libraryPayload = library::read,
        scope = scope,
    )

    /** Process death and a cold start on the same directory. */
    fun restart(wrapper: (OtaFileStore) -> OtaFileStore = storeWrapper): OtaRuntime {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = wrapper(baseStore)
        runtime = open()
        return runtime
    }

    fun check(): CheckOutcome = runBlocking { runtime.coordinator.checkNow() }

    /** A saved build of [classToken] on [clientBuild], created with the real engine (reads the repository data). */
    fun saveBuild(clientBuild: String, classToken: String = "WARRIOR"): LocalBuildRecord = runBlocking {
        val datasets = BundledTalentDatasetRepository(RepoBundleSource(setOf(clientBuild)))
        when (val created = createClassDraft(repository, datasets, ClassToken(classToken), DatasetBundleId(GameId("forever"), clientBuild))) {
            is DraftCreation.Created -> created.record
            is DraftCreation.Failed -> error("could not create a $clientBuild build: $created")
        }
    }

    /** Every file under the OTA root, relative, with its bytes' SHA-256 (to compare before/after). */
    fun tree(): Map<String, String> = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).invariantSeparatorsPath to sha256Hex(it.readBytes())
    }

    override fun close() {
        scope.cancel()
        directory.deleteRecursively()
    }
}
