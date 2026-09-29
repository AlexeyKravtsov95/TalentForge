package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.describe
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The M4.1 production screen: the real, read-only Warrior talent tree.
 *
 * The screen loads exactly the bundled baseline Forever Warrior document
 * through `BundledTalentDatasetRepository` (no JSON in composables) and shows
 * explicit Loading / Ready / Failed states. It is read-only by design: taps
 * select, nothing spends, refunds or persists — allocation UI is deferred to
 * later milestones.
 */
sealed interface TalentTreeUiState {
    data object Loading : TalentTreeUiState

    data class Ready(
        val classDisplayName: String,
        val gameDisplayName: String,
        val model: TalentTreeUiModel,
    ) : TalentTreeUiState

    data class Failed(val failure: TalentTreeUiFailure) : TalentTreeUiState
}

/** Typed screen failures: dataset loading and UI-model building stay distinct. */
sealed interface TalentTreeUiFailure {
    data class Dataset(val error: DatasetLoadError) : TalentTreeUiFailure

    data class UiModel(val error: TalentTreeUiBuildError) : TalentTreeUiFailure
}

/** Human-readable rendering of [TalentTreeUiFailure] for the Failed pane. */
fun TalentTreeUiFailure.describe(): String = when (this) {
    is TalentTreeUiFailure.Dataset -> error.describe()
    is TalentTreeUiFailure.UiModel -> when (error) {
        TalentTreeUiBuildError.NoTrees -> "the dataset contains no talent tree"
        is TalentTreeUiBuildError.MultipleTrees ->
            "the dataset contains multiple talent trees (${error.treeIds.joinToString()}), " +
                "which this screen does not present yet"

        TalentTreeUiBuildError.EmptyTree -> "the talent tree contains no nodes"
        is TalentTreeUiBuildError.DuplicateNodeId ->
            "the talent tree contains duplicate node id ${error.nodeId}"
        is TalentTreeUiBuildError.InvalidLayout ->
            "the talent tree layout is not renderable (xStep=${error.xStep})"
        is TalentTreeUiBuildError.DanglingEdgeReference ->
            "a ${error.kind.name.lowercase()} connector references a missing node " +
                "(${error.fromNodeId} → ${error.toNodeId})"
    }
}

/**
 * Renders [state]. Selection lives here as plain UI state (rememberSaveable):
 * it is independent of the tree data, which is never rebuilt on selection.
 */
@Composable
fun TalentTreeScreen(
    state: TalentTreeUiState,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    var selectedNodeId by rememberSaveable { mutableStateOf<Int?>(null) }
    val viewportState = rememberTalentTreeViewportState()

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        when (val current = state) {
            TalentTreeUiState.Loading -> {
                TreeHeader(title = "TalentForge")
                StatusPane(primary = "Loading talent data…", primaryColor = colors.textSecondary)
            }

            is TalentTreeUiState.Ready -> {
                TreeHeader(title = "${current.classDisplayName} · ${current.gameDisplayName}")
                TalentTreeCanvas(
                    model = current.model,
                    viewportState = viewportState,
                    selectedNodeId = selectedNodeId,
                    onNodeSelected = { selectedNodeId = it },
                    onSelectionCleared = { selectedNodeId = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is TalentTreeUiState.Failed -> {
                TreeHeader(title = "TalentForge")
                StatusPane(
                    primary = "Talent data failed to load",
                    primaryColor = colors.danger,
                    detail = current.failure.describe(),
                )
            }
        }
    }
}

/**
 * Self-loading entry used by the app hosts: loads the bundled baseline
 * Forever Warrior document and maps it to the UI model off the main thread.
 */
@Composable
fun WarriorTalentTreeScreen(modifier: Modifier = Modifier) {
    val state by produceState<TalentTreeUiState>(initialValue = TalentTreeUiState.Loading) {
        value = withContext(Dispatchers.Default) { loadBaselineWarriorTreeState() }
    }
    TalentTreeScreen(state = state, modifier = modifier)
}

/** Identity of the single dataset M4.1 launches into. */
private val BASELINE_GAME = GameId("forever")
private const val BASELINE_CLIENT_BUILD = "1.60.1.70009"
private val BASELINE_CLASS = ClassToken("WARRIOR")

internal suspend fun loadBaselineWarriorTreeState(): TalentTreeUiState {
    val repository = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
    return when (val outcome = repository.document(DatasetId(BASELINE_GAME, BASELINE_CLIENT_BUILD, BASELINE_CLASS))) {
        is DatasetOutcome.Failed ->
            TalentTreeUiState.Failed(TalentTreeUiFailure.Dataset(outcome.error))

        is DatasetOutcome.Loaded ->
            when (val built = buildTalentTreeUiModel(outcome.value)) {
                is TalentTreeUiBuildResult.Failed ->
                    TalentTreeUiState.Failed(TalentTreeUiFailure.UiModel(built.error))

                is TalentTreeUiBuildResult.Ready ->
                    TalentTreeUiState.Ready(
                        classDisplayName = outcome.value.classInfo.name,
                        gameDisplayName = outcome.value.game.value
                            .replaceFirstChar { it.uppercase() },
                        model = built.model,
                    )
            }
    }
}

/**
 * Minimal context row (B3 header: 44 dp, single line). The full B3 header
 * (undo/redo, x/51, spec row) is allocation UI and intentionally absent in
 * M4.1; only the real class/game context is shown.
 */
@Composable
private fun TreeHeader(title: String) {
    val colors = talentForgeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = TalentForgeSpacing.screenX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusPane(
    primary: String,
    primaryColor: androidx.compose.ui.graphics.Color,
    detail: String? = null,
) {
    val colors = talentForgeColors()
    Box(
        modifier = Modifier.fillMaxSize().padding(TalentForgeSpacing.screenX),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = primary,
                style = TalentForgeTypography.componentTitle.copy(color = primaryColor),
            )
            if (detail != null) {
                BasicText(
                    text = detail,
                    style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                )
            }
        }
    }
}
