package io.github.alexeykravtsov95.talentforge.ui.ota

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_dataset_download_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_dataset_insufficient_storage
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_dataset_needs_newer_app
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_dataset_not_published
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_dataset_offline
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_check_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_check_needs_newer_app
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_check_offline
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_check_up_to_date
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_check_updated
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.ui.library.dataVersionOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.jetbrains.compose.resources.stringResource

/** How an editor gets the dataset of a build its session does not have (client design §5.2). */
sealed interface EditorDatasetResolution {
    /** Open the build with these collaborators (a newer snapshot that now contains its build). */
    data class Ready(val session: SessionDatasets) : EditorDatasetResolution

    data class Unavailable(val bundle: DatasetBundleId, val reason: DatasetUnavailableReason) : EditorDatasetResolution
}

/**
 * One screen's lease on the snapshot it reads (client design §4.5): [hold] replaces it when the
 * screen moves to another snapshot, and it is released when the screen leaves the composition.
 * A lease handed over after that is released at once.
 */
class DatasetLeaseHolder : RememberObserver {
    private class Slot(val lease: SnapshotLeases.Lease?, val closed: Boolean)

    private val slot = MutableStateFlow(Slot(null, closed = false))

    fun hold(lease: SnapshotLeases.Lease) {
        val previous = slot.getAndUpdate { if (it.closed) it else Slot(lease, closed = false) }
        if (previous.closed) lease.release() else previous.lease?.release()
    }

    fun close() {
        slot.getAndUpdate { Slot(null, closed = true) }.lease?.release()
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = close()

    override fun onAbandoned() = close()
}

/**
 * One editor's datasets with OTA enabled: the session it reads and the lease that keeps that
 * session's releases on disk until the editor closes (client design §4.5) — the session it
 * opened with, or the one [resolve] downloaded its build into, which then takes over the lease.
 * A build opens on its own client build only; this never substitutes another one.
 */
class EditorDatasetAccess private constructor(
    private val runtime: OtaRuntime,
    /** The session the editor opened with. */
    val session: SessionDatasets,
    lease: SnapshotLeases.Lease,
) : RememberObserver {
    private val leases = DatasetLeaseHolder().also { it.hold(lease) }

    /** Whether the editor's own session has [bundle] — not whether some newer snapshot does. */
    fun isAvailable(bundle: DatasetBundleId): Boolean = bundle in session.snapshot!!.bundles

    suspend fun resolve(bundle: DatasetBundleId, onProgress: (InstallProgress) -> Unit): EditorDatasetResolution =
        when (val resolution = runtime.resolver.resolve(bundle, onProgress)) {
            is DatasetResolution.Available -> {
                leases.hold(resolution.lease)
                EditorDatasetResolution.Ready(SessionDatasets.of(resolution.snapshot))
            }
            is DatasetResolution.Unavailable -> EditorDatasetResolution.Unavailable(resolution.bundle, resolution.reason)
        }

    override fun onRemembered() = Unit

    override fun onForgotten() = leases.close()

    override fun onAbandoned() = leases.close()

    companion object {
        /**
         * Opens an editor on [preferred] — the session current when the screen opened — leased;
         * when retention removed one of its releases meanwhile, on the current snapshot instead.
         */
        fun open(runtime: OtaRuntime, preferred: SessionDatasets): EditorDatasetAccess {
            preferred.snapshot?.let { snapshot -> runtime.activation.lease(snapshot)?.let { return EditorDatasetAccess(runtime, preferred, it) } }
            while (true) {
                val current = runtime.snapshot.value
                runtime.activation.lease(current)?.let { return EditorDatasetAccess(runtime, SessionDatasets.of(current), it) }
            }
        }

        /** The client build a saved record is on — a read of the library that writes nothing (not even `lastOpenedAt`). */
        fun bundleOf(repository: LocalBuildRepository, localId: String): DatasetBundleId? =
            when (val library = repository.load()) {
                is BuildLibraryOutcome.Failed -> null
                is BuildLibraryOutcome.Loaded -> library.value.firstOrNull { it.localId == localId }?.build?.let {
                    DatasetBundleId(it.game, it.clientBuild)
                }
            }
    }
}

/** Why a build's dataset cannot be opened, in the user's language; the diagnostic code stays as reported. */
@Composable
fun DatasetUnavailableReason.text(): String = when (this) {
    DatasetUnavailableReason.NotPublished -> stringResource(Res.string.editor_dataset_not_published)
    DatasetUnavailableReason.Offline -> stringResource(Res.string.editor_dataset_offline)
    DatasetUnavailableReason.InsufficientStorage -> stringResource(Res.string.editor_dataset_insufficient_storage)
    is DatasetUnavailableReason.NeedsNewerApp -> stringResource(Res.string.editor_dataset_needs_newer_app)
    is DatasetUnavailableReason.DownloadFailed -> stringResource(Res.string.editor_dataset_download_failed, failure.diagnostic())
}

/** The last check's outcome as one status line (About), or `null` before the first check. */
@Composable
fun CheckOutcome.text(): String = when (this) {
    is CheckOutcome.Activated -> stringResource(Res.string.ota_check_updated, dataVersionOf(clientBuild), clientBuild)
    is CheckOutcome.Current -> stringResource(Res.string.ota_check_up_to_date)
    is CheckOutcome.NeedsNewerApp -> stringResource(Res.string.ota_check_needs_newer_app)
    is CheckOutcome.Failed -> when (failure) {
        is OtaFailure.Network -> stringResource(Res.string.ota_check_offline)
        else -> stringResource(Res.string.ota_check_failed, failure.diagnostic())
    }
}

/** A short, stable diagnostic for a failure: the contract code, or the client-only condition. */
fun OtaFailure.diagnostic(): String = when (this) {
    is OtaFailure.Contract -> code.name
    is OtaFailure.Http -> "HTTP $status"
    is OtaFailure.Network -> "NETWORK"
    is OtaFailure.InsufficientStorage -> "INSUFFICIENT_STORAGE"
    is OtaFailure.Io -> "IO"
    OtaFailure.Cancelled -> "CANCELLED"
}
