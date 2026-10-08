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
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
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
) {
    var load by remember(localId) { mutableStateOf<EditorLoad>(EditorLoad.Loading) }
    LaunchedEffect(localId) {
        load = EditorLoad.Loading
        load = withContext(Dispatchers.Default) {
            loadEditorSession(repository, datasets, localId, icons, descriptions)
        }
    }

    val colors = talentForgeColors()
    when (val current = load) {
        EditorLoad.Loading -> EditorPane(
            primary = "Opening build…",
            primaryColor = colors.textSecondary,
            onBack = onBack,
        )

        is EditorLoad.Failed -> EditorPane(
            primary = "This build can't be opened",
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
                model = session.model,
                engine = session.engine,
                buildState = session.buildState,
                buildTitle = record.name ?: "Draft",
                classAccent = classAccentFor(record.build.classToken).color,
                icons = session.icons,
                descriptions = session.descriptions,
            ),
            onBack = onBack,
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
                text = "Couldn't save your changes on this device",
                actionText = "Retry",
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

/** Human-readable rendering of [EditorFailure] (typed, never raw exceptions). */
fun EditorFailure.describe(): String = when (this) {
    is EditorFailure.Record -> "the local build library could not be read (${error.describeLibraryError()})"

    is EditorFailure.Dataset -> "its talent data is unavailable on this device (${error.describe()})"

    is EditorFailure.TreeUnavailable ->
        "the build targets tree $buildTreeId, but the dataset only contains trees " +
            "${availableTreeIds.joinToString()}"

    is EditorFailure.Engine -> "the allocation engine rejected this dataset ($reason)"

    is EditorFailure.InvalidBuild ->
        "the saved build is invalid (${reasonCodes.joinToString().lowercase()}) — it was not changed"

    is EditorFailure.UiModel -> "the talent tree cannot be rendered from this dataset"
}

/** Human-readable rendering of a [BuildLibraryError] for the recoverable error states. */
fun BuildLibraryError.describeLibraryError(): String = when (this) {
    is BuildLibraryError.StorageRead -> "storage read failed"
    is BuildLibraryError.StorageWrite -> "storage write failed"
    is BuildLibraryError.Malformed -> "stored data is malformed"
    is BuildLibraryError.UnsupportedStorageVersion -> "stored data uses storage version $actual"
    is BuildLibraryError.UnknownRecord -> "this build no longer exists on this device"
    is BuildLibraryError.IdCollision -> "a unique build id could not be generated ($attempts attempts)"
}
