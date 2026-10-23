@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildIdGenerator
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.ClientBuildVersion
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.OtaClock
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.IosBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.platformBuildClock
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/*
 * Support of IosOtaStagingE2ETest (M5.IOS.2): the run's settings written by
 * scripts/ios_ota_e2e.py, the publisher's stand, and one "device" — the production iOS stack
 * (PlatformBlobFetcher over NSURLSession, PlatformSignatureVerifier over Security.framework,
 * PlatformOtaFileStore in a directory of the run, IosBuildLibraryStorage over NSUserDefaults)
 * with the app's Compose resources as the bundle, limited to the builds an app version bundled.
 */

internal const val E2E_A_BUILD = "1.60.1.70374"
internal const val E2E_B_BUILD = "1.60.1.70450"
internal const val E2E_A_ID = "1.60.1.70374-r1"
internal const val E2E_B_ID = "1.60.1.70450-r1"
internal const val E2E_CATALOG_PATH = "v1/channels/staging/catalog.json"
internal const val E2E_BLOB_PREFIX = "v1/blobs/sha256/"

/** Host files: a simulator process reads and writes the Mac's file system directly. */
internal object HostFiles {
    private val files = NSFileManager.defaultManager

    fun read(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path) ?: fail("cannot read $path")
        val size = data.length.toInt()
        return if (size == 0) ByteArray(0) else data.bytes!!.readBytes(size)
    }

    fun write(path: String, bytes: ByteArray) {
        files.createDirectoryAtPath(path.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null)
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        if (!data.writeToFile(path, atomically = true)) fail("cannot write $path")
    }

    fun exists(path: String): Boolean = files.fileExistsAtPath(path)

    fun delete(path: String) {
        if (exists(path)) files.removeItemAtPath(path, error = null)
    }

    /** Every regular file under [root], relative to it. */
    fun tree(root: String): List<String> = (files.subpathsOfDirectoryAtPath(root, error = null) ?: emptyList<Any?>())
        .map { it as String }
        .filter { relative -> files.contentsOfDirectoryAtPath("$root/$relative", error = null) == null }
        .sorted()
}

/** `TF_IOS_OTA_E2E`: the settings file of the run; without it the phase cannot run and fails. */
internal class E2ESettings private constructor(json: JsonObject) {
    private val values: Map<String, String> = json.mapValues { it.value.jsonPrimitive.content }
    val stand: String = values.getValue("stand")
    val work: String = values.getValue("work")
    val origin: String = values.getValue("origin")
    val untrustedOrigin: String = values.getValue("untrustedOrigin")
    val expiredOrigin: String = values.getValue("expiredOrigin")
    val wrongHostOrigin: String = values.getValue("wrongHostOrigin")
    val closedOrigin: String = values.getValue("closedOrigin")
    val unresolvableOrigin: String = values.getValue("unresolvableOrigin")

    companion object {
        fun load(): E2ESettings {
            val path = NSProcessInfo.processInfo.environment["TF_IOS_OTA_E2E"] as? String
                ?: fail("TF_IOS_OTA_E2E is not set: run the phases through scripts/ios_ota_e2e.py")
            return E2ESettings(Json.parseToJsonElement(HostFiles.read(path).decodeToString()).jsonObject)
        }
    }
}

/** The publisher's publications (`scripts/ota_staging_stand.py prepare`). */
internal class E2EStand(private val dir: String) {
    private val report: JsonObject = Json.parseToJsonElement(HostFiles.read("$dir/report.json").decodeToString()).jsonObject

    private fun string(vararg path: String): String {
        var node = report
        for (key in path.dropLast(1)) node = node.getValue(key).jsonObject
        return node.getValue(path.last()).jsonPrimitive.content
    }

    val publishedAt: UtcInstant = UtcInstant.parseOrNull(string("now"))!!

    val keyId: String = string("keyId")

    val stagingKeys: List<OtaKeySetting> = Json.parseToJsonElement(HostFiles.read("$dir/${string("keyring")}").decodeToString())
        .jsonObject.getValue("keys").jsonArray.map {
            val key = it.jsonObject
            OtaKeySetting(key.getValue("keyId").jsonPrimitive.content, key.getValue("algorithm").jsonPrimitive.content, key.getValue("publicKeySpki").jsonPrimitive.content)
        }

    fun expiresAt(snapshot: String): UtcInstant = UtcInstant.parseOrNull(
        report.getValue("snapshots").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == snapshot }.getValue("expiresAt").jsonPrimitive.content,
    )!!

    fun manifest(releaseId: String): ByteArray = HostFiles.read("$dir/${string("manifests", releaseId, "path")}")

    fun manifestHex(releaseId: String): String = string("manifests", releaseId, "sha256").removePrefix("sha256:")

    /** path → SHA-256 hex of every file of a published release. */
    fun releaseFiles(releaseId: String): Map<String, String> =
        Json.parseToJsonElement(manifest(releaseId).decodeToString()).jsonObject.getValue("files").jsonArray.associate {
            val file = it.jsonObject
            file.getValue("path").jsonPrimitive.content to file.getValue("sha256").jsonPrimitive.content.removePrefix("sha256:")
        }

    /** The staging configuration exactly as a debug app build resolves it (contract §8). */
    fun config(baseUrl: String): OtaConfig =
        assertIs<OtaConfig.Resolution.Enabled>(OtaConfig.resolve(OtaPlatformSettings(true, OtaChannel.STAGING, baseUrl, stagingKeys))).config
}

/** Every request the client makes, in order (the iOS fetcher underneath). */
internal class RecordingFetcher(private val delegate: BlobFetcher) : BlobFetcher {
    private val lock = Mutex()
    private val urls = ArrayList<String>()

    override suspend fun fetch(url: String, maxBytes: Long): FetchResult {
        lock.withLock { urls += url }
        return delegate.fetch(url, maxBytes)
    }

    fun requests(): List<String> = runBlocking { lock.withLock { urls.toList() } }

    fun clear() = runBlocking { lock.withLock { urls.clear() } }
}

/** The app's Compose resources, limited to [builds]: what an app version that bundled only those reads. */
internal class BundledBuildsSource(private val builds: Set<String>) : DatasetAssetSource {
    private val resources = ComposeResourceDatasetAssetSource()

    override suspend fun read(path: String): ByteArray {
        val bundle = RoutingDatasetAssetSource.bundleOf(path) ?: throw AssetNotFoundException(path)
        if (bundle.clientBuild !in builds) throw AssetNotFoundException(path)
        return resources.read(path)
    }
}

internal class E2E {
    val settings = E2ESettings.load()
    val stand = E2EStand(settings.stand)
    private val control = PlatformBlobFetcher(userAgent = "TalentForge/ios-e2e-control")

    /** Points the origin key [key] at a publication and a fault (scripts/ios_ota_e2e.py). */
    fun serve(key: String, snapshot: String, fault: String = "ok") {
        val answer = runBlocking { control.fetch("${settings.origin}/_control/$key?snapshot=$snapshot&fault=$fault", 16) }
        if ((answer as? FetchResult.Body)?.bytes?.decodeToString() != "ok") fail("the origin refused /_control/$key: $answer")
    }

    fun result(test: String, line: String) {
        println("[ios-ota-e2e] $line")
        val path = "${settings.work}/results/$test.txt"
        val before = if (HostFiles.exists(path)) HostFiles.read(path).decodeToString() else ""
        HostFiles.write(path, (before + line + "\n").encodeToByteArray())
    }

    fun device(
        name: String,
        bundled: Set<String> = setOf(E2E_A_BUILD),
        origin: String = settings.origin,
        fresh: Boolean = true,
        storeWrapper: (OtaFileStore) -> OtaFileStore = { it },
    ) = IosOtaDevice(this, name, bundled, origin, fresh, storeWrapper)
}

/**
 * One app installation on the simulator: its OTA directory (kept across phases of one name, so a
 * later process cold-starts on it), its saved builds and the client. `fresh` starts from nothing:
 * no OTA directory and an empty library.
 */
internal class IosOtaDevice(
    val e2e: E2E,
    val name: String,
    val bundledBuilds: Set<String>,
    origin: String,
    fresh: Boolean,
    private val storeWrapper: (OtaFileStore) -> OtaFileStore,
) {
    val root = "${e2e.settings.work}/devices/$name/ota"

    init {
        if (fresh) {
            HostFiles.delete("${e2e.settings.work}/devices/$name")
            NSUserDefaults.standardUserDefaults.removeObjectForKey(BuildLibraryStorage.STORAGE_KEY)
        }
    }

    val config: OtaConfig = e2e.stand.config("$origin/s/$name")
    val fetcher = RecordingFetcher(PlatformBlobFetcher(userAgent = "TalentForge/ios-e2e"))
    var now: UtcInstant = e2e.stand.publishedAt.plusSeconds(60)
    val library = IosBuildLibraryStorage()
    val repository = LocalBuildRepository(library, platformBuildClock(), LocalBuildIdGenerator { NSUUID().UUIDString })
    val bundle = BundleInventory(
        DatasetBundleId(GameId("forever"), bundledBuilds.maxWith(compareBy { ClientBuildVersion.parse(it) })),
        bundledBuilds,
        BundledBuildsSource(bundledBuilds),
    )
    val baseStore: OtaFileStore = PlatformOtaFileStore(root)
    var store: OtaFileStore = storeWrapper(baseStore)
        private set
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var runtime: OtaRuntime = open()
        private set

    private fun open(): OtaRuntime = OtaRuntime.open(
        config = config,
        store = store,
        fetcher = fetcher,
        signatures = PlatformSignatureVerifier(),
        clock = OtaClock { now },
        bundle = bundle,
        libraryPayload = library::read,
        scope = scope,
    )

    fun serve(snapshot: String, fault: String = "ok") = e2e.serve(name, snapshot, fault)

    /** Process death inside this process (coroutines cancelled and awaited) and a cold start on the same directory. */
    fun restart(wrapper: (OtaFileStore) -> OtaFileStore = storeWrapper): OtaRuntime {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = wrapper(baseStore)
        runtime = open()
        return runtime
    }

    fun close() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    }

    fun check(): CheckOutcome = runBlocking { runtime.coordinator.checkNow() }

    fun active(): String? = runtime.activation.currentState().active

    fun product(): String = runtime.snapshot.value.product.clientBuild

    fun blobRequests(): List<String> = fetcher.requests().filter { E2E_BLOB_PREFIX in it }.map { it.substringAfterLast('/') }

    fun catalogRequests(): Int = fetcher.requests().count { it.endsWith(E2E_CATALOG_PATH) }

    fun installedManifest(releaseId: String): ByteArray = HostFiles.read("$root/v1/releases/$releaseId/release.json")

    /** SHA-256 hex of the files verified into `staging/<releaseId>` (the resume set). */
    fun stagedHashes(releaseId: String): Set<String> {
        val files = e2e.stand.releaseFiles(releaseId)
        return HostFiles.tree("$root/v1/staging/$releaseId").mapNotNull { files[it] }.toSet()
    }

    fun partFiles(): List<String> = HostFiles.tree(root).filter { it.endsWith(".part") }

    /** A saved build of [classToken] on [clientBuild], created by the real engine from the app's resources. */
    fun saveBuild(clientBuild: String, classToken: String): LocalBuildRecord = runBlocking {
        val datasets = BundledTalentDatasetRepository(BundledBuildsSource(setOf(clientBuild)))
        val created = createClassDraft(repository, datasets, ClassToken(classToken), DatasetBundleId(GameId("forever"), clientBuild))
        assertIs<DraftCreation.Created>(created, "a $clientBuild build").record
    }

    fun waitUntil(what: String, timeoutMillis: Long = 120_000, condition: () -> Boolean) = runBlocking {
        var waited = 0L
        while (!condition()) {
            if (waited >= timeoutMillis) fail("timed out after $timeoutMillis ms waiting for $what")
            delay(50)
            waited += 50
        }
    }
}
