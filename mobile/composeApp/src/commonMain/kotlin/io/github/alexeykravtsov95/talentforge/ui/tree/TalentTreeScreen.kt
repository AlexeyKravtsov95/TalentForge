package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.AllocationEngineException
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.describe
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The M4.2 production screen: the read-only Talent Tree plus the adaptive
 * Talent Inspector.
 *
 * The screen loads exactly the bundled baseline Forever Warrior document
 * through `BundledTalentDatasetRepository` (no JSON in composables), shows
 * explicit Loading / Ready / Failed states, and drives every allocation-state
 * visual through the real M3B `TalentAllocationEngine` over an immutable
 * `engine.emptyState()` build. It stays READ ONLY: taps select, Show
 * navigates one prerequisite hop, and nothing spends, refunds or persists —
 * mutation UI belongs to M4.3.
 */
sealed interface TalentTreeUiState {
    data object Loading : TalentTreeUiState

    data class Ready(
        val classDisplayName: String,
        val gameDisplayName: String,
        val model: TalentTreeUiModel,
        val engine: TalentAllocationEngine,
        val buildState: BuildState,
    ) : TalentTreeUiState

    data class Failed(val failure: TalentTreeUiFailure) : TalentTreeUiState
}

/** Typed screen failures: dataset loading, engine construction and UI-model building stay distinct. */
sealed interface TalentTreeUiFailure {
    data class Dataset(val error: DatasetLoadError) : TalentTreeUiFailure

    /** The M3B engine refused this document (typed [AllocationEngineException]). */
    data class AllocationEngine(val reason: String) : TalentTreeUiFailure

    data class UiModel(val error: TalentTreeUiBuildError) : TalentTreeUiFailure
}

/** Human-readable rendering of [TalentTreeUiFailure] for the Failed pane. */
fun TalentTreeUiFailure.describe(): String = when (this) {
    is TalentTreeUiFailure.Dataset -> error.describe()
    is TalentTreeUiFailure.AllocationEngine ->
        "the allocation engine rejected this dataset ($reason)"

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
 * The Show guide ([TalentShowGuide]) and the expanded-inspector flag are pure
 * presentation state; the immutable [BuildState] is never touched.
 */
@Composable
fun TalentTreeScreen(
    state: TalentTreeUiState,
    modifier: Modifier = Modifier,
    viewportState: TalentTreeViewportState = rememberTalentTreeViewportState(),
) {
    val colors = talentForgeColors()
    var selectedNodeId by rememberSaveable { mutableStateOf<Int?>(null) }
    var inspectorExpanded by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable(stateSaver = TalentShowGuideSaver) {
        mutableStateOf<TalentShowGuide?>(null)
    }
    var guideToken by remember { mutableStateOf(0) }

    // System-UI rule: the TalentForge background paints to the physical
    // window edges (background modifier first), and only the *content* is
    // inset by the safe drawing areas (status bar, navigation/gesture bar,
    // display cutouts). The 44 dp header stays a component inside the safe
    // region — the system inset is outside its height. Hosts draw edge to
    // edge (Android enableEdgeToEdge / SwiftUI ignoresSafeArea), so this one
    // padding is the single place insets are applied on every platform.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (val current = state) {
            TalentTreeUiState.Loading -> {
                TreeHeader(title = "TalentForge")
                StatusPane(primary = "Loading talent data…", primaryColor = colors.textSecondary)
            }

            is TalentTreeUiState.Ready -> {
                // Read-only projection of the engine + immutable build state
                // (pure mapping; M4.3 re-runs it when ranks actually change).
                val presentation = remember(current.engine, current.buildState) {
                    buildTalentTreePresentation(current.engine, current.buildState)
                }
                val facts = remember(current.engine) { talentNodeFacts(current.engine) }
                val selectedPresentation = selectedNodeId?.let { presentation.nodes[it] }
                val guideOriginName = showGuide
                    ?.takeIf { it.targetNodeId == selectedNodeId }
                    ?.originNodeId
                    ?.let { facts.nameOf(it) }
                val inspectorContent = selectedPresentation?.let {
                    buildTalentInspectorContent(
                        presentation = it,
                        tree = presentation,
                        facts = facts,
                        guideOriginName = guideOriginName,
                    )
                }

                TreeHeader(
                    title = "${current.classDisplayName} · ${current.gameDisplayName}",
                    pointsText = "${presentation.spentPoints} / ${presentation.pointBudget}",
                )

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val layoutClass = talentTreeLayoutClass(maxWidth.value, maxHeight.value)
                    // Captured before the nested layout lambdas: the scope's
                    // constraints are only implicitly available at this level.
                    val areaHeight = maxHeight
                    // B3 06/UX spec 03-04: the expanded sheet occupies its
                    // preferred 448 dp height, capped at the available height
                    // minus 96 dp. This is the sheet's ACTUAL geometry (and
                    // therefore exactly the covered canvas region the expand
                    // focus uses — never a mismatched estimate).
                    val expandedSheetHeight = minOf(
                        TalentInspectorMetrics.EXPANDED_SHEET_PREFERRED_DP,
                        areaHeight - TalentInspectorMetrics.SHEET_BOTTOM_CLEARANCE_DP,
                    )

                    /**
                     * Pans (animated by the canvas) so the selected node is fully
                     * visible in the canvas area not covered by the inspector.
                     * No-op when it already is (B3: "the canvas stays still").
                     * [reveal] selects the B3 08 minimal-pan mode used by
                     * inspector layout changes; Show keeps the B3 07 centring.
                     */
                    fun focusSelectedNode(
                        coveredRightDp: Float = 0f,
                        coveredBottomDp: Float = 0f,
                        reveal: Boolean = false,
                    ) {
                        val target = selectedNodeId ?: return
                        val canvas = viewportState.canvasSize
                        if (canvas == Size.Zero) return
                        val node = current.model.nodes.firstOrNull { it.id == target } ?: return
                        viewportState.requestFocus(
                            world = Offset(node.centerX, node.centerY),
                            visibleSize = Size(
                                (canvas.width - coveredRightDp).coerceAtLeast(0f),
                                (canvas.height - coveredBottomDp).coerceAtLeast(0f),
                            ),
                            mode = if (reveal) TalentTreeFocusMode.REVEAL else TalentTreeFocusMode.CENTRE,
                        )
                    }

                    /** B3: a plain selection clears any active Show guide. */
                    val onNodeSelected: (Int) -> Unit = { nodeId ->
                        showGuide = null
                        inspectorExpanded = false
                        // B3 05: when the side panel opens it takes 300 dp of the
                        // canvas; the tree pans only if the panel would cover the
                        // node. Later selections keep the (already smaller) canvas.
                        val panelOpening = selectedNodeId == null
                        selectedNodeId = nodeId
                        if (layoutClass == TalentTreeLayoutClass.LANDSCAPE && panelOpening) {
                            focusSelectedNode(
                                coveredRightDp = TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP.value,
                                reveal = true,
                            )
                        }
                    }
                    val onSelectionCleared: () -> Unit = {
                        showGuide = null
                        inspectorExpanded = false
                        selectedNodeId = null
                    }
                    /** B3 07: Show selects the direct prerequisite (one hop, never spends). */
                    val onShowPrerequisite: (Int) -> Unit = { prerequisiteId ->
                        val originId = selectedNodeId
                        if (originId != null && originId != prerequisiteId) {
                            showGuide = TalentShowGuide(originNodeId = originId, targetNodeId = prerequisiteId)
                            guideToken += 1
                            inspectorExpanded = false
                            selectedNodeId = prerequisiteId
                            val coveredBottom = if (layoutClass == TalentTreeLayoutClass.PORTRAIT) {
                                TalentInspectorMetrics.COMPACT_INSET_DP.value * 2f +
                                    TalentInspectorMetrics.COMPACT_HEIGHT_DP.value
                            } else {
                                0f
                            }
                            focusSelectedNode(coveredBottomDp = coveredBottom)
                        }
                    }

                    when (layoutClass) {
                        TalentTreeLayoutClass.TABLET -> Row(Modifier.fillMaxSize()) {
                            TalentTreeCanvas(
                                model = current.model,
                                presentation = presentation,
                                viewportState = viewportState,
                                layoutClass = layoutClass,
                                selectedNodeId = selectedNodeId,
                                showGuide = showGuide,
                                guideToken = guideToken,
                                onNodeSelected = onNodeSelected,
                                onSelectionCleared = onSelectionCleared,
                                modifier = Modifier.weight(1f),
                            )
                            TalentInspectorPersistentPanel(
                                content = inspectorContent,
                                onShowPrerequisite = onShowPrerequisite,
                            )
                        }

                        TalentTreeLayoutClass.LANDSCAPE -> Row(Modifier.fillMaxSize()) {
                            TalentTreeCanvas(
                                model = current.model,
                                presentation = presentation,
                                viewportState = viewportState,
                                layoutClass = layoutClass,
                                selectedNodeId = selectedNodeId,
                                showGuide = showGuide,
                                guideToken = guideToken,
                                onNodeSelected = onNodeSelected,
                                onSelectionCleared = onSelectionCleared,
                                modifier = Modifier.weight(1f),
                            )
                            if (inspectorContent != null) {
                                TalentInspectorSidePanel(
                                    content = inspectorContent,
                                    onDeselect = onSelectionCleared,
                                    onShowPrerequisite = onShowPrerequisite,
                                )
                            }
                        }

                        TalentTreeLayoutClass.PORTRAIT -> Box(Modifier.fillMaxSize()) {
                            TalentTreeCanvas(
                                model = current.model,
                                presentation = presentation,
                                viewportState = viewportState,
                                layoutClass = layoutClass,
                                selectedNodeId = selectedNodeId,
                                showGuide = showGuide,
                                guideToken = guideToken,
                                onNodeSelected = onNodeSelected,
                                onSelectionCleared = onSelectionCleared,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Column(Modifier.align(Alignment.BottomCenter)) {
                                if (inspectorExpanded && inspectorContent != null) {
                                    TalentInspectorExpandedSheet(
                                        content = inspectorContent,
                                        height = expandedSheetHeight,
                                        onCollapse = { inspectorExpanded = false },
                                        onDeselect = onSelectionCleared,
                                        onShowPrerequisite = onShowPrerequisite,
                                    )
                                } else {
                                    TalentInspectorCompactBar(
                                        content = inspectorContent,
                                        onToggleExpanded = {
                                            inspectorExpanded = !inspectorExpanded
                                            if (inspectorExpanded) {
                                                // B3 08: "Expanding pans the canvas the
                                                // minimum amount needed, and only when
                                                // the node would be covered" — the covered
                                                // region is the sheet's actual height.
                                                focusSelectedNode(
                                                    coveredBottomDp = expandedSheetHeight.value,
                                                    reveal = true,
                                                )
                                            }
                                        },
                                        onShowPrerequisite = onShowPrerequisite,
                                    )
                                }
                            }
                        }
                    }
                }
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

/** Saveable [TalentShowGuide] (survives recomposition and configuration change). */
private val TalentShowGuideSaver = mapSaver<TalentShowGuide?>(
    save = { guide ->
        if (guide == null) {
            emptyMap()
        } else {
            mapOf("origin" to guide.originNodeId, "target" to guide.targetNodeId)
        }
    },
    restore = { values ->
        val origin = values["origin"] as? Int
        val target = values["target"] as? Int
        if (origin != null && target != null) TalentShowGuide(origin, target) else null
    },
)

/**
 * Self-loading entry used by the app hosts: loads the bundled baseline
 * Forever Warrior document and maps it to the UI model + read-only engine
 * session off the main thread.
 */
@Composable
fun WarriorTalentTreeScreen(modifier: Modifier = Modifier) {
    val state by produceState<TalentTreeUiState>(initialValue = TalentTreeUiState.Loading) {
        value = withContext(Dispatchers.Default) { loadBaselineWarriorTreeState() }
    }
    TalentTreeScreen(state = state, modifier = modifier)
}

/** Identity of the single dataset M4.2 launches into. */
private val BASELINE_GAME = GameId("forever")
private const val BASELINE_CLIENT_BUILD = "1.60.1.70009"
private val BASELINE_CLASS = ClassToken("WARRIOR")

internal suspend fun loadBaselineWarriorTreeState(): TalentTreeUiState {
    val repository = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
    return when (val outcome = repository.document(DatasetId(BASELINE_GAME, BASELINE_CLIENT_BUILD, BASELINE_CLASS))) {
        is DatasetOutcome.Failed ->
            TalentTreeUiState.Failed(TalentTreeUiFailure.Dataset(outcome.error))

        is DatasetOutcome.Loaded -> loadWarriorTreeState(outcome.value)
    }
}

/** Builds the Ready session (UI model + read-only engine over an empty build) from a loaded document. */
internal fun loadWarriorTreeState(document: TalentDataDocument): TalentTreeUiState {
    val engine = try {
        TalentAllocationEngine(document)
    } catch (exception: AllocationEngineException) {
        return TalentTreeUiState.Failed(TalentTreeUiFailure.AllocationEngine(exception.message ?: "unknown"))
    }
    return when (val built = buildTalentTreeUiModel(document)) {
        is TalentTreeUiBuildResult.Failed ->
            TalentTreeUiState.Failed(TalentTreeUiFailure.UiModel(built.error))

        is TalentTreeUiBuildResult.Ready ->
            TalentTreeUiState.Ready(
                classDisplayName = document.classInfo.name,
                gameDisplayName = document.game.value.replaceFirstChar { it.uppercase() },
                model = built.model,
                engine = engine,
                buildState = engine.emptyState(),
            )
    }
}

/**
 * Minimal context row (B3 header: 44 dp, single line). The full B3 header
 * (undo/redo, spec row) is allocation-mutation UI and intentionally absent in
 * M4.2; the real read-only point counter (engine `spentPoints`/`pointBudget`)
 * is the one allocation fact the header shows.
 */
@Composable
private fun TreeHeader(
    title: String,
    pointsText: String? = null,
) {
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
        Spacer(Modifier.weight(1f))
        if (pointsText != null) {
            BasicText(
                text = pointsText,
                style = TalentForgeTypography.value.copy(color = colors.textPrimary),
                maxLines = 1,
            )
        }
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
