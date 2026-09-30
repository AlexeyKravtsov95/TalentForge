package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.runtime.saveable.SaverScope
import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Focused unit tests of the M4.3 build-editing session
 * ([TalentBuildEditingState]) over the REAL committed Warrior baseline: the
 * history contract (successful spend/refund/reset push exactly one undo
 * entry; denied mutations change nothing; undo/redo move the exact immutable
 * `BuildState` objects; a new mutation after undo clears redo).
 */
class TalentBuildEditingStateTest {

    private lateinit var readyState: TalentTreeUiState.Ready
    private var improvedRendId = 0
    private var deflectionId = 0

    @BeforeTest
    fun loadRealWarriorState() {
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
        improvedRendId = nodeIdOf("Improved Rend")
        deflectionId = nodeIdOf("Deflection")
    }

    private fun nodeIdOf(name: String): Int =
        assertNotNull(readyState.engine.tree.nodes.firstOrNull { it.name == name }).id

    // ------------------------------------------------------------------ spend

    @Test
    fun successfulSpendChangesCurrentState() {
        val session = TalentBuildEditingState.start(readyState.engine)

        val result = session.spend(readyState.engine, improvedRendId)

        assertTrue(result.changed)
        assertEquals(1, result.editing.current.rankOf(improvedRendId))
        assertEquals(1, readyState.engine.spentPoints(result.editing.current))
    }

    @Test
    fun successfulSpendAddsExactlyOneUndoEntry() {
        val session = TalentBuildEditingState.start(readyState.engine)

        val result = session.spend(readyState.engine, improvedRendId)

        assertEquals(1, result.editing.undoHistory.size)
        assertEquals(0, result.editing.undoHistory.single().rankOf(improvedRendId))
        assertTrue(result.editing.redoHistory.isEmpty())
    }

    @Test
    fun deniedSpendChangesNothing() {
        val session = TalentBuildEditingState.start(readyState.engine)
            .let { start ->
                // max Improved Rend first (3 successful spends)
                var current = start
                repeat(3) {
                    current = current.spend(readyState.engine, improvedRendId).editing
                }
                current
            }
        val historySizeBefore = session.undoHistory.size

        val result = session.spend(readyState.engine, improvedRendId) // 4th rank: denied

        assertFalse(result.changed)
        assertEquals(3, result.editing.current.rankOf(improvedRendId))
        assertEquals(historySizeBefore, result.editing.undoHistory.size)
        assertTrue(
            result.issues.any { it.code == AllocationIssueCode.MAX_RANK_REACHED },
            "the typed denial is preserved for tests/debugging",
        )
    }

    // ------------------------------------------------------------------ refund

    @Test
    fun successfulRefundChangesCurrentState() {
        val session = TalentBuildEditingState.start(readyState.engine)
            .let { start ->
                var current = start
                repeat(2) { current = current.spend(readyState.engine, deflectionId).editing }
                current
            }

        val result = session.refund(readyState.engine, deflectionId)

        assertTrue(result.changed)
        assertEquals(1, result.editing.current.rankOf(deflectionId))
    }

    @Test
    fun deniedRefundChangesNothing() {
        val session = TalentBuildEditingState.start(readyState.engine)

        // nothing allocated at all: NOT_ALLOCATED
        val result = session.refund(readyState.engine, deflectionId)

        assertFalse(result.changed)
        assertSame(session, result.editing, "a denied mutation returns the exact same session")
        assertTrue(result.issues.any { it.code == AllocationIssueCode.NOT_ALLOCATED })
    }

    // ------------------------------------------------------------------ undo / redo

    @Test
    fun undoRestoresExactPreviousBuildState() {
        val session = TalentBuildEditingState.start(readyState.engine)
        val before = session.current
        val spent = session.spend(readyState.engine, improvedRendId).editing

        val undone = assertNotNull(spent.undo())

        assertSame(before, undone.current, "undo restores the exact immutable BuildState object")
        assertTrue(undone.canRedo)
        assertFalse(undone.canUndo)
    }

    @Test
    fun redoRestoresExactNextBuildState() {
        val session = TalentBuildEditingState.start(readyState.engine)
        val afterSpend = session.spend(readyState.engine, improvedRendId).editing
        val undone = assertNotNull(afterSpend.undo())

        val redone = assertNotNull(undone.redo())

        assertSame(afterSpend.current, redone.current)
        assertTrue(redone.canUndo)
        assertFalse(redone.canRedo)
    }

    @Test
    fun newSuccessfulMutationAfterUndoClearsRedo() {
        val session = TalentBuildEditingState.start(readyState.engine)
        var afterSpends = session.spend(readyState.engine, improvedRendId).editing
        afterSpends = afterSpends.spend(readyState.engine, improvedRendId).editing
        assertEquals(2, afterSpends.undoHistory.size)
        val undone = assertNotNull(afterSpends.undo())
        assertEquals(1, undone.undoHistory.size, "one undo entry was consumed")
        assertTrue(undone.canRedo)

        val result = undone.spend(readyState.engine, deflectionId)

        assertTrue(result.changed)
        assertTrue(result.editing.redoHistory.isEmpty(), "redo history MUST be cleared")
        assertEquals(2, result.editing.undoHistory.size, "the new mutation stacks on the remaining undo entries")
    }

    @Test
    fun undoAndRedoWithEmptyHistoriesAreNoOps() {
        val session = TalentBuildEditingState.start(readyState.engine)

        assertNull(session.undo())
        assertNull(session.redo())
    }

    // ------------------------------------------------------------------ reset

    @Test
    fun resetIsOneAtomicHistoryStep() {
        // a real 4-point build (Improved Rend 3 + Deflection 1)
        var session = TalentBuildEditingState.start(readyState.engine)
        repeat(3) { session = session.spend(readyState.engine, improvedRendId).editing }
        session = session.spend(readyState.engine, deflectionId).editing
        assertEquals(4, readyState.engine.spentPoints(session.current))

        val result = session.reset(readyState.engine)

        assertTrue(result.changed)
        assertEquals(0, readyState.engine.spentPoints(result.editing.current))
        assertEquals(
            session.undoHistory.size + 1,
            result.editing.undoHistory.size,
            "reset is ONE atomic history operation, not one entry per refunded rank",
        )
    }

    @Test
    fun undoResetRestoresExactPriorBuild() {
        var session = TalentBuildEditingState.start(readyState.engine)
        repeat(3) { session = session.spend(readyState.engine, improvedRendId).editing }
        val beforeReset = session.current

        val result = session.reset(readyState.engine)
        val undone = assertNotNull(result.editing.undo())

        assertSame(beforeReset, undone.current, "undo reset restores the exact prior build")
    }

    @Test
    fun resetOnEmptyStateCreatesNoHistory() {
        val session = TalentBuildEditingState.start(readyState.engine)

        val result = session.reset(readyState.engine)

        assertFalse(result.changed)
        assertSame(session, result.editing)
        assertFalse(result.editing.canUndo)
        assertTrue(result.issues.isEmpty())
    }

    // ------------------------------------------------------------------ immutability / max / saver

    @Test
    fun historyNeverMutatesBuildStateObjects() {
        val session = TalentBuildEditingState.start(readyState.engine)
        val empty = session.current
        val firstSpend = session.spend(readyState.engine, improvedRendId)
        val afterFirst = firstSpend.editing.current
        val secondSpend = firstSpend.editing.spend(readyState.engine, improvedRendId)
        val afterSecond = secondSpend.editing.current
        val secondHistory = secondSpend.editing.undoHistory

        // the captured states keep their exact allocation contents and object
        // identity no matter what the session does afterwards
        val reset = secondSpend.editing.reset(readyState.engine)
        val undoneOnce = assertNotNull(reset.editing.undo())
        val undoneTwice = assertNotNull(undoneOnce.undo())

        assertTrue(empty.allocations.isEmpty())
        assertEquals(1, afterFirst.rankOf(improvedRendId))
        assertEquals(2, afterSecond.rankOf(improvedRendId))
        assertSame(empty, secondHistory[0])
        assertSame(afterFirst, secondHistory[1])
        assertSame(afterSecond, undoneOnce.current)
        assertSame(afterFirst, undoneTwice.current)
    }

    @Test
    fun spendToMaxIsOneAtomicHistoryEntry() {
        val session = TalentBuildEditingState.start(readyState.engine)

        val result = session.spendToMax(readyState.engine, deflectionId) // 0/5 → 5/5

        assertTrue(result.changed)
        assertEquals(5, result.editing.current.rankOf(deflectionId))
        assertEquals(1, result.editing.undoHistory.size)
        assertEquals(5, readyState.engine.spentPoints(result.editing.current))

        // undo restores the exact empty build
        val undone = assertNotNull(result.editing.undo())
        assertEquals(0, undone.current.rankOf(deflectionId))
    }

    @Test
    fun spendToMaxOnAMaxedNodeIsDeniedWithTypedIssues() {
        var session = TalentBuildEditingState.start(readyState.engine)
        session = session.spendToMax(readyState.engine, improvedRendId).editing

        val again = session.spendToMax(readyState.engine, improvedRendId)

        assertFalse(again.changed)
        assertSame(session, again.editing)
        assertTrue(again.issues.any { it.code == AllocationIssueCode.MAX_RANK_REACHED })
    }

    @Test
    fun saverRoundTripPreservesCurrentAndBothHistories() {
        var session = TalentBuildEditingState.start(readyState.engine)
        repeat(2) { session = session.spend(readyState.engine, improvedRendId).editing }
        session = session.spend(readyState.engine, deflectionId).editing
        val undone = assertNotNull(session.undo())

        val saved = TalentBuildEditingState.serialize(undone)
        val restored = TalentBuildEditingState.deserialize(saved)

        val restoredSession = assertNotNull(restored)
        assertEquals(undone, restoredSession, "the whole session survives save/restore")
        assertEquals(undone.current, restoredSession.current)
        assertEquals(undone.undoHistory, restoredSession.undoHistory)
        assertEquals(undone.redoHistory, restoredSession.redoHistory)
    }

    @Test
    fun saverSurvivesSessionStartFromAnInjectedBuild() {
        // tests inject prepared builds through Ready.buildState — the saver
        // must carry them as faithfully as empty states
        val preset = BuildState.of(
            readyState.engine.tree.id,
            listOf(
                io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation(
                    nodeId = improvedRendId,
                    entryId = readyState.engine.tree.nodes.first { it.id == improvedRendId }.entries.single().id,
                    rank = 3,
                ),
            ),
        )
        val session = TalentBuildEditingState(preset)

        val saved = TalentBuildEditingState.serialize(session)
        val restored = TalentBuildEditingState.deserialize(saved)

        assertEquals(session, assertNotNull(restored))
    }
}
