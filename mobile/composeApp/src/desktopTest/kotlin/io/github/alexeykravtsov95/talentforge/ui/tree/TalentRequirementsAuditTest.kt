package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import java.io.File
import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real published data only: regressions in gate counting, edge identity, and read-only inspection. */
class TalentRequirementsAuditTest {
    private val classes = listOf("WARRIOR", "PALADIN", "HUNTER", "ROGUE", "PRIEST", "SHAMAN", "MAGE", "WARLOCK", "DRUID")

    @Test
    fun allNineClassesOnEveryCommittedDatasetKeepRequirementsAndDirections() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "data/normalized/forever").isDirectory }
        val builds = File(root, "data/normalized/forever").listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }
        for (build in builds) for (classToken in classes) {
            val document = TalentDataContract.decodeTalentDataDocument(File(build, "${classToken.lowercase()}.json").readBytes())
            val engine = TalentAllocationEngine(document)
            val model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model
            val facts = talentNodeFacts(engine)
            var editing = TalentBuildEditingState.start(engine)
            val seenGates = mutableSetOf<Int>()
            val scenarios = mutableSetOf<String>()
            var refunded = false

            fun inspect(state: BuildState) {
                val before = state.allocations.toList()
                val presentation = buildTalentTreePresentation(engine, state)
                for (node in engine.tree.nodes) {
                    val projected = presentation.nodes.getValue(node.id)
                    val content = buildTalentInspectorContent(projected, presentation, facts)
                    assertEquals(node.prerequisites.map { it.nodeId }, content.prerequisites.map { it.source.nodeId })
                    assertEquals(node.prerequisites.map { it.kind }, content.prerequisites.map { it.kind })
                    assertEquals(node.unlockRequirements.size, content.requirementGates.size)
                    for ((gate, actual) in node.unlockRequirements.zip(content.requirementGates)) {
                        seenGates += gate.requiredPoints
                        assertEquals(gate.requiredPoints, actual.requiredPoints)
                        // Independent sum from saved allocations, not the projection's progress.
                        assertEquals(before.filter { it.nodeId in gate.countedNodeIds }.sumOf { it.rank }, actual.spentPoints)
                    }
                    for (prerequisite in content.prerequisites) {
                        assertEquals(state.rankOf(prerequisite.source.nodeId), prerequisite.rank)
                        assertEquals(engine.tree.nodes.single { it.id == prerequisite.source.nodeId }.maxRank, prerequisite.requiredRank)
                    }
                    if (node.prerequisites.isNotEmpty() && node.unlockRequirements.isNotEmpty()) {
                        val tierLocked = projected.issues.any { it.code == AllocationIssueCode.TIER_GATE_NOT_MET }
                        val edgeLocked = projected.unmetPrerequisiteNodeIds.isNotEmpty()
                        scenarios += "$tierLocked/$edgeLocked"
                    }
                }
                assertEquals(before, state.allocations, "inspection never changes saved allocations")
            }

            inspect(editing.current)
            // Fill each specialization independently, so its 30-point gate is reached within 51 points.
            for (section in engine.tree.sections) {
                editing = TalentBuildEditingState.start(engine)
                var changed: Boolean
                do {
                    changed = false
                    for (id in section.nodeIds) {
                        val result = editing.spend(engine, id)
                        if (result.changed) {
                            editing = result.editing
                            changed = true
                            inspect(editing.current)
                        }
                    }
                } while (changed)
                // A legal refund must immediately refresh the same requirement projection.
                for (id in section.nodeIds.reversed()) {
                    val result = editing.refund(engine, id)
                    if (result.changed) {
                        refunded = true
                        inspect(result.editing.current)
                        break
                    }
                }
            }
            assertEquals(setOf(5, 10, 15, 20, 25, 30), seenGates, "$classToken ${build.name}")
            assertTrue("true/true" in scenarios, "combined lock: $classToken")
            assertTrue("false/false" in scenarios, "both requirements satisfied: $classToken")
            assertTrue(refunded, "legal refunds exercised: $classToken")
            for (edge in model.edges) {
                if (edge.kind == TalentTreeEdgeKind.PREREQUISITE) {
                    assertTrue(engine.tree.nodes.single { it.id == edge.toNodeId }.prerequisites.any { it.nodeId == edge.fromNodeId })
                } else {
                    assertTrue(engine.tree.visualOnlyEdges.any { it.fromNodeId == edge.fromNodeId && it.toNodeId == edge.toNodeId })
                }
            }
        }
    }

    @Test
    fun warriorFourLockCombinationsAndRefundRecomputeProgress() {
        val ready = productState("WARRIOR")
        val engine = ready.engine
        val facts = talentNodeFacts(engine)
        val target = engine.tree.nodes.single { it.name == "Deep Wounds" }
        val source = engine.tree.nodes.single { it.name == "Improved Rend" }
        fun content(state: BuildState): TalentInspectorContent {
            val tree = buildTalentTreePresentation(engine, state)
            return buildTalentInspectorContent(tree.nodes.getValue(target.id), tree, facts)
        }
        var editing = TalentBuildEditingState.start(engine)
        assertEquals(0, content(editing.current).requirementGates.single().spentPoints)
        repeat(3) { editing = editing.spend(engine, source.id).editing }
        assertTrue(content(editing.current).prerequisites.single().met)
        assertNotNull(content(editing.current).tierLine)
        val refund = editing.refund(engine, source.id)
        assertTrue(refund.changed)
        assertFalse(content(refund.editing.current).prerequisites.single().met)
        assertEquals(2, content(refund.editing.current).requirementGates.single().spentPoints)
        // Gate satisfied while Improved Rend is still absent.
        editing = TalentBuildEditingState.start(engine)
        for (name in listOf("Improved Heroic Strike", "Deflection", "Improved Charge")) {
            val id = engine.tree.nodes.single { it.name == name }.id
            while (true) {
                val result = editing.spend(engine, id)
                if (!result.changed) break
                editing = result.editing
            }
        }
        assertEquals(10, content(editing.current).requirementGates.single().spentPoints)
        assertEquals(null, content(editing.current).tierLine)
        assertNotNull(content(editing.current).prerequisiteLine)
        repeat(3) { editing = editing.spend(engine, source.id).editing }
        assertTrue(engine.canSpend(editing.current, target.id).allowed)
        assertTrue(content(editing.current).prerequisites.single().met)
        assertEquals(null, content(editing.current).prerequisiteLine)
    }
}
