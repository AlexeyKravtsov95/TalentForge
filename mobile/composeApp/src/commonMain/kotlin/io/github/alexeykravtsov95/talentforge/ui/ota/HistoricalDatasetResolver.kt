package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.ota.DatasetSnapshot
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaResult
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseId

/** Why the dataset of a saved or imported build cannot be opened (client design §5.2 step 4). */
sealed interface DatasetUnavailableReason {
    /** No accepted catalog lists the build; it stays so until one does — nothing is substituted. */
    data object NotPublished : DatasetUnavailableReason

    /** Listed, but the download could not reach the server. */
    data object Offline : DatasetUnavailableReason

    /** Listed, but the download or its verification failed. */
    data class DownloadFailed(val failure: OtaFailure) : DatasetUnavailableReason

    /** Listed only in releases that need a newer app. */
    data class NeedsNewerApp(val minDataLevel: Int) : DatasetUnavailableReason

    /** Listed, but there is not enough free space to install it. */
    data object InsufficientStorage : DatasetUnavailableReason
}

sealed interface DatasetResolution {
    /**
     * The build is in [snapshot] (installed, bundled, or just downloaded); open it with this
     * snapshot. [lease] keeps every release the snapshot reads on disk: the caller owns it and
     * releases it when it stops reading the snapshot (client design §4.5).
     */
    class Available(val snapshot: DatasetSnapshot, val lease: SnapshotLeases.Lease) : DatasetResolution

    data class Unavailable(val bundle: DatasetBundleId, val reason: DatasetUnavailableReason) : DatasetResolution
}

/**
 * Resolves the dataset of a saved or imported build (client design §5.2), in order: an
 * intact installed OTA release of that client build (newest revision), the bundled dataset of
 * that build, a release of that build listed in the **last accepted catalog** — downloaded on
 * demand (install only, never activated), with progress — or a typed [DatasetResolution.Unavailable].
 *
 * A build opens only on its own client build: no other build is ever substituted, even one
 * whose data looks identical, and the record is never touched.
 */
class HistoricalDatasetResolver(
    private val config: OtaConfig,
    private val activation: DatasetActivationManager,
    private val downloads: DatasetDownloadManager,
) {
    fun isAvailable(bundle: DatasetBundleId): Boolean = bundle in activation.snapshot.value.bundles

    /**
     * The snapshot is leased before it is returned, with no suspension in between: a release
     * downloaded here cannot be deleted by retention before its session holds it. Should
     * retention have removed it right after the install (only when a newer revision of the
     * same build is installed meanwhile), the build is resolved once more.
     */
    suspend fun resolve(bundle: DatasetBundleId, onProgress: (InstallProgress) -> Unit = {}): DatasetResolution {
        repeat(MAX_INSTALLS + 1) { installs ->
            activation.leaseCurrent(bundle)?.let { return DatasetResolution.Available(it.snapshot, it.lease) }
            if (installs == MAX_INSTALLS) return@repeat
            if (bundle.game.value != config.game) return DatasetResolution.Unavailable(bundle, DatasetUnavailableReason.NotPublished)
            val listed = activation.lastAcceptedCatalog()?.catalog?.releases.orEmpty().filter { it.clientBuild == bundle.clientBuild }
            if (listed.isEmpty()) return DatasetResolution.Unavailable(bundle, DatasetUnavailableReason.NotPublished)
            val usable = listed.filter { it.minDataLevel <= config.supportedDataLevel }
                .maxByOrNull { ReleaseId.parseOrNull(it.releaseId)!! }
                ?: return DatasetResolution.Unavailable(
                    bundle,
                    DatasetUnavailableReason.NeedsNewerApp(listed.minOf { it.minDataLevel }),
                )
            val installed = downloads.install(usable, onProgress)
            if (installed is OtaResult.Err) return DatasetResolution.Unavailable(bundle, reasonOf(installed.failure))
        }
        return DatasetResolution.Unavailable(
            bundle,
            DatasetUnavailableReason.DownloadFailed(OtaFailure.Io("removed by retention after $MAX_INSTALLS installs")),
        )
    }

    private companion object {
        const val MAX_INSTALLS = 2
    }

    private fun reasonOf(failure: OtaFailure): DatasetUnavailableReason = when (failure) {
        is OtaFailure.Network -> DatasetUnavailableReason.Offline
        is OtaFailure.InsufficientStorage -> DatasetUnavailableReason.InsufficientStorage
        is OtaFailure.IncompatibleDataLevel -> DatasetUnavailableReason.NeedsNewerApp(config.supportedDataLevel + 1)
        else -> DatasetUnavailableReason.DownloadFailed(failure)
    }
}
