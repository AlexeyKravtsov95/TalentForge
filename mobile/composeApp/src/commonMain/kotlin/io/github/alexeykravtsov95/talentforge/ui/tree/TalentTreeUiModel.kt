package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree

/**
 * UI-facing, geometry-only projection of one talent tree for canvas rendering.
 *
 * It is a pure transformation of the real `TalentDataDocument` tree: node
 * identity, display strings and world-canvas positions plus the two real edge
 * kinds (prerequisites and visual-only edges). It deliberately contains no
 * allocation semantics, no ranks spent and no second copy of the contract
 * hierarchy — everything but geometry is referenced, not duplicated.
 *
 * All coordinates are world-canvas dp at the portrait 1× reference scale: a
 * uniform factor maps client units to dp (one `layout.xStep` becomes
 * [TalentTreeCanvasMetrics.GRID_STEP_DP]), preserving the client's relative
 * geometry without distortion.
 */
data class TalentTreeUiModel(
    val treeId: Int,
    val nodes: List<TalentTreeNodeUiModel>,
    val edges: List<TalentTreeEdgeUiModel>,
    val bounds: TalentTreeBounds,
)

/** One rendered node; `centerX`/`centerY` are world-canvas dp. */
data class TalentTreeNodeUiModel(
    val id: Int,
    val name: String?,
    val monogram: String,
    val maxRank: Int,
    val sectionIndex: Int?,
    val centerX: Float,
    val centerY: Float,
)

/** One rendered connector between two real nodes, in world-canvas dp. */
data class TalentTreeEdgeUiModel(
    val fromNodeId: Int,
    val toNodeId: Int,
    val kind: TalentTreeEdgeKind,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

/** The two edge kinds the dataset actually publishes. */
enum class TalentTreeEdgeKind {
    /** `TalentNode.prerequisites` — carries gameplay requirement. */
    PREREQUISITE,

    /** `TalentTree.visualOnlyEdges` — drawn by the client, no requirement. */
    VISUAL_ONLY,
}

/** World-canvas axis-aligned bounds covering every node's full visual box. */
data class TalentTreeBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f
}

/** Typed failure of the document → UI-model transformation; never a null model. */
sealed interface TalentTreeUiBuildError {
    /** The contract allows an empty `trees` array; a tree screen cannot render it. */
    data object NoTrees : TalentTreeUiBuildError

    /**
     * The contract allows several trees per class, but the current published
     * datasets contain exactly one. Rather than silently rendering only the
     * first tree, the screen fails closed until multi-tree presentation is
     * designed.
     */
    data class MultipleTrees(val treeIds: List<Int>) : TalentTreeUiBuildError

    /** A published layout with a non-positive step cannot define a grid mapping. */
    data class InvalidLayout(val xStep: Int) : TalentTreeUiBuildError

    /** An edge references a node id the tree does not contain. */
    data class DanglingEdgeReference(
        val kind: TalentTreeEdgeKind,
        val fromNodeId: Int,
        val toNodeId: Int,
    ) : TalentTreeUiBuildError
}

/** Result of [buildTalentTreeUiModel]. */
sealed interface TalentTreeUiBuildResult {
    data class Ready(val model: TalentTreeUiModel) : TalentTreeUiBuildResult

    data class Failed(val error: TalentTreeUiBuildError) : TalentTreeUiBuildResult
}

/**
 * Builds the UI model of a document's single tree. Deterministic: the same
 * document always produces the same geometry, because node positions come only
 * from `TalentNode.position` (the authoritative client coordinates) scaled by
 * one uniform factor derived from `layout.xStep`.
 */
fun buildTalentTreeUiModel(document: TalentDataDocument): TalentTreeUiBuildResult {
    val tree = document.trees.singleOrNull()
        ?: return TalentTreeUiBuildResult.Failed(
            when {
                document.trees.isEmpty() -> TalentTreeUiBuildError.NoTrees
                else -> TalentTreeUiBuildError.MultipleTrees(document.trees.map { it.id })
            },
        )

    return buildTreeUiModel(tree)
}

/** Tree-level variant of [buildTalentTreeUiModel] for callers that already hold the tree. */
fun buildTreeUiModel(tree: TalentTree): TalentTreeUiBuildResult {
    val xStep = tree.layout.xStep
    if (xStep <= 0) {
        return TalentTreeUiBuildResult.Failed(TalentTreeUiBuildError.InvalidLayout(xStep))
    }

    val factor = TalentTreeCanvasMetrics.GRID_STEP_DP / xStep
    val nodesById = HashMap<Int, TalentTreeNodeUiModel>(tree.nodes.size)

    val nodes = tree.nodes.map { node ->
        val uiNode = TalentTreeNodeUiModel(
            id = node.id,
            name = node.name,
            monogram = nodeMonogram(node.name, node.id),
            maxRank = node.maxRank,
            sectionIndex = node.sectionIndex,
            centerX = node.position.x * factor,
            centerY = node.position.y * factor,
        )
        val existing = nodesById.put(node.id, uiNode)
        check(existing == null) { "duplicate node id in tree: ${node.id}" }
        uiNode
    }

    val edges = ArrayList<TalentTreeEdgeUiModel>(
        tree.nodes.sumOf { it.prerequisites.size } + tree.visualOnlyEdges.size,
    )
    for (node in tree.nodes) {
        for (prerequisite in node.prerequisites) {
            val from = nodesById[prerequisite.nodeId]
            if (from == null || node.id !in nodesById) {
                return TalentTreeUiBuildResult.Failed(
                    TalentTreeUiBuildError.DanglingEdgeReference(
                        kind = TalentTreeEdgeKind.PREREQUISITE,
                        fromNodeId = prerequisite.nodeId,
                        toNodeId = node.id,
                    ),
                )
            }
            edges += TalentTreeEdgeUiModel(
                fromNodeId = from.id,
                toNodeId = node.id,
                kind = TalentTreeEdgeKind.PREREQUISITE,
                startX = from.centerX,
                startY = from.centerY,
                endX = nodesById.getValue(node.id).centerX,
                endY = nodesById.getValue(node.id).centerY,
            )
        }
    }
    for (edge in tree.visualOnlyEdges) {
        val from = nodesById[edge.fromNodeId]
        val to = nodesById[edge.toNodeId]
        if (from == null || to == null) {
            return TalentTreeUiBuildResult.Failed(
                TalentTreeUiBuildError.DanglingEdgeReference(
                    kind = TalentTreeEdgeKind.VISUAL_ONLY,
                    fromNodeId = edge.fromNodeId,
                    toNodeId = edge.toNodeId,
                ),
            )
        }
        edges += TalentTreeEdgeUiModel(
            fromNodeId = from.id,
            toNodeId = to.id,
            kind = TalentTreeEdgeKind.VISUAL_ONLY,
            startX = from.centerX,
            startY = from.centerY,
            endX = to.centerX,
            endY = to.centerY,
        )
    }

    return TalentTreeUiBuildResult.Ready(
        TalentTreeUiModel(
            treeId = tree.id,
            nodes = nodes,
            edges = edges,
            bounds = boundsOf(nodes),
        ),
    )
}

/** Bounds grown from node centers by the node visual half-size (not the larger touch target). */
private fun boundsOf(nodes: List<TalentTreeNodeUiModel>): TalentTreeBounds {
    val half = TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (node in nodes) {
        minX = minOf(minX, node.centerX - half)
        minY = minOf(minY, node.centerY - half)
        maxX = maxOf(maxX, node.centerX + half)
        maxY = maxOf(maxY, node.centerY + half)
    }
    return TalentTreeBounds(minX = minX, minY = minY, maxX = maxX, maxY = maxY)
}

/**
 * Deterministic compact fallback glyph derived from the real node name.
 *
 * talent-data.v1 has no icon/texture identifier (and M4.1 adds none), so node
 * content is this text fallback until a real icon renderer exists. The rule
 * mirrors the B3 mock's own monograms ("HS" Heroic Strike, "RE" Rend,
 * "TM" Tactical Mastery): a leading English "Improved" is dropped when another
 * word follows, then the first letters of the first and last remaining words
 * are used (a single remaining word contributes its first two letters). A node
 * without a published name falls back to its numeric id, never to invented art.
 */
fun nodeMonogram(name: String?, nodeId: Int): String {
    if (name.isNullOrBlank()) return nodeId.toString()
    val words = name.trim().split(WHITESPACE).filter { it.isNotBlank() }
    if (words.isEmpty()) return nodeId.toString()

    val keyWords = if (words.size > 1 && words.first().equals("improved", ignoreCase = true)) {
        words.drop(1)
    } else {
        words
    }
    val first = keyWords.first()
    val last = keyWords.last()
    return buildString {
        append(first.first())
        if (first !== last) append(last.first()) else if (first.length > 1) append(first[1])
    }.uppercase()
}

private val WHITESPACE = Regex("\\s+")
