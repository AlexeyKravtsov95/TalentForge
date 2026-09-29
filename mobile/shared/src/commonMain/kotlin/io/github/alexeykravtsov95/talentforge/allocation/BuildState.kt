package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation

/**
 * An immutable talent plan for one tree (the Kotlin equivalent of the Python
 * reference `BuildState`).
 *
 * The container is deliberately lenient — duplicates, out-of-range ranks and
 * unknown ids are representable — so an imported or corrupt build can be
 * *reported* by [TalentAllocationEngine.validate] instead of being rejected
 * before anyone can explain what is wrong with it.
 *
 * Ownership and order: the state owns its allocation storage. The constructor
 * argument is snapshotted and canonicalized into a private backing list (node
 * id, entry id, rank) — exactly like the reference, whose pydantic validator
 * sorts every construction. [allocations] returns a fresh copy of that backing
 * on every access, so no collection reachable from outside the class — neither
 * the constructor argument nor anything obtained from [allocations] — can
 * mutate an existing state, not even through a runtime cast or platform
 * interop. There is no data-class `copy()`: [empty], [of] and [withAllocation]
 * are the transition API. [allocationFor]/[rankOf] resolve the *first*
 * allocation of a node and validation treats later duplicates as
 * `DUPLICATE_ALLOCATION`.
 */
class BuildState(
    val treeId: Int,
    allocations: List<NodeAllocation>,
) {

    /**
     * Canonical (node id, entry id, rank) snapshot of the constructor
     * argument. Private: `sortedWith` always returns a fresh list, so no
     * caller alias is retained, and the backing is never handed out.
     */
    private val backing: List<NodeAllocation> =
        allocations.sortedWith(ALLOCATIONS_CANONICAL_ORDER)

    /**
     * The allocations in canonical (node id, entry id, rank) order — a fresh
     * copy on every access, so mutating a returned collection cannot change
     * this state.
     */
    val allocations: List<NodeAllocation>
        get() = backing.toList()

    /** New state where [nodeId] holds [rank] ranks of [entryId] (rank 0 removes it). */
    fun withAllocation(nodeId: Int, entryId: Int, rank: Int): BuildState {
        val kept = backing.filterTo(mutableListOf()) { it.nodeId != nodeId }
        if (rank > 0) {
            kept += NodeAllocation(nodeId = nodeId, entryId = entryId, rank = rank)
        }
        return BuildState(treeId, kept)
    }

    /** The first allocation of [nodeId] (a valid state has at most one). */
    fun allocationFor(nodeId: Int): NodeAllocation? =
        backing.firstOrNull { it.nodeId == nodeId }

    fun rankOf(nodeId: Int): Int = allocationFor(nodeId)?.rank ?: 0

    /** Node id -> purchased rank; like the reference, a later duplicate wins. */
    fun ranks(): Map<Int, Int> = backing.associate { it.nodeId to it.rank }

    override fun equals(other: Any?): Boolean =
        other is BuildState && other.treeId == treeId && other.backing == backing

    override fun hashCode(): Int = 31 * treeId + backing.hashCode()

    override fun toString(): String =
        "BuildState(tree=$treeId, ${backing.joinToString { "${it.nodeId}:${it.rank}" }})"

    companion object {
        private val ALLOCATIONS_CANONICAL_ORDER =
            compareBy<NodeAllocation>({ it.nodeId }, { it.entryId }, { it.rank })

        fun empty(treeId: Int): BuildState = BuildState(treeId, emptyList())

        /** Builds a state from arbitrary allocations; the input is snapshotted and canonicalized. */
        fun of(treeId: Int, allocations: List<NodeAllocation>): BuildState =
            BuildState(treeId, allocations)
    }
}

/** Answer of [TalentAllocationEngine.canSpend]/[canRefund]: allowed iff there is no issue. */
data class AllocationDecision(
    val issues: List<AllocationIssue> = emptyList(),
) {
    val allowed: Boolean get() = issues.isEmpty()

    val reasonCodes: List<String> get() = reasonCodes(issues)

    companion object {
        fun of(issues: List<AllocationIssue>): AllocationDecision =
            AllocationDecision(sortIssues(issues))
    }
}

/**
 * Outcome of [TalentAllocationEngine.spend]/[refund]: the decision and the
 * resulting state. When the action is not allowed, [state] is the input state,
 * unchanged.
 */
data class AllocationResult(
    val decision: AllocationDecision,
    val state: BuildState,
) {
    val allowed: Boolean get() = decision.allowed
}

/** Outcome of [TalentAllocationEngine.validate]: a build is valid iff there is no issue. */
data class BuildValidation(
    val issues: List<AllocationIssue>,
    val spentPoints: Int,
    val remainingPoints: Int,
    val pointBudget: Int,
    /**
     * Node id -> ranks the purchase closure could reach for the validated
     * target (empty when the tree does not match). Computed at the fixpoint;
     * `required`/`actual` numbers in availability issues refer to it.
     */
    val reachedRanks: Map<Int, Int> = emptyMap(),
) {
    val valid: Boolean get() = issues.isEmpty()

    val reasonCodes: List<String> get() = reasonCodes(issues)
}
