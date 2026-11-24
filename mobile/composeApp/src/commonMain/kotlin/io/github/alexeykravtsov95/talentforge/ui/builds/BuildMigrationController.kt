package io.github.alexeykravtsov95.talentforge.ui.builds

import io.github.alexeykravtsov95.talentforge.allocation.BuildMigrationResult
import io.github.alexeykravtsov95.talentforge.allocation.previewBuildMigration
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.ota.DatasetSnapshot
import io.github.alexeykravtsov95.talentforge.data.valueOrNull
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.ota.DatasetActivationManager
import io.github.alexeykravtsov95.talentforge.ui.ota.SessionDatasets
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentNodeLocalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface MigrationProblem {
    data object Stale : MigrationProblem
    data object PendingSaveFailed : MigrationProblem
    data object DatasetsUnavailable : MigrationProblem
    data class Library(val error: BuildLibraryError) : MigrationProblem
    data class Preview(val result: BuildMigrationResult) : MigrationProblem
}

internal sealed interface MigrationPreview {
    data class Ready(
        val record: LocalBuildRecord,
        val plan: BuildMigrationResult.Ready,
        val snapshot: DatasetSnapshot?,
        val russianNames: Map<Int, String>,
    ) : MigrationPreview {
        fun proposedName(unnamedTitle: String): String {
            val suffix = " — ${plan.build.clientBuild.substringAfterLast('.')}"
            val maxPrefix = LocalBuildRepository.MAX_BUILD_NAME_LENGTH - suffix.length
            val prefix = (record.name ?: unnamedTitle).take(maxPrefix).trimEnd()
            // Kotlin's length limit is in UTF-16 units. Avoid cutting an emoji in half.
            return prefix.dropLastWhile { it.isHighSurrogate() }.trimEnd() + suffix
        }
    }

    data class Failed(val problem: MigrationProblem) : MigrationPreview
}

internal sealed interface MigrationWrite {
    data class Saved(val copy: LocalBuildRecord) : MigrationWrite
    data class Failed(val problem: MigrationProblem) : MigrationWrite
}

/** One app-scoped entry point for previews and commits from build menus. */
class BuildMigrationController internal constructor(
    private val repository: LocalBuildRepository,
    private val autosave: BuildAutosaveCoordinator,
    private val bundled: SessionDatasets,
    private val activation: DatasetActivationManager?,
) {
    internal suspend fun preview(localId: String): MigrationPreview {
        // Autosave already owns the submitted write. Preview only waits and reads.
        if (autosave.flush()?.let { it.localId == localId && it.failed } == true) {
            return MigrationPreview.Failed(MigrationProblem.PendingSaveFailed)
        }
        return withContext(Dispatchers.Default) {
            val record = when (val loaded = repository.load()) {
                is BuildLibraryOutcome.Loaded -> loaded.value.firstOrNull { it.localId == localId }
                    ?: return@withContext MigrationPreview.Failed(MigrationProblem.Library(BuildLibraryError.UnknownRecord(localId)))
                is BuildLibraryOutcome.Failed ->
                    return@withContext MigrationPreview.Failed(MigrationProblem.Library(loaded.error))
            }
            val snapshot = activation?.snapshot?.value
            val lease = if (snapshot != null) {
                activation.lease(snapshot)
                    ?: return@withContext MigrationPreview.Failed(MigrationProblem.DatasetsUnavailable)
            } else {
                null
            }
            try {
                val session = snapshot?.let(SessionDatasets::of) ?: bundled
                when (val plan = previewBuildMigration(record.build, session.product, session.datasets)) {
                    is BuildMigrationResult.Ready -> MigrationPreview.Ready(
                        record, plan, snapshot,
                        russianNames = if (plan.losses.isEmpty()) emptyMap() else session.sourceNames(record),
                    )
                    else -> MigrationPreview.Failed(MigrationProblem.Preview(plan))
                }
            } finally {
                lease?.release()
            }
        }
    }

    internal suspend fun confirm(preview: MigrationPreview.Ready, name: String): MigrationWrite =
        autosave.afterAutosaveForMigration { pending ->
            if (pending?.localId == preview.record.localId && pending.failed) {
                return@afterAutosaveForMigration MigrationWrite.Failed(MigrationProblem.PendingSaveFailed)
            }
            withContext(Dispatchers.Default) {
                val commit = { repository.createMigratedCopy(preview.record, preview.plan, name) }
                val snapshot = preview.snapshot
                val session = snapshot?.let(SessionDatasets::of) ?: bundled
                if (snapshot != null && activation?.snapshot?.value !== snapshot) {
                    return@withContext MigrationWrite.Failed(MigrationProblem.Stale)
                }
                val lease = if (snapshot != null) {
                    activation?.lease(snapshot) ?: return@withContext MigrationWrite.Failed(MigrationProblem.Stale)
                } else {
                    null
                }
                val result = try {
                    val build = preview.plan.build
                    val target = session.datasets.document(build.datasetId()).valueOrNull()
                    if (target?.contentHash != preview.plan.targetContentHash ||
                        target.trees.count { it.id == build.treeId } != 1
                    ) {
                        return@withContext MigrationWrite.Failed(MigrationProblem.Stale)
                    }
                    if (snapshot == null) commit() else activation?.withCurrentSnapshot(snapshot, commit)
                } finally {
                    lease?.release()
                }
                when (result) {
                    null -> MigrationWrite.Failed(MigrationProblem.Stale)
                    is BuildLibraryOutcome.Loaded -> MigrationWrite.Saved(result.value)
                    is BuildLibraryOutcome.Failed -> MigrationWrite.Failed(
                        if (result.error == BuildLibraryError.MigrationSourceChanged) MigrationProblem.Stale
                        else MigrationProblem.Library(result.error),
                    )
                }
            }
        }
}

private suspend fun SessionDatasets.sourceNames(record: LocalBuildRecord): Map<Int, String> {
    val build = record.build
    val document = datasets.document(build.datasetId()).valueOrNull()
        ?: return emptyMap()
    val tree = document.trees.singleOrNull { it.id == build.treeId } ?: return emptyMap()
    val descriptionLoad = descriptions.load(document, tree)
    val iconLoad = icons.load(document, tree)
    val localized = localization.load(document, tree, descriptionLoad.tooltips, iconLoad.visuals)
    return (localized as? TalentNodeLocalization.Loaded)?.names.orEmpty()
}

private fun BuildFile.datasetId() = DatasetId(game, clientBuild, classToken)
