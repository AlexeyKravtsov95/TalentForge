package io.github.alexeykravtsov95.talentforge.ui.share

import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.share.BuildShareCode
import io.github.alexeykravtsov95.talentforge.share.ShareCodeEncodeError
import io.github.alexeykravtsov95.talentforge.share.ShareCodeEncoding

/** Outcome of exporting one build as a `talentforge-share.v1` code. */
sealed interface BuildExport {

    /** [code] is the exact canonical share code — the only text ever copied or shared. */
    data class Ready(val code: String) : BuildExport

    data class Failed(val failure: BuildExportFailure) : BuildExport
}

/** Typed export failures (never a message to parse). */
sealed interface BuildExportFailure {

    /** The codec refused the build. Cannot happen for a valid build of the bundled datasets; stays typed. */
    data class Encode(val error: ShareCodeEncodeError) : BuildExportFailure

    /** The persisted record could not be read (All Builds export), or no longer exists. */
    data class Library(val error: BuildLibraryError) : BuildExportFailure
}

/** Encodes [build] exactly as given. */
fun exportBuildFile(build: BuildFile): BuildExport = when (val encoded = BuildShareCode.encode(build)) {
    is ShareCodeEncoding.Encoded -> BuildExport.Ready(encoded.code)
    is ShareCodeEncoding.Failed -> BuildExport.Failed(BuildExportFailure.Encode(encoded.error))
}

/**
 * Editor export: the [state] the editing session holds RIGHT NOW, bound to
 * the dataset identity of the record it was opened from ([identity] — its
 * game / client build / class never change while editing). The persisted
 * `record.build` is deliberately not an input: it may lag behind a pending
 * autosave or a failed one, and sharing must reflect what the player sees.
 */
fun exportEditorState(state: BuildState, identity: BuildFile): BuildExport = exportBuildFile(
    buildFileOf(
        state = state,
        game = identity.game,
        clientBuild = identity.clientBuild,
        classToken = identity.classToken,
    ),
)

/**
 * All Builds export: the LATEST persisted build of exactly [localId], read at
 * the moment of sharing. Read-only — it uses [LocalBuildRepository.load], not
 * `open` (which would advance `lastOpenedAt`), so no timestamp, name, order or
 * payload changes. A missing record is the typed `UnknownRecord`.
 */
fun exportPersistedBuild(repository: LocalBuildRepository, localId: String): BuildExport =
    when (val library = repository.load()) {
        is BuildLibraryOutcome.Failed -> BuildExport.Failed(BuildExportFailure.Library(library.error))
        is BuildLibraryOutcome.Loaded -> {
            val record = library.value.firstOrNull { it.localId == localId }
            if (record == null) {
                BuildExport.Failed(BuildExportFailure.Library(BuildLibraryError.UnknownRecord(localId)))
            } else {
                exportBuildFile(record.build)
            }
        }
    }
