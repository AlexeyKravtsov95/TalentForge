package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.describe
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_retry
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_autosave_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_draft_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_dataset_downloading
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_dataset_progress
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_dataset_unavailable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_dataset
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.ui.library.SecondaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.ota.DatasetUnavailableReason
import io.github.alexeykravtsov95.talentforge.ui.ota.EditorDatasetAccess
import io.github.alexeykravtsov95.talentforge.ui.ota.EditorDatasetResolution
import io.github.alexeykravtsov95.talentforge.ui.ota.InstallProgress
import io.github.alexeykravtsov95.talentforge.ui.ota.SessionDatasets
import io.github.alexeykravtsov95.talentforge.ui.ota.text
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_engine
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_invalid_build
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_library
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_tree_unavailable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_ui_model
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_open_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_opening_build
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_error_id_collision
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_error_invalid_name
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_error_malformed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_error_storage_read
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_error_storage_write
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_error_unknown_record
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_error_unsupported_version
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentLocalizationLoader
import androidx.compose.runtime.collectAsState
import io.github.alexeykravtsov95.talentforge.ui.builds.BuildActionsOverlays
import io.github.alexeykravtsov95.talentforge.ui.builds.rememberBuildActionsPresenter
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.EditorFailure
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import io.github.alexeykravtsov95.talentforge.ui.share.BuildCodeSharing
import io.github.alexeykravtsov95.talentforge.ui.share.BuildExport
import io.github.alexeykravtsov95.talentforge.ui.share.BuildExportFailure
import io.github.alexeykravtsov95.talentforge.ui.share.BuildShareSheet
import io.github.alexeykravtsov95.talentforge.ui.share.exportEditorState
import io.github.alexeykravtsov95.talentforge.ui.share.text
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The M5.1 generic build editor host: opens a locally stored build by its
 * opaque `localId` (never by passing mutable UI state between screens),
 * resolves and validates it fail-closed through [loadEditorSession], and
 * feeds **autosave** into the app-level [BuildAutosaveCoordinator]: the one
 * [TalentTreeScreen.onBuildStateChanged] observer converts the current
 * [io.github.alexeykravtsov95.talentforge.allocation.BuildState] into the
 * record's portable [BuildFile] and submits it to the coordinator, whose
 * lifetime sits ABOVE this screen — leaving the editor can therefore never
 * cancel a pending save (back navigation flushes the coordinator first; see
 * App).
 *
 * Failure contract: the editor stays fully usable on a failed write, a
 * visible non-blocking notice offers Retry (a re-submission of the newest
 * state), the in-memory build is never rolled back, no duplicate record is
 * ever created to "recover", and a later successful save clears the error.
 *
 * M5.SHARE.2 export ([sharing] non-null): `Share build` in the ⋯ sheet
 * encodes the session's CURRENT in-memory build — read synchronously at the
 * tap and bound to this record's dataset identity — and shows the code in
 * [BuildShareSheet]. Sharing and saving are separate: a pending or failed
 * autosave neither blocks nor changes the shared code, and the save-error
 * notice stays as it was. Each Share re-encodes; no code is cached across
 * edits (the sheet is modal, so the build cannot change while it is open).
 *
 * M5.SHARE.2 rename: `Rename` in the ⋯ sheet opens the app's shared rename
 * dialog (the persisted name pre-filled, empty for a Draft) through the one
 * [io.github.alexeykravtsov95.talentforge.ui.builds.BuildActionsPresenter].
 * The write is [BuildAutosaveCoordinator.rename]: it waits for every pending
 * autosave, never runs concurrently with one, and is refused while the newest
 * save of this build has failed (the dialog says so; the save notice keeps its
 * Retry). Only a storage-confirmed rename changes the header title — the
 * editing session itself is never recreated.
 */
@Composable
fun TalentTreeBuildScreen(
    localId: String,
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    autosave: BuildAutosaveCoordinator,
    onBack: () -> Unit,
    icons: TalentIconsLoader? = null,
    descriptions: TalentDescriptionsLoader? = null,
    localization: TalentLocalizationLoader? = null,
    sharing: BuildCodeSharing? = null,
    /**
     * M5.OTA.3, OTA-enabled builds only: a saved build whose client build this session does not
     * have is resolved per client design §5.2 — downloaded on demand with progress, or shown as
     * typed unavailable with Retry. The record is never touched and no other build is used.
     */
    datasetAccess: EditorDatasetAccess? = null,
) {
    var load by remember(localId) { mutableStateOf<EditorLoad>(EditorLoad.Loading) }
    var acquisition by remember(localId) { mutableStateOf<DatasetAcquisition?>(null) }
    var attempt by remember(localId) { mutableStateOf(0) }
    LaunchedEffect(localId, attempt) {
        load = EditorLoad.Loading
        acquisition = null
        var session: SessionDatasets? = null
        if (datasetAccess != null) {
            val bundle = withContext(Dispatchers.Default) { EditorDatasetAccess.bundleOf(repository, localId) }
            if (bundle != null && !datasetAccess.isAvailable(bundle)) {
                acquisition = DatasetAcquisition.Downloading(bundle, null)
                val resolution = withContext(Dispatchers.Default) {
                    datasetAccess.resolve(bundle) { progress -> acquisition = DatasetAcquisition.Downloading(bundle, progress) }
                }
                when (resolution) {
                    is EditorDatasetResolution.Ready -> session = resolution.session
                    is EditorDatasetResolution.Unavailable -> {
                        acquisition = DatasetAcquisition.Unavailable(resolution.bundle, resolution.reason)
                        return@LaunchedEffect
                    }
                }
                acquisition = null
            }
        }
        load = withContext(Dispatchers.Default) {
            val resolved = session
            if (resolved != null) {
                loadEditorSession(repository, resolved.datasets, localId, resolved.icons, resolved.descriptions, resolved.localization)
            } else {
                loadEditorSession(repository, datasets, localId, icons, descriptions, localization)
            }
        }
    }

    val colors = talentForgeColors()
    when (val pending = acquisition) {
        is DatasetAcquisition.Downloading -> {
            EditorPane(
                primary = stringResource(Res.string.editor_dataset_downloading, pending.bundle.clientBuild),
                primaryColor = colors.textSecondary,
                detail = pending.progress?.let { stringResource(Res.string.editor_dataset_progress, it.filesDone, it.filesTotal) },
                onBack = onBack,
            )
            return
        }

        is DatasetAcquisition.Unavailable -> {
            EditorPane(
                primary = stringResource(Res.string.editor_dataset_unavailable, pending.bundle.clientBuild),
                primaryColor = colors.danger,
                detail = pending.reason.text(),
                onBack = onBack,
                actionText = stringResource(Res.string.common_retry),
                onAction = { attempt += 1 },
            )
            return
        }

        null -> Unit
    }
    when (val current = load) {
        EditorLoad.Loading -> EditorPane(
            primary = stringResource(Res.string.editor_opening_build),
            primaryColor = colors.textSecondary,
            onBack = onBack,
        )

        is EditorLoad.Failed -> EditorPane(
            primary = stringResource(Res.string.editor_open_failed),
            primaryColor = colors.danger,
            detail = current.failure.describe(),
            onBack = onBack,
        )

        is EditorLoad.Ready -> ReadyBuildEditor(
            session = current.session,
            repository = repository,
            autosave = autosave,
            onBack = onBack,
            sharing = sharing,
        )
    }
}

@Composable
private fun ReadyBuildEditor(
    session: io.github.alexeykravtsov95.talentforge.ui.library.EditorSession,
    repository: LocalBuildRepository,
    autosave: BuildAutosaveCoordinator,
    onBack: () -> Unit,
    sharing: BuildCodeSharing?,
) {
    val record = session.record
    var newestFile by remember(record.localId) { mutableStateOf<BuildFile?>(null) }
    // the open share sheet's code (saveable: rotation keeps the sheet — the
    // session restores the same build, and the sheet is modal)
    var shareCode by rememberSaveable(record.localId) { mutableStateOf<String?>(null) }
    var exportFailure by remember(record.localId) { mutableStateOf<BuildExportFailure?>(null) }
    // M5.SHARE.2: the PERSISTED name — the session's at open, then whatever a
    // storage-confirmed rename returned. Only the title changes: the editing
    // session (allocation, history, selection, cameras) never restarts.
    var persistedName by remember(record.localId) { mutableStateOf(record.name) }
    val actions = rememberBuildActionsPresenter(
        autosave = autosave,
        repository = repository,
        onRenamed = { renamed -> persistedName = renamed.name },
    )
    // a user name verbatim; an unnamed build shows the localized "Draft",
    // resolved here at display time — `record.name` stays null in storage
    val buildTitle = persistedName ?: stringResource(Res.string.editor_draft_title)
    val lastOutcome by autosave.lastOutcome.collectAsState()
    val saveFailed = lastOutcome?.let { it.localId == record.localId && it.failed } == true

    Box(Modifier.fillMaxSize()) {
        TalentTreeScreen(
            state = TalentTreeUiState.Ready(
                classDisplayName = session.classDisplayName,
                gameDisplayName = session.gameDisplayName,
                classToken = record.build.classToken,
                model = session.model,
                engine = session.engine,
                buildState = session.buildState,
                buildTitle = buildTitle,
                classAccent = classAccentFor(record.build.classToken).color,
                icons = session.icons,
                descriptions = session.descriptions,
                // M5.REL.3b.2: picked by the app language inside the screen, so a language
                // change re-projects the text and never reloads or resets the session
                localization = session.localization,
            ),
            onBack = onBack,
            // M5.UX.1: section cameras belong to THIS build only
            sessionKey = record.localId,
            onBuildStateChanged = { state ->
                val file = buildFileOf(
                    state = state,
                    game = record.build.game,
                    clientBuild = record.build.clientBuild,
                    classToken = record.build.classToken,
                )
                newestFile = file
                autosave.submit(localId = record.localId, build = file)
            },
            onShareBuild = sharing?.let {
                { state ->
                    when (val export = exportEditorState(state, identity = record.build)) {
                        is BuildExport.Ready -> {
                            exportFailure = null
                            shareCode = export.code
                        }
                        is BuildExport.Failed -> exportFailure = export.failure
                    }
                }
            },
            onRenameBuild = { actions.startRename(record.localId, persistedName) },
        )
        if (saveFailed) {
            InlineNotice(
                text = stringResource(Res.string.editor_autosave_failed),
                actionText = stringResource(Res.string.common_retry),
                onAction = { newestFile?.let { autosave.submit(record.localId, it) } },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp + TalentForgeSpacing.inline)
                    .padding(horizontal = TalentForgeSpacing.screenX),
            )
        }
        exportFailure?.let { failure ->
            InlineNotice(
                text = failure.text(),
                actionText = null,
                onAction = null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp + TalentForgeSpacing.inline)
                    .padding(horizontal = TalentForgeSpacing.screenX),
            )
        }
        val code = shareCode
        if (code != null && sharing != null) {
            BuildShareSheet(
                code = code,
                buildTitle = buildTitle,
                sharing = sharing,
                onDismiss = { shareCode = null },
            )
        }
        // the rename dialog (the editor shares its in-memory build above, never
        // through the presenter's persisted-build path)
        BuildActionsOverlays(presenter = actions, sharing = null, titleOf = { buildTitle })
    }
}

/** The editor's progress or typed unavailable state while a historical dataset is acquired (M5.OTA.3). */
private sealed interface DatasetAcquisition {
    val bundle: DatasetBundleId

    data class Downloading(override val bundle: DatasetBundleId, val progress: InstallProgress?) : DatasetAcquisition

    data class Unavailable(override val bundle: DatasetBundleId, val reason: DatasetUnavailableReason) : DatasetAcquisition
}

/** Loading/Failed pane of the editor (44 dp header with back, centred message, optional action). */
@Composable
private fun EditorPane(
    primary: String,
    primaryColor: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit,
    detail: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = talentForgeColors()
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            BackChevronButton(onClick = onBack)
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(TalentForgeSpacing.screenX),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(primary, style = TalentForgeTypography.componentTitle.copy(color = primaryColor))
                if (detail != null) {
                    BasicText(
                        detail,
                        style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                    )
                }
                if (actionText != null && onAction != null) {
                    SecondaryActionButton(
                        text = actionText,
                        onClickLabel = actionText,
                        onClick = onAction,
                        modifier = Modifier.padding(top = TalentForgeSpacing.rowGap),
                    )
                }
            }
        }
    }
}

/**
 * Localized rendering of [EditorFailure] (typed, never raw exceptions;
 * M5.REL.2b). Embedded technical details — the shared dataset diagnostic,
 * the engine's reason and the validation reason codes — stay as reported:
 * they are diagnostics, not product copy.
 */
@Composable
fun EditorFailure.describe(): String = when (this) {
    is EditorFailure.Record -> stringResource(Res.string.editor_failure_library, error.describeLibraryError())

    is EditorFailure.Dataset -> stringResource(Res.string.editor_failure_dataset, error.describe())

    is EditorFailure.TreeUnavailable ->
        stringResource(Res.string.editor_failure_tree_unavailable, buildTreeId, availableTreeIds.joinToString())

    is EditorFailure.Engine -> stringResource(Res.string.editor_failure_engine, reason)

    is EditorFailure.InvalidBuild ->
        stringResource(Res.string.editor_failure_invalid_build, reasonCodes.joinToString().lowercase())

    is EditorFailure.UiModel -> stringResource(Res.string.editor_failure_ui_model)
}

/** Localized rendering of a [BuildLibraryError] for the recoverable error states. */
@Composable
fun BuildLibraryError.describeLibraryError(): String = when (this) {
    is BuildLibraryError.StorageRead -> stringResource(Res.string.library_error_storage_read)
    is BuildLibraryError.StorageWrite -> stringResource(Res.string.library_error_storage_write)
    is BuildLibraryError.Malformed -> stringResource(Res.string.library_error_malformed)
    is BuildLibraryError.UnsupportedStorageVersion ->
        stringResource(Res.string.library_error_unsupported_version, actual)

    is BuildLibraryError.UnknownRecord -> stringResource(Res.string.library_error_unknown_record)
    is BuildLibraryError.IdCollision -> pluralStringResource(Res.plurals.library_error_id_collision, attempts, attempts)
    is BuildLibraryError.InvalidBuildName ->
        pluralStringResource(Res.plurals.library_error_invalid_name, maxLength, maxLength)
}
