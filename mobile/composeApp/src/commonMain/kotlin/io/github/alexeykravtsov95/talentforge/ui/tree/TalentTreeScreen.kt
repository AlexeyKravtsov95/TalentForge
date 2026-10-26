package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.alexeykravtsov95.talentforge.allocation.AllocationEngineException
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.describe
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_dangling_prerequisite
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_dangling_visual
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_duplicate_node
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_empty_tree
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_engine
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_invalid_layout
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_multiple_trees
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_failure_no_trees
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_loading_talent_data
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_talent_data_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.section_back_to
import io.github.alexeykravtsov95.talentforge.ui.lang.appLanguage
import io.github.alexeykravtsov95.talentforge.ui.library.localizedClassName
import io.github.alexeykravtsov95.talentforge.ui.library.REFERENCE_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.motion.prefersReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

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
        /**
         * The build's locale-independent class token (M5.REL.1) — the key the
         * editor's presentation resolves specialization display names with
         * ([TalentSpecializationNames]). Never displayed directly and never
         * part of any build.
         */
        val classToken: ClassToken,
        val model: TalentTreeUiModel,
        val engine: TalentAllocationEngine,
        /**
         * The **initial** build the editing session starts from. Production
         * passes the build opened from the local library (a draft starts from
         * the engine's empty state); tests may inject a prepared build. The
         * live build is the editing session's `current`, never this field.
         */
        val buildState: BuildState,
        /**
         * Header line 1: the build's user-given name verbatim, or the
         * localized `<Class> build` while unnamed (`buildDisplayTitle`,
         * resolved by the host at display time, never persisted).
         */
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
        /**
         * Verified Russian talent names and descriptions (M5.REL.3b.2), applied only while
         * the app language is Russian ([talentNodeDisplayText]). The default "none" keeps
         * every talent English, so lower-level callers keep working.
         */
        val localization: TalentNodeLocalization = TalentNodeLocalization.None,
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

/**
 * Localized rendering of [TalentTreeUiFailure] for the Failed pane (M5.REL.2b).
 * The typed failure is the input; the sentence is UI copy. Embedded technical
 * details — the shared dataset diagnostic and the engine's reason — stay as
 * reported (diagnostics, not product copy).
 */
@Composable
fun TalentTreeUiFailure.describe(): String = when (this) {
    is TalentTreeUiFailure.Dataset -> error.describe()
    is TalentTreeUiFailure.AllocationEngine -> stringResource(Res.string.editor_failure_engine, reason)
    is TalentTreeUiFailure.UiModel -> error.describe()
}

/** Localized rendering of a [TalentTreeUiBuildError]. */
@Composable
fun TalentTreeUiBuildError.describe(): String = when (this) {
    TalentTreeUiBuildError.NoTrees -> stringResource(Res.string.editor_failure_no_trees)
    is TalentTreeUiBuildError.MultipleTrees ->
        stringResource(Res.string.editor_failure_multiple_trees, treeIds.joinToString())

    TalentTreeUiBuildError.EmptyTree -> stringResource(Res.string.editor_failure_empty_tree)
    is TalentTreeUiBuildError.DuplicateNodeId -> stringResource(Res.string.editor_failure_duplicate_node, nodeId)
    is TalentTreeUiBuildError.InvalidLayout -> stringResource(Res.string.editor_failure_invalid_layout, xStep)
    is TalentTreeUiBuildError.DanglingEdgeReference -> stringResource(
        when (kind) {
            TalentTreeEdgeKind.PREREQUISITE -> Res.string.editor_failure_dangling_prerequisite
            TalentTreeEdgeKind.VISUAL_ONLY -> Res.string.editor_failure_dangling_visual
        },
        fromNodeId,
        toNodeId,
    )
}

/** One successful purchase's node feedback (the token restarts the glow/pop). */
private data class TalentPurchaseFeedback(
    val nodeId: Int,
    val token: Int,
)

/** One denied action's temporary state-line reason (B3 07: 2.6 s). */
private data class TalentBlockedNotice(
    val nodeId: Int,
    val reason: InspectorText.Blocked,
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
 *
 * M5.SHARE.2: [onShareBuild] (optional) adds `Share` to the ⋯ sheet and
 * receives the editing session's current [BuildState] at the moment of the
 * tap; it never mutates the session. [onRenameBuild] (optional) adds `Rename`
 * above it; the host owns the dialog and the write, and a new name arrives
 * back only through [TalentTreeUiState.Ready.buildTitle] — the editing
 * session (allocation, history, selection, cameras) is untouched.
 *
 * M5.UX.1: in portrait the tree is shown one section at a time behind section
 * tabs (approved direction 1A v2), each section with its own camera held in
 * [sectionCameras] (default: one remembered per [sessionKey] — the opened
 * build's local id — and tree, so another build never inherits it).
 * [viewportState] stays the camera of the whole-tree canvas used by the
 * landscape and tablet layouts (and by the fail-safe portrait fallback when a
 * tree's sections cannot be resolved). Tab switches, pan, zoom and FIT are
 * view state only: they never report through [onBuildStateChanged].
 */
@Composable
fun TalentTreeScreen(
    state: TalentTreeUiState,
    modifier: Modifier = Modifier,
    viewportState: TalentTreeViewportState = rememberTalentTreeViewportState(),
    onBack: (() -> Unit)? = null,
    onBuildStateChanged: ((BuildState) -> Unit)? = null,
    sessionKey: String = "",
    sectionCameras: TalentTreeSectionCameras? = null,
    onShareBuild: ((BuildState) -> Unit)? = null,
    onRenameBuild: (() -> Unit)? = null,
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
    // display cutouts). The editor header stays a component inside the safe
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
                StatusPane(
                    primary = stringResource(Res.string.editor_loading_talent_data),
                    primaryColor = colors.textSecondary,
                )
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
                sessionKey = sessionKey,
                sectionCameras = sectionCameras,
                onShareBuild = onShareBuild,
                onRenameBuild = onRenameBuild,
            )

            is TalentTreeUiState.Failed -> Column(Modifier.fillMaxSize()) {
                TreeHeader(title = "TalentForge", onBack = onBack)
                StatusPane(
                    primary = stringResource(Res.string.editor_talent_data_failed),
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
    sessionKey: String,
    sectionCameras: TalentTreeSectionCameras?,
    onShareBuild: ((BuildState) -> Unit)?,
    onRenameBuild: (() -> Unit)?,
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
    // M5.REL.1: the one language every editor surface presents in — Russian
    // only for a Russian primary device language, English otherwise. Read
    // here once; the tabs, the Back chip, the accessibility labels and (since
    // M5.REL.3b.2) the talent names and descriptions all resolve through it,
    // so a change recomposes the text only: selection, cameras, the build,
    // undo/redo and autosave are untouched.
    val language = appLanguage()
    val displayText = remember(language, current.descriptions, current.localization) {
        talentNodeDisplayText(language, current.descriptions, current.localization)
    }
    val facts = remember(current.engine, displayText.names) { talentNodeFacts(current.engine, displayText.names) }
    val selectedPresentation = selectedNodeId?.let { presentation.nodes[it] }
    // The selected talent's real icon for the inspector header (null → no tile).
    val selectedIcon = selectedNodeId?.let { current.icons.iconOf(it) }
    val selectedActions = selectedPresentation?.let {
        buildTalentNodeActions(current.engine, editing.current, it)
    }
    val guideOriginName = showGuide
        ?.takeIf { it.targetNodeId == selectedNodeId }
        ?.originNodeId
        ?.let { facts.talentName(it) }
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
            descriptions = displayText.descriptions,
        )
    }

    // M5.UX.1 sections (1A v2): a pure view of the real tree's sections;
    // the engine and the one BuildState keep working on the whole tree.
    val sectioned = remember(current.engine, current.model) {
        buildTalentTreeSections(current.engine.tree, current.model) as? TalentTreeSectionsResult.Sectioned
    }
    val rememberedCameras = rememberTalentTreeSectionCameras(sessionKey, current.model.treeId)
    val cameras = sectionCameras ?: rememberedCameras
    // per-section spent points from the live build (tabs)
    val sectionPoints = remember(sectioned, editing.current) {
        sectioned?.let { sectionSpentPoints(it, editing.current) } ?: emptyMap()
    }
    // M5.UI.TABLET.1: the tablet's whole-tree canvas names each specialization
    // tree above its top row (same labels and live counts as the phone tabs)
    val sectionHeaders = remember(sectioned, sectionPoints, current.classToken, language) {
        sectioned?.let { talentTabletSectionHeaders(it, sectionPoints, current.classToken, language) }.orEmpty()
    }
    // 1A v2 04: the way back after a cross-section Show (UI state only)
    var sectionReturn by rememberSaveable(stateSaver = TalentSectionReturnSaver) {
        mutableStateOf<TalentSectionReturn?>(null)
    }
    // 1A v2 S8: undo/redo that changed another tab highlights it (transient)
    var tabHighlight by remember { mutableStateOf<TalentTabHighlight?>(null) }
    var tabHighlightToken by remember { mutableStateOf(0) }
    // PR #19 motion: the section page transition (view state only, never saved)
    val pagerScope = rememberCoroutineScope()
    val pager = remember { TalentSectionPager(pagerScope) }
    val reducedMotion = prefersReducedMotion()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // M5.UX.2a: the portrait inspector's MEASURED covered height (never an
    // estimate) and the policy deciding when a new measurement may move the
    // camera. View state only — never part of the build or of autosave.
    var measuredInspector by remember { mutableStateOf<TalentInspectorMeasurement?>(null) }
    val inspectorSink = remember { TalentInspectorMeasureSink() }
    val revealTracker = remember { TalentInspectorRevealTracker() }

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

    /**
     * 1A v2 S8 "Undo from another branch": no tab switch — the tabs whose
     * points the history step changed (other than the open one) flash.
     */
    fun highlightChangedSections(before: BuildState, after: BuildState) {
        val sections = sectioned ?: return
        val pointsBefore = sectionSpentPoints(sections, before)
        val pointsAfter = sectionSpentPoints(sections, after)
        val open = cameras.selectedSection ?: sections.sections.first().index
        val changed = sections.sections
            .map { it.index }
            .filter { it != open && pointsBefore[it] != pointsAfter[it] }
            .toSet()
        if (changed.isNotEmpty()) {
            tabHighlightToken += 1
            tabHighlight = TalentTabHighlight(sections = changed, token = tabHighlightToken)
        }
    }

    /** Undo/redo touch ONLY the BuildState — never selection, pan, zoom, tabs or inspector layout. */
    fun performUndo() {
        val before = editing.current
        val undone = editing.undo() ?: return
        commitEditing(undone)
        blockedNotice = null
        resetNotice = null
        highlightChangedSections(before, undone.current)
    }

    fun performRedo() {
        val before = editing.current
        val redone = editing.redo() ?: return
        commitEditing(redone)
        blockedNotice = null
        resetNotice = null
        highlightChangedSections(before, redone.current)
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

    BoxWithConstraints(modifier.fillMaxSize()) {
        // 1A v2 02: H of the sheet's "min(66% H, H − top − 104)" — the editor
        // inside the system safe area
        val screenHeight = maxHeight
        // M5.UX.2b.1: ONE layout class for the header and the canvas region
        // below it, from the whole workspace (the header hosts the landscape tabs).
        val layoutClass = talentTreeWorkspaceLayoutClass(maxWidth.value, maxHeight.value)
        // M5.UX.1 (portrait) and M5.UX.2b.1 (landscape phones): one section
        // per tab (1A v2 / B3 05), all over the ONE BuildState. Tablet keeps
        // the whole-tree canvas (its own milestone), and so does a tree whose
        // sections cannot be resolved (typed fallback — no node is ever hidden).
        val pagedSections = if (layoutClass != TalentTreeLayoutClass.TABLET) sectioned else null
        val activeSection = pagedSections?.let { sections ->
            // the selected talent's own section wins (a cross-section
            // Show, or a selection carried over from another layout —
            // 1A v2 S5 "rotation keeps the section and the selection")
            selectedNodeId?.let { sections.sectionOfNode[it] }
                ?: cameras.selectedSection?.takeIf { sections.section(it) != null }
                ?: sections.sections.first().index
        }
        val activeSectionModel = activeSection?.let { pagedSections?.section(it) }
        // The camera of what the canvas shows: one section's own camera on
        // phones, the whole-tree viewport otherwise.
        val activeCamera = if (activeSection != null) cameras.stateOf(activeSection) else viewportState
        LaunchedEffect(activeSection) {
            if (activeSection != null && cameras.selectedSection != activeSection) {
                cameras.selectedSection = activeSection
            }
        }

        // PR #19 / 1A v2 S6 system Back: close the ⋯ sheet, else collapse the
        // inspector, else clear the selection — one step per Back. Registered
        // after the app's navigation handler, so it takes precedence while
        // enabled; with nothing open the app's own Back (autosave flush,
        // re-entrancy guard) navigates as before. View state only: never the build.
        // M5.UX.2b.1: only the portrait sheet expands — the landscape side panel
        // is the full detail already, so there Back clears the selection at once
        // (an expansion carried over from portrait is kept for the way back).
        // iOS edge swipe: only the open ⋯ sheet takes it — otherwise the swipe
        // does what the header chevron does and pops the editor (selection and
        // inspector are never what a swipe back changes).
        PlatformBackHandler(enabled = buildActionsOpen || selectedNodeId != null, edgeSwipe = buildActionsOpen) {
            val expanded = inspectorExpanded && layoutClass == TalentTreeLayoutClass.PORTRAIT
            when (editorBackStep(expanded, selectedNodeId != null, buildActionsOpen)) {
                EditorBackStep.CLOSE_BUILD_ACTIONS -> buildActionsOpen = false
                EditorBackStep.COLLAPSE_INSPECTOR -> onInspectorExpandedChange(false)
                EditorBackStep.CLEAR_SELECTION -> {
                    onShowGuideChange(null)
                    onInspectorExpandedChange(false)
                    onSelectedNodeIdChange(null)
                    sectionReturn = null
                }
                EditorBackStep.NAVIGATE -> Unit // disabled in this state: the app's handler receives it
            }
        }

        val onSelectionCleared: () -> Unit = {
            onShowGuideChange(null)
            onInspectorExpandedChange(false)
            onSelectedNodeIdChange(null)
            sectionReturn = null
        }

        val sectionOrder = pagedSections?.sections?.map { it.index }.orEmpty()

        // the pages' width (the canvas region spans the whole workspace width;
        // the landscape side panel overlays it and never narrows the pages)
        val pageWidthDp = maxWidth.value
        val pageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        // PR #19 motion: ONE pager position (tab ordinals) drives every page
        // and the tab indicator; a change made without motion just rests there
        val sectionCount = sectionOrder.size
        val activeOrdinal = activeSection?.let { sectionOrder.indexOf(it) } ?: -1
        if (activeOrdinal >= 0) pager.sync(activeOrdinal)

        /**
         * The pager position as drawn now: the holding finger's (from
         * where it grabbed the pages, 1:1), or the motion's. Read at draw
         * / layout time only, never per frame in composition.
         */
        fun currentPosition(): Float {
            val held = pager.heldFrom
            return if (held != null) {
                talentSectionHeldPosition(held, activeCamera.sectionOverscrollDp, pageWidthDp, sectionCount, rtl)
            } else {
                pager.position
            }
        }

        /**
         * PR #19 motion: a section change (tab, Show, Back chip) moves the
         * ONE pager position from where it is drawn now, carrying the
         * running motion's velocity — from rest the old page slides out
         * and the new one in; mid-motion nothing jumps.
         */
        fun beginSectionTransition(from: Int?, to: Int) {
            val toOrdinal = sectionOrder.indexOf(to)
            if (from == null || from == to || toOrdinal < 0) return
            pager.settle(
                from = currentPosition(),
                to = toOrdinal,
                initialVelocity = if (pager.moving) pager.velocity else 0f,
                reducedMotion = reducedMotion,
                count = sectionCount,
            )
        }

        /**
         * 1A v2 S6 tab change: one tap; deselects and closes the
         * inspector; never touches the BuildState, undo/redo or the
         * autosave. A tap on the active tab returns its camera to
         * the fit (1A v2 01).
         */
        val onSectionSelected: (Int) -> Unit = { index ->
            if (index == activeSection) {
                cameras.stateOf(index).requestFit()
            } else {
                beginSectionTransition(activeSection, index)
                onSelectionCleared()
                cameras.selectedSection = index
            }
        }

        val indicatorPosition: () -> Float = {
            currentPosition().coerceIn(0f, (sectionCount - 1).coerceAtLeast(0).toFloat())
        }

        // M5.UX.2b.1 (B3 05): a landscape phone merges the tabs into the one
        // header bar when they fit, otherwise they keep the portrait row below it.
        val landscapeTabs = if (layoutClass == TalentTreeLayoutClass.LANDSCAPE && pagedSections != null) {
            talentTreeLandscapeTabs(maxWidth.value, LocalDensity.current.fontScale, pagedSections.sections.size)
        } else {
            null
        }
        val tabsInHeader = landscapeTabs?.placement == TalentTreeLandscapeTabsPlacement.IN_HEADER

        /** The section tabs (one tab per real section), wherever the layout puts them. */
        @Composable
        fun SectionTabs() {
            if (pagedSections != null && activeSection != null) {
                TalentTreeSectionTabs(
                    tabs = talentSectionTabs(
                        sections = pagedSections,
                        points = sectionPoints,
                        icons = current.icons,
                        classToken = current.classToken,
                        language = language,
                    ),
                    selectedIndex = activeSection,
                    accent = current.classAccent,
                    icons = current.icons,
                    highlightedSections = tabHighlight?.sections.orEmpty(),
                    highlightToken = tabHighlight?.token ?: 0,
                    onSelectSection = onSectionSelected,
                    indicatorPosition = indicatorPosition,
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            TalentTreeHeaderBar(
                title = current.buildTitle,
                // M5.REL.2a class names by ClassToken; the game name is a brand
                meta = "${localizedClassName(current.classToken, current.classDisplayName) ?: current.classDisplayName} · " +
                    current.gameDisplayName,
                pointsText = "${presentation.spentPoints} / ${presentation.pointBudget}",
                canUndo = editing.canUndo,
                canRedo = editing.canRedo,
                onUndo = ::performUndo,
                onRedo = ::performRedo,
                onOpenBuildActions = { buildActionsOpen = true },
                onBack = onBack,
                tabs = if (tabsInHeader) {
                    { SectionTabs() }
                } else {
                    null
                },
                tabsWidth = (landscapeTabs?.tabsWidthDp ?: 0f).dp,
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Captured before the nested layout lambdas: the scope's
                // constraints are only implicitly available at this level.
                // In sectioned portrait the tab row sits above the canvas
                // region, so the sheet and the canvas share the height below it.
                val areaHeight = if (activeSection != null) {
                    maxHeight - TalentTreeSectionTabsMetrics.TAB_HEIGHT_DP
                } else {
                    maxHeight
                }
                // M5.UX.2a (1A v2 02): the expanded sheet wraps its content
                // between 260 dp and min(66 % H, H − top − 104), where
                // H − top is the canvas region below the header and tabs.
                val sheetLimits = expandedSheetLimits(screenHeight = screenHeight, areaHeight = areaHeight)
                val portrait = layoutClass == TalentTreeLayoutClass.PORTRAIT
                // The measurement of what is on screen right now: same talent,
                // same presentation (a stale height of another talent or of
                // the other presentation is never used).
                val liveMeasurement = measuredInspector?.takeIf {
                    portrait && inspectorContent != null &&
                        it.nodeId == selectedNodeId && it.expanded == inspectorExpanded
                }
                // Before the first measurement of a presentation (one frame):
                // the compact strip's nominal 76 dp (1A v2 02), or the sheet ceiling.
                val compactNominalDp = TalentInspectorMetrics.COMPACT_STRIP_DP.value
                // Portrait height of the canvas bottom the overlaid inspector
                // covers: the camera clamp lets the content move above it.
                val coveredBottomDp = when {
                    !portrait || inspectorContent == null -> 0f
                    liveMeasurement != null -> liveMeasurement.coveredDp
                    inspectorExpanded -> sheetLimits.max.value
                    else -> inspectorSink.lastCompactDp ?: compactNominalDp
                }
                // Show collapses to the compact bar: centre above its real height.
                val compactCoveredDp = inspectorSink.lastCompactDp ?: compactNominalDp
                // M5.UX.2b.1: the landscape side panel overlays the canvas at the
                // layout end (right; left in RTL) while a talent is selected
                val sidePanelWidth = if (layoutClass == TalentTreeLayoutClass.LANDSCAPE && inspectorContent != null) {
                    TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP
                } else {
                    0.dp
                }
                val coveredLeftDp = if (rtl) sidePanelWidth.value else 0f
                val coveredRightDp = if (rtl) 0f else sidePanelWidth.value
                SideEffect {
                    activeCamera.coveredBottomDp = coveredBottomDp
                    activeCamera.coveredLeftDp = coveredLeftDp
                    activeCamera.coveredRightDp = coveredRightDp
                    if (!portrait || inspectorContent == null) revealTracker.reset()
                }
                val reportInspector: (TalentInspectorMeasurement) -> Unit = { measurement ->
                    if (inspectorSink.accept(measurement)) measuredInspector = measurement
                }

                /**
                 * Pans (animated by the canvas) so [nodeId] is fully visible
                 * in [camera]'s canvas area not covered by the inspector.
                 * No-op when it already is (B3: "the canvas stays still").
                 * [reveal] selects the B3 08 minimal-pan mode used by
                 * inspector layout changes; Show keeps the B3 07 centring.
                 */
                fun focusNode(
                    nodeId: Int,
                    camera: TalentTreeViewportState = activeCamera,
                    coveredLeftDp: Float = 0f,
                    coveredRightDp: Float = 0f,
                    coveredBottomDp: Float = 0f,
                    reveal: Boolean = false,
                ) {
                    val canvas = camera.canvasSize
                    if (canvas == Size.Zero) return
                    val node = current.model.nodes.firstOrNull { it.id == nodeId } ?: return
                    camera.requestFocus(
                        world = Offset(node.centerX, node.centerY),
                        visibleSize = Size(
                            (canvas.width - coveredLeftDp - coveredRightDp).coerceAtLeast(0f),
                            (canvas.height - coveredBottomDp).coerceAtLeast(0f),
                        ),
                        mode = if (reveal) TalentTreeFocusMode.REVEAL else TalentTreeFocusMode.CENTRE,
                        visibleOrigin = Offset(coveredLeftDp, 0f),
                    )
                }

                // M5.UX.2b.1: the side the landscape panel covers once it is open
                // (a selection or a Show opens it), whether or not it is yet
                val panelLeftDp = if (rtl) TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP.value else 0f
                val panelRightDp = if (rtl) 0f else TalentInspectorMetrics.LANDSCAPE_PANEL_WIDTH_DP.value

                // M5.UX.2a: every new real measurement of the portrait
                // inspector goes through the reveal policy; when it says so
                // the camera pans the B3 08 minimum (scale untouched, no-op
                // while the talent is visible) above the MEASURED height.
                LaunchedEffect(liveMeasurement) {
                    val measurement = liveMeasurement ?: return@LaunchedEffect
                    if (revealTracker.onMeasured(measurement, activeCamera.gestureEpoch)) {
                        focusNode(
                            nodeId = measurement.nodeId,
                            coveredBottomDp = measurement.coveredDp,
                            reveal = true,
                        )
                    }
                }

                /** B3: a plain selection clears any active Show guide (and the Back chip). */
                val onNodeSelected: (Int) -> Unit = { nodeId ->
                    onShowGuideChange(null)
                    // Reading in sequence: a new selection keeps the inspector's
                    // presentation — an expanded sheet stays expanded with the new
                    // talent, a compact strip (or one the reader collapsed) stays
                    // compact. Only a first selection (from none) opens compact.
                    if (selectedNodeId == null) onInspectorExpandedChange(false)
                    sectionReturn = null
                    // B3 05: when the side panel opens it covers 300 dp of the
                    // canvas; the tree pans only if the panel would cover the
                    // node. Later selections keep the panel (and the canvas) as is.
                    val panelOpening = selectedNodeId == null
                    onSelectedNodeIdChange(nodeId)
                    if (layoutClass == TalentTreeLayoutClass.LANDSCAPE && panelOpening &&
                        current.model.sidePanelWouldCover(activeCamera, nodeId, panelLeftDp, panelRightDp)
                    ) {
                        focusNode(
                            nodeId = nodeId,
                            coveredLeftDp = panelLeftDp,
                            coveredRightDp = panelRightDp,
                            reveal = true,
                        )
                    }
                }

                // PR #19 round 6: the other pages are composed ahead, at rest (nearest
                // first), and kept, so no motion composes or disposes a page
                // (talentSectionComposedPages). Round 7: a page grows by a few nodes per
                // frame (talentSectionWarmBatch adapts the batch to the frame time) instead
                // of a whole page in one frame, so the editor answers a touch right after it
                // opens; a touch or a motion pauses it for the pages off screen (the warm-up loop
                // below). Section index -> composed nodes; a page
                // that is not in it composes none. The page the editor opens on is composed whole
                // from the first frame, and is kept when the first switch leaves it.
                var warmProgress by remember(pagedSections) {
                    val opening = activeSection?.let { section -> pagedSections?.section(section)?.model?.nodes?.size?.let { section to it } }
                    mutableStateOf(listOfNotNull(opening).toMap())
                }
                // the warm-up loop (round 7): one page grows by `batch` talents per frame. A page on
                // screen comes first, also while the pages move, so a page that slides in before it is
                // warm grows a few talents per frame instead of a whole page in one frame (the opening
                // traces measured that frame at 224-240 ms, in the middle of the first swipe). Pages off
                // screen grow only at rest.
                LaunchedEffect(pagedSections, activeSection) {
                    val order = sectionOrder
                    val sizes = order.map { pagedSections?.section(it)?.model?.nodes?.size ?: 0 }
                    val active = order.indexOf(activeSection)
                    fun complete(): Set<Int> = order.indices.filter { (warmProgress[order[it]] ?: 0) >= sizes[it] }.toSet()
                    fun nextPage(): Int? {
                        val done = complete()
                        val shown = talentSectionComposedPages(currentPosition(), active, order.size).firstOrNull { it !in done }
                        return when {
                            shown != null -> shown
                            pager.moving || pager.heldFrom != null -> null
                            else -> talentSectionNextWarmPage(active, order.size, done)
                        }
                    }
                    var batch = 1
                    var previousFrame = 0L
                    while (complete().size < order.size) {
                        if (nextPage() == null) {
                            previousFrame = 0L
                            snapshotFlow { nextPage() != null || complete().size == order.size }.first { it }
                            continue
                        }
                        val frame = withFrameNanos { it } // the frame that is due first: the active page's own
                        batch = talentSectionWarmBatch(batch, if (previousFrame == 0L) 0L else frame - previousFrame)
                        previousFrame = frame
                        val next = nextPage()
                        if (next == null) {
                            previousFrame = 0L
                            continue
                        }
                        val section = order[next]
                        warmProgress = warmProgress + (section to ((warmProgress[section] ?: 0) + batch).coerceAtMost(sizes[next]))
                    }
                }

                // a tap that only caught moving pages never selects (as on a native pager)
                val onCanvasNodeSelected: (Int) -> Unit = { nodeId -> if (!pager.caughtMotion) onNodeSelected(nodeId) }
                val onCanvasSelectionCleared: () -> Unit = { if (!pager.caughtMotion) onSelectionCleared() }

                // the neighbours' cameras are fitted ahead of time, so a page that
                // slides in is laid out already (an existing camera is never reset)
                LaunchedEffect(pagedSections, activeCamera.canvasSize, layoutClass) {
                    val size = activeCamera.canvasSize
                    if (size == Size.Zero) return@LaunchedEffect
                    pagedSections?.sections?.forEach { section ->
                        if (section.index != activeSection) {
                            cameras.stateOf(section.index).prepare(section.model.bounds, size, layoutClass)
                        }
                    }
                }

                /**
                 * B3 07: Show selects the direct prerequisite (one hop, never
                 * spends). 1A v2 04 (M5.UX.1): when the prerequisite lives in
                 * another section, the tab switches to it — the origin
                 * section's camera stays exactly as it was — the target's
                 * camera centres it within the content, and a Back chip
                 * offers the way back to the origin talent.
                 */
                val onShowPrerequisite: (Int) -> Unit = { prerequisiteId ->
                    val originId = selectedNodeId
                    if (originId != null && originId != prerequisiteId) {
                        // Show places the target itself (B3 07 centring): the
                        // compact bar's own measurement must not pan it again
                        revealTracker.suppressNextOpen(prerequisiteId)
                        onShowGuideChange(
                            TalentShowGuide(originNodeId = originId, targetNodeId = prerequisiteId),
                        )
                        onGuideTokenAdvance()
                        onInspectorExpandedChange(false)
                        val coveredBottom = if (layoutClass == TalentTreeLayoutClass.PORTRAIT) compactCoveredDp else 0f
                        val landscape = layoutClass == TalentTreeLayoutClass.LANDSCAPE
                        val coveredLeft = if (landscape) panelLeftDp else 0f
                        val coveredRight = if (landscape) panelRightDp else 0f
                        val targetSection = pagedSections?.sectionOfNode?.get(prerequisiteId)
                        val targetSectionModel = targetSection?.let { pagedSections?.section(it) }
                        if (targetSection != null && targetSectionModel != null && targetSection != activeSection) {
                            val targetCamera = cameras.stateOf(targetSection)
                            targetCamera.prepare(targetSectionModel.model.bounds, activeCamera.canvasSize, layoutClass)
                            targetCamera.coveredBottomDp = coveredBottom
                            targetCamera.coveredLeftDp = coveredLeft
                            targetCamera.coveredRightDp = coveredRight
                            // the Back chip floats over the top of the canvas: a target
                            // in the top row is moved below it, never left under it
                            val targetNode = targetSectionModel.model.nodes.firstOrNull { it.id == prerequisiteId }
                            val prepared = targetCamera.viewport
                            if (targetNode != null && prepared != null) {
                                val chipClearance = TalentTreeSectionTabsMetrics.FIT_TARGET_DP.value +
                                    TalentInspectorMetrics.FOCUS_CLEARANCE_DP
                                val targetY = prepared.worldToScreen(Offset(targetNode.centerX, targetNode.centerY)).y
                                if (targetY < chipClearance) {
                                    targetCamera.applyTransform(prepared.panBy(Offset(0f, chipClearance - targetY)))
                                }
                            }
                            sectionReturn = TalentSectionReturn(
                                originNodeId = originId,
                                originSection = activeSection ?: targetSection,
                            )
                            beginSectionTransition(activeSection, targetSection)
                            cameras.selectedSection = targetSection
                            onSelectedNodeIdChange(prerequisiteId)
                            focusNode(
                                prerequisiteId,
                                camera = targetCamera,
                                coveredLeftDp = coveredLeft,
                                coveredRightDp = coveredRight,
                                coveredBottomDp = coveredBottom,
                            )
                        } else {
                            onSelectedNodeIdChange(prerequisiteId)
                            focusNode(
                                prerequisiteId,
                                coveredLeftDp = coveredLeft,
                                coveredRightDp = coveredRight,
                                coveredBottomDp = coveredBottom,
                            )
                        }
                    }
                }

                /** 1A v2 04 Back chip: back to the origin talent and its untouched camera. */
                val onReturnToOrigin: (TalentSectionReturn) -> Unit = { origin ->
                    // the origin's camera is restored untouched — no reveal on reopen
                    revealTracker.suppressNextOpen(origin.originNodeId)
                    onShowGuideChange(null)
                    onInspectorExpandedChange(false)
                    sectionReturn = null
                    beginSectionTransition(activeSection, origin.originSection)
                    cameras.selectedSection = origin.originSection
                    onSelectedNodeIdChange(origin.originNodeId)
                }

                @Composable
                fun TreeCanvas(
                    model: TalentTreeUiModel,
                    camera: TalentTreeViewportState,
                    modifier: Modifier,
                    onSectionSwipe: ((Int, Float, Float) -> Unit)? = null,
                    interactive: Boolean = true,
                    pageOffsetPx: (() -> Float)? = null,
                    pageTag: String? = null,
                    composedNodeCount: () -> Int = { Int.MAX_VALUE },
                    contentTopReserveDp: Float = 0f,
                ) {
                    TalentTreeCanvas(
                        model = model,
                        presentation = presentation,
                        viewportState = camera,
                        layoutClass = layoutClass,
                        accent = current.classAccent,
                        icons = current.icons,
                        localizedNames = displayText.names,
                        selectedNodeId = selectedNodeId,
                        showGuide = showGuide,
                        guideToken = guideToken,
                        purchaseNodeId = purchaseFeedback?.nodeId,
                        purchaseToken = purchaseFeedback?.token ?: 0,
                        onNodeSelected = onCanvasNodeSelected,
                        onSelectionCleared = onCanvasSelectionCleared,
                        modifier = modifier,
                        onSectionSwipe = onSectionSwipe,
                        interactive = interactive,
                        pageOffsetPx = pageOffsetPx,
                        pageTag = pageTag,
                        composedNodeCount = composedNodeCount,
                        contentTopReserveDp = contentTopReserveDp,
                    )
                }

                /**
                 * PR #19: a confirmed section swipe opens the neighbouring
                 * section through the SAME path as a tab tap (selection cleared,
                 * cameras kept, BuildState untouched); none past the first/last.
                 * The motion continues from where the finger let go, with its
                 * velocity: one tab on from the tab the touch started on, or
                 * back to rest when the swipe was not confirmed.
                 */
                val onSectionSwipe: (Int, Float, Float) -> Unit = { direction, releaseDragDp, releaseVelocity ->
                    val from = activeSection
                    if (from != null && activeOrdinal >= 0 && pageWidthDp > 0f) {
                        val grabbedAt = pager.heldFrom ?: pager.position
                        val released = talentSectionHeldPosition(grabbedAt, releaseDragDp, pageWidthDp, sectionCount, rtl)
                        // past the first / last tab only the rubber band moved: it returns without speed
                        val outside = released < 0f || released > sectionCount - 1
                        val speed = if (outside) 0f else talentSectionPositionVelocity(releaseVelocity, pageWidthDp, rtl)
                        val target = talentSectionReleaseTarget(grabbedAt, activeOrdinal, direction, sectionCount)
                        pager.settle(released, target, speed, reducedMotion, sectionCount)
                        val to = sectionOrder[target]
                        if (to != from) {
                            onSelectionCleared()
                            cameras.selectedSection = to
                        }
                    }
                }

                // The workspace below the header, with the reset notice as its
                // one floating overlay.
                Box(Modifier.fillMaxSize()) {
                    when (layoutClass) {
                        TalentTreeLayoutClass.TABLET -> Row(Modifier.fillMaxSize()) {
                            // M5.UI.TABLET.1: the specialization headers overlay the canvas
                            // only (the persistent inspector is beside it, never over them),
                            // placed from the canvas's own camera, which keeps room for them
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                TreeCanvas(
                                    model = current.model,
                                    camera = viewportState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentTopReserveDp = if (sectionHeaders.isEmpty()) {
                                        0f
                                    } else {
                                        talentTabletHeaderReserveDp(LocalDensity.current)
                                    },
                                )
                                if (sectionHeaders.isNotEmpty()) {
                                    TalentTabletSectionHeaders(
                                        headers = sectionHeaders,
                                        viewportState = viewportState,
                                        accent = current.classAccent,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                            }
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

                        // M5.UX.2b.1: landscape phones share the portrait section pages
                        // (pager, cameras, Show + Back chip, FIT); only the chrome differs —
                        // tabs in the B3 header bar when they fit, and the B3 05 side panel
                        // instead of the bottom inspector.
                        TalentTreeLayoutClass.PORTRAIT, TalentTreeLayoutClass.LANDSCAPE -> Column(Modifier.fillMaxSize()) {
                            if (!tabsInHeader) SectionTabs()
                            Box(Modifier.fillMaxSize()) {
                                if (activeSectionModel != null) {
                                    // PR #19 motion: the section pages. One canvas per
                                    // section (keyed, its own camera): the active page is
                                    // the ONLY interactive one; the others are drawn only,
                                    // invisible to accessibility. They are composed ahead,
                                    // at rest (round 6: composing one costs 240-360 ms on a
                                    // phone), so a motion never composes or disposes a page.
                                    // All of them move by ONE position, read at draw time.
                                    // A touch stops a running motion where it is drawn; the
                                    // finger carries it.
                                    val composedPages by remember(pager, activeCamera, activeOrdinal, sectionOrder, pageWidthDp, pagedSections) {
                                        derivedStateOf {
                                            talentSectionComposedPages(
                                                currentPosition(),
                                                activeOrdinal,
                                                sectionCount,
                                                warm = warmProgress.keys.map { sectionOrder.indexOf(it) },
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .clipToBounds()
                                            .pointerInput(pager, reducedMotion, sectionCount) {
                                                awaitEachGesture {
                                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                    pager.grab()
                                                    // after the canvas has handled the release (Final pass): a touch
                                                    // that did not swipe lets the motion it stopped go on
                                                    do {
                                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                                    } while (event.changes.any { it.pressed })
                                                    pager.heldFrom?.let { held ->
                                                        pager.settle(held, pager.target, 0f, reducedMotion, sectionCount)
                                                    }
                                                    pager.endTouch()
                                                }
                                            },
                                    ) {
                                        for (ordinal in composedPages) {
                                            val section = sectionOrder[ordinal]
                                            key(section) {
                                                val pageModel = pagedSections?.section(section)
                                                val isActive = section == activeSectionModel.index
                                                if (pageModel != null) {
                                                    TreeCanvas(
                                                        model = pageModel.model,
                                                        camera = cameras.stateOf(section),
                                                        onSectionSwipe = if (isActive) onSectionSwipe else null,
                                                        interactive = isActive,
                                                        pageOffsetPx = {
                                                            talentSectionPageX(ordinal, currentPosition(), rtl) * pageWidthPx
                                                        },
                                                        pageTag = talentSectionPageTag(section),
                                                        // read only by the page's node layer: a warm-up step
                                                        // recomposes that layer, not the screen or the other pages
                                                        composedNodeCount = remember(section, pagedSections) {
                                                            val read: () -> Int = { warmProgress[section] ?: 0 }
                                                            read
                                                        },
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    val origin = sectionReturn
                                    if (origin != null && origin.originSection != activeSection) {
                                        val originSection = pagedSections?.section(origin.originSection)
                                        val originLabel = originSection?.let {
                                            talentSectionDisplayLabel(
                                                datasetName = it.datasetName,
                                                classToken = current.classToken,
                                                sectionIndex = it.index,
                                                ordinal = it.ordinal,
                                                language = language,
                                            )
                                        }
                                        TalentSectionReturnChip(
                                            text = listOfNotNull(
                                                stringResource(
                                                    Res.string.section_back_to,
                                                    facts.talentName(origin.originNodeId).text(),
                                                ),
                                                originLabel,
                                            ).joinToString(" · "),
                                            onClick = { onReturnToOrigin(origin) },
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(end = sidePanelWidth)
                                                .padding(horizontal = TalentForgeSpacing.screenX),
                                        )
                                    }
                                    if (!(portrait && inspectorExpanded && inspectorContent != null)) {
                                        TalentTreeFitButton(
                                            viewportState = activeCamera,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(
                                                    end = sidePanelWidth + TalentTreeSectionTabsMetrics.FIT_INSET_DP -
                                                        (TalentTreeSectionTabsMetrics.FIT_TARGET_DP -
                                                            TalentTreeSectionTabsMetrics.FIT_VISUAL_DP) / 2,
                                                    bottom = coveredBottomDp.dp +
                                                        TalentTreeSectionTabsMetrics.FIT_INSET_DP -
                                                        (TalentTreeSectionTabsMetrics.FIT_TARGET_DP -
                                                            TalentTreeSectionTabsMetrics.FIT_VISUAL_DP) / 2,
                                                ),
                                        )
                                    }
                                } else {
                                    TreeCanvas(current.model, viewportState, Modifier.fillMaxSize())
                                }
                                // PR #19 motion: the sheet slides up from the strip's top
                                // edge and back down (≈ 240 ms ease-out). Only its
                                // drawing moves: the layout — and so the measured,
                                // covered height the camera and the reveal policy use —
                                // is the FINAL geometry from the first frame, so the
                                // camera pans at most once and never per animation frame.
                                val shownNodeId = selectedNodeId
                                val sheetOpen = portrait && inspectorExpanded && inspectorContent != null && shownNodeId != null
                                val sheetProgress = remember { Animatable(if (sheetOpen) 1f else 0f) }
                                LaunchedEffect(sheetOpen) {
                                    val spec = tween<Float>(
                                        durationMillis = if (reducedMotion) 0 else TalentMotionMetrics.SHEET_MS,
                                        easing = TalentMotionMetrics.SHEET_EASING,
                                    )
                                    sheetProgress.animateTo(if (sheetOpen) 1f else 0f, spec)
                                }
                                // closing: the SAME talent's sheet slides down over the strip (another
                                // selection or Show simply shows its strip); derived: recomposes when the
                                // sheet leaves rest, never per frame
                                val sheetOpenedFor = remember { IntArray(1) { NO_NODE } }
                                if (sheetOpen && shownNodeId != null) sheetOpenedFor[0] = shownNodeId
                                val sheetDrawn by remember { derivedStateOf { sheetProgress.value > 0f } }
                                val sheetClosing = !sheetOpen && sheetDrawn && inspectorContent != null &&
                                    shownNodeId != null && shownNodeId == sheetOpenedFor[0]
                                val stripPx = with(LocalDensity.current) { compactCoveredDp.dp.toPx() }
                                // the compact bar and the sheet are the portrait inspector only
                                if (portrait) Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        // the sheet's lower part, still below its rest, is never drawn under the system bar
                                        .clipToBounds(),
                                ) {
                                    if (!sheetOpen) {
                                        TalentInspectorCompactBar(
                                            accent = current.classAccent,
                                            content = inspectorContent,
                                            icon = selectedIcon,
                                            actions = selectedActions,
                                            onSpendRank = onSpendRank,
                                            onRefundRank = onRefundRank,
                                            // B3 08: "Expanding pans the canvas the minimum
                                            // amount needed, and only when the node would be
                                            // covered" — done once the sheet's REAL height is
                                            // measured (the reveal policy above).
                                            onToggleExpanded = { onInspectorExpandedChange(!inspectorExpanded) },
                                            onShowPrerequisite = onShowPrerequisite,
                                            modifier = (
                                                if (shownNodeId != null && inspectorContent != null) {
                                                    Modifier.reportCoveredHeight(
                                                        nodeId = shownNodeId,
                                                        expanded = false,
                                                        onMeasured = reportInspector,
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                                ).align(Alignment.BottomCenter),
                                        )
                                    }
                                    if ((sheetOpen || sheetClosing) && inspectorContent != null && shownNodeId != null) {
                                        Box(
                                            Modifier
                                                .align(Alignment.BottomCenter)
                                                .graphicsLayer {
                                                    // 0: its top at the strip's top · 1: at rest
                                                    translationY = (1f - sheetProgress.value) *
                                                        (size.height - stripPx).coerceAtLeast(0f)
                                                }
                                                // a closing sheet is a picture: no semantics, no input
                                                .then(
                                                    if (sheetClosing) {
                                                        Modifier.clearAndSetSemantics { testTag = TALENT_INSPECTOR_SHEET_CLOSING_TAG }
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                        ) {
                                            TalentInspectorExpandedSheet(
                                                accent = current.classAccent,
                                                content = inspectorContent,
                                                icon = selectedIcon,
                                                actions = selectedActions,
                                                limits = sheetLimits,
                                                onCollapse = { onInspectorExpandedChange(false) },
                                                onDeselect = onSelectionCleared,
                                                onShowPrerequisite = onShowPrerequisite,
                                                onSpendRank = onSpendRank,
                                                onRefundRank = onRefundRank,
                                                onMaxRank = onMaxRank,
                                                modifier = if (sheetOpen) {
                                                    Modifier.reportCoveredHeight(
                                                        nodeId = shownNodeId,
                                                        expanded = true,
                                                        onMeasured = reportInspector,
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            if (sheetClosing) {
                                                Box(
                                                    Modifier
                                                        .matchParentSize()
                                                        .pointerInput(Unit) {
                                                            awaitPointerEventScope {
                                                                while (true) {
                                                                    awaitPointerEvent().changes.forEach { it.consume() }
                                                                }
                                                            }
                                                        },
                                                )
                                            }
                                        }
                                    }
                                }
                                // B3 05 landscape: "Selecting opens a 300 dp side panel with the
                                // full detail" — over the canvas, so the canvas (and its camera)
                                // keeps its size and "moves only if the panel would cover the
                                // selected node" (the reveal in onNodeSelected). Below the header,
                                // so it never covers the tabs, undo / redo or the counter.
                                if (!portrait && inspectorContent != null) {
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
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .testTag(TALENT_INSPECTOR_SIDE_PANEL_TAG)
                                            // a touch on the panel never reaches the canvas or the pages below
                                            .pointerInput(Unit) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        awaitPointerEvent().changes.forEach { it.consume() }
                                                    }
                                                }
                                            },
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
                            // M5.UX.1: below the section tabs, which stay usable
                            .padding(
                                top = if (activeSection != null && !tabsInHeader) TalentTreeSectionTabsMetrics.TAB_HEIGHT_DP else 0.dp,
                            )
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
            // M5.SHARE.2: hands over the session's CURRENT build, read
            // synchronously at the tap — never the persisted record (which a
            // pending or failed autosave may lag behind) and never a cached
            // code. Sharing changes nothing: no history entry, no autosave.
            onShareBuild = onShareBuild?.let { share ->
                {
                    buildActionsOpen = false
                    share(editing.current)
                }
            },
            onRenameBuild = onRenameBuild?.let { rename ->
                {
                    buildActionsOpen = false
                    rename()
                }
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

/** No talent (the sheet was never opened). */
private const val NO_NODE: Int = Int.MIN_VALUE

/**
 * M5.UX.2b.1 (B3 05 "The canvas moves only if the panel would cover the
 * selected node"): whether [nodeId], as [camera] shows it now, lies under a
 * side panel covering [panelLeftDp] / [panelRightDp] of the canvas (with the
 * Show clearance). Only then does opening the panel move the canvas.
 */
private fun TalentTreeUiModel.sidePanelWouldCover(
    camera: TalentTreeViewportState,
    nodeId: Int,
    panelLeftDp: Float,
    panelRightDp: Float,
): Boolean {
    val viewport = camera.viewport ?: return false
    val node = nodes.firstOrNull { it.id == nodeId } ?: return false
    val x = viewport.worldToScreen(Offset(node.centerX, node.centerY)).x
    val clearance = TalentInspectorMetrics.FOCUS_CLEARANCE_DP
    return x < panelLeftDp + clearance || x > camera.canvasSize.width - panelRightDp - clearance
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
 * 1A v2 04: after a cross-section Show, the talent (and its section) the
 * Back chip returns to. Pure navigation state — never part of the build.
 */
data class TalentSectionReturn(
    val originNodeId: Int,
    val originSection: Int,
)

/** Saveable [TalentSectionReturn] (survives recomposition and configuration change). */
private val TalentSectionReturnSaver = mapSaver<TalentSectionReturn?>(
    save = { origin ->
        if (origin == null) {
            emptyMap()
        } else {
            mapOf("node" to origin.originNodeId, "section" to origin.originSection)
        }
    },
    restore = { values ->
        val node = values["node"] as? Int
        val section = values["section"] as? Int
        if (node != null && section != null) TalentSectionReturn(node, section) else null
    },
)

/** The tabs an undo/redo just changed (1A v2 S8); [token] restarts their 600 ms flash. */
private data class TalentTabHighlight(
    val sections: Set<Int>,
    val token: Int,
)

/**
 * M5.UX.2a: plain (non-snapshot) memory of the inspector heights reported from
 * the layout pass — it filters repeats, so only a CHANGED measurement reaches
 * the snapshot state (one recomposition per real change, never per frame), and
 * keeps the last compact height for Show, which centres above the bar it is
 * about to show.
 */
private class TalentInspectorMeasureSink {
    private var last: TalentInspectorMeasurement? = null

    /** The last measured compact-bar height (dp), whichever talent it showed. */
    var lastCompactDp: Float? = null
        private set

    /** Records [measurement]; `true` when it differs from the previous one. */
    fun accept(measurement: TalentInspectorMeasurement): Boolean {
        if (!measurement.expanded) lastCompactDp = measurement.coveredDp
        if (measurement == last) return false
        last = measurement
        return true
    }
}

/**
 * Reports this layout's real height as the inspector measurement of
 * [nodeId] / [expanded] on every measure pass. A layout modifier (rather than
 * `onSizeChanged`) on purpose: the lambda captures the talent it shows, so a
 * selection change re-measures and re-reports even when the height did not
 * change — a measurement is never attributed to the previous talent.
 */
private fun Modifier.reportCoveredHeight(
    nodeId: Int,
    expanded: Boolean,
    onMeasured: (TalentInspectorMeasurement) -> Unit,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    onMeasured(TalentInspectorMeasurement(nodeId, expanded, placeable.height.toDp().value))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Baseline loader of the M4-era Warrior entry — production no longer launches
 * through it (the M5.1 app root navigates Home → New Build → the generic
 * editor); it stays as the shared fixture of the tree UI tests: loads the
 * Warrior document of the bundled reference baseline ([REFERENCE_BUNDLE],
 * which the M4/M5 screenshot and UI tests are pinned to) and maps it to a
 * Ready session (empty build) off the calling dispatcher.
 */
internal suspend fun loadBaselineWarriorTreeState(): TalentTreeUiState {
    val assets = ComposeResourceDatasetAssetSource()
    val repository = BundledTalentDatasetRepository(assets)
    val id = DatasetId(REFERENCE_BUNDLE.game, REFERENCE_BUNDLE.clientBuild, BASELINE_CLASS)
    return when (val outcome = repository.document(id)) {
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

/** Class of the single dataset M4.2/M4.3 launched into (on the reference baseline). */
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
                classToken = document.classInfo.token,
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
