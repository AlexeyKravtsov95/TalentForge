package io.github.alexeykravtsov95.talentforge.allocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BuildState mechanics: canonical order, lookups and immutable transitions. */
class BuildStateTest {

    @Test
    fun constructorCanonicalizesAllocationOrder() {
        val state = BuildState.of(
            1,
            listOf(
                SyntheticTrees.allocation(3, 1),
                SyntheticTrees.allocation(1, 2),
                SyntheticTrees.allocation(2, 1),
            ),
        )
        assertEquals(listOf(1, 2, 3), state.allocations.map { it.nodeId })
    }

    @Test
    fun outOfOrderDirectConstructionFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            BuildState(
                1,
                listOf(SyntheticTrees.allocation(2, 1), SyntheticTrees.allocation(1, 1)),
            )
        }
    }

    @Test
    fun lookupsResolveTheFirstAllocationOfANode() {
        val state = BuildState.of(
            1,
            listOf(
                SyntheticTrees.allocation(1, 2),
                SyntheticTrees.allocation(1, 5),
                SyntheticTrees.allocation(2, 1),
            ),
        )
        assertEquals(2, state.rankOf(1), "the first allocation wins, like the reference")
        assertEquals(1, state.rankOf(2))
        assertEquals(0, state.rankOf(3))
        assertNull(state.allocationFor(3))
        assertEquals(mapOf(1 to 5, 2 to 1), state.ranks(), "a later duplicate wins in ranks(), like the reference")
    }

    @Test
    fun withAllocationReplacesEntryAndZeroRemovesIt() {
        val state = BuildState.of(1, listOf(SyntheticTrees.allocation(1, 2), SyntheticTrees.allocation(2, 1)))

        val increased = state.withAllocation(1, entryId = 1001, rank = 3)
        assertEquals(3, increased.rankOf(1))
        assertEquals(1, increased.rankOf(2))
        assertTrue(state.rankOf(1) == 2, "the source state is unchanged")

        val removed = state.withAllocation(1, entryId = 1001, rank = 0)
        assertEquals(0, removed.rankOf(1))
        assertEquals(listOf(2), removed.allocations.map { it.nodeId })
    }

    @Test
    fun withAllocationKeepsCanonicalOrder() {
        val state = BuildState.of(1, listOf(SyntheticTrees.allocation(5, 1), SyntheticTrees.allocation(9, 1)))
        val updated = state.withAllocation(7, entryId = 1007, rank = 1)
        assertEquals(listOf(5, 7, 9), updated.allocations.map { it.nodeId })
    }
}
