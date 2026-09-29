package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.talentdata.ClassInfo
import io.github.alexeykravtsov95.talentforge.data.talentdata.Prerequisite
import io.github.alexeykravtsov95.talentforge.data.talentdata.PrerequisiteKind
import io.github.alexeykravtsov95.talentforge.data.talentdata.Provenance
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentLayout
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNode
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNodeType
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentPosition
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentSection
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree
import io.github.alexeykravtsov95.talentforge.data.talentdata.VisualOnlyEdge
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Geometry mapping tests against the real committed Forever Warrior dataset
 * (loaded through the production repository and resource source), plus
 * synthetic documents for the structural edge cases the baseline does not
 * contain (visual-only edges, multi-tree documents, dangling references).
 */
class TalentTreeUiModelTest {

    private val baselineBundle = DatasetBundleId(GameId("forever"), "1.60.1.70009")

    private suspend fun loadWarriorDocument(): TalentDataDocument {
        val repository = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
        val outcome = repository.document(
            DatasetId(
                game = baselineBundle.game,
                clientBuild = baselineBundle.clientBuild,
                classToken = ClassToken("WARRIOR"),
            ),
        )
        return assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(outcome).value
    }

    private suspend fun buildWarriorModel(): TalentTreeUiModel =
        when (val result = buildTalentTreeUiModel(loadWarriorDocument())) {
            is TalentTreeUiBuildResult.Ready -> result.model
            is TalentTreeUiBuildResult.Failed -> error("baseline Warrior model failed: ${result.error}")
        }

    @Test
    fun realWarriorDatasetMapsIntoNonEmptyUiTree() = runTest {
        val model = buildWarriorModel()

        assertEquals(53, model.nodes.size, "Warrior baseline has 53 nodes")
        assertTrue(model.edges.isNotEmpty(), "baseline prerequisite edges must be mapped")
        assertTrue(model.edges.all { it.kind == TalentTreeEdgeKind.PREREQUISITE })
    }

    @Test
    fun everyRenderedNodeHasAUniqueId() = runTest {
        val model = buildWarriorModel()

        val ids = model.nodes.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "node ids must be unique (stable keys)")
    }

    @Test
    fun everyPrerequisiteConnectorReferencesExistingNodes() = runTest {
        val model = buildWarriorModel()
        val centers = model.nodes.associate { it.id to Offset(it.centerX, it.centerY) }

        for (edge in model.edges.filter { it.kind == TalentTreeEdgeKind.PREREQUISITE }) {
            assertContains(centers.keys, edge.fromNodeId)
            assertContains(centers.keys, edge.toNodeId)
            assertEquals(centers.getValue(edge.fromNodeId), Offset(edge.startX, edge.startY))
            assertEquals(centers.getValue(edge.toNodeId), Offset(edge.endX, edge.endY))
        }
    }

    @Test
    fun everyVisualOnlyConnectorReferencesExistingNodes() {
        val document = syntheticDocument(
            tree(
                id = 1,
                nodes = listOf(node(id = 10, x = 0, y = 0), node(id = 11, x = 600, y = 0)),
                visualOnlyEdges = listOf(VisualOnlyEdge(fromNodeId = 10, toNodeId = 11)),
            ),
        )

        val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model
        val visualEdges = model.edges.filter { it.kind == TalentTreeEdgeKind.VISUAL_ONLY }

        assertEquals(1, visualEdges.size)
        val ids = model.nodes.map { it.id }.toSet()
        assertTrue(visualEdges.all { it.fromNodeId in ids && it.toNodeId in ids })
    }

    @Test
    fun danglingVisualOnlyEdgeFailsClosed() {
        val document = syntheticDocument(
            tree(
                id = 1,
                nodes = listOf(node(id = 10, x = 0, y = 0)),
                visualOnlyEdges = listOf(VisualOnlyEdge(fromNodeId = 10, toNodeId = 99)),
            ),
        )

        val failure = assertIs<TalentTreeUiBuildResult.Failed>(buildTalentTreeUiModel(document))
        val error = assertIs<TalentTreeUiBuildError.DanglingEdgeReference>(failure.error)
        assertEquals(TalentTreeEdgeKind.VISUAL_ONLY, error.kind)
        assertEquals(99, error.toNodeId)
    }

    @Test
    fun danglingPrerequisiteEdgeFailsClosed() {
        val document = syntheticDocument(
            tree(
                id = 1,
                nodes = listOf(
                    node(id = 10, x = 0, y = 0),
                    node(id = 11, x = 0, y = 600, prerequisites = listOf(Prerequisite(99, PrerequisiteKind.SUFFICIENT))),
                ),
            ),
        )

        val failure = assertIs<TalentTreeUiBuildResult.Failed>(buildTalentTreeUiModel(document))
        assertIs<TalentTreeUiBuildError.DanglingEdgeReference>(failure.error)
    }

    @Test
    fun coordinateTransformationPreservesOrderingAndRelativeGeometry() = runTest {
        val document = loadWarriorDocument()
        val tree = document.trees.single()
        val model = buildWarriorModel()
        val clientById = tree.nodes.associate { it.id to it.position }
        val factor = TalentTreeCanvasMetrics.GRID_STEP_DP / tree.layout.xStep

        // ordering preserved in both axes
        for (left in model.nodes) {
            for (right in model.nodes) {
                val leftClient = clientById.getValue(left.id)
                val rightClient = clientById.getValue(right.id)
                assertEquals(
                    leftClient.x.compareTo(rightClient.x),
                    left.centerX.compareTo(right.centerX),
                    "x ordering must be preserved for ${left.id} vs ${right.id}",
                )
                assertEquals(
                    leftClient.y.compareTo(rightClient.y),
                    left.centerY.compareTo(right.centerY),
                    "y ordering must be preserved for ${left.id} vs ${right.id}",
                )
            }
        }

        // uniform scale: every client distance maps to the same dp factor
        val first = model.nodes.first()
        for (other in model.nodes) {
            val clientA = clientById.getValue(first.id)
            val clientB = clientById.getValue(other.id)
            val clientDx = (clientB.x - clientA.x).toFloat()
            val worldDx = other.centerX - first.centerX
            if (abs(clientDx) > 1f) {
                assertTrue(abs(worldDx / clientDx - factor) < 1e-4f, "x scale must be uniform")
            }
            val clientDy = (clientB.y - clientA.y).toFloat()
            val worldDy = other.centerY - first.centerY
            if (abs(clientDy) > 1f) {
                assertTrue(abs(worldDy / clientDy - factor) < 1e-4f, "y scale must be uniform and equal to x")
            }
        }
    }

    @Test
    fun computedTreeBoundsContainAllNodeCentres() = runTest {
        val model = buildWarriorModel()
        val half = TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f

        for (node in model.nodes) {
            assertTrue(node.centerX - half >= model.bounds.minX - 1e-3f, "node ${node.id} left of bounds")
            assertTrue(node.centerX + half <= model.bounds.maxX + 1e-3f, "node ${node.id} right of bounds")
            assertTrue(node.centerY - half >= model.bounds.minY - 1e-3f, "node ${node.id} above bounds")
            assertTrue(node.centerY + half <= model.bounds.maxY + 1e-3f, "node ${node.id} below bounds")
        }
    }

    @Test
    fun mappingIsDeterministicForTheSameDataset() = runTest {
        assertEquals(buildWarriorModel(), buildWarriorModel())
    }

    @Test
    fun sectionsMapThroughToTheUiModel() = runTest {
        val model = buildWarriorModel()
        val sectionIndices = model.nodes.mapNotNull { it.sectionIndex }.toSortedSet()

        assertEquals(setOf(0, 1, 2), sectionIndices, "Warrior sections are 0/1/2")
    }

    @Test
    fun monogramFollowsTheDocumentedRule() {
        assertEquals("HS", nodeMonogram("Improved Heroic Strike", 1))
        assertEquals("RE", nodeMonogram("Improved Rend", 1))
        assertEquals("DW", nodeMonogram("Deep Wounds", 1))
        assertEquals("MS", nodeMonogram("Mortal Strike", 1))
        assertEquals("TM", nodeMonogram("Improved Tactical Mastery", 1))
        assertEquals("AM", nodeMonogram("Anger Management", 1))
        assertEquals("DE", nodeMonogram("Deflection", 1))
        assertEquals("EN", nodeMonogram("Enrage", 1))
        assertEquals("105958", nodeMonogram(null, 105958))
        assertEquals("105958", nodeMonogram("   ", 105958))
    }

    @Test
    fun emptyTreesDocumentFailsClosed() {
        val failure = assertIs<TalentTreeUiBuildResult.Failed>(
            buildTalentTreeUiModel(syntheticDocument(trees = emptyList())),
        )
        assertIs<TalentTreeUiBuildError.NoTrees>(failure.error)
    }

    @Test
    fun multipleTreesDocumentFailsClosedInsteadOfDroppingData() {
        val failure = assertIs<TalentTreeUiBuildResult.Failed>(
            buildTalentTreeUiModel(
                syntheticDocument(
                    trees = listOf(
                        tree(id = 1, nodes = listOf(node(id = 10, x = 0, y = 0))),
                        tree(id = 2, nodes = listOf(node(id = 20, x = 0, y = 0))),
                    ),
                ),
            ),
        )
        val error = assertIs<TalentTreeUiBuildError.MultipleTrees>(failure.error)
        assertEquals(listOf(1, 2), error.treeIds)
    }

    @Test
    fun nonPositiveLayoutStepFailsClosed() {
        val brokenTree = tree(
            id = 1,
            layout = TalentLayout(xStep = 0, yStep = 600),
            nodes = listOf(node(id = 10, x = 0, y = 0)),
        )
        val failure = assertIs<TalentTreeUiBuildResult.Failed>(
            buildTalentTreeUiModel(syntheticDocument(listOf(brokenTree))),
        )
        assertIs<TalentTreeUiBuildError.InvalidLayout>(failure.error)
    }

    // ---- synthetic contract helpers -------------------------------------------------

    private fun syntheticDocument(trees: List<TalentTree>): TalentDataDocument =
        TalentDataDocument(
            game = GameId("forever"),
            clientBuild = "synthetic",
            classInfo = ClassInfo(id = 1, token = ClassToken("WARRIOR"), name = "Warrior"),
            trees = trees,
            contentHash = io.github.alexeykravtsov95.talentforge.data.ContentHash("sha256:synthetic"),
            generatedAt = "synthetic",
            provenance = Provenance(source = "synthetic", tables = emptyList()),
        )

    private fun syntheticDocument(tree: TalentTree): TalentDataDocument =
        syntheticDocument(listOf(tree))

    private fun tree(
        id: Int,
        nodes: List<TalentNode>,
        visualOnlyEdges: List<VisualOnlyEdge> = emptyList(),
        layout: TalentLayout = TalentLayout(xStep = 600, yStep = 600),
    ): TalentTree = TalentTree(
        id = id,
        pointBudget = null,
        layout = layout,
        sections = listOf(TalentSection(index = 0, name = null, nodeIds = nodes.map { it.id })),
        nodes = nodes,
        visualOnlyEdges = visualOnlyEdges,
    )

    private fun node(
        id: Int,
        x: Int,
        y: Int,
        prerequisites: List<Prerequisite> = emptyList(),
    ): TalentNode = TalentNode(
        id = id,
        sectionIndex = 0,
        position = TalentPosition(x = x, y = y, row = y / 600, column = x / 600),
        type = TalentNodeType.Single,
        flags = 0,
        maxRank = 5,
        spellId = null,
        name = "Talent $id",
        prerequisites = prerequisites,
        unlockRequirements = emptyList(),
        unresolvedConditionIds = emptyList(),
        entries = emptyList(),
    )
}
