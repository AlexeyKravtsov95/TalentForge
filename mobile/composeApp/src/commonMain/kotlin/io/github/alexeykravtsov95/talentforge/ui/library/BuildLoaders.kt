package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.allocation.AllocationEngineException
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.allocation.toBuildState
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentNodeDescriptions
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentNodeIcons
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeUiBuildError
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeUiModel
import io.github.alexeykravtsov95.talentforge.ui.tree.buildTreeUiModel

/**
 * One build card's honest metadata: every field that could not be resolved
 * from real data is `null` and is simply not shown — a failed resolution
 * never substitutes an invented name or a fake point count.
 */
data class BuildCardModel(
    val record: LocalBuildRecord,
    /** Real class display name from the class document; `null` when unresolved. */
    val classDisplayName: String?,
    /** Engine-computed spent points of the saved build; `null` when unresolved. */
    val spentPoints: Int?,
    /** The tree's real point budget; `null` when unresolved. */
    val pointBudget: Int?,
) {
    /** `Draft` while unnamed — never a generated fake name. */
    val title: String get() = record.name ?: "Draft"
}

/** Home's answer to "what do I edit now" (UX/UI spec B): Continue first. */
data class HomeModel(
    val continueCard: BuildCardModel?,
    /** The recents AFTER the Continue card, newest first (the screen slices by width). */
    val recentCards: List<BuildCardModel>,
    val totalBuilds: Int,
)

/** The New Build screen's real class list, resolved from the bundle manifest. */
data class NewBuildChoices(
    val gameDisplayName: String,
    val versionDetail: String,
    val classes: List<NewBuildClassChoice>,
)

data class NewBuildClassChoice(
    val token: ClassToken,
    /** Real display name; `null` when the class document failed to load. */
    val displayName: String?,
) {
    /** A document that failed to load offers no draft creation (fail closed). */
    val available: Boolean get() = displayName != null
}

/** Library-backed screen data: Loading / Ready / typed Failed (never fake-empty). */
sealed interface LibraryData<out T> {
    data object Loading : LibraryData<Nothing>

    data class Ready<out T>(val value: T) : LibraryData<T>

    data class Failed(val error: BuildLibraryError) : LibraryData<Nothing>
}

/** The opened editor session: the record plus everything the tree needs. */
data class EditorSession(
    val record: LocalBuildRecord,
    val engine: TalentAllocationEngine,
    val model: TalentTreeUiModel,
    val buildState: io.github.alexeykravtsov95.talentforge.allocation.BuildState,
    val classDisplayName: String,
    val gameDisplayName: String,
    /**
     * Real talent icons of the tree (M5.V1). Presentation only: a missing or
     * broken icon — or visuals that fail to load at all — leaves nodes on
     * their monogram tile and never fails the session.
     */
    val icons: TalentNodeIcons = TalentNodeIcons.None,
    /**
     * Verified talent descriptions of the tree (M5.V2). Presentation only:
     * descriptions that fail to load show the neutral "unavailable" line in
     * the inspector and never fail the session.
     */
    val descriptions: TalentNodeDescriptions = TalentNodeDescriptions.None,
)

/** Typed, fail-closed editor opening failures. No silent migration exists. */
sealed interface EditorFailure {
    data class Record(val error: BuildLibraryError) : EditorFailure

    data class Dataset(val error: DatasetLoadError) : EditorFailure

    data class TreeUnavailable(val buildTreeId: Int, val availableTreeIds: List<Int>) : EditorFailure

    data class Engine(val reason: String) : EditorFailure

    data class InvalidBuild(val reasonCodes: List<String>) : EditorFailure

    data class UiModel(val error: TalentTreeUiBuildError) : EditorFailure
}

sealed interface EditorLoad {
    data object Loading : EditorLoad

    data class Ready(val session: EditorSession) : EditorLoad

    data class Failed(val failure: EditorFailure) : EditorLoad
}

/** Outcome of New Build's tap-a-class flow: persisted draft, or typed failure. */
sealed interface DraftCreation {
    data class Created(val record: LocalBuildRecord) : DraftCreation

    data class Failed(val failure: Failure) : DraftCreation

    sealed interface Failure {
        data class Dataset(val error: DatasetLoadError) : Failure

        data class Engine(val reason: String) : Failure

        data class Library(val error: BuildLibraryError) : Failure
    }
}

/**
 * Opens a saved build. Fail-closed by construction:
 *
 * 1. `repository.open(localId)` touches `lastOpenedAt` (a real write — its
 *    failure is surfaced, not skipped);
 * 2. the record's exact dataset identity (game / client build / class token)
 *    resolves through the bundled repository — an unavailable dataset NEVER
 *    falls back to the current baseline;
 * 3. the saved `treeId` must exist in the loaded document;
 * 4. the saved allocations must pass the real engine validation — no rank is
 *    clamped, dropped or "repaired".
 *
 * M5.1 has no migration: any mismatch is a typed [EditorFailure].
 */
suspend fun loadEditorSession(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    localId: String,
    icons: TalentIconsLoader? = null,
    descriptions: TalentDescriptionsLoader? = null,
): EditorLoad {
    val record = when (val opened = repository.open(localId)) {
        is BuildLibraryOutcome.Failed -> return EditorLoad.Failed(EditorFailure.Record(opened.error))
        is BuildLibraryOutcome.Loaded -> opened.value
    }
    val build = record.build
    val document = when (
        val outcome = datasets.document(
            DatasetId(game = build.game, clientBuild = build.clientBuild, classToken = build.classToken),
        )
    ) {
        is DatasetOutcome.Failed -> return EditorLoad.Failed(EditorFailure.Dataset(outcome.error))
        is DatasetOutcome.Loaded -> outcome.value
    }
    val tree = document.trees.firstOrNull { it.id == build.treeId }
        ?: return EditorLoad.Failed(
            EditorFailure.TreeUnavailable(buildTreeId = build.treeId, availableTreeIds = document.trees.map { it.id }),
        )
    val engine = try {
        TalentAllocationEngine(document, treeId = tree.id)
    } catch (exception: AllocationEngineException) {
        return EditorLoad.Failed(EditorFailure.Engine(exception.message ?: "unknown"))
    }
    val state = build.toBuildState()
    val validation = engine.validate(state)
    if (!validation.valid) {
        return EditorLoad.Failed(EditorFailure.InvalidBuild(validation.reasonCodes))
    }
    return when (val built = buildTreeUiModel(tree)) {
        is io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeUiBuildResult.Failed ->
            EditorLoad.Failed(EditorFailure.UiModel(built.error))

        is io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeUiBuildResult.Ready ->
            EditorLoad.Ready(
                EditorSession(
                    record = record,
                    engine = engine,
                    model = built.model,
                    buildState = state,
                    classDisplayName = document.classInfo.name,
                    gameDisplayName = gameDisplayName(document.game),
                    // Loaded last, after every allocation gate passed: icons
                    // can never influence whether a build opens.
                    icons = icons?.load(document, tree)?.icons ?: TalentNodeIcons.None,
                    descriptions = descriptions?.load(document, tree)?.descriptions
                        ?: TalentNodeDescriptions.None,
                ),
            )
    }
}

/**
 * Home's model from the real library. The Continue card is the most recently
 * *edited* build (UX/UI spec B.1) — the library's canonical order; a library
 * load failure is a typed [LibraryData.Failed], never an empty Home.
 */
suspend fun loadHomeModel(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
): LibraryData<HomeModel> = loadCardList(repository, datasets) { cards ->
    HomeModel(
        continueCard = cards.firstOrNull(),
        recentCards = cards.drop(1),
        totalBuilds = cards.size,
    )
}

/** The All Builds list (M5.1 basic version): every record, newest edited first. */
suspend fun loadBuildsModel(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
): LibraryData<List<BuildCardModel>> = loadCardList(repository, datasets) { it }

private suspend fun <T> loadCardList(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    transform: suspend (List<BuildCardModel>) -> T,
): LibraryData<T> = when (val outcome = repository.load()) {
    is BuildLibraryOutcome.Failed -> LibraryData.Failed(outcome.error)
    is BuildLibraryOutcome.Loaded -> {
        val resolver = DatasetClassResolver(datasets)
        LibraryData.Ready(transform(outcome.value.map { resolver.cardOf(it) }))
    }
}

/**
 * New Build's class list: the manifest defines the tokens (never hardcoded
 * filenames); display names come from the real class documents. One class
 * document failing loads fails THAT class conservatively (disabled card, no
 * invented name) — the other classes are unaffected. Returns `null` when the
 * manifest itself cannot load (the screen's Failed state).
 */
suspend fun loadNewBuildChoices(datasets: TalentDatasetRepository): NewBuildChoices? {
    val manifest = when (val outcome = datasets.manifest(BASELINE_BUNDLE)) {
        is DatasetOutcome.Failed -> return null
        is DatasetOutcome.Loaded -> outcome.value
    }
    val resolver = DatasetClassResolver(datasets)
    val classes = manifest.classes.map { entry ->
        val document = resolver.baselineDocumentOf(entry.token)
        NewBuildClassChoice(
            token = entry.token,
            displayName = (document as? DatasetOutcome.Loaded)?.value?.classInfo?.name,
        )
    }
    return NewBuildChoices(
        gameDisplayName = gameDisplayName(manifest.game),
        versionDetail = "Talent data ${dataVersionOf(manifest.clientBuild)}",
        classes = classes,
    )
}

/**
 * The New Build tap: load the real document, construct the real engine, take
 * its `emptyState()`, bind the exact dataset identity into a [BuildFile] and
 * PERSIST the draft — only a successfully persisted draft is "Created". The
 * editor must never open over a failed persistence looking saved.
 */
suspend fun createClassDraft(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    classToken: ClassToken,
): DraftCreation {
    val document = when (
        val outcome = datasets.document(
            DatasetId(game = BASELINE_BUNDLE.game, clientBuild = BASELINE_BUNDLE.clientBuild, classToken = classToken),
        )
    ) {
        is DatasetOutcome.Failed -> return DraftCreation.Failed(DraftCreation.Failure.Dataset(outcome.error))
        is DatasetOutcome.Loaded -> outcome.value
    }
    val engine = try {
        TalentAllocationEngine(document)
    } catch (exception: AllocationEngineException) {
        return DraftCreation.Failed(DraftCreation.Failure.Engine(exception.message ?: "unknown"))
    }
    val emptyBuild: BuildFile = buildFileOf(
        state = engine.emptyState(),
        game = document.game,
        clientBuild = document.clientBuild,
        classToken = document.classInfo.token,
    )
    return when (val created = repository.createDraft(emptyBuild)) {
        is BuildLibraryOutcome.Failed -> DraftCreation.Failed(DraftCreation.Failure.Library(created.error))
        is BuildLibraryOutcome.Loaded -> DraftCreation.Created(created.value)
    }
}

/**
 * Per-load cache of class documents/engines, keyed by the **exact dataset
 * identity** (`DatasetId`, never the class token alone): Home/All Builds
 * cards must resolve each record's OWN game/clientBuild/classToken —
 * substituting the currently bundled baseline for a record saved against
 * another dataset would be a silent current-dataset substitution.
 * Deliberately short-lived (one loader run) — not a global cache.
 */
private class DatasetClassResolver(private val datasets: TalentDatasetRepository) {

    private val documents = mutableMapOf<DatasetId, DatasetOutcome<TalentDataDocument>>()
    private val engines = mutableMapOf<Pair<DatasetId, Int>, TalentAllocationEngine?>()

    /** Loads (and caches) the document of an exact dataset identity. */
    suspend fun documentOf(id: DatasetId): DatasetOutcome<TalentDataDocument> =
        documents.getOrPut(id) { datasets.document(id) }

    /** The New Build screen intentionally creates from the bundled baseline bundle. */
    suspend fun baselineDocumentOf(token: ClassToken): DatasetOutcome<TalentDataDocument> =
        documentOf(DatasetId(BASELINE_BUNDLE.game, BASELINE_BUNDLE.clientBuild, token))

    private suspend fun engineOf(id: DatasetId, treeId: Int): TalentAllocationEngine? =
        engines.getOrPut(id to treeId) {
            when (val document = documentOf(id)) {
                is DatasetOutcome.Failed -> null
                is DatasetOutcome.Loaded -> try {
                    TalentAllocationEngine(document.value, treeId = treeId)
                } catch (_: AllocationEngineException) {
                    null
                }
            }
        }

    /**
     * One card's honest metadata. Engine-derived point values appear ONLY
     * when every gate passes for the record's EXACT identity: the dataset
     * loads, the saved `treeId` exists in it, and the saved allocations
     * validate with the real engine. No migration, no baseline fallback, no
     * clamping — a failed gate simply leaves the derived fields absent; only
     * facts already in the persisted record (plus the class display name when
     * the exact document loaded) are shown.
     */
    suspend fun cardOf(record: LocalBuildRecord): BuildCardModel {
        val id = DatasetId(
            game = record.build.game,
            clientBuild = record.build.clientBuild,
            classToken = record.build.classToken,
        )
        return when (val document = documentOf(id)) {
            is DatasetOutcome.Failed ->
                BuildCardModel(record, classDisplayName = null, spentPoints = null, pointBudget = null)

            is DatasetOutcome.Loaded -> {
                val displayName = document.value.classInfo.name
                val tree = document.value.trees.firstOrNull { it.id == record.build.treeId }
                val engine = tree?.let { engineOf(id, it.id) }
                if (engine == null) {
                    return BuildCardModel(record, displayName, spentPoints = null, pointBudget = null)
                }
                val state = record.build.toBuildState()
                if (!engine.validate(state).valid) {
                    return BuildCardModel(record, displayName, spentPoints = null, pointBudget = null)
                }
                BuildCardModel(
                    record = record,
                    classDisplayName = displayName,
                    spentPoints = engine.spentPoints(state),
                    pointBudget = engine.pointBudget,
                )
            }
        }
    }
}
