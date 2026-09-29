package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import io.github.alexeykravtsov95.talentforge.data.talentdata.ClassInfo
import io.github.alexeykravtsov95.talentforge.data.talentdata.PointBudget
import io.github.alexeykravtsov95.talentforge.data.talentdata.Prerequisite
import io.github.alexeykravtsov95.talentforge.data.talentdata.PrerequisiteKind
import io.github.alexeykravtsov95.talentforge.data.talentdata.Provenance
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentEntry
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentLayout
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNode
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNodeType
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentPosition
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree
import io.github.alexeykravtsov95.talentforge.data.talentdata.UnlockRequirement

/**
 * Synthetic talent documents for the engine tests. Mechanics absent from the
 * committed Forever data (multi-entry nodes, tiered/selection types, mixed edge
 * kinds, unknown flags, required cycles) are only representable synthetically —
 * exactly like the synthetic conformance scenarios.
 */
internal object SyntheticTrees {

    fun node(
        id: Int,
        maxRank: Int = 1,
        type: TalentNodeType = TalentNodeType.Single,
        flags: Int = 0,
        prerequisites: List<Prerequisite> = emptyList(),
        unlockRequirements: List<UnlockRequirement> = emptyList(),
        unresolvedConditionIds: List<Int> = emptyList(),
        entries: List<TalentEntry>? = null,
    ): TalentNode = TalentNode(
        id = id,
        sectionIndex = null,
        position = TalentPosition(x = 0, y = 0, row = 0, column = 0),
        type = type,
        flags = flags,
        maxRank = maxRank,
        spellId = id + 90000,
        name = "node-$id",
        prerequisites = prerequisites,
        unlockRequirements = unlockRequirements,
        unresolvedConditionIds = unresolvedConditionIds,
        entries = entries ?: listOf(entry(id, maxRank)),
    )

    fun entry(nodeId: Int, maxRank: Int, entryId: Int = nodeId + 1000, entryType: Int = 1): TalentEntry =
        TalentEntry(
            id = entryId,
            definitionId = entryId,
            spellId = nodeId + 90000,
            name = "node-$nodeId",
            maxRank = maxRank,
            entryType = entryType,
            descriptionTemplate = null,
            effects = emptyList(),
        )

    fun document(
        nodes: List<TalentNode>,
        treeId: Int = 1,
        maxPoints: Int = 51,
        trees: List<TalentTree>? = null,
    ): TalentDataDocument = TalentDataDocument(
        schemaVersion = 1,
        game = GameId("forever"),
        clientBuild = "1.60.1.70009",
        classInfo = ClassInfo(id = 1, token = ClassToken("WARRIOR"), name = "Warrior"),
        trees = trees ?: listOf(
            TalentTree(
                id = treeId,
                pointBudget = PointBudget(currencyId = 3820, maxPoints = maxPoints),
                layout = TalentLayout(xStep = 10, yStep = 10),
                sections = emptyList(),
                nodes = nodes,
                visualOnlyEdges = emptyList(),
            ),
        ),
        contentHash = ContentHash("sha256:synthetic"),
        generatedAt = "2026-01-01T00:00:00Z",
        provenance = Provenance(source = "synthetic", tables = emptyList()),
    )

    fun required(nodeId: Int): Prerequisite = Prerequisite(nodeId, PrerequisiteKind.REQUIRED)

    fun sufficient(nodeId: Int): Prerequisite = Prerequisite(nodeId, PrerequisiteKind.SUFFICIENT)

    fun gate(requiredPoints: Int, countedNodeIds: List<Int>): UnlockRequirement =
        UnlockRequirement(requiredPoints = requiredPoints, countedNodeIds = countedNodeIds)

    /**
     * The default test tree: two free roots, a 5-point-gated node counting the
     * roots, a sufficient-dependent of the gated node and a required-dependent
     * of root A.
     */
    fun defaultTree(): List<TalentNode> = listOf(
        node(1, maxRank = 3),
        node(2, maxRank = 3),
        node(3, maxRank = 2, unlockRequirements = listOf(gate(5, listOf(1, 2)))),
        node(4, maxRank = 1, prerequisites = listOf(sufficient(3))),
        node(5, maxRank = 1, prerequisites = listOf(required(1))),
    )

    /** (nodeId, entryId, rank) triple helper. */
    fun allocation(nodeId: Int, rank: Int, entryId: Int = nodeId + 1000): NodeAllocation =
        NodeAllocation(nodeId = nodeId, entryId = entryId, rank = rank)
}
