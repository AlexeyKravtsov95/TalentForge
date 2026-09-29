package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.talentdata.PrerequisiteKind
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentEntry
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNode
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree

/** The engine cannot be constructed for this document/configuration. */
class AllocationEngineException(message: String) : Exception(message)

/**
 * The headless Kotlin port of the Python reference `TalentAllocationEngine`
 * (docs/talent-allocation-semantics.md): allocation rules for one tree of a
 * normalized `talent-data.v1` document.
 *
 * It answers, for one talent tree:
 *  - can a rank be purchased / refunded, and exactly why not (typed issues);
 *  - is an arbitrary build valid, independently of how it was created;
 *  - how many points are spent / remaining.
 *
 * Pure and deterministic: no hidden mutation, no clock, no randomness, no I/O.
 * Validity is *reachability* (VALID-1): a plan is valid iff it can be obtained
 * from an empty tree by purchasing one rank at a time, each purchase allowed by
 * the rules, within the point budget.
 *
 * The M2 rules EDGE-ACTIVE and EDGE-SUF are implemented as the documented
 * **provisional reference hypothesis** — passing the conformance suite proves
 * parity with the Python reference, not agreement with the live client.
 *
 * No level-derived point limits are calculated here: the runtime budget of a
 * character (e.g. level-20 `maxQuantity = 11`) is caller-supplied [pointLimit]
 * territory.
 */
class TalentAllocationEngine(
    val document: TalentDataDocument,
    treeId: Int? = null,
    pointLimit: Int? = null,
) {

    val tree: TalentTree

    /** Usable points: the dataset budget, or the configured lower [pointLimit]. */
    val pointBudget: Int

    private val index: TreeIndex

    init {
        val errorCodes = structuralErrorCodes(document)
        if (errorCodes.isNotEmpty()) {
            throw AllocationEngineException(
                "document ${document.classInfo.token} ${document.clientBuild} has validation " +
                    "errors $errorCodes; refusing to allocate on an invalid dataset",
            )
        }
        tree = selectTree(document, treeId)
        val budget = tree.pointBudget
            ?: throw AllocationEngineException(
                "tree ${tree.id} has no point budget (TraitTreeXTraitCurrency); " +
                    "points cannot be counted - fail closed",
            )
        val maxPoints = budget.maxPoints
        if (pointLimit != null && pointLimit !in 0..maxPoints) {
            throw AllocationEngineException(
                "point_limit must be within 0..$maxPoints (tree ${tree.id} budget), got $pointLimit",
            )
        }
        pointBudget = pointLimit ?: maxPoints
        index = TreeIndex(tree)
    }

    // ------------------------------------------------------------------ state / inventory

    fun emptyState(): BuildState = BuildState.empty(tree.id)

    /** Nodes this engine refuses to allocate, whatever the build (fail-closed report). */
    fun unsupportedNodes(): Map<Int, List<AllocationIssue>> =
        index.staticIssues.filterValues { it.isNotEmpty() }

    // ------------------------------------------------------------------ points

    /** Points spent by the state's allocations (never negative). */
    fun spentPoints(state: BuildState): Int =
        maxOf(0, state.allocations.asSequence().filter { it.rank > 0 }.sumOf { it.rank } *
            AllocationRules.RANK_COST_POINTS)

    /** Points still available (never negative, 0 for an over-budget state). */
    fun remainingPoints(state: BuildState): Int = maxOf(0, pointBudget - spentPoints(state))

    // ------------------------------------------------------------------ validation

    /**
     * Check an arbitrary build (imported, saved, migrated or corrupt),
     * independently of how it was created.
     *
     * Sequence (§9): tree id; per allocation unknown node, duplicate, entry
     * ownership, rank bounds; budget; static fail-closed issues of allocated
     * nodes; purchase closure; explanation of blocked target ranks at the
     * fixpoint. This is *not* a final-state-only check — a plan that satisfies
     * every rule in its final state but cannot be purchased rank by rank
     * (self-supporting cycles, ordering gaps) is invalid.
     */
    fun validate(state: BuildState): BuildValidation {
        val issues = mutableListOf<AllocationIssue>()
        if (state.treeId != tree.id) {
            issues += AllocationIssue(
                code = AllocationIssueCode.UNKNOWN_TREE,
                message = "build is for tree ${state.treeId}, engine allocates tree ${tree.id}",
                actual = state.treeId,
                required = tree.id,
            )
            return validation(state, issues, emptyMap())
        }

        val target = mutableMapOf<Int, Int>()
        val seen = mutableSetOf<Int>()
        for (allocation in state.allocations) {
            val node = index.nodes[allocation.nodeId]
            if (node == null) {
                issues += AllocationIssue(
                    code = AllocationIssueCode.UNKNOWN_NODE,
                    message = "node ${allocation.nodeId} does not exist in tree ${tree.id}",
                    nodeId = allocation.nodeId,
                )
                continue
            }
            if (allocation.nodeId in seen) {
                issues += AllocationIssue(
                    code = AllocationIssueCode.DUPLICATE_ALLOCATION,
                    message = "node ${node.id} (${node.name}) is allocated more than once",
                    nodeId = node.id,
                )
                continue
            }
            seen += allocation.nodeId
            val entry = entryOf(node, allocation.entryId) ?: run {
                issues += invalidEntry(node, allocation.entryId)
                continue
            }
            if (allocation.rank !in 1..entry.maxRank) {
                issues += AllocationIssue(
                    code = AllocationIssueCode.RANK_OUT_OF_BOUNDS,
                    message = "node ${node.id} (${node.name}) has rank ${allocation.rank}, " +
                        "allowed 1..${entry.maxRank}",
                    nodeId = node.id,
                    entryId = entry.id,
                    required = entry.maxRank,
                    actual = allocation.rank,
                )
                continue
            }
            target[node.id] = allocation.rank
        }

        val spent = AllocationRules.pointsSpent(target)
        if (spent > pointBudget) {
            issues += AllocationIssue(
                code = AllocationIssueCode.POINT_BUDGET_EXCEEDED,
                message = "build spends $spent points, budget is $pointBudget",
                required = pointBudget,
                actual = spent,
            )
        }
        for (nodeId in target.keys.sorted()) {
            issues += index.staticIssues.getValue(nodeId)
        }

        val maxRanks = target.keys.associateWith { index.nodes.getValue(it).maxRank }
        val reached = AllocationRules.reachableRanks(index, target, maxRanks)
        for (nodeId in target.keys.sorted()) {
            if ((reached[nodeId] ?: 0) >= target.getValue(nodeId) ||
                index.staticIssues.getValue(nodeId).isNotEmpty()
            ) {
                continue
            }
            // Blocked at the fixpoint of the purchase closure: explain with the
            // rules that fail there (this is what exposes self-supporting cycles
            // and ordering gaps).
            issues += AllocationRules.availabilityIssues(index, reached, index.nodes.getValue(nodeId))
        }
        return validation(state, issues, reached)
    }

    private fun validation(
        state: BuildState,
        issues: List<AllocationIssue>,
        reached: Map<Int, Int>,
    ): BuildValidation = BuildValidation(
        issues = sortIssues(issues),
        spentPoints = spentPoints(state),
        remainingPoints = remainingPoints(state),
        pointBudget = pointBudget,
        reachedRanks = reached.entries.sortedBy { it.key }.associate { it.key to it.value },
    )

    // ------------------------------------------------------------------ spend

    /** May one more rank of [nodeId] (entry [entryId]) be purchased? */
    fun canSpend(state: BuildState, nodeId: Int, entryId: Int? = null): AllocationDecision =
        AllocationDecision.of(spendIssues(state, nodeId, entryId).first)

    /**
     * Purchase one rank. Not allowed -> the input state is returned unchanged.
     * The reference ordering and issue collection is preserved: static issues,
     * max rank, points, gates and edges are *all* collected, never
     * short-circuited.
     */
    fun spend(state: BuildState, nodeId: Int, entryId: Int? = null): AllocationResult {
        val (issues, entry) = spendIssues(state, nodeId, entryId)
        val decision = AllocationDecision.of(issues)
        if (!decision.allowed || entry == null) {
            return AllocationResult(decision, state)
        }
        val newState = state.withAllocation(nodeId, entry.id, state.rankOf(nodeId) + 1)
        return AllocationResult(decision, newState)
    }

    private fun spendIssues(
        state: BuildState,
        nodeId: Int,
        entryId: Int?,
    ): Pair<List<AllocationIssue>, TalentEntry?> {
        requireValid(state)?.let { return it to null }
        val node = index.nodes[nodeId]
            ?: return listOf(unknownNode(nodeId)) to null

        val current = state.allocationFor(nodeId)
        val resolvedEntryId: Int
        if (entryId == null) {
            if (current != null) {
                resolvedEntryId = current.entryId
            } else if (node.entries.size == 1) {
                resolvedEntryId = node.entries[0].id
            } else {
                return listOf(
                    AllocationIssue(
                        code = AllocationIssueCode.ENTRY_NOT_SELECTED,
                        message = "node ${node.id} (${node.name}) has ${node.entries.size} " +
                            "entries; an entry must be chosen",
                        nodeId = node.id,
                    ),
                ) to null
            }
        } else {
            resolvedEntryId = entryId
        }
        val entry = entryOf(node, resolvedEntryId)
            ?: return listOf(invalidEntry(node, resolvedEntryId)) to null
        // A valid state only holds single-entry nodes (multi-entry/selection
        // nodes fail closed), so a purchased node's entry is the only one:
        // switching entries (selection swaps) is not reachable and deliberately
        // not modelled.

        val issues = index.staticIssues.getValue(node.id).toMutableList()
        val rank = current?.rank ?: 0
        if (rank >= entry.maxRank) {
            issues += AllocationIssue(
                code = AllocationIssueCode.MAX_RANK_REACHED,
                message = "node ${node.id} (${node.name}) is already $rank/${entry.maxRank}",
                nodeId = node.id,
                entryId = entry.id,
                required = entry.maxRank,
                actual = rank,
            )
        }
        val spent = spentPoints(state)
        if (spent + AllocationRules.RANK_COST_POINTS > pointBudget) {
            issues += AllocationIssue(
                code = AllocationIssueCode.NO_POINTS_REMAINING,
                message = "$spent/$pointBudget points spent, a rank costs " +
                    "${AllocationRules.RANK_COST_POINTS}",
                nodeId = node.id,
                required = AllocationRules.RANK_COST_POINTS,
                actual = pointBudget - spent,
            )
        }
        issues += AllocationRules.availabilityIssues(index, state.ranks(), node)
        return issues to entry
    }

    // ------------------------------------------------------------------ refund

    /** May one rank of [nodeId] be removed from the plan? */
    fun canRefund(state: BuildState, nodeId: Int): AllocationDecision =
        AllocationDecision.of(refundIssues(state, nodeId).first)

    /**
     * Remove one rank. Not allowed -> the input state is returned unchanged.
     *
     * Rule REFUND-1: this is *planner-plan editing*, not in-game committed-rank
     * refund behaviour — removing a rank is allowed iff the resulting plan is
     * still valid (VALID-1). No cascade: dependents are never removed
     * implicitly; each rule the remaining plan would break is reported as one
     * `DEPENDENT_NODE_WOULD_BREAK` (with the failing rule as [AllocationIssue.cause]).
     */
    fun refund(state: BuildState, nodeId: Int): AllocationResult {
        val (issues, candidate) = refundIssues(state, nodeId)
        val decision = AllocationDecision.of(issues)
        if (!decision.allowed || candidate == null) {
            return AllocationResult(decision, state)
        }
        return AllocationResult(decision, candidate)
    }

    private fun refundIssues(state: BuildState, nodeId: Int): Pair<List<AllocationIssue>, BuildState?> {
        requireValid(state)?.let { return it to null }
        val node = index.nodes[nodeId]
            ?: return listOf(unknownNode(nodeId)) to null
        val current = state.allocationFor(nodeId)
            ?: return listOf(
                AllocationIssue(
                    code = AllocationIssueCode.NOT_ALLOCATED,
                    message = "node ${node.id} (${node.name}) has no purchased rank to refund",
                    nodeId = node.id,
                    actual = 0,
                ),
            ) to null
        val candidate = state.withAllocation(nodeId, current.entryId, current.rank - 1)
        val after = validate(candidate)
        val issues = after.issues.map { broken ->
            AllocationIssue(
                code = AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK,
                message = "refunding ${node.name} (${node.id}) would invalidate node " +
                    "${broken.nodeId}: ${broken.message}",
                nodeId = broken.nodeId,
                relatedNodeIds = listOf(node.id),
                required = broken.required,
                actual = broken.actual,
                cause = broken.code,
            )
        }
        return issues to candidate
    }

    // ------------------------------------------------------------------ helpers

    private fun requireValid(state: BuildState): List<AllocationIssue>? {
        val validation = validate(state)
        if (validation.valid) {
            return null
        }
        return listOf(
            AllocationIssue(
                code = AllocationIssueCode.INVALID_BUILD_STATE,
                message = "the current build is not valid " +
                    "(${validation.reasonCodes.joinToString()}); validate() explains why",
            ),
        )
    }

    private fun selectTree(document: TalentDataDocument, treeId: Int?): TalentTree =
        if (treeId == null) {
            if (document.trees.size != 1) {
                throw AllocationEngineException(
                    "document has ${document.trees.size} trees; pass treeId explicitly",
                )
            }
            document.trees[0]
        } else {
            document.trees.firstOrNull { it.id == treeId }
                ?: throw AllocationEngineException("document has no tree $treeId")
        }

    private fun entryOf(node: TalentNode, entryId: Int): TalentEntry? =
        node.entries.firstOrNull { it.id == entryId }

    private fun unknownNode(nodeId: Int): AllocationIssue = AllocationIssue(
        code = AllocationIssueCode.UNKNOWN_NODE,
        message = "node $nodeId does not exist in tree ${tree.id}",
        nodeId = nodeId,
    )

    private fun invalidEntry(node: TalentNode, entryId: Int): AllocationIssue = AllocationIssue(
        code = AllocationIssueCode.INVALID_ENTRY,
        message = "entry $entryId does not belong to node ${node.id} (${node.name}); entries: " +
            node.entries.joinToString(", ") { it.id.toString() },
        nodeId = node.id,
        entryId = entryId,
    )
}

/**
 * Allocation-relevant structural validation of a document, ported from the
 * Python reference constructor's `validate_document` gate: the engine refuses to
 * allocate on a structurally invalid dataset.
 *
 * Deliberate scope: the Python validator additionally checks layout grid
 * consistency, section partitions, position sharing, spell/name data quality and
 * the canonical content hash. Those are *pipeline* concerns — this engine
 * consumes only the allocation-relevant structure, and the canonical hash
 * algorithm is not part of the consumer contract (published hashes are verified
 * against the manifest by `BundledTalentDatasetRepository`, not recomputed
 * here). Error codes keep the Python names.
 */
private fun structuralErrorCodes(document: TalentDataDocument): List<String> {
    val codes = mutableListOf<String>()
    if (document.trees.isEmpty()) {
        return listOf("NO_TREES")
    }
    codes += duplicateCodes(document.trees.map { it.id }, "DUPLICATE_TREE_ID")
    val nodes = document.trees.flatMap { it.nodes }
    codes += duplicateCodes(nodes.map { it.id }, "DUPLICATE_NODE_ID")
    codes += duplicateCodes(nodes.flatMap { it.entries }.map { it.id }, "DUPLICATE_ENTRY_ID")

    for (tree in document.trees) {
        if (tree.nodes.isEmpty()) {
            codes += "TREE_HAS_NO_NODES"
            continue
        }
        val nodeIds = tree.nodes.mapTo(mutableSetOf()) { it.id }
        for (node in tree.nodes) {
            if (node.entries.isEmpty()) codes += "NODE_WITHOUT_ENTRIES"
            if (node.maxRank <= 0) codes += "INVALID_MAX_RANK"
            for (prerequisite in node.prerequisites) {
                if (prerequisite.nodeId !in nodeIds) codes += "DANGLING_PREREQUISITE"
                if (prerequisite.nodeId == node.id) codes += "SELF_PREREQUISITE"
            }
            for (requirement in node.unlockRequirements) {
                if (requirement.countedNodeIds.any { it !in nodeIds }) {
                    codes += "UNLOCK_COUNTS_UNKNOWN_NODES"
                }
                if (requirement.requiredPoints <= 0) codes += "INVALID_UNLOCK_POINTS"
            }
            for (entry in node.entries) {
                if (entry.maxRank <= 0) codes += "INVALID_ENTRY_MAX_RANK"
            }
        }
        for (edge in tree.visualOnlyEdges) {
            if (edge.fromNodeId !in nodeIds || edge.toNodeId !in nodeIds) {
                codes += "DANGLING_VISUAL_EDGE"
            }
        }
        if (containsRequiredPrerequisiteCycle(tree)) {
            codes += "REQUIRED_PREREQUISITE_CYCLE"
        }
    }
    return codes.distinct().sorted()
}

private fun duplicateCodes(ids: List<Int>, code: String): List<String> =
    if (ids.groupingBy { it }.eachCount().any { it.value > 1 }) listOf(code) else emptyList()

/**
 * A cycle made exclusively of Sufficient edges is not a deadlock (observed in
 * real Forever data — Hunter's Bestial Wrath <-> Intimidation) and stays
 * allowed; a cycle through at least one Required edge deadlocks and fails
 * closed, exactly like the reference validator.
 */
private fun containsRequiredPrerequisiteCycle(tree: TalentTree): Boolean {
    val graph = tree.nodes.associate { node ->
        node.id to node.prerequisites.map { it.nodeId }
    }
    val white = 0
    val grey = 1
    val black = 2
    val color = tree.nodes.associate { it.id to white }.toMutableMap()
    val stack = mutableListOf<Int>()

    fun visit(node: Int): List<Int>? {
        color[node] = grey
        stack += node
        for (next in graph.getValue(node)) {
            if (color[next] == grey) {
                return stack.subList(stack.indexOf(next), stack.size) + next
            }
            if (color[next] == white) {
                visit(next)?.let { return it }
            }
        }
        stack.removeAt(stack.size - 1)
        color[node] = black
        return null
    }

    val cycle: List<Int> = graph.keys.sorted().firstNotNullOfOrNull { node ->
        if (color[node] == white) visit(node) else null
    } ?: return false

    val requiredEdges = buildSet {
        for (node in tree.nodes) {
            for (prerequisite in node.prerequisites) {
                if (prerequisite.kind == PrerequisiteKind.REQUIRED) {
                    add(node.id to prerequisite.nodeId)
                }
            }
        }
    }
    return cycle.zipWithNext().any { it in requiredEdges }
}
