package io.github.alexeykravtsov95.talentforge.data.talentdata

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.GameId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root of a `talent-data.v1` document (docs/schema/talent-data.v1.schema.json):
 * the normalized talent tree dataset of one class at one client build.
 */
@Serializable
data class TalentDataDocument(
    val schemaVersion: Int = 1,
    val game: GameId,
    val clientBuild: String,
    @SerialName("class") val classInfo: ClassInfo,
    val trees: List<TalentTree>,
    val contentHash: ContentHash,
    val generatedAt: String,
    val provenance: Provenance,
)

/** ClassRef: locale-independent identity plus display name. */
@Serializable
data class ClassInfo(
    val id: Int,
    val token: ClassToken,
    val name: String,
)

/** Provenance: which pipeline source and DB2 tables produced the document. */
@Serializable
data class Provenance(
    val source: String,
    val tables: List<String>,
)

/** A single talent tree. Forever currently publishes one tree per class. */
@Serializable
data class TalentTree(
    val id: Int,
    val pointBudget: PointBudget?,
    val layout: TalentLayout,
    val sections: List<TalentSection>,
    val nodes: List<TalentNode>,
    val visualOnlyEdges: List<VisualOnlyEdge>,
)

/** TraitCurrency-backed spendable budget of a tree. */
@Serializable
data class PointBudget(
    val currencyId: Int,
    val maxPoints: Int,
)

/** Dominant grid spacing of the client layout, in client units. */
@Serializable
data class TalentLayout(
    val xStep: Int,
    val yStep: Int,
)

/** Sub-tree grouping of nodes; the whole tree is one section when unresolved. */
@Serializable
data class TalentSection(
    val index: Int,
    val name: String?,
    val nodeIds: List<Int>,
)

/** TraitEdge.Type 0: drawn by the client but carrying no gameplay requirement. */
@Serializable
data class VisualOnlyEdge(
    val fromNodeId: Int,
    val toNodeId: Int,
)
