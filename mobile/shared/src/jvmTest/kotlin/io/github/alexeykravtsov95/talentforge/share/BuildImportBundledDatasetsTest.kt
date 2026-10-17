package io.github.alexeykravtsov95.talentforge.share

import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Share codes against the datasets the app actually ships
 * (`composeApp/.../composeResources/files/dataset`, kept byte-identical to
 * `data/normalized/` by `scripts/sync_mobile_datasets.py --check`), through the
 * real [BundledTalentDatasetRepository] and [TalentAllocationEngine].
 */
class BuildImportBundledDatasetsTest {

    private val game = GameId("forever")
    private val bundledRoot: File =
        TestRepoAssets.repoRoot.resolve("mobile/composeApp/src/commonMain/composeResources/files")

    /** The bundled repository, recording every document lookup. */
    private class Recording(private val delegate: TalentDatasetRepository) : TalentDatasetRepository {
        val requested = mutableListOf<DatasetId>()

        override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> =
            delegate.manifest(bundle)

        override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> {
            requested += id
            return delegate.document(id)
        }
    }

    private fun repository() = Recording(
        BundledTalentDatasetRepository(
            object : DatasetAssetSource {
                override suspend fun read(path: String): ByteArray {
                    val file = bundledRoot.resolve(path)
                    if (!file.isFile) throw AssetNotFoundException(path)
                    return file.readBytes()
                }
            },
        ),
    )

    private val bundledBuilds: List<String> by lazy {
        bundledRoot.resolve("dataset/forever").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
    }

    private val classTokens: List<String> = TestRepoAssets.classTokens.map { it.uppercase() }

    private suspend fun document(repository: TalentDatasetRepository, clientBuild: String, token: String) =
        assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            repository.document(DatasetId(game, clientBuild, ClassToken(token))),
        ).value

    private fun engineOf(document: TalentDataDocument) =
        TalentAllocationEngine(document, treeId = document.trees.single().id)

    /**
     * A valid plan obtained purely through [TalentAllocationEngine.spend], never
     * by writing allocations directly:
     *
     * 1. targets — [priority] and, recursively, their prerequisites
     *    (prerequisites first) — get every rank the engine allows; while a
     *    target is still blocked (tier gate, prerequisite), ONE rank is bought
     *    elsewhere in top-row-first order and the targets are retried;
     * 2. then passes over all nodes (top row first, node id) buy one rank
     *    wherever allowed, until [limit] points are spent or nothing is buyable.
     */
    private fun purchased(engine: TalentAllocationEngine, limit: Int, priority: List<Int> = emptyList()): BuildState {
        val nodes = engine.tree.nodes.associateBy { it.id }
        val targets = mutableListOf<Int>()
        fun visit(id: Int) {
            if (id in targets) return
            nodes.getValue(id).prerequisites.forEach { visit(it.nodeId) }
            targets += id
        }
        priority.forEach(::visit)
        val fill = engine.tree.nodes.sortedWith(compareBy({ it.position.row }, { it.id }))
        var state = engine.emptyState()
        fun buy(nodeId: Int): Boolean {
            if (engine.spentPoints(state) >= limit) return false
            val result = engine.spend(state, nodeId)
            if (result.allowed) state = result.state
            return result.allowed
        }
        while (engine.spentPoints(state) < limit) {
            for (target in targets) {
                while (buy(target)) continue
            }
            val blocked = targets.filter { state.rankOf(it) < nodes.getValue(it).maxRank }
            if (blocked.isEmpty()) break
            // advance the blocked targets' tier gates first: buy in the nodes they count
            val counted = blocked.flatMap { id -> nodes.getValue(id).unlockRequirements.flatMap { it.countedNodeIds } }.toSet()
            val advance = fill.sortedBy { it.id !in counted }.filter { it.id !in targets }
            if (advance.none { buy(it.id) }) break
        }
        var progressed = true
        while (progressed && engine.spentPoints(state) < limit) {
            progressed = false
            for (node in fill) if (buy(node.id)) progressed = true
        }
        assertTrue(engine.validate(state).valid)
        return state
    }

    private fun fileOf(state: BuildState, document: TalentDataDocument): BuildFile =
        buildFileOf(state, document.game, document.clientBuild, document.classInfo.token)

    private fun codeOf(build: BuildFile): String =
        assertIs<ShareCodeEncoding.Encoded>(BuildShareCode.encode(build), "encode $build").code

    private suspend fun ready(code: String, repository: TalentDatasetRepository): BuildImportPreview =
        when (val result = prepareBuildImport(code, repository)) {
            is BuildImportPreparation.Ready -> result.preview
            is BuildImportPreparation.Rejected -> throw AssertionError("rejected ${result.failure} for $code")
        }

    private suspend fun failure(code: String, repository: TalentDatasetRepository): BuildImportFailure =
        assertIs<BuildImportPreparation.Rejected>(prepareBuildImport(code, repository), code).failure

    private suspend fun reasonCodes(code: String, repository: TalentDatasetRepository): List<String> =
        assertIs<BuildImportFailure.InvalidAllocation>(failure(code, repository)).reasonCodes

    // ------------------------------------------------------------------ every class, every bundled build

    @Test
    fun theFiveShippedBuildsAreTheOnesUnderTest() {
        assertEquals(
            listOf("1.60.1.70009", "1.60.1.70245", "1.60.1.70338", "1.60.1.70374", "1.60.1.70450"),
            bundledBuilds,
        )
    }

    @Test
    fun emptyPartialAndFullBuildsOfEveryClassOnEveryBundledBuildImportExactly() = runTest {
        val repository = repository()
        val codes = mutableMapOf<String, BuildFile>()
        for (clientBuild in bundledBuilds) {
            val manifest = assertIs<DatasetOutcome.Loaded<DatasetManifest>>(
                repository.manifest(DatasetBundleId(game, clientBuild)),
            ).value
            assertEquals(classTokens.sorted(), manifest.classes.map { it.token.value }.sorted())
            for (entry in manifest.classes) {
                val document = document(repository, clientBuild, entry.token.value)
                val engine = engineOf(document)
                val full = purchased(engine, engine.pointBudget)
                assertEquals(engine.pointBudget, engine.spentPoints(full), "${entry.token} $clientBuild is full")
                for (state in listOf(engine.emptyState(), purchased(engine, 13), full)) {
                    val build = fileOf(state, document)
                    val code = codeOf(build)
                    assertEquals(code, codeOf(build), "deterministic")
                    assertTrue(code.length <= 200, "compact: ${code.length} chars for ${entry.token} $clientBuild")
                    assertEquals(build, assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build)

                    val preview = ready(code, repository)
                    assertEquals(build, preview.build, "exact round trip")
                    assertEquals(clientBuild, preview.clientBuild)
                    assertEquals(entry.token, preview.classToken)
                    assertEquals(document.classInfo.name, preview.className)
                    assertEquals(document.trees.single().id, preview.treeId)
                    assertEquals(engine.spentPoints(state), preview.spentPoints)
                    assertEquals(engine.pointBudget, preview.pointBudget)
                    assertEquals(null, codes.put(code, build), "one code, one build")
                }
            }
        }
        assertEquals(5 * 9 * 3, codes.size)
        println("share-code length: max ${codes.keys.maxOf { it.length }}, min ${codes.keys.minOf { it.length }}")
    }

    // ------------------------------------------------------------------ dataset regressions

    @Test
    fun druidKingOfTheJungleKeepsItsThreeRanksOnlyOn70009() = runTest {
        val repository = repository()
        val druid70009 = document(repository, "1.60.1.70009", "DRUID")
        val state = purchased(engineOf(druid70009), 51, priority = listOf(KING_OF_THE_JUNGLE))
        assertEquals(3, state.rankOf(KING_OF_THE_JUNGLE))
        val build = fileOf(state, druid70009)
        assertEquals("1.60.1.70009", ready(codeOf(build), repository).clientBuild)

        // node 104951 became the 1-rank "Shifting Power" in 70245: the same allocation is no later build
        for (later in listOf("1.60.1.70245", "1.60.1.70338", "1.60.1.70374", "1.60.1.70450")) {
            val codes = reasonCodes(codeOf(build.copy(clientBuild = later)), repository)
            assertTrue("RANK_OUT_OF_BOUNDS" in codes, "$later: $codes")
        }
    }

    @Test
    fun druidImprovedShiftingPowerExistsOnlyAfter70009() = runTest {
        val repository = repository()
        val druid70374 = document(repository, "1.60.1.70374", "DRUID")
        val state = purchased(engineOf(druid70374), 51, priority = listOf(IMPROVED_SHIFTING_POWER))
        assertTrue(state.rankOf(IMPROVED_SHIFTING_POWER) > 0)
        val build = fileOf(state, druid70374)
        ready(codeOf(build), repository)
        assertTrue("UNKNOWN_NODE" in reasonCodes(codeOf(build.copy(clientBuild = "1.60.1.70009")), repository))
    }

    @Test
    fun warriorNodesRemovedAndAddedBetween70245And70338() = runTest {
        val repository = repository()
        val warrior70245 = document(repository, "1.60.1.70245", "WARRIOR")
        val removedIds = listOf(TOUGHNESS, PRECISION, IMPROVED_CLEAVE)
        val removed = purchased(engineOf(warrior70245), 51, priority = removedIds)
        assertTrue(removedIds.all { removed.rankOf(it) > 0 })
        val onRemoved = fileOf(removed, warrior70245)
        ready(codeOf(onRemoved), repository)
        assertTrue("UNKNOWN_NODE" in reasonCodes(codeOf(onRemoved.copy(clientBuild = "1.60.1.70338")), repository))

        val warrior70338 = document(repository, "1.60.1.70338", "WARRIOR")
        val added = purchased(engineOf(warrior70338), 51, priority = listOf(IRON_WILL, GORE_DRINKER))
        assertTrue(added.rankOf(IRON_WILL) > 0 && added.rankOf(GORE_DRINKER) > 0)
        val onAdded = fileOf(added, warrior70338)
        ready(codeOf(onAdded), repository)
        assertTrue("UNKNOWN_NODE" in reasonCodes(codeOf(onAdded.copy(clientBuild = "1.60.1.70245")), repository))
    }

    @Test
    fun priestAndMageNodesRemovedAndAddedBetween70374And70450() = runTest {
        val repository = repository()
        // Improved Mind Blast (105827) is gone in 70450: a 70374 code naming it imports only on 70374
        val priest70374 = document(repository, "1.60.1.70374", "PRIEST")
        val removed = purchased(engineOf(priest70374), 51, priority = listOf(IMPROVED_MIND_BLAST_70374))
        assertEquals(5, removed.rankOf(IMPROVED_MIND_BLAST_70374))
        val onRemoved = fileOf(removed, priest70374)
        assertEquals(onRemoved, ready(codeOf(onRemoved), repository).build)
        assertTrue("UNKNOWN_NODE" in reasonCodes(codeOf(onRemoved.copy(clientBuild = "1.60.1.70450")), repository))

        // Arcane Wrath (113627) exists since 70425: a 70450 code naming it imports only on 70450
        val mage70450 = document(repository, "1.60.1.70450", "MAGE")
        val added = purchased(engineOf(mage70450), 51, priority = listOf(ARCANE_BLAST, ARCANE_WRATH))
        assertEquals(1, added.rankOf(ARCANE_WRATH))
        val onAdded = fileOf(added, mage70450)
        assertEquals(onAdded, ready(codeOf(onAdded), repository).build)
        assertTrue("UNKNOWN_NODE" in reasonCodes(codeOf(onAdded.copy(clientBuild = "1.60.1.70374")), repository))
    }

    @Test
    fun identicalTopologiesOf70338And70374KeepDistinctBuildIdentities() = runTest {
        val repository = repository()
        for (token in classTokens) {
            val document70338 = document(repository, "1.60.1.70338", token)
            val build70338 = fileOf(purchased(engineOf(document70338), 51), document70338)
            val build70374 = build70338.copy(clientBuild = "1.60.1.70374")
            val code70338 = codeOf(build70338)
            val code70374 = codeOf(build70374)
            assertNotEquals(code70338, code70374, token)

            repository.requested.clear()
            val preview70338 = ready(code70338, repository)
            val preview70374 = ready(code70374, repository)
            assertEquals(build70338, preview70338.build)
            assertEquals(build70374, preview70374.build)
            assertEquals(preview70338.build.allocations, preview70374.build.allocations)
            assertEquals(
                listOf(
                    DatasetId(game, "1.60.1.70338", ClassToken(token)),
                    DatasetId(game, "1.60.1.70374", ClassToken(token)),
                ),
                repository.requested,
                "each code resolved its own build",
            )
        }
    }

    // ------------------------------------------------------------------ build identity policy

    @Test
    fun aPreviousBuildResolvesAgainstItselfNeverAgainstTheProductDataset() = runTest {
        val repository = repository()
        for (token in classTokens) {
            val reference = document(repository, "1.60.1.70009", token)
            val code = codeOf(fileOf(purchased(engineOf(reference), 51), reference))
            repository.requested.clear()
            assertEquals("1.60.1.70009", ready(code, repository).clientBuild)
            assertEquals(listOf(DatasetId(game, "1.60.1.70009", ClassToken(token))), repository.requested)
        }
    }

    @Test
    fun unavailableClientBuildsAreRejectedWithoutFallback() = runTest {
        val repository = repository()
        // 69977 is a canonical dataset of the repository, but the app does not ship it
        assertTrue(TestRepoAssets.forever69977.isDirectory)
        for (clientBuild in listOf("1.60.1.69977", "1.60.1.99999", "1.61.0.70374")) {
            val build = BuildFile(game = game, clientBuild = clientBuild, classToken = ClassToken("WARRIOR"), treeId = 1117)
            repository.requested.clear()
            assertEquals(BuildImportFailure.ClientBuildUnavailable(game, clientBuild), failure(codeOf(build), repository))
            assertEquals(listOf(DatasetId(game, clientBuild, ClassToken("WARRIOR"))), repository.requested)
        }
        val otherGame = BuildFile(game = GameId("classic"), clientBuild = "1.60.1.70374", classToken = ClassToken("WARRIOR"), treeId = 1117)
        assertIs<BuildImportFailure.ClientBuildUnavailable>(failure(codeOf(otherGame), repository))
        val unknownClass = BuildFile(game = game, clientBuild = "1.60.1.70374", classToken = ClassToken("MONK"), treeId = 1117)
        assertIs<BuildImportFailure.ClassUnavailable>(failure(codeOf(unknownClass), repository))
    }

    // ------------------------------------------------------------------ real-data mutations

    @Test
    fun mutationsOfARealBuildAreRejectedByTheEngine() = runTest {
        val repository = repository()
        val document = document(repository, "1.60.1.70374", "WARRIOR")
        val engine = engineOf(document)
        val build = fileOf(purchased(engine, 51), document)
        val nodes = engine.tree.nodes.associateBy { it.id }
        fun mutated(transform: (List<NodeAllocation>) -> List<NodeAllocation>) =
            codeOf(build.copy(allocations = transform(build.allocations)))

        val first = build.allocations.first()
        val maxRank = nodes.getValue(first.nodeId).entries.single().maxRank
        // incorrect rank: one above the entry's maximum
        val tooHigh = mutated { list -> list.map { if (it == first) it.copy(rank = maxRank + 1) else it } }
        assertTrue("RANK_OUT_OF_BOUNDS" in reasonCodes(tooHigh, repository))
        // unknown talent entry and unknown node
        val foreignEntry = mutated { list -> list.map { if (it == first) it.copy(entryId = 999_999) else it } }
        assertTrue("INVALID_ENTRY" in reasonCodes(foreignEntry, repository))
        assertTrue("UNKNOWN_NODE" in reasonCodes(mutated { it + NodeAllocation(999_999, 999_998, 1) }, repository))
        // invalid prerequisites / gates: drop every top-row node, keep everything bought after them
        val roots = engine.tree.nodes.filter { it.position.row == 0 }.map { it.id }.toSet()
        val orphaned = reasonCodes(mutated { list -> list.filter { it.nodeId !in roots } }, repository)
        assertTrue(orphaned.any { it.endsWith("_NOT_MET") }, "$orphaned")
        // overspending: a full build plus one more rank
        val unbought = engine.tree.nodes.first { node -> build.allocations.none { it.nodeId == node.id } }
        val over = reasonCodes(mutated { it + NodeAllocation(unbought.id, unbought.entries.single().id, 1) }, repository)
        assertTrue("POINT_BUDGET_EXCEEDED" in over, "$over")
        // mismatched tree identity: the warrior allocation under the mage tree id
        assertIs<BuildImportFailure.TreeMismatch>(failure(codeOf(build.copy(treeId = 1112)), repository))
    }

    // ------------------------------------------------------------------ documentation examples

    @Test
    fun theDocumentedExamplesAreRealImports() = runTest {
        val repository = repository()
        val druid70009 = document(repository, "1.60.1.70009", "DRUID")
        val druid = fileOf(purchased(engineOf(druid70009), 51, priority = listOf(KING_OF_THE_JUNGLE)), druid70009)
        val warrior70374 = document(repository, "1.60.1.70374", "WARRIOR")
        val warrior = fileOf(purchased(engineOf(warrior70374), 13), warrior70374)
        val mage = BuildFile(game = game, clientBuild = "1.60.1.70374", classToken = ClassToken("MAGE"), treeId = 1112)
        val examples = listOf(DOC_DRUID_70009_FULL to druid, DOC_WARRIOR_70374_PARTIAL to warrior, DOC_MAGE_70374_EMPTY to mage)
        for ((code, build) in examples) {
            println("doc example ${build.classToken} ${build.clientBuild}: ${codeOf(build)}\n  ${build.allocations}")
        }
        for ((code, build) in examples) {
            assertEquals(code, codeOf(build))
            assertEquals(build, ready(code, repository).build)
        }
        val doc = TestRepoAssets.repoRoot.resolve("docs/build-sharing.md").readText()
        for ((code, _) in examples) assertTrue(code in doc, "docs/build-sharing.md shows $code")
    }

    private companion object {
        const val KING_OF_THE_JUNGLE = 104951
        const val IMPROVED_SHIFTING_POWER = 113563
        const val TOUGHNESS = 105973
        const val PRECISION = 105929
        const val IMPROVED_CLEAVE = 105936
        const val IRON_WILL = 113570
        const val GORE_DRINKER = 113569
        const val IMPROVED_MIND_BLAST_70374 = 105827
        const val ARCANE_BLAST = 105806
        const val ARCANE_WRATH = 113627

        const val DOC_DRUID_70009_FULL = "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1G60WGAH2JAN4M9G882VDV61P3H8C020RE0M0GA083041020R1080G208204102081080G2082041G20G2080G2081041G20HYGMB54"
        const val DOC_WARRIOR_70374_PARTIAL = "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT0ENT1A994JKTJVM40HMNV0TH96601088G40820411008206N3QGF90ABEE1AA"
        const val DOC_MAGE_70374_EMPTY = "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT08KA18X2XG200AYM31R0"
    }
}
