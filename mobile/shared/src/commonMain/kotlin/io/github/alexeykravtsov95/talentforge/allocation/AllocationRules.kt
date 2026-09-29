package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.talentdata.PrerequisiteKind
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentEntry
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNode
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNodeType
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree

/**
 * Allocation rules of WoW: Forever talent trees, as pure functions over a tree
 * index — the Kotlin port of the Python reference `talent_data.allocation.rules`
 * (docs/talent-allocation-semantics.md §2). Anything the evidence does not cover
 * is *not* approximated: it is turned into a fail-closed issue.
 *
 * Terminology
 * rank map  `{node_id: purchased ranks}` of a (partial) build.
 * active  a prerequisite edge is active when its source node is fully ranked
 * (EDGE-ACTIVE — provisional reference rule, not client-proven).
 * available  every rule for purchasing the next rank of a node is met.
 */
object AllocationRules {

    /**
     * Rule COST-1: every purchased rank costs exactly one point of the tree's
     * point-budget currency. Not an assumption — normalization refuses to
     * produce a v1 document for a tree whose cost rows say otherwise.
     */
    const val RANK_COST_POINTS: Int = 1

    /**
     * TraitNodeType values the engine has evidence for. Forever 1.60.1 only uses
     * Single (0); tiered/selection/sub-tree selection never occur in Forever
     * data, so their rules cannot be verified -> fail closed.
     */
    val SUPPORTED_NODE_TYPES: Set<String> = setOf("single")

    /**
     * TraitNodeEntryType values with evidence. Forever 1.60.1 only uses
     * SpendSquare (1); other entry types carry their own mechanics -> fail closed.
     */
    val SUPPORTED_ENTRY_TYPES: Set<Int> = setOf(1)

    /** Enum.TraitNodeFlag.NeverPurchasable (2). */
    const val NODE_FLAG_NEVER_PURCHASABLE: Int = 2

    /**
     * TestGridPositioned (8): editor layout flag, never read by the client UI
     * code, observed on 14 Forever nodes whose purchase rules are otherwise
     * identical. Every other flag is unobserved in Forever and fails closed.
     */
    const val NODE_FLAG_TEST_GRID_POSITIONED: Int = 8

    val ALLOCATION_NEUTRAL_NODE_FLAGS: Int = NODE_FLAG_TEST_GRID_POSITIONED

    /** Every flag bit whose allocation semantics are proven or explicitly reported. */
    val KNOWN_NODE_FLAGS: Int = ALLOCATION_NEUTRAL_NODE_FLAGS or NODE_FLAG_NEVER_PURCHASABLE

    /**
     * Rule COST-1 / GATE-2: currency spent = purchased ranks x 1 point.
     * Granted/free ranks do not exist in the v1 contract; only purchased ranks
     * cost — and count — points.
     */
    fun pointsSpent(ranks: Map<Int, Int>, nodeIds: Iterable<Int>? = null): Int =
        if (nodeIds == null) {
            sumPositive(ranks.values) * RANK_COST_POINTS
        } else {
            sumPositive(nodeIds.map { ranks[it] ?: 0 }) * RANK_COST_POINTS
        }

    /**
     * Rule EDGE-ACTIVE: a prerequisite edge is active iff its source node is
     * fully ranked (purchased ranks == max ranks of the source). Same rule for
     * Required and Sufficient. **Provisional reference hypothesis — requires
     * client verification (E1) before the Kotlin contract is frozen.**
     */
    fun edgeActive(index: TreeIndex, ranks: Map<Int, Int>, sourceId: Int): Boolean {
        val source = index.nodes.getValue(sourceId)
        return (ranks[sourceId] ?: 0) >= source.maxRank
    }

    /**
     * Rule GATE-1/GATE-2: each unlock requirement needs [UnlockRequirement.requiredPoints]
     * points already spent in its counted nodes. All requirements of a node must
     * hold (conditions without the IsSufficient flag are conjunctive; every
     * Forever gate has Flags=0).
     */
    fun gateIssues(index: TreeIndex, ranks: Map<Int, Int>, node: TalentNode): List<AllocationIssue> =
        node.unlockRequirements.mapNotNull { requirement ->
            val spent = pointsSpent(ranks, requirement.countedNodeIds)
            if (spent < requirement.requiredPoints) {
                AllocationIssue(
                    code = AllocationIssueCode.TIER_GATE_NOT_MET,
                    message = "node ${node.id} (${node.name}) needs ${requirement.requiredPoints} " +
                        "points spent in ${requirement.countedNodeIds.size} counted nodes, has $spent",
                    nodeId = node.id,
                    required = requirement.requiredPoints,
                    actual = spent,
                )
            } else {
                null
            }
        }

    /**
     * Rule EDGE-REQ: every RequiredForAvailability edge must be active (ALL).
     * Rule EDGE-SUF: at least one SufficientForAvailability edge must be active
     * (ANY) — **provisional reference hypothesis, requires client verification
     * (E2) before the Kotlin contract is frozen.** Mixed required+sufficient
     * nodes are fail-closed statically (UNSUPPORTED_EDGE_COMBINATION) and are
     * not evaluated here.
     */
    fun edgeIssues(index: TreeIndex, ranks: Map<Int, Int>, node: TalentNode): List<AllocationIssue> {
        val required = index.requiredSources.getValue(node.id)
        val sufficient = index.sufficientSources.getValue(node.id)
        if (required.isNotEmpty() && sufficient.isNotEmpty()) {
            // reported by the static issue; never evaluate an unproven combination
            return emptyList()
        }
        val issues = mutableListOf<AllocationIssue>()
        for (sourceId in required) {
            if (!edgeActive(index, ranks, sourceId)) {
                val source = index.nodes.getValue(sourceId)
                issues += AllocationIssue(
                    code = AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET,
                    message = "node ${node.id} (${node.name}) requires ${source.name} ($sourceId) " +
                        "at ${source.maxRank}/${source.maxRank}, has ${ranks[sourceId] ?: 0}",
                    nodeId = node.id,
                    relatedNodeIds = listOf(sourceId),
                    required = source.maxRank,
                    actual = ranks[sourceId] ?: 0,
                )
            }
        }
        if (sufficient.isNotEmpty() && sufficient.none { edgeActive(index, ranks, it) }) {
            issues += AllocationIssue(
                code = AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET,
                message = "node ${node.id} (${node.name}) needs one of " +
                    sufficient.joinToString(", ") { "${index.nodes.getValue(it).name} ($it)" } +
                    " fully ranked",
                nodeId = node.id,
                relatedNodeIds = sufficient,
            )
        }
        return issues
    }

    /** State-dependent availability of the next rank: tier gates + prerequisite edges. */
    fun availabilityIssues(
        index: TreeIndex,
        ranks: Map<Int, Int>,
        node: TalentNode,
    ): List<AllocationIssue> = gateIssues(index, ranks, node) + edgeIssues(index, ranks, node)

    /** Purchase rule without the budget (used by the reachability closure). */
    fun canPurchaseNextRank(
        index: TreeIndex,
        ranks: Map<Int, Int>,
        nodeId: Int,
        maxRank: Int,
    ): Boolean {
        val node = index.nodes.getValue(nodeId)
        return index.staticIssues.getValue(nodeId).isEmpty() &&
            (ranks[nodeId] ?: 0) < maxRank &&
            availabilityIssues(index, ranks, node).isEmpty()
    }

    /**
     * Rule VALID-1: the largest part of `target` obtainable by purchasing one
     * rank at a time from an empty tree, every purchase obeying the rules.
     *
     * Greedy closure is exact because every purchase rule is *monotone*: adding
     * ranks never lowers points spent in a group and never deactivates an edge
     * (edges only activate at max rank and ranks are only added), and the static
     * fail-closed rules do not depend on the state. MutuallyExclusive edges —
     * the one non-monotone rule in the client enum — do not exist in Forever
     * and cannot enter a v1 document. So if any valid purchase order reaches
     * `target`, the closure reaches it too; the budget is checked separately.
     */
    fun reachableRanks(
        index: TreeIndex,
        target: Map<Int, Int>,
        maxRanks: Map<Int, Int>,
    ): Map<Int, Int> {
        val reached = mutableMapOf<Int, Int>()
        var progress = true
        while (progress) {
            progress = false
            for (nodeId in target.keys.sorted()) {
                val limit = minOf(target.getValue(nodeId), maxRanks.getValue(nodeId))
                while ((reached[nodeId] ?: 0) < limit &&
                    canPurchaseNextRank(index, reached, nodeId, maxRanks.getValue(nodeId))
                ) {
                    reached[nodeId] = (reached[nodeId] ?: 0) + 1
                    progress = true
                }
            }
        }
        return reached
    }

    private fun sumPositive(values: Iterable<Int>): Int =
        values.filter { it > 0 }.sum()
}

/**
 * Precomputed lookups of one [TalentTree] for the rule functions. All maps are
 * built once per engine; the document passed the engine's structural checks, so
 * node and entry ids are unique.
 */
class TreeIndex(tree: TalentTree) {

    val tree: TalentTree = tree
    val nodes: Map<Int, TalentNode> = tree.nodes.associateBy { it.id }
    val entries: Map<Int, TalentEntry> =
        tree.nodes.flatMap { it.entries }.associateBy { it.id }
    val entryNode: Map<Int, Int> =
        buildMap { tree.nodes.forEach { node -> node.entries.forEach { put(it.id, node.id) } } }

    /** Node -> RequiredForAvailability sources (sorted; duplicates preserved). */
    val requiredSources: Map<Int, List<Int>> =
        tree.nodes.associate { node ->
            node.id to node.prerequisites
                .filter { it.kind == PrerequisiteKind.REQUIRED }
                .map { it.nodeId }
                .sorted()
        }

    /** Node -> SufficientForAvailability sources (sorted; duplicates preserved). */
    val sufficientSources: Map<Int, List<Int>> =
        tree.nodes.associate { node ->
            node.id to node.prerequisites
                .filter { it.kind == PrerequisiteKind.SUFFICIENT }
                .map { it.nodeId }
                .sorted()
        }

    /** Node -> fail-closed issues (dataset-only; never depend on a build state). */
    val staticIssues: Map<Int, List<AllocationIssue>> =
        tree.nodes.associate { node ->
            node.id to staticIssues(node, requiredSources.getValue(node.id), sufficientSources.getValue(node.id))
        }

    /**
     * Fail-closed issues that make a node never purchasable by this engine,
     * whatever the build. They depend only on the dataset, not on the build
     * state (docs/talent-allocation-semantics.md §10).
     */
    private fun staticIssues(
        node: TalentNode,
        required: List<Int>,
        sufficient: List<Int>,
    ): List<AllocationIssue> {
        val issues = mutableListOf<AllocationIssue>()
        if (node.type !is TalentNodeType.Single) {
            issues += AllocationIssue(
                code = AllocationIssueCode.UNSUPPORTED_NODE_TYPE,
                message = "node ${node.id} (${node.name}) has type '${node.type.wireName}'; only " +
                    "${AllocationRules.SUPPORTED_NODE_TYPES.sorted()} nodes have proven allocation rules",
                nodeId = node.id,
            )
        } else if (node.entries.size != 1) {
            issues += AllocationIssue(
                code = AllocationIssueCode.UNSUPPORTED_NODE_TYPE,
                message = "node ${node.id} (${node.name}) is 'single' but has ${node.entries.size} entries",
                nodeId = node.id,
                actual = node.entries.size,
            )
        }
        for (entry in node.entries) {
            if (entry.entryType !in AllocationRules.SUPPORTED_ENTRY_TYPES) {
                issues += AllocationIssue(
                    code = AllocationIssueCode.UNSUPPORTED_ENTRY_TYPE,
                    message = "entry ${entry.id} of node ${node.id} has NodeEntryType " +
                        "${entry.entryType}; supported: ${AllocationRules.SUPPORTED_ENTRY_TYPES.sorted()}",
                    nodeId = node.id,
                    entryId = entry.id,
                    actual = entry.entryType,
                )
            }
        }
        if (node.flags and AllocationRules.NODE_FLAG_NEVER_PURCHASABLE != 0) {
            issues += AllocationIssue(
                code = AllocationIssueCode.NODE_NEVER_PURCHASABLE,
                message = "node ${node.id} (${node.name}) is flagged NeverPurchasable",
                nodeId = node.id,
                actual = node.flags,
            )
        }
        val unknownFlags = node.flags and AllocationRules.KNOWN_NODE_FLAGS.inv()
        if (unknownFlags != 0) {
            issues += AllocationIssue(
                code = AllocationIssueCode.UNSUPPORTED_NODE_FLAGS,
                message = "node ${node.id} (${node.name}) has TraitNode.Flags bits " +
                    "0x${unknownFlags.toString(16)} whose allocation semantics are not proven",
                nodeId = node.id,
                actual = node.flags,
            )
        }
        if (required.isNotEmpty() && sufficient.isNotEmpty()) {
            issues += AllocationIssue(
                code = AllocationIssueCode.UNSUPPORTED_EDGE_COMBINATION,
                message = "node ${node.id} (${node.name}) mixes RequiredForAvailability $required " +
                    "and SufficientForAvailability $sufficient prerequisites; how they combine is not proven",
                nodeId = node.id,
                relatedNodeIds = (required + sufficient).sorted(),
            )
        }
        for (conditionId in node.unresolvedConditionIds) {
            issues += AllocationIssue(
                code = AllocationIssueCode.UNRESOLVED_CONDITION,
                message = "cannot verify availability of node ${node.id} (${node.name}): " +
                    "TraitCond $conditionId is unresolved",
                nodeId = node.id,
                conditionId = conditionId,
            )
        }
        return issues
    }
}

/** Wire form of a node type (used in messages; matches `TalentNodeTypeSerializer`). */
private val TalentNodeType.wireName: String
    get() = when (this) {
        TalentNodeType.Single -> "single"
        TalentNodeType.Tiered -> "tiered"
        TalentNodeType.Selection -> "selection"
        is TalentNodeType.Unknown -> rawValue
    }
