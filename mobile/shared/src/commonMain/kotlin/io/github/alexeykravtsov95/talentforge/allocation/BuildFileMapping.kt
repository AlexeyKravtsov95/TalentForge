package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile

/**
 * Pure conversions between the domain editing state and the portable
 * `allocation-build.v1` payload.
 *
 * Only the **build** crosses this boundary: `BuildState`'s allocation triples
 * are exactly the contract's `allocations`, and the dataset identity
 * (game / client build / class token) is carried by the record that owns the
 * build. Editing-session UI details — selection, viewport, inspector
 * expansion, Show guide, undo/redo history, notices, animations — are never
 * serialized; a reopened build starts with empty histories by design.
 */
fun BuildFile.toBuildState(): BuildState = BuildState.of(treeId = treeId, allocations = allocations)

/** Rebuilds the portable payload of [state] bound to its dataset identity. */
fun buildFileOf(
    state: BuildState,
    game: GameId,
    clientBuild: String,
    classToken: ClassToken,
): BuildFile = BuildFile(
    game = game,
    clientBuild = clientBuild,
    classToken = classToken,
    treeId = state.treeId,
    allocations = state.allocations,
)
