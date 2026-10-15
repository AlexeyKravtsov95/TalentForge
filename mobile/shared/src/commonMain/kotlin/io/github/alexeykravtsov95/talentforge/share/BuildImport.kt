package io.github.alexeykravtsov95.talentforge.share

import io.github.alexeykravtsov95.talentforge.allocation.AllocationEngineException
import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssue
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.reasonCodes
import io.github.alexeykravtsov95.talentforge.allocation.toBuildState
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile

/** Outcome of [prepareBuildImport]: a verified preview, or a typed reason it cannot be imported. */
sealed interface BuildImportPreparation {
    data class Ready(val preview: BuildImportPreview) : BuildImportPreparation

    data class Rejected(val failure: BuildImportFailure) : BuildImportPreparation
}

/**
 * A share code that decoded AND validated against its own exact dataset.
 * [build] is the verified portable payload, in canonical allocation order —
 * exactly what M5.SHARE.2 hands to `LocalBuildRepository.createDraft` once the
 * user confirms. Nothing here has been persisted.
 */
data class BuildImportPreview(
    val build: BuildFile,
    /** Class name as published by the dataset document (data, not localized UI copy). */
    val className: String,
    val spentPoints: Int,
    val pointBudget: Int,
) {
    val game: GameId get() = build.game
    val clientBuild: String get() = build.clientBuild
    val classToken: ClassToken get() = build.classToken
    val treeId: Int get() = build.treeId
}

/** Typed reasons a share code cannot become a build. Program logic branches on the type only. */
sealed interface BuildImportFailure {

    /** The text is not a well-formed `talentforge-share.v1` code. */
    data class InvalidCode(val error: ShareCodeDecodeError) : BuildImportFailure

    /** No dataset of this exact game and client build is bundled. Never substituted by another build. */
    data class ClientBuildUnavailable(val game: GameId, val clientBuild: String) : BuildImportFailure

    /** The bundle exists but has no document for this class. */
    data class ClassUnavailable(
        val game: GameId,
        val clientBuild: String,
        val classToken: ClassToken,
    ) : BuildImportFailure

    /** The exact dataset exists but failed an integrity or parsing check. */
    data class DatasetRejected(val error: DatasetLoadError) : BuildImportFailure

    /** The code's tree is not a tree of that class document. */
    data class TreeMismatch(val buildTreeId: Int, val availableTreeIds: List<Int>) : BuildImportFailure

    /** The engine refuses the dataset itself; [reason] is a diagnostic only. */
    data class EngineUnavailable(val reason: String) : BuildImportFailure

    /**
     * The allocation is not a valid plan on its own dataset — unknown nodes or
     * entries, invalid ranks, unmet prerequisites or tier gates, overspending,
     * unsupported mechanics. [issues] are the engine's, in canonical order.
     */
    data class InvalidAllocation(val issues: List<AllocationIssue>) : BuildImportFailure {
        val reasonCodes: List<String> get() = reasonCodes(issues)
    }
}

/**
 * Read-only import preparation (M5.SHARE.1):
 *
 * 1. parse the share code and check its integrity ([BuildShareCode.decode]);
 * 2. resolve the code's EXACT game / client build / class through [datasets] —
 *    a code made on `1.60.1.70009` is checked against `1.60.1.70009`, never
 *    against the product dataset or the newest bundle, and is never migrated;
 * 3. require the code's tree to exist in that document;
 * 4. validate the allocation with the real [TalentAllocationEngine].
 *
 * Writes nothing: the function has no access to any build library or storage,
 * and the dataset repository is only read. Cancellation propagates.
 */
suspend fun prepareBuildImport(code: String, datasets: TalentDatasetRepository): BuildImportPreparation {
    val build = when (val decoded = BuildShareCode.decode(code)) {
        is ShareCodeDecoding.Failed -> return rejected(BuildImportFailure.InvalidCode(decoded.error))
        is ShareCodeDecoding.Decoded -> decoded.build
    }
    val id = DatasetId(game = build.game, clientBuild = build.clientBuild, classToken = build.classToken)
    val document = when (val outcome = datasets.document(id)) {
        is DatasetOutcome.Loaded -> outcome.value
        is DatasetOutcome.Failed -> return rejected(datasetFailure(build, outcome.error))
    }
    if (document.trees.none { it.id == build.treeId }) {
        return rejected(
            BuildImportFailure.TreeMismatch(
                buildTreeId = build.treeId,
                availableTreeIds = document.trees.map { it.id },
            ),
        )
    }
    val engine = try {
        TalentAllocationEngine(document, treeId = build.treeId)
    } catch (exception: AllocationEngineException) {
        return rejected(BuildImportFailure.EngineUnavailable(exception.message ?: "engine refused the dataset"))
    }
    val validation = engine.validate(build.toBuildState())
    if (!validation.valid) {
        return rejected(BuildImportFailure.InvalidAllocation(validation.issues))
    }
    return BuildImportPreparation.Ready(
        BuildImportPreview(
            build = build,
            className = document.classInfo.name,
            spentPoints = validation.spentPoints,
            pointBudget = validation.pointBudget,
        ),
    )
}

/**
 * A missing manifest means the client build is not bundled; a missing class
 * entry means the class is not in that bundle. Every other dataset error is an
 * integrity failure of the exact dataset and stays typed as such.
 */
private fun datasetFailure(build: BuildFile, error: DatasetLoadError): BuildImportFailure = when {
    error is DatasetLoadError.ManifestUnavailable && error.error is DatasetLoadError.AssetMissing ->
        BuildImportFailure.ClientBuildUnavailable(game = build.game, clientBuild = build.clientBuild)

    error is DatasetLoadError.ClassEntryMissing ->
        BuildImportFailure.ClassUnavailable(
            game = build.game,
            clientBuild = build.clientBuild,
            classToken = build.classToken,
        )

    else -> BuildImportFailure.DatasetRejected(error)
}

private fun rejected(failure: BuildImportFailure): BuildImportPreparation = BuildImportPreparation.Rejected(failure)
