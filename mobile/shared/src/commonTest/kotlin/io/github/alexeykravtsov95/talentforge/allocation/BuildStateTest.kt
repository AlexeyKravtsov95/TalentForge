package io.github.alexeykravtsov95.talentforge.allocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** BuildState mechanics: canonical order, snapshot ownership, lookups and transitions. */
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
    fun stateSnapshotsTheCallerOwnedCollection() {
        val source = mutableListOf(
            SyntheticTrees.allocation(3, 1),
            SyntheticTrees.allocation(1, 3),
        )
        val state = BuildState.of(1, source)
        assertEquals(listOf(1, 3), state.allocations.map { it.nodeId })

        // clearing, refilling and reordering the caller's mutable list must not
        // change the already-created state
        source.clear()
        source += SyntheticTrees.allocation(9, 5)
        source.add(0, SyntheticTrees.allocation(2, 2))

        assertEquals(listOf(1, 3), state.allocations.map { it.nodeId })
        assertEquals(3, state.rankOf(1))
        assertEquals(1, state.rankOf(3))
        assertEquals(0, state.rankOf(9))
        assertEquals(0, state.rankOf(2))
    }

    @Test
    fun everyConstructionPathProducesAnOwnedCanonicalSnapshot() {
        // the direct constructor canonicalizes too — there is no public path
        // (no copy()) that could construct an out-of-order or aliased state
        val direct = BuildState(
            1,
            mutableListOf(SyntheticTrees.allocation(2, 1), SyntheticTrees.allocation(1, 1)),
        )
        assertEquals(listOf(1, 2), direct.allocations.map { it.nodeId })

        val empty = BuildState.empty(1)
        assertEquals(emptyList(), empty.allocations)

        val transitioned = direct.withAllocation(2, entryId = 1002, rank = 3)
        assertEquals(listOf(1, 2), transitioned.allocations.map { it.nodeId })
        assertEquals(listOf(1, 2), direct.allocations.map { it.nodeId }, "source state unchanged")
        assertEquals(3, transitioned.rankOf(2))
    }

    @Test
    fun corruptAllocationsRemainRepresentable() {
        // duplicates and out-of-range ranks stay in the state so validate()
        // can report them (the reference keeps them too)
        val state = BuildState.of(
            1,
            listOf(
                SyntheticTrees.allocation(1, 2),
                SyntheticTrees.allocation(1, 5),
                SyntheticTrees.allocation(2, 0),
                SyntheticTrees.allocation(3, -1),
            ),
        )
        assertEquals(4, state.allocations.size)
        assertEquals(listOf(1, 1, 2, 3), state.allocations.map { it.nodeId })
    }

    @Test
    fun valueEqualityIgnoresInputCollectionIdentity() {
        assertEquals(
            BuildState.of(1, listOf(SyntheticTrees.allocation(1, 1))),
            BuildState(1, mutableListOf(SyntheticTrees.allocation(1, 1))),
        )
        assertNotEquals(
            BuildState.of(2, listOf(SyntheticTrees.allocation(1, 1))),
            BuildState.of(1, listOf(SyntheticTrees.allocation(1, 1))),
        )
        assertNotEquals(
            BuildState.of(1, listOf(SyntheticTrees.allocation(1, 1))),
            BuildState.of(1, listOf(SyntheticTrees.allocation(1, 2))),
        )
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
        assertEquals(null, state.allocationFor(3))
        assertEquals(mapOf(1 to 5, 2 to 1), state.ranks(), "a later duplicate wins in ranks(), like the reference")
    }

    @Test
    fun withAllocationReplacesEntryAndZeroRemovesIt() {
        val state = BuildState.of(1, listOf(SyntheticTrees.allocation(1, 2), SyntheticTrees.allocation(2, 1)))

        val increased = state.withAllocation(1, entryId = 1001, rank = 3)
        assertEquals(3, increased.rankOf(1))
        assertEquals(1, increased.rankOf(2))
        assertEquals(2, state.rankOf(1), "the source state is unchanged")

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
