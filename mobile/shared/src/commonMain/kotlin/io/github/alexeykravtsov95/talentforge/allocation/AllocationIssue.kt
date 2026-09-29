package io.github.alexeykravtsov95.talentforge.allocation

/**
 * Stable, machine-readable reason codes of the allocation engine
 * (docs/talent-allocation-semantics.md §11).
 *
 * **Declaration order is the canonical sort order of issues** — it intentionally
 * mirrors the Python reference `AllocationIssueCode` declaration order and must
 * not be changed: the conformance suite compares issues in that order, and
 * `name` is the wire value the suite compares. Messages are never part of the
 * contract.
 */
enum class AllocationIssueCode {
    // --- identity / structure of the request or the build state
    UNKNOWN_TREE,
    UNKNOWN_NODE,
    INVALID_ENTRY,
    ENTRY_NOT_SELECTED,
    DUPLICATE_ALLOCATION,
    RANK_OUT_OF_BOUNDS,
    INVALID_BUILD_STATE,

    // --- fail-closed: mechanics the engine cannot prove
    UNSUPPORTED_NODE_TYPE,
    UNSUPPORTED_ENTRY_TYPE,
    UNSUPPORTED_NODE_FLAGS,
    UNSUPPORTED_EDGE_COMBINATION,
    UNRESOLVED_CONDITION,
    NODE_NEVER_PURCHASABLE,

    // --- ranks and points
    MAX_RANK_REACHED,
    NOT_ALLOCATED,
    NO_POINTS_REMAINING,
    POINT_BUDGET_EXCEEDED,

    // --- availability
    TIER_GATE_NOT_MET,
    REQUIRED_PREREQUISITE_NOT_MET,
    SUFFICIENT_PREREQUISITE_NOT_MET,

    // --- refund
    DEPENDENT_NODE_WOULD_BREAK,
}

/**
 * One reason why an action is not allowed / a build is not valid.
 *
 * [nodeId] is the node the issue is *about* (for `DEPENDENT_NODE_WOULD_BREAK`:
 * the dependent that would break). [relatedNodeIds] are the other nodes involved
 * (prerequisite sources, the node being refunded, …). [required]/[actual] carry
 * numbers (points, ranks, flags) where the code has them. [cause] is set on
 * `DEPENDENT_NODE_WOULD_BREAK`: the availability rule the dependent would fail.
 *
 * [message] is a human diagnostic only — automation branches on [code] and the
 * structured fields, never on the message.
 */
data class AllocationIssue(
    val code: AllocationIssueCode,
    val message: String,
    val nodeId: Int? = null,
    val entryId: Int? = null,
    val relatedNodeIds: List<Int> = emptyList(),
    val conditionId: Int? = null,
    val required: Int? = null,
    val actual: Int? = null,
    val cause: AllocationIssueCode? = null,
) {
    /** Canonical comparison key (docs/talent-allocation-semantics.md §13.6). */
    internal fun sortKey(): IssueSortKey =
        IssueSortKey(
            codeOrder = code.ordinal,
            nodeId = nodeId ?: -1,
            entryId = entryId ?: -1,
            relatedNodeIds = relatedNodeIds,
            conditionId = conditionId ?: -1,
            causeOrder = cause?.ordinal ?: -1,
            message = message,
        )

    override fun toString(): String = "[$code] $message"
}

/**
 * Sort key of an issue: code declaration order, then nodeId, entryId,
 * relatedNodeIds (lexicographic), conditionId, cause code order — with the
 * message as the final tie-breaker, exactly like the Python reference
 * (`(sort_key(), message)` pairs are de-duplicated and sorted).
 */
internal data class IssueSortKey(
    val codeOrder: Int,
    val nodeId: Int,
    val entryId: Int,
    val relatedNodeIds: List<Int>,
    val conditionId: Int,
    val causeOrder: Int,
    val message: String,
) : Comparable<IssueSortKey> {
    override fun compareTo(other: IssueSortKey): Int {
        codeOrder.compareTo(other.codeOrder).let { if (it != 0) return it }
        nodeId.compareTo(other.nodeId).let { if (it != 0) return it }
        entryId.compareTo(other.entryId).let { if (it != 0) return it }
        compareRelatedNodeIds(other).let { if (it != 0) return it }
        conditionId.compareTo(other.conditionId).let { if (it != 0) return it }
        causeOrder.compareTo(other.causeOrder).let { if (it != 0) return it }
        return message.compareTo(other.message)
    }

    private fun compareRelatedNodeIds(other: IssueSortKey): Int {
        val shared = minOf(relatedNodeIds.size, other.relatedNodeIds.size)
        for (index in 0 until shared) {
            val ordering = relatedNodeIds[index].compareTo(other.relatedNodeIds[index])
            if (ordering != 0) return ordering
        }
        return relatedNodeIds.size - other.relatedNodeIds.size
    }
}

/**
 * Canonical, deterministic order (and de-duplication) of issues: issues with an
 * identical sort key *and* message collapse into one, the rest order by the key.
 */
fun sortIssues(issues: Iterable<AllocationIssue>): List<AllocationIssue> {
    val unique = LinkedHashMap<IssueSortKey, AllocationIssue>()
    for (issue in issues) {
        unique[issue.sortKey()] = issue
    }
    return unique.keys.sorted().map(unique::getValue)
}

/**
 * Sorted, de-duplicated code values — the compact form used by conformance
 * vectors (plain alphabetical order of the wire names, not declaration order).
 */
fun reasonCodes(issues: Iterable<AllocationIssue>): List<String> =
    issues.map { it.code.name }.distinct().sorted()
