package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.talentdata.Prerequisite
import io.github.alexeykravtsov95.talentforge.data.talentdata.PrerequisiteKind
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentSection
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.REFERENCE_BUNDLE
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * M5.UX.1 section projection against the REAL bundled datasets of EVERY bundled
 * client build (1.60.1.70009 reference, 1.60.1.70245, 1.60.1.70338 and
 * 1.60.1.70374 retained products, 1.60.1.70450 product), all nine classes:
 * the tree splits into exactly its published sections, no node id is lost or
 * duplicated, no connector is invented or broken, bounds come from each
 * section's own nodes, the geometry is the original one, the per-section
 * points add up to the engine's counter — and an unresolvable section layout
 * fails safe with a typed reason instead of hiding a node.
 */
class TalentTreeSectionsTest {

    private val repository = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    private val classes = listOf(
        "WARRIOR", "PALADIN", "HUNTER", "ROGUE", "PRIEST", "SHAMAN", "MAGE", "WARLOCK", "DRUID",
    )

    private fun document(bundle: DatasetBundleId, classToken: String): TalentDataDocument = runBlocking {
        assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            repository.document(DatasetId(bundle.game, bundle.clientBuild, ClassToken(classToken))),
        ).value
    }

    private fun model(document: TalentDataDocument): TalentTreeUiModel =
        assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model

    private fun sectioned(document: TalentDataDocument): TalentTreeSectionsResult.Sectioned =
        assertIs<TalentTreeSectionsResult.Sectioned>(
            buildTalentTreeSections(document.trees.single(), model(document)),
            "${document.classInfo.token} ${document.clientBuild} must split into sections",
        )

    /** Every bundled (build, class) document. */
    private fun everyDocument(): List<TalentDataDocument> =
        ALL_BUNDLES.flatMap { bundle -> classes.map { document(bundle, it) } }

    // ------------------------------------------------------------------ partition

    @Test
    fun everyRealTreeSplitsIntoExactlyItsPublishedSections() {
        val documents = everyDocument()
        assertEquals(45, documents.size, "nine classes on each of the five bundled builds")
        for (document in documents) {
            val tree = document.trees.single()
            val label = "${document.classInfo.token} ${document.clientBuild}"
            val result = sectioned(document)

            assertEquals(tree.sections.map { it.index }.sorted(), result.sections.map { it.index }, label)
            // the datasets publish no section names: data-driven "Tree N", never a guessed spec name
            assertEquals(listOf("Tree 1", "Tree 2", "Tree 3"), result.sections.map { it.label }, "$label labels")
            assertEquals(listOf(0, 1, 2), result.sections.map { it.ordinal }, label)
            for (section in tree.sections) {
                val view = assertNotNull(result.section(section.index))
                assertEquals(section.nodeIds.toSet(), view.model.nodes.map { it.id }.toSet(), "$label ${view.label}")
                assertTrue(
                    tree.nodes.filter { it.id in section.nodeIds }.all { it.sectionIndex == section.index },
                    "$label: every node of ${view.label} carries its sectionIndex",
                )
            }
        }
    }

    @Test
    fun noNodeIdIsLostOrDuplicatedAndEveryNodeKeepsItsIdentityAndPosition() {
        for (document in everyDocument()) {
            val label = "${document.classInfo.token} ${document.clientBuild}"
            val whole = model(document)
            val result = sectioned(document)
            val ids = result.sections.flatMap { section -> section.model.nodes.map { it.id } }

            assertEquals(whole.nodes.size, ids.size, "$label: nodes across all tabs")
            assertEquals(whole.nodes.map { it.id }.toSet(), ids.toSet(), "$label: no lost node id")
            assertEquals(ids.size, ids.toSet().size, "$label: no node in two tabs")
            assertEquals(whole.nodes.map { it.id }.toSet(), result.sectionOfNode.keys, label)
            // the same node values: identity, names, ranks AND world positions (relative geometry)
            val byId = whole.nodes.associateBy { it.id }
            for (section in result.sections) {
                for (node in section.model.nodes) {
                    assertEquals(byId.getValue(node.id), node, "$label node ${node.id}")
                    assertEquals(section.index, node.sectionIndex)
                }
                assertEquals(whole.treeId, section.model.treeId)
            }
        }
    }

    @Test
    fun sectionConnectorsAreExactlyTheIntraSectionEdgesAndNoneIsLost() {
        for (document in everyDocument()) {
            val label = "${document.classInfo.token} ${document.clientBuild}"
            val whole = model(document)
            val result = sectioned(document)
            val intra = result.sections.flatMap { it.model.edges }

            for (section in result.sections) {
                val ids = section.model.nodes.map { it.id }.toSet()
                for (edge in section.model.edges) {
                    assertTrue(edge.fromNodeId in ids && edge.toNodeId in ids, "$label: edge leaves ${section.label}")
                    assertTrue(edge in whole.edges, "$label: invented edge $edge")
                }
            }
            // every real connector is either inside one section or reported as cross-section
            assertEquals(whole.edges.size, intra.size + result.crossSectionEdges.size, label)
            assertEquals(whole.edges.toSet(), (intra + result.crossSectionEdges).toSet(), label)
            // the published Forever data has no connector between two sections
            assertTrue(result.crossSectionEdges.isEmpty(), "$label: cross-section connectors ${result.crossSectionEdges}")
        }
    }

    @Test
    fun sectionBoundsAreTightAroundTheSectionsOwnNodes() {
        val half = TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f
        for (document in everyDocument()) {
            for (section in sectioned(document).sections) {
                val nodes = section.model.nodes
                val bounds = section.model.bounds
                val label = "${document.classInfo.token} ${document.clientBuild} ${section.label}"
                assertEquals(nodes.minOf { it.centerX } - half, bounds.minX, 1e-3f, label)
                assertEquals(nodes.maxOf { it.centerX } + half, bounds.maxX, 1e-3f, label)
                assertEquals(nodes.minOf { it.centerY } - half, bounds.minY, 1e-3f, label)
                assertEquals(nodes.maxOf { it.centerY } + half, bounds.maxY, 1e-3f, label)
            }
        }
    }

    @Test
    fun readingOrderStartsAtTheTopLeftTalent() {
        for (document in everyDocument()) {
            for (section in sectioned(document).sections) {
                val first = section.model.nodes.first { it.id == section.readingOrderNodeIds.first() }
                assertTrue(
                    section.model.nodes.all {
                        it.centerY > first.centerY || (it.centerY == first.centerY && it.centerX >= first.centerX)
                    },
                    "${document.classInfo.token} ${section.label}: the tab icon talent is the top-left one",
                )
                assertEquals(section.model.nodes.size, section.readingOrderNodeIds.toSet().size)
            }
        }
    }

    // ------------------------------------------------------------------ points

    @Test
    fun sectionPointsAddUpToTheEngineCounterForRealBuilds() {
        for (document in listOf(document(REFERENCE_BUNDLE, "WARRIOR"), document(PRODUCT_BUNDLE, "DRUID"))) {
            val engine = TalentAllocationEngine(document)
            val result = sectioned(document)

            // a full-budget build produced by greedy ENGINE spends (never hand-written)
            var session = TalentBuildEditingState.start(engine)
            var progressed = true
            while (progressed && engine.spentPoints(session.current) < engine.pointBudget) {
                progressed = false
                for (node in engine.tree.nodes) {
                    val spent = session.spend(engine, node.id)
                    if (spent.changed) {
                        session = spent.editing
                        progressed = true
                    }
                }
            }
            val build = session.current
            assertEquals(engine.pointBudget, engine.spentPoints(build), "the greedy fill reaches the budget")
            val points = sectionSpentPoints(result, build)

            assertEquals(engine.spentPoints(build), points.values.sum(), document.classInfo.token.value)
            for (section in result.sections) {
                val expected = section.model.nodes.sumOf { build.rankOf(it.id) }
                assertEquals(expected, points[section.index], "${document.classInfo.token} ${section.label}")
            }
            assertEquals(
                result.sections.associate { it.index to 0 },
                sectionSpentPoints(result, engine.emptyState()),
                "an empty build has 0 everywhere",
            )
        }
    }

    @Test
    fun theProductArcaneTabCarriesArcaneWrathAlthoughNoGateCountsIt() {
        // 1.60.1.70450: Arcane Wrath is in no gate group; the dataset places it in the Arcane
        // tab by its column (docs/forever-dataset.md), and the projection shows it there
        val mage = document(PRODUCT_BUNDLE, "MAGE")
        val tree = mage.trees.single()
        val arcane = sectioned(mage).sections.first()
        assertEquals(19, arcane.model.nodes.size)
        assertTrue(arcane.model.nodes.any { it.id == ARCANE_WRATH && it.name == "Arcane Wrath" })
        assertEquals(listOf(19, 17, 19), sectioned(mage).sections.map { it.model.nodes.size })
        val counted = tree.nodes.flatMap { n -> n.unlockRequirements.flatMap { it.countedNodeIds } }.toSet()
        assertFalse(ARCANE_WRATH in counted, "no gate counts Arcane Wrath")
    }

    // ------------------------------------------------------------------ geometry on phones

    @Test
    fun hitTargetsOfOneSectionNeverOverlapAtAnyZoom() {
        for (document in everyDocument()) {
            for (section in sectioned(document).sections) {
                val nodes = section.model.nodes
                var scale = TalentTreeCanvasMetrics.ZOOM_MIN
                while (scale <= TalentTreeCanvasMetrics.ZOOM_MAX + 1e-6f) {
                    val viewport = TalentTreeViewport(scale, Offset.Zero)
                    val target = effectiveNodeTargetDp(viewport, minimumNodeTargetDp(TalentTreeLayoutClass.PORTRAIT))
                    for (i in nodes.indices) {
                        for (j in i + 1 until nodes.size) {
                            // square targets overlap iff both axis distances are below the side
                            val chebyshev = maxOf(
                                abs(nodes[i].centerX - nodes[j].centerX),
                                abs(nodes[i].centerY - nodes[j].centerY),
                            ) * scale
                            assertTrue(
                                chebyshev >= target,
                                "${document.classInfo.token} ${document.clientBuild} ${section.label}: " +
                                    "${nodes[i].name} / ${nodes[j].name} targets overlap at ${scale}×",
                            )
                        }
                    }
                    scale += 0.02f
                }
            }
        }
    }

    @Test
    fun firstOpenFitsAStandardSectionToThePhoneWidthWithoutEmptyBetweenSectionSpace() {
        // 320×568, 360×800, 393×852: the portrait canvas is as wide as the screen
        for (width in listOf(320f, 360f, 393f)) {
            val canvas = Size(width, 600f)
            val expectedScale = (width / TalentTreeCanvasMetrics.SECTION_FIT_REFERENCE_WIDTH_DP)
                .coerceIn(TalentTreeCanvasMetrics.ZOOM_MIN, 1f)
            for (document in everyDocument()) {
                for (section in sectioned(document).sections) {
                    val bounds = section.model.bounds
                    val fit = TalentTreeViewport.sectionFitFor(bounds, canvas, TalentTreeLayoutClass.PORTRAIT)
                    val label = "${document.classInfo.token} ${document.clientBuild} ${section.label} @${width}dp"
                    assertEquals(expectedScale, fit.scale, 1e-4f, label)
                    // 1A v2 S1: nodes stay ≥ 45 dp on the smallest phone, 52 dp from 354 dp up
                    assertTrue(TalentTreeCanvasMetrics.NODE_VISUAL_DP * fit.scale >= 45f, label)
                    val topLeft = fit.worldToScreen(Offset(bounds.minX, bounds.minY))
                    val bottomRight = fit.worldToScreen(Offset(bounds.maxX, bounds.maxY))
                    assertEquals(TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP, topLeft.y, 1e-3f, "$label top-aligned")
                    if (bounds.width * fit.scale + 2 * TalentTreeCanvasMetrics.CAMERA_FIELD_DP <= width) {
                        // the whole section is on screen and centred — nothing but this tree
                        assertEquals(topLeft.x, width - bottomRight.x, 1e-3f, "$label centred")
                        assertTrue(topLeft.x >= TalentTreeCanvasMetrics.CAMERA_FIELD_DP, "$label on screen")
                        if (abs(bounds.width - STANDARD_SECTION_WIDTH_DP) < 1e-3f) {
                            // a four-column section leaves less than one grid pitch on each
                            // side: no empty column, no neighbouring tree, no gap to pan across
                            assertTrue(topLeft.x < TalentTreeCanvasMetrics.GRID_STEP_DP * fit.scale, "$label margin")
                        }
                    } else {
                        // only a section carrying a documented far off-grid node is wider:
                        // it starts at the margin, so its real grid is on screen
                        assertEquals(TalentTreeCanvasMetrics.CANVAS_START_MARGIN_DP, topLeft.x, 1e-3f, label)
                    }
                }
            }
        }
    }

    private companion object {
        /** Mage "Arcane Wrath": new in 1.60.1.70425, counted by no gate group. */
        const val ARCANE_WRATH = 113627

        /** Three pitches + one node: a four-column section at scale 1. */
        const val STANDARD_SECTION_WIDTH_DP: Float =
            3 * TalentTreeCanvasMetrics.GRID_STEP_DP + TalentTreeCanvasMetrics.NODE_VISUAL_DP
    }

    // ------------------------------------------------------------------ fail-safe

    private fun warrior(): TalentDataDocument = document(REFERENCE_BUNDLE, "WARRIOR")

    private fun sectionsOfModified(transform: (TalentDataDocument) -> TalentDataDocument): TalentTreeSectionsResult {
        val base = warrior()
        val modified = transform(base)
        return buildTalentTreeSections(modified.trees.single(), model(base))
    }

    @Test
    fun aNodeWithoutASectionFailsSafeWithItsTypedReason() {
        val orphan = warrior().trees.single().nodes.first()
        val result = sectionsOfModified { document ->
            val tree = document.trees.single()
            document.copy(
                trees = listOf(
                    tree.copy(nodes = tree.nodes.map { if (it.id == orphan.id) it.copy(sectionIndex = null) else it }),
                ),
            )
        }
        assertEquals(
            TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.NodeWithoutSection(orphan.id)),
            result,
        )
    }

    @Test
    fun anUnknownSectionIndexFailsSafeWithItsTypedReason() {
        val node = warrior().trees.single().nodes.last()
        val result = sectionsOfModified { document ->
            val tree = document.trees.single()
            document.copy(
                trees = listOf(tree.copy(nodes = tree.nodes.map { if (it.id == node.id) it.copy(sectionIndex = 7) else it })),
            )
        }
        assertEquals(
            TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.UnknownSectionIndex(node.id, 7)),
            result,
        )
    }

    @Test
    fun membershipThatDisagreesWithTheNodesFailsSafe() {
        val tree = warrior().trees.single()
        val section0 = tree.sections.first { it.index == 0 }
        val moved = section0.nodeIds.first()
        // the section lists move the node to section 1, the node itself still says section 0
        val result = sectionsOfModified { document ->
            val t = document.trees.single()
            document.copy(
                trees = listOf(
                    t.copy(
                        sections = t.sections.map { section ->
                            when (section.index) {
                                0 -> section.copy(nodeIds = section.nodeIds - moved)
                                1 -> section.copy(nodeIds = section.nodeIds + moved)
                                else -> section
                            }
                        },
                    ),
                ),
            )
        }
        val unsectioned = assertIs<TalentTreeSectionsResult.Unsectioned>(result)
        assertIs<TalentTreeSectionIssue.MembershipMismatch>(unsectioned.issue)
        assertEquals(moved, (unsectioned.issue as TalentTreeSectionIssue.MembershipMismatch).nodeId)
    }

    @Test
    fun missingAndDuplicateSectionsFailSafe() {
        assertEquals(
            TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.NoSections),
            sectionsOfModified { d -> d.copy(trees = listOf(d.trees.single().copy(sections = emptyList()))) },
        )
        assertEquals(
            TalentTreeSectionsResult.Unsectioned(TalentTreeSectionIssue.DuplicateSectionIndex(0)),
            sectionsOfModified { d ->
                val t = d.trees.single()
                d.copy(trees = listOf(t.copy(sections = t.sections + TalentSection(0, null, emptyList()))))
            },
        )
    }

    @Test
    fun aPublishedSectionNameIsUsedAsIsAndAnEmptySectionIsAllowed() {
        val result = sectionsOfModified { d ->
            val t = d.trees.single()
            d.copy(
                trees = listOf(
                    t.copy(
                        sections = t.sections.map { if (it.index == 1) it.copy(name = "Named") else it } +
                            TalentSection(index = 3, name = null, nodeIds = emptyList()),
                    ),
                ),
            )
        }
        val sections = assertIs<TalentTreeSectionsResult.Sectioned>(result).sections
        assertEquals(listOf("Tree 1", "Named", "Tree 3", "Tree 4"), sections.map { it.label })
        val empty = sections.last()
        assertTrue(empty.model.nodes.isEmpty() && empty.model.edges.isEmpty())
        assertTrue(empty.readingOrderNodeIds.isEmpty())
        // an empty section still gets a finite fit
        val fit = TalentTreeViewport.sectionFitFor(empty.model.bounds, Size(360f, 600f), TalentTreeLayoutClass.PORTRAIT)
        assertTrue(fit.scale.isFinite() && fit.offset.x.isFinite() && fit.offset.y.isFinite())
    }

    // ------------------------------------------------------------------ synthetic cross-section dependency

    @Test
    fun aSyntheticCrossSectionPrerequisiteIsEnforcedByTheEngineAndReportedNotDrawn() {
        val fixture = crossSectionFixture(warrior())
        val result = sectioned(fixture.document)

        val cross = result.crossSectionEdges.single()
        assertEquals(fixture.sourceId, cross.fromNodeId)
        assertEquals(fixture.targetId, cross.toNodeId)
        assertTrue(result.sections.none { section -> cross in section.model.edges }, "never drawn inside a tab")
        assertEquals(1, result.sectionOfNode[fixture.sourceId])
        assertEquals(2, result.sectionOfNode[fixture.targetId])

        // the ENGINE still enforces it: the target is locked behind the other section's talent
        val engine = TalentAllocationEngine(fixture.document)
        assertFalse(engine.canSpend(engine.emptyState(), fixture.targetId).allowed, "target locked")
    }

    @Test
    fun theSectionViewIsDeterministic() {
        val document = document(PRODUCT_BUNDLE, "HUNTER")
        assertEquals(sectioned(document), sectioned(document))
    }
}

/**
 * Synthetic, test-only fixture (M5.UX.1): a real Warrior document plus ONE
 * invented REQUIRED prerequisite from a first-tier talent of section 1
 * ("Tree 2") to a first-tier talent of section 2 ("Tree 3") — the analogue
 * of the approved PDF's illustrative Omen of Clarity → Feral Charge link,
 * which exists in no real dataset and is never added to one. Used only to
 * regression-test cross-section Show navigation.
 */
internal data class CrossSectionFixture(
    val document: TalentDataDocument,
    val sourceId: Int,
    val targetId: Int,
)

internal fun crossSectionFixture(real: TalentDataDocument): CrossSectionFixture {
    val tree = real.trees.single()
    fun firstTierOf(section: Int) = tree.nodes
        .filter { it.sectionIndex == section && it.prerequisites.isEmpty() && it.unlockRequirements.isEmpty() }
        .minWith(compareBy({ it.position.y }, { it.position.x }))
    val source = firstTierOf(1)
    val target = firstTierOf(2)
    val synthetic = tree.copy(
        nodes = tree.nodes.map { node ->
            if (node.id == target.id) {
                node.copy(prerequisites = listOf(Prerequisite(nodeId = source.id, kind = PrerequisiteKind.REQUIRED)))
            } else {
                node
            }
        },
    )
    return CrossSectionFixture(real.copy(trees = listOf(synthetic)), sourceId = source.id, targetId = target.id)
}
