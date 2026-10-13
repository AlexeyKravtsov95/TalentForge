package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.allocation.AllocationRules
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree

/**
 * M5.UX.1 section projection: the real tree's `sections` (each a sub-tree of
 * the one class tree — the tabs of 1A v2) mapped onto the existing
 * geometry-only [TalentTreeUiModel].
 *
 * It is a pure *view* of the one tree, never a second game model: node ids,
 * names, max ranks and world positions are the very same
 * [TalentTreeNodeUiModel] instances, the allocation engine and its single
 * [BuildState] keep working on the whole tree (dependencies on nodes of other
 * sections still count), and nothing here knows any allocation rule.
 *
 * Per section:
 * - [TalentTreeSectionUiModel.model] holds only the section's nodes, the
 *   connectors whose BOTH ends are in the section, and bounds computed from
 *   the section's actual nodes — world positions are untouched, so the
 *   section keeps the client's relative geometry exactly (the camera, not the
 *   model, brings it on screen);
 * - connectors between two sections are not drawn in either canvas; they are
 *   reported in [TalentTreeSectionsResult.Sectioned.crossSectionEdges] (none
 *   exist in the published Forever data) and still enforced by the engine.
 *
 * Labels come from the data: the dataset's section name when it publishes
 * one ([TalentTreeSectionUiModel.datasetName]), otherwise "Tree N" by
 * position (1A v2 S8: a null name reads "Tree N"). The projection itself
 * never guesses a specialization — since M5.REL.1 the presentation layer
 * resolves the approved specialization name from (class token, section
 * index) through [talentSectionDisplayLabel]; this locale-neutral
 * [TalentTreeSectionUiModel.label] is its fallback input.
 */
data class TalentTreeSectionUiModel(
    /** The dataset's `TalentSection.index` — the stable section identity. */
    val index: Int,
    /** Tab position, 0-based, in ascending [index] order. */
    val ordinal: Int,
    /** The dataset's own section name, or `null` when it publishes none. */
    val datasetName: String?,
    /**
     * The locale-neutral label: the dataset's section name, or "Tree N" when
     * the dataset has none. The localized display label is resolved from
     * [datasetName] at presentation time (M5.REL.1).
     */
    val label: String,
    /** The section's own nodes/connectors/bounds (same node instances as the whole tree). */
    val model: TalentTreeUiModel,
    /**
     * The section's node ids in reading order (top row first, then left to
     * right) — the tab icon is the first of them that has a real icon.
     */
    val readingOrderNodeIds: List<Int>,
)

/** Result of [buildTalentTreeSections]: sectioned, or an explicit typed reason why not. */
sealed interface TalentTreeSectionsResult {

    data class Sectioned(
        val sections: List<TalentTreeSectionUiModel>,
        /** Every node id → its section index (covers every node of the tree). */
        val sectionOfNode: Map<Int, Int>,
        /** Real connectors whose ends lie in different sections (drawn in neither canvas). */
        val crossSectionEdges: List<TalentTreeEdgeUiModel>,
    ) : TalentTreeSectionsResult {

        fun section(index: Int): TalentTreeSectionUiModel? = sections.firstOrNull { it.index == index }

        /** The section a node belongs to (null only for an id that is not in the tree). */
        fun sectionOf(nodeId: Int): TalentTreeSectionUiModel? = sectionOfNode[nodeId]?.let(::section)
    }

    /**
     * The tree's sections cannot be presented as tabs without hiding or
     * guessing a node. The screen then shows the whole tree on one canvas
     * (every node visible, nothing re-assigned) — fail-safe, never silent:
     * the [issue] says exactly what was inconsistent.
     */
    data class Unsectioned(val issue: TalentTreeSectionIssue) : TalentTreeSectionsResult
}

/** Why a tree cannot be split into section tabs (presentation precondition, not a dataset validator). */
sealed interface TalentTreeSectionIssue {
    /** The tree publishes no section at all. */
    data object NoSections : TalentTreeSectionIssue

    /** Two sections share one index. */
    data class DuplicateSectionIndex(val index: Int) : TalentTreeSectionIssue

    /** A node has no `sectionIndex` (the contract allows null). */
    data class NodeWithoutSection(val nodeId: Int) : TalentTreeSectionIssue

    /** A node names a section index the tree does not publish. */
    data class UnknownSectionIndex(val nodeId: Int, val sectionIndex: Int) : TalentTreeSectionIssue

    /**
     * A section's `nodeIds` and the nodes' `sectionIndex` disagree (a node
     * listed in another section, listed twice, or not listed at all).
     */
    data class MembershipMismatch(val sectionIndex: Int, val nodeId: Int) : TalentTreeSectionIssue

    /** The UI model and the tree disagree on the node set (never expected; fail-safe). */
    data class ModelMismatch(val nodeId: Int) : TalentTreeSectionIssue
}

/**
 * Splits [model] (built from [tree]) into its section views. Deterministic;
 * every node of [model] lands in exactly one section, or the result is
 * [TalentTreeSectionsResult.Unsectioned] with the first inconsistency found.
 * An empty section is allowed (1A v2 S8 "empty branch") and keeps a
 * degenerate bounds box at the origin.
 */
fun buildTalentTreeSections(tree: TalentTree, model: TalentTreeUiModel): TalentTreeSectionsResult {
    if (tree.sections.isEmpty()) {
        return TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.NoSections)
    }
    val publishedIndices = HashSet<Int>()
    for (section in tree.sections) {
        if (!publishedIndices.add(section.index)) {
            return TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.DuplicateSectionIndex(section.index))
        }
    }

    val treeNodeIds = tree.nodes.mapTo(HashSet()) { it.id }
    val modelNodeIds = model.nodes.mapTo(HashSet()) { it.id }
    tree.nodes.firstOrNull { it.id !in modelNodeIds }?.let {
        return TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.ModelMismatch(it.id))
    }
    model.nodes.firstOrNull { it.id !in treeNodeIds }?.let {
        return TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.ModelMismatch(it.id))
    }

    // node.sectionIndex is the membership; every section.nodeIds must agree exactly
    val sectionOfNode = LinkedHashMap<Int, Int>(tree.nodes.size)
    for (node in tree.nodes) {
        val index = node.sectionIndex
            ?: return TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.NodeWithoutSection(node.id))
        if (index !in publishedIndices) {
            return TalentTreeSectionsResult.Unsectioned(
                TalentTreeSectionIssue.UnknownSectionIndex(nodeId = node.id, sectionIndex = index),
            )
        }
        sectionOfNode[node.id] = index
    }
    for (section in tree.sections) {
        val listed = HashSet<Int>()
        for (nodeId in section.nodeIds) {
            if (!listed.add(nodeId) || sectionOfNode[nodeId] != section.index) {
                return TalentTreeSectionsResult.Unsectioned(
                    TalentTreeSectionIssue.MembershipMismatch(sectionIndex = section.index, nodeId = nodeId),
                )
            }
        }
        val unlisted = sectionOfNode.entries.firstOrNull { it.value == section.index && it.key !in listed }
        if (unlisted != null) {
            return TalentTreeSectionsResult.Unsectioned(
                TalentTreeSectionIssue.MembershipMismatch(sectionIndex = section.index, nodeId = unlisted.key),
            )
        }
    }

    val sections = tree.sections.sortedBy { it.index }.mapIndexed { ordinal, section ->
        val nodes = model.nodes.filter { sectionOfNode[it.id] == section.index }
        val ids = nodes.mapTo(HashSet()) { it.id }
        TalentTreeSectionUiModel(
            index = section.index,
            ordinal = ordinal,
            datasetName = section.name?.takeIf { it.isNotBlank() },
            label = section.name?.takeIf { it.isNotBlank() } ?: "Tree ${ordinal + 1}",
            model = TalentTreeUiModel(
                treeId = model.treeId,
                nodes = nodes,
                edges = model.edges.filter { it.fromNodeId in ids && it.toNodeId in ids },
                bounds = sectionBoundsOf(nodes),
            ),
            readingOrderNodeIds = nodes
                .sortedWith(compareBy<TalentTreeNodeUiModel>({ it.centerY }, { it.centerX }, { it.id }))
                .map { it.id },
        )
    }
    return TalentTreeSectionsResult.Sectioned(
        sections = sections,
        sectionOfNode = sectionOfNode,
        crossSectionEdges = model.edges.filter { sectionOfNode[it.fromNodeId] != sectionOfNode[it.toNodeId] },
    )
}

/** Section bounds from its actual nodes (node visual box); an empty section gets a zero box at the origin. */
private fun sectionBoundsOf(nodes: List<TalentTreeNodeUiModel>): TalentTreeBounds {
    if (nodes.isEmpty()) return TalentTreeBounds(0f, 0f, 0f, 0f)
    val half = TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f
    return TalentTreeBounds(
        minX = nodes.minOf { it.centerX } - half,
        minY = nodes.minOf { it.centerY } - half,
        maxX = nodes.maxOf { it.centerX } + half,
        maxY = nodes.maxOf { it.centerY } + half,
    )
}

/**
 * Points spent in each section of [result] for [state], by section index —
 * the allocation rules' own point count (`AllocationRules.pointsSpent`, rule
 * COST-1/GATE-2, the count the engine itself uses for tier gates) restricted
 * to the section's nodes. For a valid build the sum over all sections equals
 * `TalentAllocationEngine.spentPoints(state)` (the header counter).
 */
fun sectionSpentPoints(
    result: TalentTreeSectionsResult.Sectioned,
    state: BuildState,
): Map<Int, Int> {
    val ranks = state.ranks()
    return result.sections.associate { section ->
        section.index to AllocationRules.pointsSpent(ranks, section.model.nodes.map { it.id })
    }
}
