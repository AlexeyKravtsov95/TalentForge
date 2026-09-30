package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BuildState ↔ BuildFile conversions: the allocation triples are the whole
 * portable payload — nothing else from the editing session crosses.
 */
class BuildFileMappingTest {

    private val game = GameId("forever")
    private val token = ClassToken("WARRIOR")
    private val clientBuild = "1.60.1.70009"

    @Test
    fun buildFileRoundTripsThroughTheBuildState() {
        val file = buildFileOf(
            state = BuildState.of(
                treeId = 1117,
                allocations = listOf(
                    NodeAllocation(31, 3101, 2),
                    NodeAllocation(11, 1101, 5),
                ),
            ),
            game = game,
            clientBuild = clientBuild,
            classToken = token,
        )

        val state = file.toBuildState()

        assertEquals(1117, state.treeId)
        // BuildState canonicalizes (node, entry, rank) order
        assertEquals(
            listOf(NodeAllocation(11, 1101, 5), NodeAllocation(31, 3101, 2)),
            state.allocations,
        )
        assertEquals(file, buildFileOf(state, game, clientBuild, token))
    }

    @Test
    fun emptyStateMapsToAnEmptyAllocationListAndBack() {
        val state = BuildState.empty(1082)

        val file = buildFileOf(state, game, clientBuild, ClassToken("SHAMAN"))

        assertEquals(emptyList(), file.allocations)
        assertEquals(1082, file.treeId)
        assertEquals(ClassToken("SHAMAN"), file.classToken)
        assertEquals(state, file.toBuildState())
    }
}
