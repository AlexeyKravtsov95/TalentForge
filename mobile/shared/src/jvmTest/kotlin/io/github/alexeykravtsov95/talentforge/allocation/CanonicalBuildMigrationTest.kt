package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.share.BuildShareCode
import io.github.alexeykravtsov95.talentforge.share.ShareCodeDecoding
import io.github.alexeykravtsov95.talentforge.share.ShareCodeEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CanonicalBuildMigrationTest {
    @Test
    fun transfers51WarriorPointsFrom70959To71037AndRoundTripsTf1() = runTest {
        val game = GameId("forever")
        val sourceId = DatasetId(game, "1.60.1.70959", ClassToken("WARRIOR"))
        val targetId = sourceId.copy(clientBuild = "1.60.1.71037")
        val assets = object : DatasetAssetSource {
            override suspend fun read(path: String): ByteArray =
                TestRepoAssets.repoRoot.resolve("data/normalized/${path.removePrefix("dataset/")}").readBytes()
        }
        val datasets = BundledTalentDatasetRepository(assets)
        val source = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(datasets.document(sourceId)).value
        val target = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(datasets.document(targetId)).value
        val sourceEngine = TalentAllocationEngine(source)
        val targetEngine = TalentAllocationEngine(target)
        var state = sourceEngine.emptyState()
        val nodeIds = sourceEngine.tree.nodes.map { it.id }.sorted()
        repeat(51) {
            val next = nodeIds.first { sourceEngine.canSpend(state, it).allowed }
            state = sourceEngine.spend(state, next).state
        }
        val build = buildFileOf(state, game, sourceId.clientBuild, sourceId.classToken)
        val plan = assertIs<BuildMigrationResult.Ready>(
            previewBuildMigration(build, DatasetBundleId(game, targetId.clientBuild), datasets),
        )
        assertEquals(51, plan.transferredPoints)
        assertEquals(0, plan.lostPoints)
        assertEquals(0, plan.remainingPoints)
        assertEquals(build.allocations, plan.build.allocations)
        assertEquals(targetId.clientBuild, plan.build.clientBuild)
        assertTrue(targetEngine.validate(plan.build.toBuildState()).valid)
        val code = assertIs<ShareCodeEncoding.Encoded>(BuildShareCode.encode(plan.build)).code
        assertTrue(code.startsWith("TF1"))
        assertEquals(plan.build, assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build)
    }
}
