package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.BUNDLED_CLIENT_BUILDS
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Runs unchanged on JVM and the iOS simulator, using packaged real datasets. */
class TalentRequirementsPlatformTest {
    @Test
    fun everyBundledClassKeepsGameplayRequirementsSeparateFromVisualEdges() = runTest {
        val repository = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
        for (clientBuild in BUNDLED_CLIENT_BUILDS) {
            for (token in listOf("WARRIOR", "PALADIN", "HUNTER", "ROGUE", "PRIEST", "SHAMAN", "MAGE", "WARLOCK", "DRUID")) {
                val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
                    repository.document(DatasetId(PRODUCT_BUNDLE.game, clientBuild, ClassToken(token))),
                ).value
                val engine = TalentAllocationEngine(document)
                val state = engine.emptyState()
                val tree = buildTalentTreePresentation(engine, state)
                val facts = talentNodeFacts(engine)
                for (node in engine.tree.nodes) {
                    val content = buildTalentInspectorContent(tree.nodes.getValue(node.id), tree, facts)
                    assertEquals(node.prerequisites.map { it.nodeId }, content.prerequisites.map { it.source.nodeId })
                    assertEquals(node.unlockRequirements.map { it.requiredPoints }, content.requirementGates.map { it.requiredPoints })
                    assertTrue(content.requirementGates.all { it.spentPoints == 0 })
                    assertTrue(content.prerequisites.all { !it.met })
                }
                if (token == "HUNTER") {
                    val node = engine.tree.nodes.single { it.name == "Intimidation" }
                    val content = buildTalentInspectorContent(tree.nodes.getValue(node.id), tree, facts)
                    assertEquals(InspectorText.RequiresPrerequisites(2, anyOf = true), content.prerequisiteLine)
                    assertEquals(setOf("Bestial Wrath", "Bestial Swiftness"), content.prerequisites.map { it.source.published }.toSet())
                    // With one alternative completed the node opens even though the other remains incomplete.
                    val section = engine.tree.sections.single { node.id in it.nodeIds }
                    var editing = TalentBuildEditingState.start(engine)
                    while (!engine.canSpend(editing.current, node.id).allowed) {
                        var changed = false
                        for (id in section.nodeIds - node.id) {
                            if (engine.canSpend(editing.current, node.id).allowed) break
                            val result = editing.spend(engine, id)
                            if (result.changed) {
                                editing = result.editing
                                changed = true
                            }
                        }
                        assertTrue(changed, "Intimidation must be reachable through Bestial Swiftness")
                    }
                    val unlockedTree = buildTalentTreePresentation(engine, editing.current)
                    val unlocked = buildTalentInspectorContent(unlockedTree.nodes.getValue(node.id), unlockedTree, facts)
                    assertEquals(TalentNodeState.AVAILABLE, unlockedTree.nodes.getValue(node.id).state)
                    assertEquals(1, unlocked.prerequisites.count { it.met })
                    assertEquals(null, unlocked.prerequisiteLine)
                    assertEquals(null, unlocked.tierLine)
                }
                if (token == "WARRIOR") {
                    val source = engine.tree.nodes.single { it.name == "Improved Rend" }
                    val target = engine.tree.nodes.single { it.name == "Deep Wounds" }
                    var editing = TalentBuildEditingState.start(engine)
                    repeat(3) { editing = editing.spend(engine, source.id).editing }
                    var projection = buildTalentTreePresentation(engine, editing.current)
                    var content = buildTalentInspectorContent(projection.nodes.getValue(target.id), projection, facts)
                    assertTrue(content.prerequisites.single().met)
                    assertNotNull(content.tierLine)
                    assertEquals(3, content.requirementGates.single().spentPoints)
                    editing = editing.refund(engine, source.id).editing
                    projection = buildTalentTreePresentation(engine, editing.current)
                    content = buildTalentInspectorContent(projection.nodes.getValue(target.id), projection, facts)
                    assertFalse(content.prerequisites.single().met)
                    assertEquals(2, content.requirementGates.single().spentPoints)
                }
                assertTrue(state.allocations.isEmpty(), "inspection never mutates the original saved ranks")
            }
        }
    }
}
