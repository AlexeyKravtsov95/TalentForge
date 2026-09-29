package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * JVM-runtime proof of the [BuildState] ownership boundary. The common tests
 * cover both boundaries on every platform; this one pins the hostile path the
 * review called out: the collection returned by [BuildState.allocations] is a
 * fresh copy whose runtime type on the JVM *is* mutable (an `ArrayList` behind
 * the read-only `List` interface) — and mutating it still cannot change the
 * state.
 */
class BuildStateImmutabilityJvmTest {

    @Test
    fun exposedAllocationsAreMutableRuntimeCopiesDecoupledFromTheState() {
        val state = BuildState.of(
            1,
            listOf(
                NodeAllocation(nodeId = 2, entryId = 1002, rank = 1),
                NodeAllocation(nodeId = 1, entryId = 1001, rank = 3),
            ),
        )
        assertEquals(listOf(1, 2), state.allocations.map { it.nodeId })

        val exposed = state.allocations
        // prove the cast is possible on this runtime, so the mutation below is
        // really exercised and the test can never pass vacuously
        val hostile = assertIs<MutableList<NodeAllocation>>(exposed)
        hostile.clear()
        hostile += NodeAllocation(nodeId = 9, entryId = 1009, rank = 9)

        // the exposed copy was mutated...
        assertEquals(1, exposed.size)
        assertEquals(9, exposed.single().nodeId)
        // ...and the state is untouched
        assertEquals(listOf(1, 2), state.allocations.map { it.nodeId })
        assertEquals(3, state.rankOf(1))
        assertEquals(1, state.rankOf(2))
        assertEquals(0, state.rankOf(9))
    }

    @Test
    fun eachAllocationsAccessReturnsAFreshCopy() {
        // two allocations: the JVM copy is a real ArrayList, i.e. runtime-mutable
        val state = BuildState.of(
            1,
            listOf(
                NodeAllocation(nodeId = 1, entryId = 1001, rank = 2),
                NodeAllocation(nodeId = 2, entryId = 1002, rank = 1),
            ),
        )
        val first = state.allocations
        val second = state.allocations
        // two reads never share one exposed collection: mutating one cannot
        // even affect the other, let alone the state
        (first as MutableList<NodeAllocation>).clear()
        assertEquals(listOf(1, 2), second.map { it.nodeId })
        assertEquals(listOf(1, 2), state.allocations.map { it.nodeId })
    }
}
