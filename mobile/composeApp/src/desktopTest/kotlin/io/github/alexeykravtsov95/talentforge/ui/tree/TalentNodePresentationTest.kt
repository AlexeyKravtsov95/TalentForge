package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
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
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * M4.2 read-only allocation-state projection tests: the real committed
 * Forever Warrior dataset through the real `TalentAllocationEngine`, plus
 * synthetic documents/states for the categories the empty baseline build does
 * not exercise (partial, maxed, no points, unresolved, unsupported). The
 * engine is only ever *queried* here — the mapper must never spend, refund or
 * otherwise mutate the supplied state.
 */
class TalentNodePresentationTest {

    private val IMPROVED_REND = 105956
    private val IMPROVED_CHARGE = 105955
    private val DEEP_WOUNDS = 105950

    private suspend fun loadWarriorEngine(): TalentAllocationEngine {
        val repository = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
        val outcome = repository.document(
            DatasetId(GameId("forever"), "1.60.1.70009", ClassToken("WARRIOR")),
        )
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(outcome).value
        return TalentAllocationEngine(document)
    }

    // ------------------------------------------------------------------ real baseline (empty build)

    @Test
    fun realWarriorEmptyBuildProjectsEveryNodeWithZeroRank() = runTest {
        val engine = loadWarriorEngine()
        val state = engine.emptyState()
        val presentation = buildTalentTreePresentation(engine, state)

        assertEquals(engine.tree.nodes.size, presentation.nodes.size)
        assertTrue(presentation.nodes.values.all { it.rank == 0 && !it.isAllocated })
        assertEquals(0, presentation.spentPoints)
        assertEquals(51, presentation.remainingPoints)
        assertEquals(51, presentation.pointBudget)
    }

    @Test
    fun tierOneTalentIsAvailableDerivedFromEngineCanSpend() = runTest {
        val engine = loadWarriorEngine()
        val state = engine.emptyState()
        val presentation = buildTalentTreePresentation(engine, state)

        val improvedRend = presentation.nodes.getValue(IMPROVED_REND)
        assertEquals(TalentNodeState.AVAILABLE, improvedRend.state)
        assertEquals("0/3", improvedRend.rankText)
        assertTrue(improvedRend.issues.isEmpty(), "engine reports no issue for Improved Rend")
        assertTrue(engine.canSpend(state, IMPROVED_REND).allowed)
    }

    @Test
    fun tierGatedTalentKeepsStructuredRequiredActualValues() = runTest {
        val engine = loadWarriorEngine()
        val presentation = buildTalentTreePresentation(engine, engine.emptyState())

        // Improved Charge: 5-point tier gate counting the three tier-1 nodes.
        val improvedCharge = presentation.nodes.getValue(IMPROVED_CHARGE)
        assertEquals(TalentNodeState.TIER_LOCKED, improvedCharge.state)
        assertEquals(1, improvedCharge.tierGates.size)
        assertEquals(5, improvedCharge.tierGates.single().requiredPoints)
        assertEquals(0, improvedCharge.tierGates.single().spentPoints)
        assertTrue(improvedCharge.unmetPrerequisiteNodeIds.isEmpty())
        assertContains(improvedCharge.issues.map { it.code }, AllocationIssueCode.TIER_GATE_NOT_MET)
    }

    @Test
    fun prerequisiteLockedTalentPreservesBothReasonsAndSourceIds() = runTest {
        val engine = loadWarriorEngine()
        val presentation = buildTalentTreePresentation(engine, engine.emptyState())

        // Deep Wounds: sufficient prerequisite Improved Rend AND a 10-point
        // gate — the empty build reports both; neither fact may be collapsed.
        val deepWounds = presentation.nodes.getValue(DEEP_WOUNDS)
        assertEquals(listOf(IMPROVED_REND), deepWounds.unmetPrerequisiteNodeIds)
        assertEquals(1, deepWounds.tierGates.size)
        assertEquals(10, deepWounds.tierGates.single().requiredPoints)
        val codes = deepWounds.issues.map { it.code }
        assertContains(codes, AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET)
        assertContains(codes, AllocationIssueCode.TIER_GATE_NOT_MET)
        // UI-only precedence: the node-specific, Show-navigable reason leads.
        assertEquals(TalentNodeState.PREREQUISITE_LOCKED, deepWounds.state)
    }

    @Test
    fun mappingNeverMutatesTheSuppliedBuildState() = runTest {
        val engine = loadWarriorEngine()
        val state = engine.emptyState()

        buildTalentTreePresentation(engine, state)

        assertTrue(state.allocations.isEmpty(), "the empty build stays empty")
        assertEquals(0, engine.spentPoints(state), "nothing was spent")
        assertTrue(engine.validate(state).valid, "the state the UI holds is still the engine's empty build")
    }

    // ------------------------------------------------------------------ synthetic states (arbitrary BuildState)

    @Test
    fun rankIsDerivedFromBuildStateNotHardcoded() = runTest {
        val engine = TalentAllocationEngine(syntheticDocument())
        val empty = buildTalentTreePresentation(engine, engine.emptyState())
        val partial = buildTalentTreePresentation(
            engine,
            BuildState.of(1, listOf(NodeAllocation(1, 1001, 2))),
        )

        assertEquals("0/3", empty.nodes.getValue(1).rankText)
        assertEquals(2, partial.nodes.getValue(1).rank)
        assertEquals(TalentNodeState.PARTIAL, partial.nodes.getValue(1).state)
        assertEquals("2/3", partial.nodes.getValue(1).rankText)
    }

    @Test
    fun maxedTalentProjectsMaxedState() = runTest {
        val engine = TalentAllocationEngine(syntheticDocument())
        val presentation = buildTalentTreePresentation(
            engine,
            BuildState.of(1, listOf(NodeAllocation(1, 1001, 3))),
        )

        val maxed = presentation.nodes.getValue(1)
        assertEquals(TalentNodeState.MAXED, maxed.state)
        assertTrue(maxed.isMaxed)
        assertEquals(3, presentation.spentPoints)
    }

    @Test
    fun exhaustedBudgetProjectsNoPointsForReachableTalent() = runTest {
        val engine = TalentAllocationEngine(syntheticDocument(maxPoints = 1))
        val presentation = buildTalentTreePresentation(
            engine,
            BuildState.of(1, listOf(NodeAllocation(1, 1001, 1))),
        )

        val other = presentation.nodes.getValue(2)
        assertEquals(TalentNodeState.NO_POINTS, other.state)
        assertEquals(0, presentation.remainingPoints)
        assertContains(other.issues.map { it.code }, AllocationIssueCode.NO_POINTS_REMAINING)
    }

    @Test
    fun unresolvedConditionMapsToSafeUnavailablePresentation() = runTest {
        val document = syntheticDocument(
            nodes = listOf(
                syntheticNode(1, unresolvedConditionIds = listOf(7)),
                syntheticNode(2),
            ),
        )
        val engine = TalentAllocationEngine(document)
        val presentation = buildTalentTreePresentation(engine, engine.emptyState())

        val unresolved = presentation.nodes.getValue(1)
        assertEquals(TalentNodeState.UNAVAILABLE, unresolved.state)
        assertContains(unresolved.issues.map { it.code }, AllocationIssueCode.UNRESOLVED_CONDITION)
        assertTrue(unresolved.issues.isNotEmpty())
    }

    @Test
    fun unsupportedNodeTypeAndFlagsNeverLookPurchasable() = runTest {
        val document = syntheticDocument(
            nodes = listOf(
                syntheticNode(1, type = TalentNodeType.Tiered),
                syntheticNode(2, flags = 2), // NeverPurchasable
                syntheticNode(3, flags = 64), // unknown flag bits
            ),
        )
        val engine = TalentAllocationEngine(document)
        val presentation = buildTalentTreePresentation(engine, engine.emptyState())

        assertEquals(TalentNodeState.UNAVAILABLE, presentation.nodes.getValue(1).state)
        assertEquals(TalentNodeState.UNAVAILABLE, presentation.nodes.getValue(2).state)
        assertEquals(TalentNodeState.UNAVAILABLE, presentation.nodes.getValue(3).state)
    }

    // ------------------------------------------------------------------ inspector content

    @Test
    fun realWarriorSelectedNodeProducesInspectorContentFromRealData() = runTest {
        val engine = loadWarriorEngine()
        val tree = buildTalentTreePresentation(engine, engine.emptyState())
        val facts = talentNodeFacts(engine)

        val content = buildTalentInspectorContent(tree.nodes.getValue(IMPROVED_REND), tree, facts)

        assertEquals(TalentName(IMPROVED_REND, "Improved Rend"), content.name)
        assertEquals("0/3", content.rankText)
        assertEquals(InspectorText.Available, content.stateLine)
        assertNull(content.showAction)
        assertNull(content.prerequisiteLine)
        assertNull(content.tierLine)
    }

    @Test
    fun prerequisiteLockedContentSpellsOutReasonAndSingleHopShow() = runTest {
        val engine = loadWarriorEngine()
        val tree = buildTalentTreePresentation(engine, engine.emptyState())
        val facts = talentNodeFacts(engine)

        val content = buildTalentInspectorContent(tree.nodes.getValue(DEEP_WOUNDS), tree, facts)

        assertEquals(TalentName(DEEP_WOUNDS, "Deep Wounds"), content.name)
        assertEquals(
            InspectorText.RequiresPrerequisite(TalentName(IMPROVED_REND, "Improved Rend"), requiredRank = 3),
            content.prerequisiteLine,
        )
        assertNull(content.stateLine, "a locked node's lock lines are its state")
        val show = assertNotNull(content.showAction)
        assertEquals(IMPROVED_REND, show.prerequisiteNodeId)
        assertEquals(TalentName(IMPROVED_REND, "Improved Rend"), show.prerequisiteName)
        // both lock facts stay visible: tier meter present alongside the prereq
        assertEquals(1, content.tierMeters.size)
        assertEquals(InspectorText.SpendMorePoints(deficit = 10), content.tierLine)
    }

    @Test
    fun tierOnlyLockedContentHasMeterAndNoShow() = runTest {
        val engine = loadWarriorEngine()
        val tree = buildTalentTreePresentation(engine, engine.emptyState())
        val facts = talentNodeFacts(engine)

        val content = buildTalentInspectorContent(tree.nodes.getValue(IMPROVED_CHARGE), tree, facts)

        assertNull(content.showAction, "a tier-only lock never invents a prerequisite Show")
        assertEquals(InspectorText.SpendMorePoints(deficit = 5), content.tierLine)
        assertEquals(5, content.tierMeters.single().requiredPoints)
        assertEquals(0, content.tierMeters.single().spentPoints)
    }

    @Test
    fun guideOriginAppendsNeededForSuffixToTargetStateLine() = runTest {
        val engine = loadWarriorEngine()
        val tree = buildTalentTreePresentation(engine, engine.emptyState())
        val facts = talentNodeFacts(engine)

        val plain = buildTalentInspectorContent(tree.nodes.getValue(IMPROVED_REND), tree, facts)
        val guided = buildTalentInspectorContent(
            tree.nodes.getValue(IMPROVED_REND),
            tree,
            facts,
            guideOriginName = facts.talentName(DEEP_WOUNDS),
        )

        assertEquals(InspectorText.Available, plain.stateLine)
        assertEquals(
            InspectorText.NeededFor(InspectorText.Available, TalentName(DEEP_WOUNDS, "Deep Wounds")),
            guided.stateLine,
        )
    }

    @Test
    fun noPointsContentReportsRealSpentBudgetValues() = runTest {
        val engine = TalentAllocationEngine(syntheticDocument(maxPoints = 1))
        val tree = buildTalentTreePresentation(
            engine,
            BuildState.of(1, listOf(NodeAllocation(1, 1001, 1))),
        )
        val facts = talentNodeFacts(engine)

        val content = buildTalentInspectorContent(tree.nodes.getValue(2), tree, facts)

        assertEquals(InspectorText.NoPointsLeft(spentPoints = 1, pointBudget = 1), content.stateLine)
    }

    @Test
    fun multipleUnmetPrerequisiteSourcesFailSafeWithoutShow() = runTest {
        // B3 does not define which prerequisite Show picks when several are
        // unmet; the committed Warrior data never exercises this, so the
        // presentation must not invent a preference.
        val document = syntheticDocument(
            nodes = listOf(
                syntheticNode(1),
                syntheticNode(2),
                syntheticNode(
                    3,
                    prerequisites = listOf(
                        Prerequisite(1, PrerequisiteKind.REQUIRED),
                        Prerequisite(2, PrerequisiteKind.REQUIRED),
                    ),
                ),
            ),
        )
        val engine = TalentAllocationEngine(document)
        val tree = buildTalentTreePresentation(engine, engine.emptyState())
        val facts = talentNodeFacts(engine)

        val content = buildTalentInspectorContent(tree.nodes.getValue(3), tree, facts)

        assertNull(content.showAction)
        assertEquals(listOf(1, 2), tree.nodes.getValue(3).unmetPrerequisiteNodeIds)
        assertEquals(InspectorText.RequiresPrerequisites(count = 2), content.prerequisiteLine)
    }
}

// ------------------------------------------------------------------ synthetic helpers (composeApp-local)

private fun syntheticNode(
    id: Int,
    maxRank: Int = 3,
    type: TalentNodeType = TalentNodeType.Single,
    flags: Int = 0,
    prerequisites: List<Prerequisite> = emptyList(),
    unlockRequirements: List<UnlockRequirement> = emptyList(),
    unresolvedConditionIds: List<Int> = emptyList(),
): TalentNode = TalentNode(
    id = id,
    sectionIndex = null,
    position = TalentPosition(x = id * 10, y = 0, row = 0, column = id),
    type = type,
    flags = flags,
    maxRank = maxRank,
    spellId = id + 90000,
    name = "node-$id",
    prerequisites = prerequisites,
    unlockRequirements = unlockRequirements,
    unresolvedConditionIds = unresolvedConditionIds,
    entries = listOf(
        TalentEntry(
            id = id + 1000,
            definitionId = id + 1000,
            spellId = id + 90000,
            name = "node-$id",
            maxRank = maxRank,
            entryType = 1,
            descriptionTemplate = null,
            effects = emptyList(),
        ),
    ),
)

private fun syntheticDocument(
    nodes: List<TalentNode> = listOf(syntheticNode(1), syntheticNode(2)),
    maxPoints: Int = 51,
): TalentDataDocument = TalentDataDocument(
    schemaVersion = 1,
    game = GameId("forever"),
    clientBuild = "1.60.1.70009",
    classInfo = ClassInfo(id = 1, token = ClassToken("WARRIOR"), name = "Warrior"),
    trees = listOf(
        TalentTree(
            id = 1,
            pointBudget = PointBudget(currencyId = 3820, maxPoints = maxPoints),
            layout = TalentLayout(xStep = 10, yStep = 10),
            sections = emptyList(),
            nodes = nodes,
            visualOnlyEdges = emptyList(),
        ),
    ),
    contentHash = io.github.alexeykravtsov95.talentforge.data.ContentHash("sha256:synthetic"),
    generatedAt = "2026-01-01T00:00:00Z",
    provenance = Provenance(source = "synthetic", tables = emptyList()),
)
