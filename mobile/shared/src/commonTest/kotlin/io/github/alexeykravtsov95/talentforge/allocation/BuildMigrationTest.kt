package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.allocation
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.document
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.entry
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.gate
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.node
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.required
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BuildMigrationTest {
    private val nodes = listOf(node(1, maxRank = 3), node(2, maxRank = 2), node(3), node(4), node(5))
    private val source = document(nodes)
    private val original = BuildFile(
        game = source.game,
        clientBuild = source.clientBuild,
        classToken = source.classInfo.token,
        treeId = 1,
        allocations = listOf(allocation(3, 1), allocation(2, 2), allocation(1, 3)),
    )
    private val product = DatasetBundleId(source.game, "1.60.1.71037")

    @Test
    fun preservesAnUnderfilledBuildDespitePresentationAndEffectChanges() = runTest {
        val oldNode = nodes.first()
        val updated = oldNode.copy(
            name = "new display name",
            position = oldNode.position.copy(x = 50),
            entries = listOf(oldNode.entries.single().copy(name = "rename", descriptionTemplate = "New tuning")),
        )
        val target = target(listOf(updated) + nodes.drop(1))
        val plan = ready(target)
        assertEquals(original.allocations.sortedBy { it.nodeId }, plan.build.allocations)
        assertEquals(6, plan.transferredPoints)
        assertEquals(45, plan.remainingPoints)
        assertEquals(0, plan.lostPoints)
        assertEquals(product.clientBuild, plan.build.clientBuild)
    }

    @Test
    fun removedAndRepurposedTalentsNeverSpendOnReplacementEntries() = runTest {
        val target = target(listOf(
            node(2, maxRank = 2, entries = listOf(entry(2, 2, entryId = 9902))),
            nodes[2].copy(entries = listOf(entry(3, 1).copy(spellId = 7520))),
            nodes[3],
            nodes[4],
        ))
        val plan = ready(target)
        assertTrue(plan.build.allocations.isEmpty())
        assertEquals(6, plan.lostPoints)
        assertEquals(51, plan.remainingPoints)
        assertEquals(
            listOf(MigrationLossReason.TALENT_REMOVED, MigrationLossReason.ENTRY_CHANGED, MigrationLossReason.ENTRY_CHANGED),
            plan.losses.map { it.reasons.single() },
        )
    }

    @Test
    fun keepsLegalRanksAndExplainsChangedPrerequisitesAndTierGates() = runTest {
        val target = target(listOf(
            node(1, maxRank = 2),
            node(2, maxRank = 2, prerequisites = listOf(required(4))),
            node(3, unlockRequirements = listOf(gate(3, listOf(1)))),
            nodes[3],
            nodes[4],
        ))
        val plan = ready(target)
        assertEquals(listOf(allocation(1, 2)), plan.build.allocations)
        assertEquals(2, plan.transferredPoints)
        assertEquals(4, plan.lostPoints)
        assertEquals(49, plan.remainingPoints)
        assertEquals(
            listOf(MigrationLossReason.RANK_REDUCED, MigrationLossReason.PREREQUISITE, MigrationLossReason.TIER_GATE),
            plan.losses.map { it.reasons.single() },
        )
    }

    @Test
    fun replaysPrerequisitesUntilReachableAndCapsReducedBudgetInStableOrder() = runTest {
        val target = target(listOf(
            node(1, maxRank = 3, prerequisites = listOf(required(3))),
            nodes[1], nodes[2], nodes[3], nodes[4],
        ), maxPoints = 4)
        val plan = ready(target)
        val reordered = ready(target, original.copy(allocations = original.allocations.reversed()))
        assertEquals(listOf(allocation(1, 1), allocation(2, 2), allocation(3, 1)), plan.build.allocations)
        assertEquals(plan.build, reordered.build)
        assertEquals(plan.losses, reordered.losses)
        assertEquals(0, plan.remainingPoints)
        assertEquals(listOf(MigrationLossReason.POINT_BUDGET), plan.losses.single().reasons)
    }

    @Test
    fun refusesTreeIdsWithoutAUniqueVerifiedMatchAndIncompatibleClass() = runTest {
        val target = target()
        val tree = target.trees.single()
        val ambiguous = target.copy(trees = listOf(tree, tree))
        val changedId = target.copy(trees = listOf(tree.copy(id = 21), tree.copy(id = 22)))
        val recycled = target(nodes = listOf(node(91), node(92)))
        for (unmatched in listOf(ambiguous, changedId, recycled)) {
            assertEquals(BuildMigrationResult.UnmatchedTree, preview(unmatched))
        }
        assertEquals(BuildMigrationResult.IncompatibleClass, preview(target.copy(classInfo = target.classInfo.copy(
            token = ClassToken("MAGE"),
        ))))
    }

    @Test
    fun failsClosedOnMissingCorruptOrInvalidSourceAndTarget() = runTest {
        val missing = DatasetLoadError.AssetMissing("dataset")
        val corrupt = DatasetLoadError.InvalidJson("dataset", "bad JSON")
        for (error in listOf(missing, corrupt)) {
            val failed = DatasetOutcome.Failed(error)
            assertEquals(
                BuildMigrationResult.DatasetUnavailable(BuildMigrationResult.Side.SOURCE, error),
                previewBuildMigration(original, product, repository(failed, DatasetOutcome.Loaded(target()))),
            )
            assertEquals(
                BuildMigrationResult.DatasetUnavailable(BuildMigrationResult.Side.TARGET, error),
                previewBuildMigration(original, product, repository(DatasetOutcome.Loaded(source), failed)),
            )
        }
        assertIs<BuildMigrationResult.InvalidDataset>(preview(target(nodes = nodes + node(1))))
        assertIs<BuildMigrationResult.InvalidSource>(
            preview(target(), original.copy(allocations = listOf(allocation(1, 4)))),
        )
    }

    @Test
    fun onlyEarlierNumericVersionsOfTheSameGameAreEligible() = runTest {
        for (version in listOf("1.60.1.71037", "1.60.1.71038", "1.60.2.1", "1.60.1.bad")) {
            val build = original.copy(clientBuild = version)
            assertFalse(build.canMigrateTo(product))
            assertEquals(BuildMigrationResult.IneligibleVersion, preview(target(), build))
        }
        assertFalse(original.copy(game = io.github.alexeykravtsov95.talentforge.data.GameId("classic")).canMigrateTo(product))
        assertTrue(original.copy(clientBuild = "1.59.999.99999").canMigrateTo(product))
        assertTrue(original.copy(clientBuild = "1.60.1.9999").canMigrateTo(product))
    }

    private fun target(nodes: List<TalentNode> = this.nodes, maxPoints: Int = 51) =
        document(nodes, maxPoints = maxPoints).copy(clientBuild = product.clientBuild)

    private suspend fun preview(target: TalentDataDocument, build: BuildFile = original) =
        previewBuildMigration(build, product, repository(DatasetOutcome.Loaded(source), DatasetOutcome.Loaded(target)))

    private suspend fun ready(target: TalentDataDocument, build: BuildFile = original): BuildMigrationResult.Ready =
        assertIs<BuildMigrationResult.Ready>(preview(target, build)).also {
            assertTrue(TalentAllocationEngine(target, it.build.treeId).validate(it.build.toBuildState()).valid)
        }

    private fun repository(old: DatasetOutcome<TalentDataDocument>, active: DatasetOutcome<TalentDataDocument>) =
        object : TalentDatasetRepository {
            override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> =
                error("migration reads verified class documents")
            override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> = when (id.clientBuild) {
                source.clientBuild -> old
                product.clientBuild -> active
                else -> error("unexpected dataset: $id")
            }
        }
}
