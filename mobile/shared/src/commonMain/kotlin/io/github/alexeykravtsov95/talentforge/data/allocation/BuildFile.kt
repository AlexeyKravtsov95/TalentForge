package io.github.alexeykravtsov95.talentforge.data.allocation

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root of an `allocation-build.v1` file (docs/schema/allocation-build.v1.schema.json):
 * a portable build plan bound to a dataset identity.
 *
 * Serialization contract only — M3A intentionally contains no allocation
 * semantics (validation, spend/refund rules arrive with the M3B engine).
 */
@Serializable
data class BuildFile(
    val schemaVersion: Int = 1,
    val game: GameId,
    val clientBuild: String,
    @SerialName("class") val classToken: ClassToken,
    val treeId: Int,
    val allocations: List<NodeAllocation> = emptyList(),
)

/** Purchased ranks of one node: which entry is selected and how many ranks it has. */
@Serializable
data class NodeAllocation(
    val nodeId: Int,
    val entryId: Int,
    val rank: Int,
)
