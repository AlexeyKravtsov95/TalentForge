package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceMutation
import io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceMutationOp
import io.github.alexeykravtsov95.talentforge.data.conformance.MutationValue
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Executor-level mutation semantics the committed 53 scenarios do not
 * exercise: every schema-legal `Mutation.value` form (integer | string | null)
 * must behave exactly like the Python reference, and integer mutations must
 * never silently narrow a Long to a wrapped Int.
 */
class AllocationConformanceMutationTest {

    private fun document() = SyntheticTrees.document(SyntheticTrees.defaultTree())

    private fun mutation(
        op: ConformanceMutationOp,
        value: MutationValue?,
        nodeId: Int = 1,
    ): ConformanceMutation = ConformanceMutation(op = op, nodeId = nodeId, value = value)

    private fun mutatedNodeType(value: MutationValue?): TalentNodeType =
        AllocationConformance.applyMutations(document(), listOf(mutation(ConformanceMutationOp.SET_NODE_TYPE, value)))
            .trees[0]
            .nodes
            .first { it.id == 1 }
            .type

    @Test
    fun setNodeTypeAcceptsEverySchemaValueFormLikeTheReference() {
        // string form — the shape the committed suite uses
        assertEquals(TalentNodeType.Selection, mutatedNodeType(MutationValue.TextValue("selection")))
        assertEquals(TalentNodeType.Tiered, mutatedNodeType(MutationValue.TextValue("tiered")))

        // integer form: the reference stores str(value), e.g. str(7) == "7"
        assertEquals(TalentNodeType.Unknown("7"), mutatedNodeType(MutationValue.IntegralValue(7)))
        assertEquals(
            TalentNodeType.Unknown("7"),
            mutatedNodeType(MutationValue.TextValue("7")),
            "integer and string forms produce the same node type",
        )

        // null form: the reference stores str(None) == "None" — never a crash
        assertEquals(TalentNodeType.Unknown("None"), mutatedNodeType(value = null))
    }

    @Test
    fun setNodeTypeWithNullFollowsTheUnsupportedNodePath() {
        val mutated = AllocationConformance.applyMutations(
            document(),
            listOf(mutation(ConformanceMutationOp.SET_NODE_TYPE, value = null)),
        )
        val engine = TalentAllocationEngine(mutated)

        assertTrue(engine.unsupportedNodes().containsKey(1))
        assertEquals(
            listOf("UNSUPPORTED_NODE_TYPE"),
            engine.canSpend(engine.emptyState(), 1).reasonCodes,
        )
        // the import of a plan containing the mutated node is invalid, like any
        // other unsupported shape
        val validation = engine.validate(
            BuildState.of(1, listOf(SyntheticTrees.allocation(1, 1))),
        )
        assertEquals(listOf("UNSUPPORTED_NODE_TYPE"), validation.reasonCodes)
    }

    @Test
    fun integerMutationsOutsideTheIntRangeFailClosed() {
        val outOfRange = listOf(
            MutationValue.IntegralValue(Int.MAX_VALUE.toLong() + 1), // 2147483648
            MutationValue.IntegralValue(Int.MIN_VALUE.toLong() - 1), // -2147483649
        )
        val ops = listOf(
            ConformanceMutationOp.SET_NODE_FLAGS,
            ConformanceMutationOp.SET_ENTRY_TYPE,
            ConformanceMutationOp.ADD_UNRESOLVED_CONDITION,
        )
        for (op in ops) {
            for (value in outOfRange) {
                assertFailsWith<IllegalArgumentException>(
                    message = "$op must fail closed on $value instead of narrowing it",
                ) {
                    AllocationConformance.applyMutations(document(), listOf(mutation(op, value)))
                }
            }
        }
    }

    @Test
    fun integerMutationsAtTheIntBoundariesConvertExactly() {
        val maxFlags = AllocationConformance.applyMutations(
            document(),
            listOf(mutation(ConformanceMutationOp.SET_NODE_FLAGS, MutationValue.IntegralValue(Int.MAX_VALUE.toLong()))),
        )
        assertEquals(Int.MAX_VALUE, maxFlags.trees[0].nodes.first { it.id == 1 }.flags)

        val minFlags = AllocationConformance.applyMutations(
            document(),
            listOf(mutation(ConformanceMutationOp.SET_NODE_FLAGS, MutationValue.IntegralValue(Int.MIN_VALUE.toLong()))),
        )
        assertEquals(Int.MIN_VALUE, minFlags.trees[0].nodes.first { it.id == 1 }.flags)

        // the engine reports the exact flag value, not a wrapped one
        val engine = TalentAllocationEngine(maxFlags)
        val flagsIssue = engine.canSpend(engine.emptyState(), 1).issues
            .first { it.code == AllocationIssueCode.UNSUPPORTED_NODE_FLAGS }
        assertEquals(Int.MAX_VALUE, flagsIssue.actual)
    }
}
