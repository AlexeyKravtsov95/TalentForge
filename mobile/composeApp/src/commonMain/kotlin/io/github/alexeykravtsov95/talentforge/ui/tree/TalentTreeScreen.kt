package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.delay

/**
 * The M4.3/M5.1 production screen: the Talent Tree as an interactive **local
 * build editor**.
 *
 * The screen renders explicit Loading / Ready / Failed states and owns the
 * in-memory editing session ([TalentBuildEditingState]): which immutable
 * [BuildState] is current, plus undo/redo history. Every mutation — +, −, Max,
 * Reset — goes through the real M3B `TalentAllocationEngine`
 * (`spend`/`refund`/`emptyState`); nothing else ever touches ranks. Node taps
 * still only select; Show still navigates exactly one prerequisite hop and
 * never spends.
 *
 * Since M5.1 the screen is dataset-generic: callers hand it a Ready state for
 * ANY bundled class (editor host [TalentTreeBuildScreen]) plus the build's
 * title line and its class accent; the former hardcoded Warrior entry remains
 * only as the baseline test loader ([loadBaselineWarriorTreeState]).
 *
 * After every successful mutation the whole M4.2 presentation (node states,
 * rank chips, counters, connectors, tier meters) is recomputed from
 * `engine + current BuildState` — no UI state is patched by hand.
 */
sealed interface TalentTreeUiState {
    data object Loading : TalentTreeUiState

    data class Ready(
        val classDisplayName: String,
        val gameDisplayName: String,
        val model: TalentTreeUiModel,
        val engine: TalentAllocationEngine,
        /**
         * The **initial** build the editing session starts from. Production
         * passes the build opened from the local library (a draft starts from
         * the engine's empty state); tests may inject a prepared build. The
         * live build is the editing session's `current`, never this field.
         */
        val buildState: BuildState,
        /** Header line 1: the build's name, or "Draft" while unnamed. */
        val buildTitle: String,
        /**
         * The class accent of THIS build's class — an explicit presentation
         * input since M5.1 (Warrior: the spec-frozen accent; other classes:
         * the documented neutral fallback). Never read from the theme
         * directly: the Warrior token is semantically Warrior-only.
         */
        val classAccent: androidx.compose.ui.graphics.Color,
        /**
         * Real talent icons by node id (M5.V1), decoded before the state is
         * built. A node without one renders its monogram tile; the default is
         * "no icons", so lower-level callers keep working without visuals.
         */
        val icons: TalentNodeIcons = TalentNodeIcons.None,
        /**
         * Verified talent descriptions by node id (M5.V2), loaded before the
         * state is built. The default "none" leaves the inspector without a
         * description section, so lower-level callers keep working.
         */
        val descriptions: TalentNodeDescriptions = TalentNodeDescriptions.None,
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

/** One successful purchase's node feedback (the token restarts the glow/pop). */
private data class TalentPurchaseFeedback(
    val nodeId: Int,
    val token: Int,
)

/** One denied action's temporary state-line reason (B3 07: 2.6 s). */
private data class TalentBlockedNotice(
    val nodeId: Int,
    val reason: String,
    val token: Int,
)

/** B3 07: the blocked-action reason shows in the state line for 2.6 s. */
private const val BLOCKED_REASON_MS: Long = 2600

/**
 * Renders [state]. Selection, the Show guide, inspector expansion and the
 * build-editing session live here as plain UI state (the session is
 * `rememberSaveable`-survived: current build + both histories persist across
 * recomposition and configuration change; only allocation triples are
 * serialized, never the engine). The [BuildState]s themselves stay immutable.
 *
 * M5.1 wiring: [onBack] renders the header's back affordance (the screen is
 * no longer the app root), and [onBuildStateChanged] is the ONE observer of
 * the current build actually changing — every successful mutation (spend,
 * refund, Max, undo, redo, reset) reports through it, and the autosave owner
 * above converts the reported state into the persisted BuildFile. Selection,
 * pan, zoom, Show and denied actions never report. Both are optional so the
 * lower-level M4 usage (tests, previews) still works standalone.
 */
@Composable
fun TalentTreeScreen(
    state: TalentTreeUiState,
    modifier: Modifier = Modifier,
    viewportState: TalentTreeViewportState = rememberTalentTreeViewportState(),
    onBack: (() -> Unit)? = null,
    onBuildStateChanged: ((BuildState) -> Unit)? = null,
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (val current = state) {
            TalentTreeUiState.Loading -> Column(Modifier.fillMaxSize()) {
                TreeHeader(title = "TalentForge", onBack = onBack)
                StatusPane(primary = "Loading talent data…", primaryColor = colors.textSecondary)
            }

            is TalentTreeUiState.Ready -> ReadyTalentTreeScreen(
                current = current,
                selectedNodeId = selectedNodeId,
                onSelectedNodeIdChange = { selectedNodeId = it },
                inspectorExpanded = inspectorExpanded,
                onInspectorExpandedChange = { inspectorExpanded = it },
                showGuide = showGuide,
                onShowGuideChange = { showGuide = it },
                guideToken = guideToken,
                onGuideTokenAdvance = { guideToken += 1 },
                viewportState = viewportState,
                onBack = onBack,
                onBuildStateChanged = onBuildStateChanged,
            )

            is TalentTreeUiState.Failed -> Column(Modifier.fillMaxSize()) {
                TreeHeader(title = "TalentForge", onBack = onBack)
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
 * The Ready workspace: header (title · undo · redo · x / 51 · ⋯), the
 * adaptive canvas/inspector layouts, the editing session that drives them,
 * and the ⋯ / reset overlays over the whole workspace.
 */
@Composable
private fun ReadyTalentTreeScreen(
    current: TalentTreeUiState.Ready,
    selectedNodeId: Int?,
    onSelectedNodeIdChange: (Int?) -> Unit,
    inspectorExpanded: Boolean,
    onInspectorExpandedChange: (Boolean) -> Unit,
    showGuide: TalentShowGuide?,
    onShowGuideChange: (TalentShowGuide?) -> Unit,
    guideToken: Int,
    onGuideTokenAdvance: () -> Unit,
    viewportState: TalentTreeViewportState,
    onBack: (() -> Unit)?,
    onBuildStateChanged: ((BuildState) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // The editing session: which immutable BuildState is current + history.
    // Keyed by the engine, saved as allocation triples only (never the
    // engine); restored across recomposition and configuration change.
    var editing by rememberSaveable(current.engine, stateSaver = TalentBuildEditingState.Saver) {
        mutableStateOf(TalentBuildEditingState(current.buildState))
    }

    // Restoration safety net for the autosave contract: a configuration
    // change rebuilds the whole composition — including the save pipeline. If
    // the restored session is newer than the persisted build this editor was
    // opened from (a mutation whose save was still in flight), re-submit it
    // through the ONE change path so it still reaches storage. A fresh open
    // starts exactly at the persisted build, so this is a no-op there.
    LaunchedEffect(current.engine) {
        if (editing.current != current.buildState) {
            onBuildStateChanged?.invoke(editing.current)
        }
    }

    // Feedback state (transient by design — animations and notices, not build
    // history; they die with the process rather than being serialized).
    var purchaseFeedback by remember { mutableStateOf<TalentPurchaseFeedback?>(null) }
    var blockedNotice by remember { mutableStateOf<TalentBlockedNotice?>(null) }
    var feedbackToken by remember { mutableStateOf(0) }
    var buildActionsOpen by rememberSaveable { mutableStateOf(false) }
    var resetNotice by remember { mutableStateOf<TalentResetNotice?>(null) }
    var resetNoticeToken by remember { mutableStateOf(0) }

    // The one source of allocation truth for every visual: engine + current.
    val presentation = remember(current.engine, editing.current) {
        buildTalentTreePresentation(current.engine, editing.current)
    }
    val facts = remember(current.engine) { talentNodeFacts(current.engine) }
    val selectedPresentation = selectedNodeId?.let { presentation.nodes[it] }
    // The selected talent's real icon for the inspector header (null → no tile).
    val selectedIcon = selectedNodeId?.let { current.icons.iconOf(it) }
    val selectedActions = selectedPresentation?.let {
        buildTalentNodeActions(current.engine, editing.current, it)
    }
    val guideOriginName = showGuide
        ?.takeIf { it.targetNodeId == selectedNodeId }
        ?.originNodeId
        ?.let { facts.nameOf(it) }
    // B3 04 state 07 / UX/UI spec G: a disabled control is always paired with
    // its reason, in the steady state — a correctly disabled button can never
    // be clicked to produce a denial, so the explanation is derived up front
    // from the same typed issues that disabled it.
    val disabledActionReason = selectedActions?.disabledActionExplanation(
        facts = facts,
        spentPoints = presentation.spentPoints,
        pointBudget = presentation.pointBudget,
    )
    val selectedBlockedReason = blockedNotice
        ?.takeIf { it.nodeId == selectedNodeId }
        ?.reason
        ?: disabledActionReason
    val inspectorContent = selectedPresentation?.let {
        buildTalentInspectorContent(
            presentation = it,
            tree = presentation,
            facts = facts,
            guideOriginName = guideOriginName,
            blockedReason = selectedBlockedReason,
            descriptions = current.descriptions,
        )
    }

    // B3 07: "A blocked refund shows its reason in the state line for 2.6 s."
    LaunchedEffect(blockedNotice) {
        if (blockedNotice != null) {
            delay(BLOCKED_REASON_MS)
            blockedNotice = null
        }
    }

    /**
     * The ONE commit path of the editing session (M5.1 autosave contract):
     * every successful mutation — spend, refund, Max, undo, redo, reset —
     * lands here and here only, and [onBuildStateChanged] fires exactly when
     * the current [BuildState] actually changed. Denied actions, selection,
     * pan/zoom, Show and inspector expansion never call it. There is no
     * second mutation route anywhere in the screen.
     */
    fun commitEditing(next: TalentBuildEditingState) {
        val before = editing.current
        editing = next
        if (next.current != before) {
            onBuildStateChanged?.invoke(next.current)
        }
    }

    /**
     * Applies one editing result. The engine result is authoritative even
     * though the buttons were enabled/disabled from the same decisions: a
     * denied action changes nothing (state, history, counters) and only shows
     * its reason in the state line; a successful mutation clears the
     * temporary Show guide (documented conservative choice — the guide
     * explains *why a rank cannot be spent yet*, which a mutation may
     * change) while keeping the selected node, the viewport and the
     * inspector layout untouched.
     */
    fun applyEdit(result: TalentBuildEditResult, purchasedNodeId: Int? = null) {
        if (result.changed) {
            commitEditing(result.editing)
            onShowGuideChange(null)
            blockedNotice = null
            // The reset notice promises "Undo restores what Reset cleared" —
            // that is true only while Reset is still the LATEST successful
            // history operation. Any later successful mutation (spend/refund/
            // Max) would make its generic Undo revert the wrong action, so
            // the notice goes; a denied action leaves it (history unchanged).
            resetNotice = null
            if (purchasedNodeId != null) {
                feedbackToken += 1
                purchaseFeedback = TalentPurchaseFeedback(purchasedNodeId, feedbackToken)
            }
        } else {
            val nodeId = purchasedNodeId ?: selectedNodeId ?: return
            feedbackToken += 1
            blockedNotice = TalentBlockedNotice(
                nodeId = nodeId,
                reason = blockedActionReason(result.issues, facts),
                token = feedbackToken,
            )
        }
    }

    /** Undo/redo touch ONLY the BuildState — never selection, pan, zoom or inspector layout. */
    fun performUndo() {
        val undone = editing.undo() ?: return
        commitEditing(undone)
        blockedNotice = null
        resetNotice = null
    }

    fun performRedo() {
        val redone = editing.redo() ?: return
        commitEditing(redone)
        blockedNotice = null
        resetNotice = null
    }

    val onSpendRank: (Int) -> Unit = { nodeId ->
        applyEdit(editing.spend(current.engine, nodeId), purchasedNodeId = nodeId)
    }
    val onRefundRank: (Int) -> Unit = { nodeId ->
        applyEdit(editing.refund(current.engine, nodeId))
    }
    val onMaxRank: (Int) -> Unit = { nodeId ->
        applyEdit(editing.spendToMax(current.engine, nodeId), purchasedNodeId = nodeId)
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TalentTreeHeaderBar(
                title = current.buildTitle,
                meta = "${current.classDisplayName} · ${current.gameDisplayName}",
                pointsText = "${presentation.spentPoints} / ${presentation.pointBudget}",
                canUndo = editing.canUndo,
                canRedo = editing.canRedo,
                onUndo = ::performUndo,
                onRedo = ::performRedo,
                onOpenBuildActions = { buildActionsOpen = true },
                onBack = onBack,
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
                    onShowGuideChange(null)
                    onInspectorExpandedChange(false)
                    // B3 05: when the side panel opens it takes 300 dp of the
                    // canvas; the tree pans only if the panel would cover the
                    // node. Later selections keep the (already smaller) canvas.
                    val panelOpening = selectedNodeId == null
                    onSelectedNodeIdChange(nodeId)
                    if (layoutClass == TalentTreeLayoutClass.LANDSCAPE && panelOpening) {
                        focusSelectedNode(
                            coveredRightDp = TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP.value,
                            reveal = true,
                        )
                    }
                }
                val onSelectionCleared: () -> Unit = {
                    onShowGuideChange(null)
                    onInspectorExpandedChange(false)
                    onSelectedNodeIdChange(null)
                }
                /** B3 07: Show selects the direct prerequisite (one hop, never spends). */
                val onShowPrerequisite: (Int) -> Unit = { prerequisiteId ->
                    val originId = selectedNodeId
                    if (originId != null && originId != prerequisiteId) {
                        onShowGuideChange(
                            TalentShowGuide(originNodeId = originId, targetNodeId = prerequisiteId),
                        )
                        onGuideTokenAdvance()
                        onInspectorExpandedChange(false)
                        onSelectedNodeIdChange(prerequisiteId)
                        val coveredBottom = if (layoutClass == TalentTreeLayoutClass.PORTRAIT) {
                            TalentInspectorMetrics.COMPACT_INSET_DP.value * 2f +
                                TalentInspectorMetrics.COMPACT_HEIGHT_DP.value
                        } else {
                            0f
                        }
                        focusSelectedNode(coveredBottomDp = coveredBottom)
                    }
                }

                // The workspace below the header, with the reset notice as its
                // one floating overlay.
                Box(Modifier.fillMaxSize()) {
                    when (layoutClass) {
                        TalentTreeLayoutClass.TABLET -> Row(Modifier.fillMaxSize()) {
                            TalentTreeCanvas(
                                model = current.model,
                                presentation = presentation,
                                viewportState = viewportState,
                                layoutClass = layoutClass,
                                accent = current.classAccent,
                                icons = current.icons,
                                selectedNodeId = selectedNodeId,
                                showGuide = showGuide,
                                guideToken = guideToken,
                                purchaseNodeId = purchaseFeedback?.nodeId,
                                purchaseToken = purchaseFeedback?.token ?: 0,
                                onNodeSelected = onNodeSelected,
                                onSelectionCleared = onSelectionCleared,
                                modifier = Modifier.weight(1f),
                            )
                            TalentInspectorPersistentPanel(
                                accent = current.classAccent,
                                content = inspectorContent,
                                icon = selectedIcon,
                                actions = selectedActions,
                                onShowPrerequisite = onShowPrerequisite,
                                onSpendRank = onSpendRank,
                                onRefundRank = onRefundRank,
                                onMaxRank = onMaxRank,
                            )
                        }

                        TalentTreeLayoutClass.LANDSCAPE -> Row(Modifier.fillMaxSize()) {
                            TalentTreeCanvas(
                                model = current.model,
                                presentation = presentation,
                                viewportState = viewportState,
                                layoutClass = layoutClass,
                                accent = current.classAccent,
                                icons = current.icons,
                                selectedNodeId = selectedNodeId,
                                showGuide = showGuide,
                                guideToken = guideToken,
                                purchaseNodeId = purchaseFeedback?.nodeId,
                                purchaseToken = purchaseFeedback?.token ?: 0,
                                onNodeSelected = onNodeSelected,
                                onSelectionCleared = onSelectionCleared,
                                modifier = Modifier.weight(1f),
                            )
                            if (inspectorContent != null) {
                                TalentInspectorSidePanel(
                                    accent = current.classAccent,
                                    content = inspectorContent,
                                    icon = selectedIcon,
                                    actions = selectedActions,
                                    onDeselect = onSelectionCleared,
                                    onShowPrerequisite = onShowPrerequisite,
                                    onSpendRank = onSpendRank,
                                    onRefundRank = onRefundRank,
                                    onMaxRank = onMaxRank,
                                )
                            }
                        }

                        TalentTreeLayoutClass.PORTRAIT -> Box(Modifier.fillMaxSize()) {
                            TalentTreeCanvas(
                                model = current.model,
                                presentation = presentation,
                                viewportState = viewportState,
                                layoutClass = layoutClass,
                                accent = current.classAccent,
                                icons = current.icons,
                                selectedNodeId = selectedNodeId,
                                showGuide = showGuide,
                                guideToken = guideToken,
                                purchaseNodeId = purchaseFeedback?.nodeId,
                                purchaseToken = purchaseFeedback?.token ?: 0,
                                onNodeSelected = onNodeSelected,
                                onSelectionCleared = onSelectionCleared,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Column(Modifier.align(Alignment.BottomCenter)) {
                                if (inspectorExpanded && inspectorContent != null) {
                                    TalentInspectorExpandedSheet(
                                        accent = current.classAccent,
                                        content = inspectorContent,
                                        icon = selectedIcon,
                                        actions = selectedActions,
                                        height = expandedSheetHeight,
                                        onCollapse = { onInspectorExpandedChange(false) },
                                        onDeselect = onSelectionCleared,
                                        onShowPrerequisite = onShowPrerequisite,
                                        onSpendRank = onSpendRank,
                                        onRefundRank = onRefundRank,
                                        onMaxRank = onMaxRank,
                                    )
                                } else {
                                    TalentInspectorCompactBar(
                                        accent = current.classAccent,
                                        content = inspectorContent,
                                        icon = selectedIcon,
                                        actions = selectedActions,
                                        onSpendRank = onSpendRank,
                                        onRefundRank = onRefundRank,
                                        onToggleExpanded = {
                                            val expanding = !inspectorExpanded
                                            onInspectorExpandedChange(expanding)
                                            if (expanding) {
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

                    // The reset notice floats at the TOP of the canvas region —
                    // the spec's notice model (UX/UI spec 07 places snackbars
                    // "at the top"; 01 ADAPT 3 floats notices "over the top of
                    // the canvas"). Anchored here, below the header and inset
                    // from any side inspector by its REAL width, it can never
                    // rest over the compact bar, the expanded sheet, the
                    // side/persistent panel or any − / + / Max / Show control,
                    // in every layout, without device-specific coordinates.
                    val sideInspectorWidth = when (layoutClass) {
                        TalentTreeLayoutClass.TABLET -> TalentInspectorMetrics.TABLET_PANEL_WIDTH_DP
                        TalentTreeLayoutClass.LANDSCAPE ->
                            if (inspectorContent != null) {
                                TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP
                            } else {
                                0.dp
                            }

                        TalentTreeLayoutClass.PORTRAIT -> 0.dp
                    }
                    TalentResetSnackbar(
                        notice = resetNotice,
                        onUndo = {
                            performUndo()
                            resetNotice = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(end = sideInspectorWidth)
                            .padding(
                                top = TalentForgeSpacing.rowGap,
                                start = TalentForgeSpacing.screenX,
                                end = TalentForgeSpacing.screenX,
                            ),
                    )
                }
            }
        }

        // M4.3 modal overlay over the whole workspace (header included): the
        // ⋯ build-actions sheet. The reset notice is NOT here — it floats at
        // the top of the canvas region inside the layout above, where it can
        // never cover the inspector or its actions.
        TalentBuildActionsSheet(
            visible = buildActionsOpen,
            onDismiss = { buildActionsOpen = false },
            onResetTalents = {
                // UX/UI spec 03 §4: no confirmation — Reset is recoverable
                // through the SAME history mechanism (one atomic entry).
                val cleared = current.engine.spentPoints(editing.current)
                val result = editing.reset(current.engine)
                if (result.changed) {
                    commitEditing(result.editing)
                    onShowGuideChange(null)
                    blockedNotice = null
                    resetNoticeToken += 1
                    resetNotice = TalentResetNotice(pointsCleared = cleared, id = resetNoticeToken)
                }
                buildActionsOpen = false
            },
        )
        // Auto-dismiss of the reset snackbar (implementation-safe 4 s; the
        // design sources freeze no duration for it, unlike B3's 2.6 s blocked
        // reason line).
        LaunchedEffect(resetNotice?.id) {
            if (resetNotice != null) {
                delay(TalentBuildOverlayMetrics.RESET_SNACKBAR_MS.toLong())
                resetNotice = null
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
 * Baseline loader of the M4-era Warrior entry — production no longer launches
 * through it (the M5.1 app root navigates Home → New Build → the generic
 * editor); it stays as the shared fixture of the tree UI tests: loads the
 * bundled baseline Forever Warrior document and maps it to a Ready session
 * (empty build) off the calling dispatcher.
 */
internal suspend fun loadBaselineWarriorTreeState(): TalentTreeUiState {
    val assets = ComposeResourceDatasetAssetSource()
    val repository = BundledTalentDatasetRepository(assets)
    return when (val outcome = repository.document(DatasetId(BASELINE_GAME, BASELINE_CLIENT_BUILD, BASELINE_CLASS))) {
        is DatasetOutcome.Failed ->
            TalentTreeUiState.Failed(TalentTreeUiFailure.Dataset(outcome.error))

        is DatasetOutcome.Loaded -> when (val state = loadWarriorTreeState(outcome.value)) {
            // The baseline renders like production: with the bundled real icons.
            is TalentTreeUiState.Ready -> state.copy(
                icons = TalentIconsLoader(assets)
                    .load(outcome.value, outcome.value.trees.single())
                    .icons,
                // ... and with the bundled real descriptions (M5.V2).
                descriptions = TalentDescriptionsLoader(assets)
                    .load(outcome.value, outcome.value.trees.single())
                    .descriptions,
            )

            else -> state
        }
    }
}

/** Identity of the single dataset M4.2/M4.3 launched into. */
private val BASELINE_GAME = GameId("forever")
private const val BASELINE_CLIENT_BUILD = "1.60.1.70009"
private val BASELINE_CLASS = ClassToken("WARRIOR")

/** Builds the baseline Ready session (UI model + engine over an empty build) from a loaded document. */
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
                buildTitle = document.classInfo.name,
                classAccent = classAccentFor(document.classInfo.token).color,
            )
    }
}

/**
 * Minimal context row for the Loading/Failed panes (B3 header: 44 dp, single
 * line). The interactive header — undo/redo, points, ⋯ — exists only in the
 * Ready workspace ([TalentTreeHeaderBar]).
 */
@Composable
private fun TreeHeader(
    title: String,
    pointsText: String? = null,
    onBack: (() -> Unit)? = null,
) {
    val colors = talentForgeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = TalentForgeSpacing.screenX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            BackChevronButton(onClick = onBack)
        }
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
