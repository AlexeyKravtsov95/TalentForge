package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.AssetSourceException
import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogRelease
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.InstallAttempt
import io.github.alexeykravtsov95.talentforge.data.ota.InstalledRelease
import io.github.alexeykravtsov95.talentforge.data.ota.OtaClock
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaReleaseManifest
import io.github.alexeykravtsov95.talentforge.data.ota.OtaResult
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseFile
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseId
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseValidator
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseVerifier
import io.github.alexeykravtsov95.talentforge.data.ota.StagingPolicy
import io.github.alexeykravtsov95.talentforge.data.ota.failure
import io.github.alexeykravtsov95.talentforge.data.sha256Ref
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Download progress of one install attempt (files and bytes still to acquire). */
data class InstallProgress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long)

/** How an install attempt acquired the release (the S5/S12 evidence, and the §7 performance metrics). */
data class InstallReport(
    val release: IntactRelease,
    val alreadyInstalled: Boolean,
    val manifestDownloaded: Boolean,
    val reusedStaged: Int,
    val copiedFromInstalled: Int,
    val copiedFromBundle: Int,
    val downloaded: Int,
    val downloadedBytes: Long,
)

/**
 * Plans, fetches, verifies and stages one release, then installs it with one atomic rename
 * (client design §4.3, contract §7.1–§7.3). It never activates anything: activation is the
 * separate state write of [DatasetActivationManager.activate].
 *
 * Staging is progress, not a release (§4.7): an attempt resumes only when `attempt.json` pins
 * the manifest bytes the verified catalog pins now; every reused file — staged, installed or
 * bundled — is re-read and re-hashed against this manifest first; downloads go to `<path>.part`
 * and are renamed only after their own length and SHA-256 matched. The release becomes
 * `releases/<id>` only after the snapshot checks (§6.4) and the app's own repositories
 * ([ReleaseValidator]) accepted every file.
 */
class DatasetDownloadManager(
    private val config: OtaConfig,
    private val fetcher: BlobFetcher,
    private val cache: DatasetCache,
    private val activation: DatasetActivationManager,
    private val bundle: BundleInventory,
    private val clock: OtaClock,
) {
    private val store get() = cache.store
    private val registry = Mutex()
    private val locks = HashMap<String, Mutex>()
    private val running = HashSet<String>()

    /** Release ids with an install in progress — never touched by retention. */
    suspend fun runningAttempts(): Set<String> = registry.withLock { running.toSet() }

    private class Abort(val failure: OtaFailure, val space: StagingPolicy.SpaceDecision? = null) : Exception()

    suspend fun install(entry: CatalogRelease, onProgress: (InstallProgress) -> Unit = {}): OtaResult<InstallReport> {
        val lock = registry.withLock { locks.getOrPut(entry.releaseId) { Mutex() } }
        return lock.withLock {
            registry.withLock { running += entry.releaseId }
            try {
                installLocked(entry, onProgress)
            } finally {
                registry.withLock { running -= entry.releaseId }
            }
        }
    }

    private suspend fun installLocked(entry: CatalogRelease, onProgress: (InstallProgress) -> Unit): OtaResult<InstallReport> {
        val id = entry.releaseId
        activation.currentState().rejected.firstOrNull { it.releaseId == id && it.manifestSha256 == entry.manifestSha256 }?.let {
            return OtaResult.Err(it.code.failure("rejected earlier for the same manifest bytes"))
        }
        activation.intactRelease(id)?.let { installed ->
            if (installed.record.manifestSha256 == entry.manifestSha256) {
                return OtaResult.Ok(InstallReport(installed, true, false, 0, 0, 0, 0, 0))
            }
            // an installed release pinned to other bytes violates the append-only rule (§4); it
            // is kept as it is (saved builds may use it) and the attempt is retried later
            return OtaResult.Err(OtaFailure.ManifestHashMismatch("installed $id pins ${installed.record.manifestSha256}"))
        }
        return try {
            OtaResult.Ok(stageAndInstall(entry, onProgress))
        } catch (abort: Abort) {
            OtaResult.Err(endAttempt(entry, abort.failure, abort.space))
        } catch (e: OtaIoException) {
            val failure = if (e.noSpace) noSpace(entry) else OtaFailure.Io(e.message ?: "I/O failure")
            OtaResult.Err(endAttempt(entry, failure, if (e.noSpace) spaceNow(entry) else null))
        } catch (e: CancellationException) {
            deletePartFiles(id)
            throw e
        }
    }

    private suspend fun stageAndInstall(entry: CatalogRelease, onProgress: (InstallProgress) -> Unit): InstallReport {
        val id = entry.releaseId
        val now = clock.now()
        // 1. open or create staging/<id>, reconciled with the verified catalog entry
        when (val start = StagingPolicy.startAttempt(cache.stagingExists(id), cache.readAttempt(id), entry)) {
            is StagingPolicy.AttemptStart.Resume -> Unit
            is StagingPolicy.AttemptStart.Restart -> {
                cache.deleteViaTrash(cache.stagingFor(id))
                cache.writeAttempt(InstallAttempt(id, entry.manifestSha256, entry.manifestByteLength, now, now))
            }
            StagingPolicy.AttemptStart.Create ->
                cache.writeAttempt(InstallAttempt(id, entry.manifestSha256, entry.manifestByteLength, now, now))
        }
        deletePartFiles(id)

        // 2. the manifest: the staged copy if it still matches the pin, else the blob
        val stagedManifest = store.readOrNull(cache.stagingManifestPath(id))
        val manifestReused = stagedManifest != null && stagedManifest.size.toLong() == entry.manifestByteLength &&
            sha256Ref(stagedManifest) == entry.manifestSha256
        val manifestBytes = if (manifestReused) {
            stagedManifest!!
        } else {
            if (stagedManifest != null) store.delete(cache.stagingManifestPath(id))
            fetchBlob(entry.manifestSha256.removePrefix("sha256:"), entry.manifestByteLength) {
                OtaFailure.ManifestHashMismatch("manifest blob exceeds its pinned ${entry.manifestByteLength} bytes")
            }
        }
        val manifest = when (val verified = ReleaseVerifier.verifyManifest(manifestBytes, entry, config.game, config.supportedDataLevel)) {
            is OtaResult.Err -> throw Abort(verified.failure)
            is OtaResult.Ok -> verified.value
        }
        if (!manifestReused) store.writeSynced(cache.stagingManifestPath(id), manifestBytes)

        // 3. what is already validly staged (re-read and re-hashed; a bad copy is deleted)
        val staged = HashSet<String>()
        for (file in manifest.files) {
            val path = cache.stagingFile(id, file.path)
            val bytes = store.readOrNull(path) ?: continue
            if (matches(bytes, file)) staged += file.path else store.delete(path)
        }
        val missing = manifest.files.filter { it.path !in staged }

        // 4. free space for what is still missing, plus the margin
        val space = StagingPolicy.space(store.freeSpaceBytes(), StagingPolicy.missingBytes(manifest, staged))
        if (space !is StagingPolicy.SpaceDecision.Proceed) {
            val (required, available) = when (space) {
                is StagingPolicy.SpaceDecision.InsufficientKeepStaging -> space.requiredBytes to space.availableBytes
                is StagingPolicy.SpaceDecision.InsufficientDeleteStaging -> space.requiredBytes to space.availableBytes
                else -> 0L to 0L
            }
            throw Abort(OtaFailure.InsufficientStorage(required, available), space)
        }

        // 5. acquire every missing file, cheapest source first, bounded parallelism
        val counters = Counters(missing.size, missing.sumOf { it.byteLength })
        onProgress(counters.progress())
        val semaphore = Semaphore(config.parallelism)
        coroutineScope {
            missing.map { file ->
                async {
                    semaphore.withPermit {
                        val source = acquire(manifest, file)
                        stageFile(id, file, source.bytes)
                        counters.add(file.byteLength, source)
                        onProgress(counters.progress())
                    }
                }
            }.awaitAll()
        }
        if (missing.isNotEmpty() || !manifestReused) {
            cache.writeAttempt(InstallAttempt(id, entry.manifestSha256, entry.manifestByteLength, cache.readAttempt(id)?.startedAt ?: now, clock.now()))
        }

        // 6. the snapshot as a whole (§6.4), then the app's own repositories
        val dataset = when (
            val checked = ReleaseVerifier.verifyFiles(
                manifest,
                { file -> store.readOrNull(cache.stagingFile(id, file.path)) ?: ByteArray(0) },
                knownSameBuildManifest(manifest.clientBuild),
            )
        ) {
            is OtaResult.Err -> throw Abort(checked.failure)
            is OtaResult.Ok -> checked.value
        }
        val stagingSource = ReleaseAssetSource(store, cache.stagingFor(id), id, manifest)
        val validation = ReleaseValidator.validate(stagingSource, dataset)
        if (validation is OtaResult.Err) throw Abort(validation.failure)

        // 7. install: one atomic rename staging/<id> -> releases/<id>; the attempt record goes after
        if (store.exists(cache.releaseDir(id))) cache.deleteViaTrash(cache.releaseDir(id))
        store.rename(cache.stagingFor(id), cache.releaseDir(id))
        store.delete("${cache.releaseDir(id)}/${DatasetCache.ATTEMPT}")
        val release = IntactRelease(InstalledRelease(id, manifest.clientBuild, entry.manifestSha256), manifest)
        activation.recordInstalled(release)
        return InstallReport(
            release = release,
            alreadyInstalled = false,
            manifestDownloaded = !manifestReused,
            reusedStaged = staged.size,
            copiedFromInstalled = counters.fromInstalled,
            copiedFromBundle = counters.fromBundle,
            downloaded = counters.downloaded,
            downloadedBytes = counters.downloadedBytes,
        )
    }

    private enum class Origin { INSTALLED, BUNDLE, NETWORK }

    private class Acquired(val bytes: ByteArray, val origin: Origin)

    private class Counters(val filesTotal: Int, val bytesTotal: Long) {
        private val lock = Mutex()
        var filesDone = 0
        var bytesDone = 0L
        var fromInstalled = 0
        var fromBundle = 0
        var downloaded = 0
        var downloadedBytes = 0L

        suspend fun add(bytes: Long, source: Acquired) = lock.withLock {
            filesDone++
            bytesDone += bytes
            when (source.origin) {
                Origin.INSTALLED -> fromInstalled++
                Origin.BUNDLE -> fromBundle++
                Origin.NETWORK -> {
                    downloaded++
                    downloadedBytes += bytes
                }
            }
        }

        fun progress() = InstallProgress(filesDone, filesTotal, bytesDone, bytesTotal)
    }

    /** The same bytes in an installed release, the bundled file of the same build and path, or the blob. */
    private suspend fun acquire(manifest: OtaReleaseManifest, file: ReleaseFile): Acquired {
        for (installed in activation.intactReleases()) {
            val match = installed.manifest.files.firstOrNull { it.sha256 == file.sha256 } ?: continue
            val bytes = store.readOrNull(cache.releaseFile(installed.record.releaseId, match.path)) ?: continue
            if (matches(bytes, file)) return Acquired(bytes, Origin.INSTALLED)
        }
        if (manifest.clientBuild in bundle.clientBuilds) {
            val bytes = try {
                bundle.assets.read(file.path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AssetSourceException) {
                null
            }
            if (bytes != null && matches(bytes, file)) return Acquired(bytes, Origin.BUNDLE)
        }
        val bytes = fetchBlob(file.sha256Hex, file.byteLength) { OtaFailure.BlobSizeMismatch("${file.path}: more than ${file.byteLength} bytes") }
        if (bytes.size.toLong() != file.byteLength) {
            throw Abort(OtaFailure.BlobSizeMismatch("${file.path}: ${bytes.size} bytes, manifest says ${file.byteLength}"))
        }
        val ref = sha256Ref(bytes)
        if (ref != file.sha256) throw Abort(OtaFailure.BlobHashMismatch("${file.path}: $ref"))
        return Acquired(bytes, Origin.NETWORK)
    }

    /** The blob; only a transfer that did not complete is repeated ([fetchRetrying]), any answer of the origin is final. */
    private suspend fun fetchBlob(hex: String, maxBytes: Long, overLimit: () -> OtaFailure): ByteArray =
        when (val response = fetcher.fetchRetrying(config.blobUrl(hex), maxBytes, config.fetchRetryDelaysMillis)) {
            is FetchResult.Body -> if (response.exceededLimit) throw Abort(overLimit()) else response.bytes
            is FetchResult.HttpStatus -> throw Abort(OtaFailure.Http(response.status))
            is FetchResult.NetworkError -> throw Abort(OtaFailure.Network(response.detail))
        }

    /** `<path>.part`, synced, then renamed to `<path>` — only bytes that already matched their pin. */
    private fun stageFile(id: String, file: ReleaseFile, bytes: ByteArray) {
        val target = cache.stagingFile(id, file.path)
        val part = target + DatasetCache.PART_SUFFIX
        try {
            store.writeSynced(part, bytes)
            store.rename(part, target)
        } catch (e: OtaIoException) {
            store.delete(part)
            throw e
        }
    }

    private fun matches(bytes: ByteArray, file: ReleaseFile): Boolean =
        bytes.size.toLong() == file.byteLength && sha256Ref(bytes) == file.sha256

    /**
     * Same-build identity (§6.4 #7): the dataset manifest of this client build the device
     * already trusts — the bundled one, else that of an installed release of the same build.
     */
    private suspend fun knownSameBuildManifest(clientBuild: String): DatasetManifest? {
        val path = "dataset/${config.game}/$clientBuild/manifest.json"
        val bytes = if (clientBuild in bundle.clientBuilds) {
            try {
                bundle.assets.read(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AssetSourceException) {
                throw Abort(OtaFailure.Io("bundled $path unreadable"))
            }
        } else {
            val installed = activation.intactReleases()
                .filter { it.record.clientBuild == clientBuild }
                .maxByOrNull { ReleaseId.parseOrNull(it.record.releaseId)!! }
                ?: return null
            val source = ReleaseAssetSource(store, cache.releaseDir(installed.record.releaseId), installed.record.releaseId, installed.manifest)
            try {
                source.read(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AssetSourceException) {
                throw Abort(OtaFailure.Io("installed $path unreadable"))
            }
        }
        return try {
            TalentDataContract.decodeDatasetManifest(bytes)
        } catch (e: Exception) {
            throw Abort(OtaFailure.Io("trusted dataset manifest of $clientBuild unreadable"))
        }
    }

    /** The end of a failed attempt (§4.7): keep for resume, or reject the release, or free the space. */
    private suspend fun endAttempt(entry: CatalogRelease, failure: OtaFailure, space: StagingPolicy.SpaceDecision?): OtaFailure {
        val id = entry.releaseId
        try {
            when (val end = StagingPolicy.attemptEnd(failure, space)) {
                StagingPolicy.AttemptEnd.KeepForResume -> deletePartFiles(id)
                StagingPolicy.AttemptEnd.DeleteStaging -> cache.deleteViaTrash(cache.stagingFor(id))
                is StagingPolicy.AttemptEnd.RejectRelease -> {
                    cache.deleteViaTrash(cache.stagingFor(id))
                    activation.recordRejected(id, entry.manifestSha256, end.code)
                }
            }
        } catch (_: OtaIoException) {
            // cleanup is retried by recover(): the failure that ended the attempt stands
        }
        return failure
    }

    private fun deletePartFiles(id: String) {
        if (!cache.stagingExists(id)) return
        cache.partFiles(cache.stagingFor(id)).forEach(store::delete)
    }

    private fun spaceNow(entry: CatalogRelease): StagingPolicy.SpaceDecision =
        StagingPolicy.space(store.freeSpaceBytes(), entry.totalByteLength)

    private fun noSpace(entry: CatalogRelease): OtaFailure =
        OtaFailure.InsufficientStorage(entry.totalByteLength + StagingPolicy.SPACE_MARGIN_BYTES, store.freeSpaceBytes())
}
