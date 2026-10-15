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
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_dataset
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
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.EditorFailure
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
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
) {
    var load by remember(localId) { mutableStateOf<EditorLoad>(EditorLoad.Loading) }
    LaunchedEffect(localId) {
        load = EditorLoad.Loading
        load = withContext(Dispatchers.Default) {
            loadEditorSession(repository, datasets, localId, icons, descriptions, localization)
        }
    }

    val colors = talentForgeColors()
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
            autosave = autosave,
            onBack = onBack,
        )
    }
}

@Composable
private fun ReadyBuildEditor(
    session: io.github.alexeykravtsov95.talentforge.ui.library.EditorSession,
    autosave: BuildAutosaveCoordinator,
    onBack: () -> Unit,
) {
    val record = session.record
    var newestFile by remember(record.localId) { mutableStateOf<BuildFile?>(null) }
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
                // a user name verbatim; an unnamed build shows the localized "Draft",
                // resolved here at display time — `record.name` stays null in storage
                buildTitle = record.name ?: stringResource(Res.string.editor_draft_title),
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
    }
}

/** Loading/Failed pane of the editor (44 dp header with back, centred message). */
@Composable
private fun EditorPane(
    primary: String,
    primaryColor: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit,
    detail: String? = null,
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
