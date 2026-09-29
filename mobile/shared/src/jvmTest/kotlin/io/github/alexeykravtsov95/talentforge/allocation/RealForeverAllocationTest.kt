package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Engine invariants pinned on the committed Forever 1.60.1.70009 structures
 * (real datasets, direct engine API — independent of the conformance executor).
 */
class RealForeverAllocationTest {

    private fun documents(): Map<String, TalentDataDocument> =
        TestRepoAssets.classTokens.associate { token ->
            token.uppercase() to TalentDataContract.decodeTalentDataDocument(
                TestRepoAssets.forever70009.resolve("$token.json").readBytes(),
            )
        }

    @Test
    fun everyClassEngineBuildsWithPinnedFailClosedInventory() {
        val documents = documents()
        assertEquals(9, documents.size)
        for ((token, document) in documents) {
            val engine = TalentAllocationEngine(document)
            assertEquals(51, engine.pointBudget, token)
            assertTrue(engine.validate(engine.emptyState()).valid, token)

            val unsupported = engine.unsupportedNodes()
            if (token == "ROGUE") {
                val issues = unsupported.getValue(105712)
                assertEquals(listOf(AllocationIssueCode.UNRESOLVED_CONDITION), issues.map { it.code })
                assertEquals(50984, issues.single().conditionId)
            } else {
                assertTrue(unsupported.isEmpty(), "$token: unexpected unsupported nodes ${unsupported.keys}")
            }
        }
    }

    @Test
    fun warriorSufficientPrerequisiteAndRefundOnRealStructures() {
        // Deep Wounds (105950) needs Improved Rend (105956) fully ranked and 10
        // points in its counted row; Improved Rend is max 3 (EDGE-ACTIVE).
        val engine = TalentAllocationEngine(documents().getValue("WARRIOR"))
        val state = BuildState.of(
            engine.tree.id,
            listOf(
                NodeAllocation(105954, 130684, 3),
                NodeAllocation(105956, 130686, 2),
                NodeAllocation(105957, 130687, 2),
                NodeAllocation(105958, 130688, 3),
            ),
        )
        assertTrue(engine.validate(state).valid)
        assertEquals(10, engine.spentPoints(state))

        val blocked = engine.canSpend(state, 105950)
        assertFalse(blocked.allowed)
        val issue = blocked.issues.single()
        assertEquals(AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET, issue.code)
        assertEquals(105950, issue.nodeId)
        assertEquals(listOf(105956), issue.relatedNodeIds)

        val maxed = engine.spend(state, 105956).state
        assertTrue(engine.canSpend(maxed, 105950).allowed)

        // once Deep Wounds is purchased, refunding Improved Rend would break it
        val withDeepWounds = engine.spend(maxed, 105950).state
        val refundDecision = engine.canRefund(withDeepWounds, 105956)
        assertFalse(refundDecision.allowed)
        val dependent = refundDecision.issues.single()
        assertEquals(AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK, dependent.code)
        assertEquals(105950, dependent.nodeId)
        assertEquals(listOf(105956), dependent.relatedNodeIds)
        assertEquals(AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET, dependent.cause)
    }

    @Test
    fun rogueVenomIsUnplannableEvenWhenEveryProvenRuleIsMet() {
        // Venom (105712) carries unresolved TraitCond 50984: the node is never
        // purchasable and an imported plan containing it is invalid, even with
        // every proven rule of the plan satisfied (31 points, capstone gate met).
        val engine = TalentAllocationEngine(documents().getValue("ROGUE"))
        val spent = BuildState.of(
            engine.tree.id,
            listOf(
                NodeAllocation(105709, 130438, 1),
                NodeAllocation(105714, 130443, 3),
                NodeAllocation(105716, 130445, 5),
                NodeAllocation(105717, 130446, 2),
                NodeAllocation(105720, 130449, 2),
                NodeAllocation(105721, 130450, 3),
                NodeAllocation(105722, 130451, 5),
                NodeAllocation(105723, 130452, 2),
                NodeAllocation(105739, 130468, 3),
                NodeAllocation(105742, 130471, 3),
                NodeAllocation(105759, 130488, 1),
            ),
        )
        assertTrue(engine.validate(spent).valid)
        assertEquals(30, engine.spentPoints(spent))

        val decision = engine.canSpend(spent, 105712)
        assertFalse(decision.allowed)
        assertEquals(listOf("UNRESOLVED_CONDITION"), decision.reasonCodes)
        assertEquals(50984, decision.issues.single().conditionId)

        val withVenom = spent.withAllocation(105712, entryId = 130441, rank = 1)
        val validation = engine.validate(withVenom)
        assertFalse(validation.valid)
        assertEquals(listOf("UNRESOLVED_CONDITION"), validation.reasonCodes)
        assertEquals(31, validation.spentPoints)
    }

    @Test
    fun classTokensDecodeWithStrictContractReader() {
        // The engine consumes documents decoded by the strict M3A reader; the
        // class token identity is preserved end to end.
        for (token in listOf("WARRIOR", "ROGUE", "HUNTER")) {
            val document = documents().getValue(token)
            assertEquals(ClassToken(token), document.classInfo.token)
            assertEquals("forever", document.game.value)
            assertEquals("1.60.1.70009", document.clientBuild)
        }
    }
}
