package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_rename_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_rename_pending_save_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_error_build_missing
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.BuildRenameOutcome
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.share.BuildCodeSharing
import io.github.alexeykravtsov95.talentforge.ui.share.BuildExport
import io.github.alexeykravtsov95.talentforge.ui.share.BuildExportFailure
import io.github.alexeykravtsov95.talentforge.ui.share.BuildShareSheet
import io.github.alexeykravtsov95.talentforge.ui.share.exportPersistedBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Why the open rename dialog shows an error line (typed; mapped to copy by [BuildActionsOverlays]). */
sealed interface RenameProblem {

    /** Storage refused the rename (or the name was refused): retryable. */
    data object Failed : RenameProblem

    /** The record no longer exists. */
    data object Missing : RenameProblem

    /** The newest talent change of this build is not saved yet: retry saving first. */
    data object PendingSaveFailed : RenameProblem
}

/** The open rename dialog: the record it targets, by opaque id. */
data class RenameTarget(val localId: String)

/** The open share sheet: the record and the exact code read at the tap. */
data class SharedBuildCode(val localId: String, val code: String)

/** A persisted build could not be read for sharing (retryable notice). */
data class ShareFailureNotice(val localId: String, val failure: BuildExportFailure)

/**
 * M5.SHARE.2 — the ONE presenter of the per-build Rename and Share actions,
 * used by Home's Recent rows, All Builds and the editor (no screen has its own
 * rename or share logic):
 *
 * - **Rename** goes through [BuildAutosaveCoordinator.rename] — the repository's
 *   trimming/length rule and metadata-only contract, serialized with autosave —
 *   from the shared [RenameBuildDialog] (current name pre-filled, empty for a
 *   Draft). Only a storage-confirmed rename closes the dialog and reports
 *   [onRenamed]; any failure keeps the typed text and shows a retryable line.
 * - **Share** of a persisted build reads the LATEST stored build of exactly
 *   that `localId` at the tap ([exportPersistedBuild] — `load`, never `open`,
 *   so nothing is written) and shows the code in [BuildShareSheet]. The editor
 *   shares its in-memory build itself and does not use this path.
 *
 * - **Delete** (Home's Recent rows and All Builds — deliberately NOT the
 *   editor, which only offers the recoverable Reset) asks for confirmation in
 *   the shared [DeleteBuildDialog] and calls `LocalBuildRepository.deleteBuild`;
 *   only a storage-confirmed delete closes it and reports [onDeleted].
 *
 * One operation at a time: [launchExclusive] ignores taps while one runs
 * ([busy]), so double taps never rename, delete or read twice; All Builds'
 * Duplicate runs through it too.
 */
@Stable
class BuildActionsPresenter internal constructor(
    private val autosave: BuildAutosaveCoordinator,
    private val repository: LocalBuildRepository,
    private val scope: CoroutineScope,
    private val onRenamed: (LocalBuildRecord) -> Unit,
    private val onRecordMissing: () -> Unit,
    private val onDeleted: () -> Unit,
) {

    var busy by mutableStateOf(false)
        private set

    var renaming by mutableStateOf<RenameTarget?>(null)
        private set

    var renameText by mutableStateOf("")
        private set

    var renameProblem by mutableStateOf<RenameProblem?>(null)
        private set

    var shared by mutableStateOf<SharedBuildCode?>(null)
        private set

    var shareFailure by mutableStateOf<ShareFailureNotice?>(null)
        private set

    /** The record whose delete confirmation is open (Home Recent and All Builds only — never the editor). */
    var deleting by mutableStateOf<String?>(null)
        private set

    var deleteFailed by mutableStateOf(false)
        private set

    /** Runs [action] unless another operation is in flight; `false` when the tap was ignored. */
    fun launchExclusive(action: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true
        scope.launch {
            try {
                action()
            } finally {
                busy = false
            }
        }
        return true
    }

    /** Opens the rename dialog for [localId], pre-filled with its current name (empty for a Draft). */
    fun startRename(localId: String, currentName: String?) {
        if (busy) return
        renameText = currentName.orEmpty()
        renameProblem = null
        renaming = RenameTarget(localId)
    }

    fun updateRenameText(text: String) {
        if (busy) return
        renameText = text
        renameProblem = null
    }

    fun cancelRename() {
        if (busy) return
        renaming = null
        renameProblem = null
    }

    fun confirmRename() {
        val target = renaming ?: return
        val name = renameText
        launchExclusive {
            when (val outcome = autosave.rename(target.localId, name)) {
                is BuildRenameOutcome.Renamed -> {
                    renaming = null
                    renameProblem = null
                    onRenamed(outcome.record)
                }
                BuildRenameOutcome.PendingSaveFailed -> renameProblem = RenameProblem.PendingSaveFailed
                is BuildRenameOutcome.Failed -> if (outcome.error is BuildLibraryError.UnknownRecord) {
                    renameProblem = RenameProblem.Missing
                    onRecordMissing()
                } else {
                    renameProblem = RenameProblem.Failed
                }
            }
        }
    }

    /**
     * Shares the latest persisted build of [localId]; [onSettled] runs once
     * the read finished either way (callers close the sheet they opened it from).
     */
    fun sharePersisted(localId: String, onSettled: () -> Unit = {}) {
        launchExclusive {
            when (val export = withContext(Dispatchers.Default) { exportPersistedBuild(repository, localId) }) {
                is BuildExport.Ready -> {
                    shareFailure = null
                    shared = SharedBuildCode(localId, export.code)
                }
                is BuildExport.Failed -> {
                    shareFailure = ShareFailureNotice(localId, export.failure)
                    if (export.failure.isMissingRecord()) onRecordMissing()
                }
            }
            onSettled()
        }
    }

    fun closeShare() {
        shared = null
    }

    /** Opens the irreversible-delete confirmation for [localId]. */
    fun startDelete(localId: String) {
        if (busy) return
        deleteFailed = false
        deleting = localId
    }

    fun cancelDelete() {
        if (busy) return
        deleting = null
        deleteFailed = false
    }

    /**
     * Deletes the confirmed record. Only a storage-confirmed delete closes the
     * dialog and reports [onDeleted]; a failure keeps it open with a retryable
     * line (a vanished record also reloads the list).
     */
    fun confirmDelete() {
        val localId = deleting ?: return
        launchExclusive {
            when (val outcome = withContext(Dispatchers.Default) { repository.deleteBuild(localId) }) {
                is BuildLibraryOutcome.Loaded -> {
                    deleting = null
                    deleteFailed = false
                    onDeleted()
                }
                is BuildLibraryOutcome.Failed -> {
                    deleteFailed = true
                    if (outcome.error is BuildLibraryError.UnknownRecord) onRecordMissing()
                }
            }
        }
    }
}

private fun BuildExportFailure.isMissingRecord(): Boolean =
    this is BuildExportFailure.Library && error is BuildLibraryError.UnknownRecord

/**
 * The presenter of one screen. [onRenamed] receives the storage-confirmed
 * record (Home and All Builds reload, the editor updates its title);
 * [onRecordMissing] runs when the targeted record turned out to be gone.
 */
@Composable
fun rememberBuildActionsPresenter(
    autosave: BuildAutosaveCoordinator,
    repository: LocalBuildRepository,
    onRenamed: (LocalBuildRecord) -> Unit,
    onRecordMissing: () -> Unit = {},
    onDeleted: () -> Unit = {},
): BuildActionsPresenter {
    val scope = rememberCoroutineScope()
    val renamed by rememberUpdatedState(onRenamed)
    val missing by rememberUpdatedState(onRecordMissing)
    val deleted by rememberUpdatedState(onDeleted)
    return remember(autosave, repository, scope) {
        BuildActionsPresenter(
            autosave = autosave,
            repository = repository,
            scope = scope,
            onRenamed = { renamed(it) },
            onRecordMissing = { missing() },
            onDeleted = { deleted() },
        )
    }
}

/**
 * The presenter's modal surfaces: the rename dialog, the delete confirmation
 * and the persisted-build share sheet, with system Back closing a dialog
 * first (never while its write runs). [titleOf] names the build on the
 * delete dialog and the share sheet.
 */
@Composable
fun BuildActionsOverlays(
    presenter: BuildActionsPresenter,
    sharing: BuildCodeSharing?,
    titleOf: @Composable (localId: String) -> String,
) {
    if (presenter.renaming != null) {
        // registered only while the dialog is shown, so it is the NEWEST
        // handler and wins over the host screen's own (e.g. the editor's
        // deselect-on-Back) — the same rule as BuildShareSheet
        PlatformBackHandler(enabled = true) { presenter.cancelRename() }
        RenameBuildDialog(
            text = presenter.renameText,
            onTextChange = presenter::updateRenameText,
            maxLength = LocalBuildRepository.MAX_BUILD_NAME_LENGTH,
            busy = presenter.busy,
            errorText = presenter.renameProblem?.text(),
            onSave = presenter::confirmRename,
            onCancel = presenter::cancelRename,
        )
    }
    val deleting = presenter.deleting
    if (deleting != null) {
        PlatformBackHandler(enabled = true) { presenter.cancelDelete() }
        DeleteBuildDialog(
            buildTitle = titleOf(deleting),
            busy = presenter.busy,
            error = presenter.deleteFailed,
            onDelete = presenter::confirmDelete,
            onCancel = presenter::cancelDelete,
        )
    }
    val code = presenter.shared
    if (code != null && sharing != null) {
        BuildShareSheet(
            code = code.code,
            buildTitle = titleOf(code.localId),
            sharing = sharing,
            onDismiss = presenter::closeShare,
        )
    }
}

@Composable
private fun RenameProblem.text(): String = when (this) {
    RenameProblem.Failed -> stringResource(Res.string.builds_rename_failed)
    RenameProblem.Missing -> stringResource(Res.string.share_error_build_missing)
    RenameProblem.PendingSaveFailed -> stringResource(Res.string.builds_rename_pending_save_failed)
}
