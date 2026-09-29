package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNode
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Focused engine invariants on synthetic structures (mirrors the Python unit
 * suite). Real committed Forever structures are exercised by the conformance
 * replay (`AllocationConformanceReplayTest`) and `RealForeverAllocationTest`.
 *
 * EDGE-ACTIVE and EDGE-SUF are implemented as the documented provisional
 * reference hypothesis — these tests pin engine behaviour, not client truth.
 */
class AllocationEngineTest {

    private fun engine(
        nodes: List<TalentNode> = SyntheticTrees.defaultTree(),
        maxPoints: Int = 51,
        pointLimit: Int? = null,
    ): TalentAllocationEngine = TalentAllocationEngine(
        document = SyntheticTrees.document(nodes, maxPoints = maxPoints),
        pointLimit = pointLimit,
    )

    private fun state(vararg allocations: NodeAllocation): BuildState = BuildState.of(1, allocations.toList())

    // ------------------------------------------------------------- 1. empty state

    @Test
    fun emptyStateIsValid() {
        val engine = engine()
        val validation = engine.validate(engine.emptyState())
        assertTrue(validation.valid)
        assertTrue(validation.issues.isEmpty())
        assertEquals(0, validation.spentPoints)
        assertEquals(51, validation.remainingPoints)
        assertEquals(51, validation.pointBudget)
        assertTrue(validation.reachedRanks.isEmpty())
    }

    // ------------------------------------------------------------- 2. simple spend

    @Test
    fun simpleSpendPurchasesOneRankAndReturnsANewState() {
        val engine = engine()
        val empty = engine.emptyState()

        assertTrue(engine.canSpend(empty, 1).allowed)
        val result = engine.spend(empty, 1)

        assertTrue(result.allowed)
        assertEquals(1, result.state.rankOf(1))
        assertEquals(0, empty.rankOf(1), "the input state must be unchanged")
        assertEquals(1, engine.spentPoints(result.state))
        assertEquals(50, engine.remainingPoints(result.state))
    }

    @Test
    fun omittedEntryResolvesToTheOnlyEntryOfANode() {
        val engine = engine()
        val result = engine.spend(engine.emptyState(), 1)
        assertEquals(1001, result.state.allocationFor(1)?.entryId)
    }

    // ------------------------------------------------------------- 3. max rank

    @Test
    fun maxRankReached() {
        val engine = engine()
        var state = engine.emptyState()
        repeat(3) { state = engine.spend(state, 1).state }

        val decision = engine.canSpend(state, 1)
        assertFalse(decision.allowed)
        assertEquals(listOf("MAX_RANK_REACHED"), decision.reasonCodes)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.MAX_RANK_REACHED, issue.code)
        assertEquals(1, issue.nodeId)
        assertEquals(1001, issue.entryId)
        assertEquals(3, issue.required)
        assertEquals(3, issue.actual)
    }

    // ------------------------------------------------------------- 4. no points remaining

    @Test
    fun noPointsRemainingUnderConfiguredLimit() {
        val engine = engine(pointLimit = 3)
        var state = engine.emptyState()
        repeat(3) { state = engine.spend(state, 1).state }
        assertEquals(0, engine.remainingPoints(state))

        val decision = engine.canSpend(state, 2)
        assertFalse(decision.allowed)
        assertEquals(listOf("NO_POINTS_REMAINING"), decision.reasonCodes)
        val issue = decision.issues.single()
        assertEquals(2, issue.nodeId)
        assertEquals(1, issue.required)
        assertEquals(0, issue.actual)
    }

    // ------------------------------------------------------------- 5. tier gates

    @Test
    fun gateNotMet() {
        val engine = engine()
        val decision = engine.canSpend(engine.emptyState(), 3)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.TIER_GATE_NOT_MET, issue.code)
        assertEquals(3, issue.nodeId)
        assertEquals(5, issue.required)
        assertEquals(0, issue.actual)
    }

    @Test
    fun gateMetByPointsSpentInCountedNodesOnly() {
        val engine = engine()
        // 4 points in the counted nodes are not enough (GATE-1, evaluated on the
        // pre-purchase state).
        var state = state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 1))
        assertFalse(engine.canSpend(state, 3).allowed)

        state = state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 2))
        assertTrue(engine.canSpend(state, 3).allowed)
    }

    // ------------------------------------------------------------- 6. sufficient prerequisite

    @Test
    fun sufficientPrerequisiteRequiresMaxRank() {
        val engine = engine()
        // node 4 needs node 3 fully ranked (EDGE-ACTIVE: 1/2 is not enough).
        var state = state(
            SyntheticTrees.allocation(1, 3),
            SyntheticTrees.allocation(2, 2),
            SyntheticTrees.allocation(3, 1),
        )
        val decision = engine.canSpend(state, 4)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET, issue.code)
        assertEquals(4, issue.nodeId)
        assertEquals(listOf(3), issue.relatedNodeIds)

        state = state(
            SyntheticTrees.allocation(1, 3),
            SyntheticTrees.allocation(2, 2),
            SyntheticTrees.allocation(3, 2),
        )
        assertTrue(engine.canSpend(state, 4).allowed)
    }

    @Test
    fun anyOfSeveralSufficientPrerequisitesSuffices() {
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(2, maxRank = 1, prerequisites = listOf(SyntheticTrees.sufficient(1))),
            SyntheticTrees.node(3, maxRank = 1, prerequisites = listOf(SyntheticTrees.sufficient(1))),
            SyntheticTrees.node(
                4,
                maxRank = 1,
                prerequisites = listOf(SyntheticTrees.sufficient(2), SyntheticTrees.sufficient(3)),
            ),
        )
        val engine = engine(nodes)
        val bothRoots = state(SyntheticTrees.allocation(1, 3))
        val decision = engine.canSpend(bothRoots, 4)
        assertFalse(decision.allowed)
        assertEquals(listOf(2, 3), decision.issues.single().relatedNodeIds)

        assertTrue(engine.canSpend(state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 1)), 4).allowed)
    }

    // ------------------------------------------------------------- 7. required prerequisite

    @Test
    fun requiredPrerequisiteMustBeFullyRanked() {
        val engine = engine()
        var state = state(SyntheticTrees.allocation(1, 2))
        val decision = engine.canSpend(state, 5)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET, issue.code)
        assertEquals(5, issue.nodeId)
        assertEquals(listOf(1), issue.relatedNodeIds)
        assertEquals(3, issue.required)
        assertEquals(2, issue.actual)

        state = state(SyntheticTrees.allocation(1, 3))
        assertTrue(engine.canSpend(state, 5).allowed)
    }

    @Test
    fun allRequiredPrerequisitesMustBeActive() {
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(2, maxRank = 3),
            SyntheticTrees.node(
                3,
                maxRank = 1,
                prerequisites = listOf(SyntheticTrees.required(1), SyntheticTrees.required(2)),
            ),
        )
        val engine = engine(nodes)
        val decision = engine.canSpend(state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 2)), 3)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(3, issue.nodeId)
        assertEquals(listOf(2), issue.relatedNodeIds, "only the inactive source is reported")
        assertEquals(3, issue.required)
        assertEquals(2, issue.actual)

        val bothMaxed = state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 3))
        assertTrue(engine.canSpend(bothMaxed, 3).allowed)
    }

    // ------------------------------------------------------------- 8. mixed edges

    @Test
    fun mixedRequiredAndSufficientEdgesFailClosed() {
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(2, maxRank = 3),
            SyntheticTrees.node(
                3,
                maxRank = 1,
                prerequisites = listOf(SyntheticTrees.required(1), SyntheticTrees.sufficient(2)),
            ),
        )
        val engine = engine(nodes)

        val decision = engine.canSpend(state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 3)), 3)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.UNSUPPORTED_EDGE_COMBINATION, issue.code)
        assertEquals(3, issue.nodeId)
        assertEquals(listOf(1, 2), issue.relatedNodeIds)

        // Never purchasable, whatever the state: the import of the node is invalid too.
        val validation = engine.validate(state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(3, 1)))
        assertFalse(validation.valid)
        assertEquals(listOf("UNSUPPORTED_EDGE_COMBINATION"), validation.reasonCodes)
        assertEquals(setOf(3), engine.unsupportedNodes().keys)
    }

    // ------------------------------------------------------------- 9. unresolved conditions

    @Test
    fun unresolvedConditionFailsClosedEvenWhenEveryProvenRuleIsMet() {
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(
                2,
                maxRank = 1,
                prerequisites = listOf(SyntheticTrees.sufficient(1)),
                unresolvedConditionIds = listOf(50984),
            ),
        )
        val engine = engine(nodes)

        val decision = engine.canSpend(state(SyntheticTrees.allocation(1, 3)), 2)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.UNRESOLVED_CONDITION, issue.code)
        assertEquals(2, issue.nodeId)
        assertEquals(50984, issue.conditionId)

        val validation = engine.validate(state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 1)))
        assertFalse(validation.valid)
        assertEquals(listOf("UNRESOLVED_CONDITION"), validation.reasonCodes)
    }

    // ------------------------------------------------------------- 10. unsupported shapes

    @Test
    fun unsupportedNodeTypeFailsClosed() {
        val engine = engine(listOf(SyntheticTrees.node(1, type = TalentNodeType.Tiered)))
        val decision = engine.canSpend(engine.emptyState(), 1)
        assertEquals(listOf("UNSUPPORTED_NODE_TYPE"), decision.reasonCodes)
        assertNull(decision.issues.single().actual)
    }

    @Test
    fun singleNodeWithSeveralEntriesFailsClosed() {
        val node = SyntheticTrees.node(
            1,
            entries = listOf(SyntheticTrees.entry(1, 1, entryId = 1001), SyntheticTrees.entry(1, 1, entryId = 1002)),
        )
        val engine = engine(listOf(node))

        // without an entry the request cannot even be resolved (ENTRY-1)
        assertEquals(
            listOf("ENTRY_NOT_SELECTED"),
            engine.canSpend(engine.emptyState(), 1).reasonCodes,
        )

        // with a resolved entry the node itself is an unsupported shape
        val decision = engine.canSpend(engine.emptyState(), 1, entryId = 1001)
        assertEquals(listOf("UNSUPPORTED_NODE_TYPE"), decision.reasonCodes)
        assertEquals(2, decision.issues.single().actual)
    }

    @Test
    fun unsupportedEntryTypeFailsClosed() {
        val node = SyntheticTrees.node(1, entries = listOf(SyntheticTrees.entry(1, 1, entryType = 3)))
        val engine = engine(listOf(node))
        val decision = engine.canSpend(engine.emptyState(), 1)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.UNSUPPORTED_ENTRY_TYPE, issue.code)
        assertEquals(1001, issue.entryId)
        assertEquals(3, issue.actual)
    }

    @Test
    fun neverPurchasableFlagFailsClosedAndUnknownFlagsFailClosed() {
        val never = engine(listOf(SyntheticTrees.node(1, flags = 2)))
        assertEquals(listOf("NODE_NEVER_PURCHASABLE"), never.canSpend(never.emptyState(), 1).reasonCodes)

        val unknown = engine(listOf(SyntheticTrees.node(1, flags = 16)))
        val decision = unknown.canSpend(unknown.emptyState(), 1)
        assertEquals(listOf("UNSUPPORTED_NODE_FLAGS"), decision.reasonCodes)
        assertEquals(16, decision.issues.single().actual)

        // The editor flag 8 (TestGridPositioned) is allocation-neutral.
        val editor = engine(listOf(SyntheticTrees.node(1, flags = 8)))
        assertTrue(editor.canSpend(editor.emptyState(), 1).allowed)

        // NeverPurchasable is reported once even when combined with unknown bits.
        val both = engine(listOf(SyntheticTrees.node(1, flags = 2 + 16)))
        assertEquals(
            listOf("NODE_NEVER_PURCHASABLE", "UNSUPPORTED_NODE_FLAGS"),
            both.canSpend(both.emptyState(), 1).reasonCodes,
        )
    }

    // ------------------------------------------------------------- 11. invalid entry

    @Test
    fun entryOutsideTheNodeIsInvalid() {
        val engine = engine()
        val decision = engine.canSpend(engine.emptyState(), 1, entryId = 9999)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.INVALID_ENTRY, issue.code)
        assertEquals(1, issue.nodeId)
        assertEquals(9999, issue.entryId)
    }

    @Test
    fun multiEntryNodeWithoutSelectedEntryFailsClosed() {
        val node = SyntheticTrees.node(
            1,
            entries = listOf(SyntheticTrees.entry(1, 1, entryId = 1001), SyntheticTrees.entry(1, 1, entryId = 1002)),
        )
        val engine = engine(listOf(node))
        val decision = engine.canSpend(engine.emptyState(), 1)
        assertFalse(decision.allowed)
        assertEquals(listOf("ENTRY_NOT_SELECTED"), decision.reasonCodes)
    }

    // ------------------------------------------------------------- 12./13. refunds

    @Test
    fun refundBreakingADependentIsRefusedWithTheBreakingRuleAsCause() {
        val engine = engine()
        val state = state(
            SyntheticTrees.allocation(1, 3),
            SyntheticTrees.allocation(2, 2),
            SyntheticTrees.allocation(3, 2),
            SyntheticTrees.allocation(4, 1),
        )

        val decision = engine.canRefund(state, 3)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK, issue.code)
        assertEquals(4, issue.nodeId)
        assertEquals(listOf(3), issue.relatedNodeIds)
        assertEquals(AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET, issue.cause)
    }

    @Test
    fun refundIsAllowedOnceTheDependentIsRemoved() {
        val engine = engine()
        val state = state(
            SyntheticTrees.allocation(1, 3),
            SyntheticTrees.allocation(2, 2),
            SyntheticTrees.allocation(3, 2),
            SyntheticTrees.allocation(4, 1),
        )

        val afterDependent = engine.refund(state, 4)
        assertTrue(afterDependent.allowed)
        assertEquals(0, afterDependent.state.rankOf(4))

        val afterPrerequisite = engine.refund(afterDependent.state, 3)
        assertTrue(afterPrerequisite.allowed)
        assertEquals(1, afterPrerequisite.state.rankOf(3))
    }

    @Test
    fun refundThatWouldDropATierGateReportsTierGateCauseWithNumbers() {
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(2, maxRank = 2, unlockRequirements = listOf(SyntheticTrees.gate(3, listOf(1)))),
        )
        val engine = engine(nodes)
        val state = state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 1))

        val decision = engine.canRefund(state, 1)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK, issue.code)
        assertEquals(2, issue.nodeId)
        assertEquals(listOf(1), issue.relatedNodeIds)
        assertEquals(AllocationIssueCode.TIER_GATE_NOT_MET, issue.cause)
        assertEquals(3, issue.required)
        assertEquals(2, issue.actual)
    }

    @Test
    fun refundOfAnUnallocatedNodeIsRejected() {
        val engine = engine()
        val decision = engine.canRefund(engine.emptyState(), 1)
        assertFalse(decision.allowed)
        val issue = decision.issues.single()
        assertEquals(AllocationIssueCode.NOT_ALLOCATED, issue.code)
        assertEquals(1, issue.nodeId)
        assertEquals(0, issue.actual)
    }

    @Test
    fun refundsNeverCascadeDeleteDependents() {
        val engine = engine()
        val state = state(
            SyntheticTrees.allocation(1, 3),
            SyntheticTrees.allocation(2, 2),
            SyntheticTrees.allocation(3, 2),
            SyntheticTrees.allocation(4, 1),
        )
        val result = engine.refund(state, 3)
        assertFalse(result.allowed)
        // the refused refund leaves node 4 in place — no cascade
        assertEquals(1, result.state.rankOf(4))
        assertEquals(2, result.state.rankOf(3))
    }

    // ------------------------------------------------------------- 14./15. corrupt imports

    @Test
    fun importedRankOutOfBoundsIsReported() {
        val engine = engine()

        val tooHigh = engine.validate(state(SyntheticTrees.allocation(1, 4)))
        assertFalse(tooHigh.valid)
        val issue = tooHigh.issues.single()
        assertEquals(AllocationIssueCode.RANK_OUT_OF_BOUNDS, issue.code)
        assertEquals(1, issue.nodeId)
        assertEquals(1001, issue.entryId)
        assertEquals(3, issue.required)
        assertEquals(4, issue.actual)

        val zero = engine.validate(state(SyntheticTrees.allocation(1, 0)))
        assertEquals(listOf("RANK_OUT_OF_BOUNDS"), zero.reasonCodes)
        assertEquals(0, zero.issues.single().actual)
    }

    @Test
    fun duplicateAllocationIsReported() {
        val engine = engine()
        val validation = engine.validate(
            state(
                SyntheticTrees.allocation(1, 1),
                SyntheticTrees.allocation(1, 2),
            ),
        )
        assertFalse(validation.valid)
        val issue = validation.issues.single()
        assertEquals(AllocationIssueCode.DUPLICATE_ALLOCATION, issue.code)
        assertEquals(1, issue.nodeId)
        // ranks are raw state data: both positive ranks still count as spent
        assertEquals(3, validation.spentPoints)
    }

    @Test
    fun unknownNodeInABuildIsReported() {
        val engine = engine()
        val validation = engine.validate(state(SyntheticTrees.allocation(99, 1)))
        assertFalse(validation.valid)
        val issue = validation.issues.single()
        assertEquals(AllocationIssueCode.UNKNOWN_NODE, issue.code)
        assertEquals(99, issue.nodeId)
    }

    @Test
    fun buildForAnotherTreeIsRejectedBeforeAnythingElse() {
        val engine = engine()
        val validation = engine.validate(
            BuildState.of(999, listOf(SyntheticTrees.allocation(99, 7), SyntheticTrees.allocation(1, 9))),
        )
        assertFalse(validation.valid)
        val issue = validation.issues.single()
        assertEquals(AllocationIssueCode.UNKNOWN_TREE, issue.code)
        assertEquals(1, issue.required)
        assertEquals(999, issue.actual)
    }

    // ------------------------------------------------------------- 16. point limits

    @Test
    fun pointLimitBelowMaxPointsIsTheUsableBudget() {
        val engine = engine(pointLimit = 5)
        assertEquals(5, engine.pointBudget)

        val overBudget = engine.validate(
            state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 3)),
        )
        assertFalse(overBudget.valid)
        val issue = overBudget.issues.single()
        assertEquals(AllocationIssueCode.POINT_BUDGET_EXCEEDED, issue.code)
        assertEquals(5, issue.required)
        assertEquals(6, issue.actual)
        assertEquals(0, overBudget.remainingPoints, "remaining points are never negative")

        val within = engine.validate(state(SyntheticTrees.allocation(1, 3), SyntheticTrees.allocation(2, 2)))
        assertTrue(within.valid)
        assertEquals(5, within.spentPoints)
        assertEquals(0, within.remainingPoints)
    }

    @Test
    fun invalidPointLimitsAreRejected() {
        val nodes = SyntheticTrees.defaultTree()
        assertFailsWith<AllocationEngineException> {
            engine(nodes, pointLimit = -1)
        }
        assertFailsWith<AllocationEngineException> {
            engine(nodes, pointLimit = 52)
        }
        assertEquals(51, engine(nodes).pointBudget)
        assertEquals(0, engine(nodes, pointLimit = 0).pointBudget)
    }

    // ------------------------------------------------------------- 17. ordering

    @Test
    fun issueOrderingIsCodeDeclarationOrderThenStructuredFields() {
        // Corrupt import mixing structural problems: duplicate, rank out of
        // bounds, unresolved condition and an over-budget target total.
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(2, maxRank = 2, unresolvedConditionIds = listOf(50984)),
            SyntheticTrees.node(3, maxRank = 3),
        )
        val corrupt = engine(nodes, pointLimit = 2)
        val validation = corrupt.validate(
            state(
                SyntheticTrees.allocation(1, 1),
                SyntheticTrees.allocation(1, 2),
                SyntheticTrees.allocation(2, 2),
                SyntheticTrees.allocation(3, 9),
            ),
        )
        assertFalse(validation.valid)
        assertEquals(
            listOf("DUPLICATE_ALLOCATION", "RANK_OUT_OF_BOUNDS", "UNRESOLVED_CONDITION", "POINT_BUDGET_EXCEEDED"),
            validation.issues.map { it.code.name },
            "issues order by code declaration order, then node id",
        )
        assertEquals(
            listOf("DUPLICATE_ALLOCATION", "POINT_BUDGET_EXCEEDED", "RANK_OUT_OF_BOUNDS", "UNRESOLVED_CONDITION"),
            validation.reasonCodes,
            "reasonCodes are sorted distinct code values (alphabetical), not declaration order",
        )
    }

    @Test
    fun issueCodesKeepTheReferenceWireNamesInDeclarationOrder() {
        assertEquals(
            listOf(
                "UNKNOWN_TREE", "UNKNOWN_NODE", "INVALID_ENTRY", "ENTRY_NOT_SELECTED",
                "DUPLICATE_ALLOCATION", "RANK_OUT_OF_BOUNDS", "INVALID_BUILD_STATE",
                "UNSUPPORTED_NODE_TYPE", "UNSUPPORTED_ENTRY_TYPE", "UNSUPPORTED_NODE_FLAGS",
                "UNSUPPORTED_EDGE_COMBINATION", "UNRESOLVED_CONDITION", "NODE_NEVER_PURCHASABLE",
                "MAX_RANK_REACHED", "NOT_ALLOCATED", "NO_POINTS_REMAINING", "POINT_BUDGET_EXCEEDED",
                "TIER_GATE_NOT_MET", "REQUIRED_PREREQUISITE_NOT_MET", "SUFFICIENT_PREREQUISITE_NOT_MET",
                "DEPENDENT_NODE_WOULD_BREAK",
            ),
            AllocationIssueCode.entries.map { it.name },
        )
    }

    @Test
    fun duplicateIssuesCollapseAndIssuesSortCanonically() {
        val issues = listOf(
            AllocationIssue(AllocationIssueCode.TIER_GATE_NOT_MET, "n2", nodeId = 2, required = 5, actual = 1),
            AllocationIssue(AllocationIssueCode.TIER_GATE_NOT_MET, "n1", nodeId = 1, required = 5, actual = 1),
            AllocationIssue(AllocationIssueCode.TIER_GATE_NOT_MET, "n2", nodeId = 2, required = 5, actual = 1),
            AllocationIssue(AllocationIssueCode.UNKNOWN_NODE, "z", nodeId = 7),
        )
        val sorted = sortIssues(issues)
        assertEquals(3, sorted.size, "the exact duplicate collapses")
        assertEquals(listOf(7, 1, 2), sorted.map { it.nodeId }, "code order first, then node id")
        assertEquals("n2", sorted[2].message)
    }

    // ------------------------------------------------------------- 18. refused actions

    @Test
    fun refusedActionsLeaveTheStateUnchanged() {
        val engine = engine()
        val state = state(
            SyntheticTrees.allocation(1, 3),
            SyntheticTrees.allocation(2, 2),
            SyntheticTrees.allocation(3, 2),
            SyntheticTrees.allocation(4, 1),
        )

        val refusedSpend = engine.spend(state, 1)
        assertFalse(refusedSpend.allowed)
        assertSame(state, refusedSpend.state, "a refused spend returns the identical state instance")

        val refusedRefund = engine.refund(state, 3)
        assertFalse(refusedRefund.allowed)
        assertSame(state, refusedRefund.state, "a refused refund returns the identical state instance")

        val allowedSpend = engine.spend(state, 2)
        assertTrue(allowedSpend.allowed)
        assertNotSame(state, allowedSpend.state)
    }

    // ------------------------------------------------------------- invalid states

    @Test
    fun actionsOnAnInvalidStateAreRefused() {
        val engine = engine()
        val invalid = state(SyntheticTrees.allocation(1, 9))

        for (decision in listOf(engine.canSpend(invalid, 2), engine.canRefund(invalid, 1))) {
            assertFalse(decision.allowed)
            val issue = assertIs<AllocationIssue>(decision.issues.single())
            assertEquals(AllocationIssueCode.INVALID_BUILD_STATE, issue.code)
            assertNull(issue.nodeId)
        }
    }

    // ------------------------------------------------------------- validity: reachability

    @Test
    fun selfSupportingSufficientCycleWithoutARootIsInvalid() {
        // Hunter Bestial Wrath <-> Intimidation shape: 2 and 3 support each
        // other; only node 1 roots the cycle.
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(2, maxRank = 1, prerequisites = listOf(SyntheticTrees.sufficient(3))),
            SyntheticTrees.node(
                3,
                maxRank = 1,
                prerequisites = listOf(SyntheticTrees.sufficient(1), SyntheticTrees.sufficient(2)),
            ),
        )
        val engine = engine(nodes)
        val cycleOnly = engine.validate(state(SyntheticTrees.allocation(2, 1), SyntheticTrees.allocation(3, 1)))
        assertFalse(cycleOnly.valid, "a cycle cannot support itself (VALID-1)")
        assertEquals(
            listOf("SUFFICIENT_PREREQUISITE_NOT_MET", "SUFFICIENT_PREREQUISITE_NOT_MET"),
            cycleOnly.issues.map { it.code.name },
        )
        assertEquals(setOf(2, 3), cycleOnly.issues.map { it.nodeId }.toSet())

        // rooted through node 1: purchases reach the cycle, the plan is valid
        val rooted = engine.validate(
            state(
                SyntheticTrees.allocation(1, 3),
                SyntheticTrees.allocation(2, 1),
                SyntheticTrees.allocation(3, 1),
            ),
        )
        assertTrue(rooted.valid)
    }

    @Test
    fun gateIsEvaluatedBeforeThePurchaseAndMayCountTheNodeItself() {
        // Capstone-style gate: 2 points among {node 1, node 2} — node 2's own
        // rank cannot satisfy its own gate (evaluated before the purchase).
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(2, maxRank = 1, unlockRequirements = listOf(SyntheticTrees.gate(2, listOf(1, 2)))),
        )
        val engine = engine(nodes)

        val selfCounting = engine.validate(state(SyntheticTrees.allocation(1, 1), SyntheticTrees.allocation(2, 1)))
        assertFalse(selfCounting.valid)
        val issue = selfCounting.issues.single()
        assertEquals(AllocationIssueCode.TIER_GATE_NOT_MET, issue.code)
        assertEquals(2, issue.required)
        assertEquals(1, issue.actual)

        assertTrue(engine.validate(state(SyntheticTrees.allocation(1, 2), SyntheticTrees.allocation(2, 1))).valid)
    }

    @Test
    fun finalStateSatisfactionIsNotValidity() {
        // In its final state node 4 sees node 3 fully ranked, but the plan
        // cannot be purchased rank by rank: node 3's gate needs 5 points that
        // the plan never had. A final-state-only check would accept it.
        val engine = engine()
        val unreachable = engine.validate(
            state(SyntheticTrees.allocation(2, 2), SyntheticTrees.allocation(3, 2), SyntheticTrees.allocation(4, 1)),
        )
        assertFalse(unreachable.valid)
        assertEquals(
            listOf("SUFFICIENT_PREREQUISITE_NOT_MET", "TIER_GATE_NOT_MET"),
            unreachable.reasonCodes,
        )
        val gateIssue = unreachable.issues.first { it.code == AllocationIssueCode.TIER_GATE_NOT_MET }
        assertEquals(3, gateIssue.nodeId)
        assertEquals(5, gateIssue.required)
        assertEquals(2, gateIssue.actual)
    }

    @Test
    fun canSpendCollectsEveryRelevantIssueWithoutShortCircuiting() {
        // Node 4 is unallocated with a gate and a sufficient prerequisite, and
        // the budget is exhausted: three independent issues in one decision.
        val nodes = listOf(
            SyntheticTrees.node(1, maxRank = 3),
            SyntheticTrees.node(2, maxRank = 3),
            SyntheticTrees.node(3, maxRank = 2, unlockRequirements = listOf(SyntheticTrees.gate(5, listOf(1, 2)))),
            SyntheticTrees.node(
                4,
                maxRank = 1,
                prerequisites = listOf(SyntheticTrees.sufficient(3)),
                unlockRequirements = listOf(SyntheticTrees.gate(6, listOf(1, 2))),
            ),
        )
        val engine = engine(nodes, pointLimit = 6)
        val state = state(
            SyntheticTrees.allocation(1, 3),
            SyntheticTrees.allocation(2, 2),
            SyntheticTrees.allocation(3, 1),
        )
        assertTrue(engine.validate(state).valid)

        val decision = engine.canSpend(state, 4)
        assertFalse(decision.allowed)
        assertEquals(
            listOf("NO_POINTS_REMAINING", "TIER_GATE_NOT_MET", "SUFFICIENT_PREREQUISITE_NOT_MET"),
            decision.issues.map { it.code.name },
        )

        // An allocated node at max rank with the budget exhausted collects both
        // of its issues as well.
        val maxed = engine.canSpend(state, 1)
        assertEquals(
            listOf("MAX_RANK_REACHED", "NO_POINTS_REMAINING"),
            maxed.issues.map { it.code.name },
        )
    }

    // ------------------------------------------------------------- construction

    @Test
    fun constructorFailsClosedOnStructurallyInvalidDocuments() {
        fun failureCodes(nodes: List<TalentNode>): String =
            assertFailsWith<AllocationEngineException> {
                engine(nodes)
            }.message.orEmpty()

        assertTrue("DANGLING_PREREQUISITE" in failureCodes(listOf(
            SyntheticTrees.node(1, prerequisites = listOf(SyntheticTrees.required(99))),
        )))
        assertTrue("SELF_PREREQUISITE" in failureCodes(listOf(
            SyntheticTrees.node(1, prerequisites = listOf(SyntheticTrees.required(1))),
        )))
        assertTrue("NODE_WITHOUT_ENTRIES" in failureCodes(listOf(
            SyntheticTrees.node(1, entries = emptyList()),
        )))
        assertTrue("INVALID_MAX_RANK" in failureCodes(listOf(SyntheticTrees.node(1, maxRank = 0))))
        assertTrue("DUPLICATE_NODE_ID" in failureCodes(listOf(
            SyntheticTrees.node(1),
            SyntheticTrees.node(1),
        )))
        assertTrue("INVALID_ENTRY_MAX_RANK" in failureCodes(listOf(
            SyntheticTrees.node(1, entries = listOf(SyntheticTrees.entry(1, 0))),
        )))
        assertTrue("UNLOCK_COUNTS_UNKNOWN_NODES" in failureCodes(listOf(
            SyntheticTrees.node(1, unlockRequirements = listOf(SyntheticTrees.gate(1, listOf(99)))),
        )))
        assertTrue("INVALID_UNLOCK_POINTS" in failureCodes(listOf(
            SyntheticTrees.node(1, unlockRequirements = listOf(SyntheticTrees.gate(0, listOf(1)))),
        )))
        assertTrue("REQUIRED_PREREQUISITE_CYCLE" in failureCodes(listOf(
            SyntheticTrees.node(1, prerequisites = listOf(SyntheticTrees.required(2))),
            SyntheticTrees.node(2, prerequisites = listOf(SyntheticTrees.required(1))),
        )))
    }

    @Test
    fun sufficientPrerequisiteCyclesDoNotBlockConstruction() {
        // Hunter Bestial Wrath <-> Intimidation shape: a sufficient-only cycle
        // is not a deadlock and stays allocatable.
        val nodes = listOf(
            SyntheticTrees.node(1, prerequisites = listOf(SyntheticTrees.sufficient(2))),
            SyntheticTrees.node(2, prerequisites = listOf(SyntheticTrees.sufficient(1))),
        )
        val engine = engine(nodes)
        assertTrue(engine.validate(engine.emptyState()).valid)
    }

    @Test
    fun multiTreeDocumentsNeedAnExplicitTreeId() {
        val tree = { id: Int ->
            io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree(
                id = id,
                pointBudget = io.github.alexeykravtsov95.talentforge.data.talentdata.PointBudget(3820, 51),
                layout = io.github.alexeykravtsov95.talentforge.data.talentdata.TalentLayout(10, 10),
                sections = emptyList(),
                nodes = listOf(SyntheticTrees.node(id)),
                visualOnlyEdges = emptyList(),
            )
        }
        val document = SyntheticTrees.document(emptyList(), trees = listOf(tree(1), tree(2)))

        assertFailsWith<AllocationEngineException> { TalentAllocationEngine(document) }
        val engine = TalentAllocationEngine(document, treeId = 2)
        assertEquals(2, engine.tree.id)
        assertFailsWith<AllocationEngineException> { TalentAllocationEngine(document, treeId = 3) }
    }

    @Test
    fun treeWithoutAPointBudgetFailsClosed() {
        val base = SyntheticTrees.document(SyntheticTrees.defaultTree())
        val document = base.copy(
            trees = listOf(base.trees[0].copy(pointBudget = null)),
        )
        assertFailsWith<AllocationEngineException> { TalentAllocationEngine(document) }
    }

    @Test
    fun unsupportedNodesListTheFailClosedInventory() {
        val nodes = listOf(
            SyntheticTrees.node(1),
            SyntheticTrees.node(2, unresolvedConditionIds = listOf(7)),
            SyntheticTrees.node(3, flags = 2),
        )
        val engine = engine(nodes)
        assertEquals(setOf(2, 3), engine.unsupportedNodes().keys)
        assertEquals(
            listOf(AllocationIssueCode.UNRESOLVED_CONDITION),
            engine.unsupportedNodes().getValue(2).map { it.code },
        )
        assertEquals(
            listOf(AllocationIssueCode.NODE_NEVER_PURCHASABLE),
            engine.unsupportedNodes().getValue(3).map { it.code },
        )
    }
}
