package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import io.github.alexeykravtsov95.talentforge.data.ota.ClientBuildVersion
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNode

/** Availability is based on the active product, including every numeric version component. */
fun BuildFile.canMigrateTo(product: DatasetBundleId): Boolean {
    if (game != product.game) return false
    val source = ClientBuildVersion.parseOrNull(clientBuild) ?: return false
    val target = ClientBuildVersion.parseOrNull(product.clientBuild) ?: return false
    return source < target
}

sealed interface BuildMigrationResult {
    /** Only the migration planner can issue a validated copy. */
    class Ready internal constructor(
        val source: BuildFile,
        val build: BuildFile,
        val targetContentHash: ContentHash,
        val transferredPoints: Int,
        val remainingPoints: Int,
        val losses: List<MigrationLoss>,
    ) : BuildMigrationResult {
        val lostPoints: Int get() = losses.sumOf { it.source.rank - it.transferredRank }
    }

    data object IneligibleVersion : BuildMigrationResult
    data object IncompatibleClass : BuildMigrationResult
    data object UnmatchedTree : BuildMigrationResult
    data class DatasetUnavailable(val side: Side, val error: DatasetLoadError) : BuildMigrationResult
    data class InvalidDataset(val side: Side, val detail: String) : BuildMigrationResult
    data class InvalidSource(val issues: List<AllocationIssue>) : BuildMigrationResult
    data class InvalidTarget(val issues: List<AllocationIssue>) : BuildMigrationResult

    enum class Side { SOURCE, TARGET }
}

data class MigrationLoss(
    val source: NodeAllocation,
    val name: String?,
    val transferredRank: Int,
    val reasons: List<MigrationLossReason>,
)

enum class MigrationLossReason {
    TALENT_REMOVED,
    ENTRY_CHANGED,
    RANK_REDUCED,
    PREREQUISITE,
    TIER_GATE,
    POINT_BUDGET,
    UNSUPPORTED_RULE,
}

/** Read-only. The caller provides a repository over one consistent set of dataset sources. */
suspend fun previewBuildMigration(
    build: BuildFile,
    product: DatasetBundleId,
    datasets: TalentDatasetRepository,
): BuildMigrationResult {
    if (!build.canMigrateTo(product)) return BuildMigrationResult.IneligibleVersion
    val sourceId = DatasetId(build.game, build.clientBuild, build.classToken)
    val targetId = sourceId.copy(clientBuild = product.clientBuild)
    val source = when (val outcome = datasets.document(sourceId)) {
        is DatasetOutcome.Loaded -> outcome.value
        is DatasetOutcome.Failed ->
            return BuildMigrationResult.DatasetUnavailable(BuildMigrationResult.Side.SOURCE, outcome.error)
    }
    val target = when (val outcome = datasets.document(targetId)) {
        is DatasetOutcome.Loaded -> outcome.value
        is DatasetOutcome.Failed ->
            return BuildMigrationResult.DatasetUnavailable(BuildMigrationResult.Side.TARGET, outcome.error)
    }
    if (source.game != sourceId.game || source.clientBuild != sourceId.clientBuild ||
        source.classInfo.token != sourceId.classToken || target.game != targetId.game ||
        target.clientBuild != targetId.clientBuild || target.classInfo.token != targetId.classToken ||
        source.classInfo.id != target.classInfo.id
    ) {
        return BuildMigrationResult.IncompatibleClass
    }
    return migrate(build, source, target)
}

private fun migrate(
    build: BuildFile,
    source: TalentDataDocument,
    target: TalentDataDocument,
): BuildMigrationResult {
    val sourceEngine = try {
        TalentAllocationEngine(source, build.treeId)
    } catch (e: AllocationEngineException) {
        return BuildMigrationResult.InvalidDataset(BuildMigrationResult.Side.SOURCE, e.message.orEmpty())
    }
    val sourceValidation = sourceEngine.validate(build.toBuildState())
    if (!sourceValidation.valid) return BuildMigrationResult.InvalidSource(sourceValidation.issues)

    // Forever publishes TraitTree IDs. A different or non-unique ID has no
    // recorded correspondence. Require corroborating talent identity and currency.
    if (target.trees.count { it.id == build.treeId } != 1) return BuildMigrationResult.UnmatchedTree
    val targetEngine = try {
        TalentAllocationEngine(target, build.treeId)
    } catch (e: AllocationEngineException) {
        return BuildMigrationResult.InvalidDataset(BuildMigrationResult.Side.TARGET, e.message.orEmpty())
    }
    val oldNodes = sourceEngine.tree.nodes.associateBy { it.id }
    val newNodes = targetEngine.tree.nodes.associateBy { it.id }
    if (sourceEngine.tree.pointBudget?.currencyId != targetEngine.tree.pointBudget?.currencyId ||
        oldNodes.values.none { old -> newNodes[old.id]?.let { old.hasSameTalent(it) } == true }
    ) {
        return BuildMigrationResult.UnmatchedTree
    }

    val allocations = build.allocations.sortedBy { it.nodeId }
    val rejected = mutableMapOf<Int, MigrationLossReason>()
    val candidates = allocations.mapNotNull { allocation ->
        val node = newNodes[allocation.nodeId]
        val old = oldNodes.getValue(allocation.nodeId)
        when {
            node == null -> {
                rejected[allocation.nodeId] = MigrationLossReason.TALENT_REMOVED
                null
            }
            !old.hasSameTalent(node) -> {
                rejected[allocation.nodeId] = MigrationLossReason.ENTRY_CHANGED
                null
            }
            else -> allocation.copy(rank = minOf(allocation.rank, node.entries.single().maxRank))
        }
    }
    var state = targetEngine.emptyState()
    // Replay only source ranks, ascending by node ID, filling each reachable
    // node before the next. Revisit blocked nodes when purchases unlock them.
    do {
        val before = state
        for (allocation in candidates) {
            while (state.rankOf(allocation.nodeId) < allocation.rank) {
                val attempt = targetEngine.spend(state, allocation.nodeId, allocation.entryId)
                if (!attempt.decision.allowed) break
                state = attempt.state
            }
        }
    } while (state != before && targetEngine.remainingPoints(state) > 0)

    val validation = targetEngine.validate(state)
    if (!validation.valid) return BuildMigrationResult.InvalidTarget(validation.issues)

    val limits = candidates.associate { it.nodeId to it.rank }
    val losses = allocations.mapNotNull { allocation ->
        val transferred = state.rankOf(allocation.nodeId)
        if (transferred == allocation.rank) return@mapNotNull null
        val reason = rejected[allocation.nodeId]
        val reasons = if (reason != null) {
            listOf(reason)
        } else {
            val limit = limits.getValue(allocation.nodeId)
            buildList {
                if (limit < allocation.rank) add(MigrationLossReason.RANK_REDUCED)
                if (transferred < limit) {
                    addAll(targetEngine.canSpend(state, allocation.nodeId, allocation.entryId).issues.map {
                        it.code.toMigrationLossReason()
                    }.distinct())
                }
            }
        }
        val old = oldNodes.getValue(allocation.nodeId)
        MigrationLoss(allocation, old.entries.single().name ?: old.name, transferred, reasons)
    }
    return BuildMigrationResult.Ready(
        source = build,
        build = buildFileOf(state, target.game, target.clientBuild, target.classInfo.token),
        targetContentHash = target.contentHash,
        transferredPoints = validation.spentPoints,
        remainingPoints = validation.remainingPoints,
        losses = losses,
    )
}

/** Names, coordinates, and effect values are display data. A recycled ID is insufficient. */
private fun TalentNode.hasSameTalent(other: TalentNode): Boolean {
    val oldEntry = entries.singleOrNull() ?: return false
    val newEntry = other.entries.singleOrNull() ?: return false
    return type == other.type && flags == other.flags && spellId == other.spellId &&
        oldEntry.id == newEntry.id && oldEntry.definitionId == newEntry.definitionId &&
        oldEntry.spellId == newEntry.spellId && oldEntry.entryType == newEntry.entryType
}

private fun AllocationIssueCode.toMigrationLossReason(): MigrationLossReason = when (this) {
    AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET, AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET ->
        MigrationLossReason.PREREQUISITE
    AllocationIssueCode.TIER_GATE_NOT_MET -> MigrationLossReason.TIER_GATE
    AllocationIssueCode.NO_POINTS_REMAINING -> MigrationLossReason.POINT_BUDGET
    else -> MigrationLossReason.UNSUPPORTED_RULE
}
