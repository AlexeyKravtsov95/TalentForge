package io.github.alexeykravtsov95.talentforge.share

import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.allocation
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.node
import io.github.alexeykravtsov95.talentforge.allocation.SyntheticTrees.required
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.FixedBuildClock
import io.github.alexeykravtsov95.talentforge.data.builds.InMemoryBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.builds.SequentialBuildIds
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNodeType
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.encoded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * [prepareBuildImport] over in-memory datasets (common: runs on JVM and on the
 * iOS simulator). The real bundled datasets are exercised by the JVM
 * `BuildImportBundledDatasetsTest`.
 */
class BuildImportTest {

    /** Serves exactly the documents it was given and records every lookup. */
    private class RecordingDatasets(
        private val documents: Map<DatasetId, DatasetOutcome<TalentDataDocument>>,
    ) : TalentDatasetRepository {
        val requested = mutableListOf<DatasetId>()

        override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> =
            error("import preparation must resolve documents, not manifests")

        override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> {
            requested += id
            return documents[id] ?: DatasetOutcome.Failed(
                DatasetLoadError.ManifestUnavailable(
                    DatasetLoadError.AssetMissing("dataset/${id.game.value}/${id.clientBuild}/manifest.json"),
                ),
            )
        }
    }

    private val old = "1.60.1.70009"
    private val new = "1.60.1.70374"

    /** Old: node 1 has 3 ranks. New: node 1 has 1 rank, node 6 exists. Same tree id. */
    private val oldDocument = SyntheticTrees.document(SyntheticTrees.defaultTree(), treeId = 7)
    private val newDocument = SyntheticTrees.document(
        SyntheticTrees.defaultTree().map { if (it.id == 1) node(1, maxRank = 1) else it } + node(6),
        treeId = 7,
    ).copy(clientBuild = new, contentHash = ContentHash("sha256:synthetic-new"))

    private fun idOf(clientBuild: String) = DatasetId(GameId("forever"), clientBuild, ClassToken("WARRIOR"))

    private fun datasets(vararg extra: Pair<DatasetId, DatasetOutcome<TalentDataDocument>>) = RecordingDatasets(
        mapOf(idOf(old) to DatasetOutcome.Loaded(oldDocument), idOf(new) to DatasetOutcome.Loaded(newDocument)) +
            extra,
    )

    private fun code(clientBuild: String, vararg allocations: Triple<Int, Int, Int>, treeId: Int = 7): String =
        encoded(
            ShareCodeTestSupport.build(
                clientBuild = clientBuild,
                treeId = treeId,
                allocations = allocations.map { (node, rank, entry) -> allocation(node, rank, entry) },
            ),
        )

    private fun a(node: Int, rank: Int, entry: Int = node + 1000) = Triple(node, rank, entry)

    private fun rejected(result: BuildImportPreparation): BuildImportFailure =
        assertIs<BuildImportPreparation.Rejected>(result, "expected a rejection").failure

    private fun invalidCodes(result: BuildImportPreparation): List<String> =
        assertIs<BuildImportFailure.InvalidAllocation>(rejected(result)).reasonCodes

    // ------------------------------------------------------------------ verified preview

    @Test
    fun aValidCodeYieldsAVerifiedPreviewOfItsOwnBuild() = runTest {
        val repository = datasets()
        val result = prepareBuildImport(code(old, a(2, 3), a(1, 3), a(3, 1)), repository)

        val preview = assertIs<BuildImportPreparation.Ready>(result).preview
        assertEquals(GameId("forever"), preview.game)
        assertEquals(old, preview.clientBuild)
        assertEquals(ClassToken("WARRIOR"), preview.classToken)
        assertEquals("Warrior", preview.className)
        assertEquals(7, preview.treeId)
        assertEquals(7, preview.spentPoints)
        assertEquals(51, preview.pointBudget)
        // the verified portable build, canonical order, nothing added or dropped
        assertEquals(
            BuildFile(
                game = GameId("forever"),
                clientBuild = old,
                classToken = ClassToken("WARRIOR"),
                treeId = 7,
                allocations = listOf(allocation(1, 3), allocation(2, 3), allocation(3, 1)),
            ),
            preview.build,
        )
        assertEquals(listOf(idOf(old)), repository.requested)
    }

    @Test
    fun anEmptyBuildIsAValidImport() = runTest {
        val preview = assertIs<BuildImportPreparation.Ready>(prepareBuildImport(code(new), datasets())).preview
        assertEquals(0, preview.spentPoints)
        assertTrue(preview.build.allocations.isEmpty())
    }

    // ------------------------------------------------------------------ exact dataset identity

    @Test
    fun theCodeIsValidatedAgainstItsExactClientBuildOnly() = runTest {
        // valid on the old build, invalid on the new one — and vice versa
        val repository = datasets()
        assertIs<BuildImportPreparation.Ready>(prepareBuildImport(code(old, a(1, 3)), repository))
        assertEquals(listOf("RANK_OUT_OF_BOUNDS"), invalidCodes(prepareBuildImport(code(new, a(1, 3)), repository)))
        assertIs<BuildImportPreparation.Ready>(prepareBuildImport(code(new, a(6, 1)), repository))
        assertEquals(listOf("UNKNOWN_NODE"), invalidCodes(prepareBuildImport(code(old, a(6, 1)), repository)))
        // each preparation looked up exactly the build its code names, nothing else
        assertEquals(listOf(idOf(old), idOf(new), idOf(new), idOf(old)), repository.requested)
    }

    @Test
    fun anUnavailableClientBuildIsRejectedAndNeverSubstituted() = runTest {
        val repository = datasets()
        val failure = rejected(prepareBuildImport(code("1.60.1.69977", a(1, 1)), repository))
        assertEquals(BuildImportFailure.ClientBuildUnavailable(GameId("forever"), "1.60.1.69977"), failure)
        assertEquals(listOf(idOf("1.60.1.69977")), repository.requested, "no other build was tried")
    }

    @Test
    fun aMissingClassAndAFailedIntegrityCheckStayDistinct() = runTest {
        val missingClass = idOf("1.60.1.70100")
        val tampered = idOf("1.60.1.70200")
        val integrity = DatasetLoadError.ContentHashMismatch(
            manifestPath = "m",
            documentPath = "d",
            expected = ContentHash("sha256:a"),
            actual = ContentHash("sha256:b"),
        )
        val repository = datasets(
            missingClass to DatasetOutcome.Failed(DatasetLoadError.ClassEntryMissing("m", ClassToken("WARRIOR"))),
            tampered to DatasetOutcome.Failed(integrity),
        )
        assertEquals(
            BuildImportFailure.ClassUnavailable(GameId("forever"), "1.60.1.70100", ClassToken("WARRIOR")),
            rejected(prepareBuildImport(code("1.60.1.70100"), repository)),
        )
        assertEquals(
            BuildImportFailure.DatasetRejected(integrity),
            rejected(prepareBuildImport(code("1.60.1.70200"), repository)),
        )
    }

    @Test
    fun aTreeTheClassDoesNotHaveIsATreeMismatch() = runTest {
        assertEquals(
            BuildImportFailure.TreeMismatch(buildTreeId = 8, availableTreeIds = listOf(7)),
            rejected(prepareBuildImport(code(old, a(1, 1), treeId = 8), datasets())),
        )
    }

    @Test
    fun aDatasetTheEngineRefusesIsTyped() = runTest {
        val noBudget = idOf("1.60.1.70300")
        val document = oldDocument.copy(
            clientBuild = "1.60.1.70300",
            trees = oldDocument.trees.map { it.copy(pointBudget = null) },
        )
        val failure = rejected(
            prepareBuildImport(code("1.60.1.70300"), datasets(noBudget to DatasetOutcome.Loaded(document))),
        )
        assertIs<BuildImportFailure.EngineUnavailable>(failure)
    }

    // ------------------------------------------------------------------ allocation validation (real engine)

    @Test
    fun invalidAllocationsAreRejectedWithTheEngineReasons() = runTest {
        val repository = datasets()
        // invalid rank
        assertEquals(listOf("RANK_OUT_OF_BOUNDS"), invalidCodes(prepareBuildImport(code(old, a(1, 4)), repository)))
        // unknown node and unknown entry
        assertEquals(listOf("UNKNOWN_NODE"), invalidCodes(prepareBuildImport(code(old, a(99, 1)), repository)))
        assertEquals(
            listOf("INVALID_ENTRY"),
            invalidCodes(prepareBuildImport(code(old, a(1, 1, entry = 4242)), repository)),
        )
        // insufficient prerequisite: node 5 requires node 1
        assertEquals(
            listOf("REQUIRED_PREREQUISITE_NOT_MET"),
            invalidCodes(prepareBuildImport(code(old, a(5, 1)), repository)),
        )
        // unmet tier gate: node 3 needs 5 points in nodes 1 and 2
        val gate = assertIs<BuildImportFailure.InvalidAllocation>(
            rejected(prepareBuildImport(code(old, a(1, 3), a(3, 1)), repository)),
        )
        assertEquals(listOf("TIER_GATE_NOT_MET"), gate.reasonCodes)
        assertEquals(AllocationIssueCode.TIER_GATE_NOT_MET, gate.issues.single().code)
        assertEquals(3, gate.issues.single().nodeId)
    }

    @Test
    fun overspendingIsRejected() = runTest {
        val small = idOf("1.60.1.70400")
        val document = SyntheticTrees.document(SyntheticTrees.defaultTree(), treeId = 7, maxPoints = 4)
            .copy(clientBuild = "1.60.1.70400")
        val codes = invalidCodes(
            prepareBuildImport(
                code("1.60.1.70400", a(1, 3), a(2, 3)),
                datasets(small to DatasetOutcome.Loaded(document)),
            ),
        )
        assertTrue("POINT_BUDGET_EXCEEDED" in codes, "$codes")
    }

    @Test
    fun unsupportedMechanicsAreRejected() = runTest {
        val tiered = idOf("1.60.1.70500")
        val document = SyntheticTrees.document(
            listOf(node(1, maxRank = 2, type = TalentNodeType.Tiered), node(2, prerequisites = listOf(required(1)))),
            treeId = 7,
        ).copy(clientBuild = "1.60.1.70500")
        val codes = invalidCodes(
            prepareBuildImport(code("1.60.1.70500", a(1, 1)), datasets(tiered to DatasetOutcome.Loaded(document))),
        )
        assertTrue("UNSUPPORTED_NODE_TYPE" in codes, "$codes")
    }

    @Test
    fun aMalformedCodeNeverReachesTheDatasets() = runTest {
        val repository = datasets()
        val valid = code(old, a(1, 1))
        val inputs = listOf("", "TF1", "TF2ABC", "hello", valid.lowercase(), "TF1" + "0".repeat(5000))
        for (text in inputs) {
            assertIs<BuildImportFailure.InvalidCode>(rejected(prepareBuildImport(text, repository)), text)
        }
        val flipped = valid.substring(0, 10) + (if (valid[10] == '0') '1' else '0') + valid.substring(11)
        assertIs<ShareCodeDecodeError.ChecksumMismatch>(
            assertIs<BuildImportFailure.InvalidCode>(rejected(prepareBuildImport(flipped, repository))).error,
        )
        assertTrue(repository.requested.isEmpty(), "${repository.requested}")
    }

    // ------------------------------------------------------------------ read-only

    @Test
    fun preparingImportsWritesNothingAndLeavesSavedBuildsUntouched() = runTest {
        val storage = InMemoryBuildLibraryStorage()
        val library = LocalBuildRepository(storage, FixedBuildClock(), SequentialBuildIds())
        val saved = listOf(
            ShareCodeTestSupport.build(clientBuild = old, treeId = 7, allocations = listOf(allocation(1, 3))),
            ShareCodeTestSupport.build(clientBuild = new, treeId = 7, allocations = listOf(allocation(6, 1))),
        )
        saved.forEach { library.createDraft(it) }
        val writesBefore = storage.writeCount
        val payloadBefore = storage.payload

        val repository = datasets()
        // export every saved build and prepare it for import — plus a rejected one
        for (build in saved) {
            val preview = assertIs<BuildImportPreparation.Ready>(prepareBuildImport(encoded(build), repository)).preview
            assertEquals(build, preview.build)
        }
        rejected(prepareBuildImport(code(new, a(1, 3)), repository))

        assertEquals(writesBefore, storage.writeCount, "import preparation performed a storage write")
        assertEquals(payloadBefore, storage.payload)
        val records = when (val loaded = library.load()) {
            is BuildLibraryOutcome.Loaded -> loaded.value
            is BuildLibraryOutcome.Failed -> error("library unreadable: ${loaded.error}")
        }
        assertEquals(saved.toSet(), records.map { it.build }.toSet())
    }
}
