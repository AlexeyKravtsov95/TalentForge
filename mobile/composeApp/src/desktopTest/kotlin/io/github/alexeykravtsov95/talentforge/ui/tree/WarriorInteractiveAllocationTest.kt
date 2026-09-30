package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * M4.3 real-Warrior state transitions through the editing session over the
 * REAL committed baseline dataset and the REAL M3B engine: the milestone's
 * acceptance loop (Improved Rend rank-up, denied 4th rank, live point
 * counter, tier-gate presentation, Deep Wounds unlocking only when BOTH its
 * real gate and prerequisite are met, plain refund, and a dependent-blocking
 * refund denied with the whole build unchanged).
 */
class WarriorInteractiveAllocationTest {

    private lateinit var readyState: TalentTreeUiState.Ready

    private var improvedRend = 0
    private var deflection = 0
    private var improvedCharge = 0
    private var deepWounds = 0

    @BeforeTest
    fun loadRealWarriorState() {
        readyState = runBlocking { loadBaselineWarriorTreeState() } as TalentTreeUiState.Ready
        improvedRend = nodeIdOf("Improved Rend")
        deflection = nodeIdOf("Deflection")
        improvedCharge = nodeIdOf("Improved Charge")
        deepWounds = nodeIdOf("Deep Wounds")
    }

    private fun nodeIdOf(name: String): Int =
        assertNotNull(readyState.engine.tree.nodes.firstOrNull { it.name == name }).id

    private fun sessionOf(vararg ranks: Pair<Int, Int>): TalentBuildEditingState {
        var session = TalentBuildEditingState.start(readyState.engine)
        for ((nodeId, count) in ranks) {
            repeat(count) {
                val result = session.spend(readyState.engine, nodeId)
                assertTrue(result.changed, "preset spend on $nodeId must succeed (real engine)")
                session = result.editing
            }
        }
        return session
    }

    /** The M4.2 presentation recomputed from the session's current build. */
    private fun presentationOf(session: TalentBuildEditingState): TalentTreePresentation =
        buildTalentTreePresentation(readyState.engine, session.current)

    // ------------------------------------------------------------------ the acceptance loop

    @Test
    fun improvedRendRanksUpOneRankPerExplicitSpend() {
        var session = TalentBuildEditingState.start(readyState.engine)

        val expectedRanks = listOf(1, 2, 3)
        for (expected in expectedRanks) {
            val result = session.spend(readyState.engine, improvedRend)
            assertTrue(result.changed)
            session = result.editing
            assertEquals(expected, session.current.rankOf(improvedRend))
        }
    }

    @Test
    fun fourthSpendOnImprovedRendIsDeniedAndTheBuildStaysAtThreeRanks() {
        var session = TalentBuildEditingState.start(readyState.engine)
        repeat(3) { session = session.spend(readyState.engine, improvedRend).editing }
        val before = session.current

        val result = session.spend(readyState.engine, improvedRend)

        assertFalse(result.changed)
        assertEquals(before, result.editing.current)
        assertEquals(3, result.editing.current.rankOf(improvedRend))
        assertTrue(result.issues.any { it.code == AllocationIssueCode.MAX_RANK_REACHED })
    }

    @Test
    fun headerPointsTrackTheRealEngineCounter() {
        var session = TalentBuildEditingState.start(readyState.engine)
        assertEquals("0 / 51", headerTextOf(session))

        repeat(3) {
            session = session.spend(readyState.engine, improvedRend).editing
        }
        assertEquals("3 / 51", headerTextOf(session))
        assertEquals(3, presentationOf(session).spentPoints)
        assertEquals(48, presentationOf(session).remainingPoints)
    }

    private fun headerTextOf(session: TalentBuildEditingState): String {
        val presentation = presentationOf(session)
        return "${presentation.spentPoints} / ${presentation.pointBudget}"
    }

    // ------------------------------------------------------------------ tier gates

    @Test
    fun tierGatePresentationChangesAsRealPointsAreSpent() {
        val empty = presentationOf(TalentBuildEditingState.start(readyState.engine))
        // Improved Charge's real gate: 5 points among Improved Rend /
        // Deflection / Heroic Strike.
        assertEquals(TalentNodeState.TIER_LOCKED, empty.nodes.getValue(improvedCharge).state)
        assertTrue(empty.nodes.getValue(improvedCharge).tierGates.isNotEmpty())

        // 5 real points (Improved Rend 3 + Deflection 2) satisfy the gate
        val session = sessionOf(improvedRend to 3, deflection to 2)
        val presentation = presentationOf(session)
        assertEquals(TalentNodeState.AVAILABLE, presentation.nodes.getValue(improvedCharge).state)
        assertTrue(presentation.nodes.getValue(improvedCharge).tierGates.isEmpty())
    }

    // ------------------------------------------------------------------ deeper talent unlock

    @Test
    fun deepWoundsUnlocksOnlyWhenBothItsGateAndPrerequisiteAreMet() {
        val engine = readyState.engine

        // empty build: the prerequisite (Improved Rend maxed) is unmet
        val emptyPresentation = presentationOf(TalentBuildEditingState.start(engine))
        assertEquals(TalentNodeState.PREREQUISITE_LOCKED, emptyPresentation.nodes.getValue(deepWounds).state)
        assertFalse(engine.canSpend(emptyState(), deepWounds).allowed)

        // Improved Rend 3/3 (3 points): prerequisite met, 10-point gate not
        val threePoints = sessionOf(improvedRend to 3)
        val threePointsPresentation = presentationOf(threePoints)
        assertEquals(TalentNodeState.TIER_LOCKED, threePointsPresentation.nodes.getValue(deepWounds).state)
        assertFalse(engine.canSpend(threePoints.current, deepWounds).allowed)

        // 10 real points: Improved Rend 3 + Deflection 5 + Improved Charge 2
        val tenPoints = sessionOf(improvedRend to 3, deflection to 5, improvedCharge to 2)
        val tenPointsPresentation = presentationOf(tenPoints)
        assertEquals(10, tenPointsPresentation.spentPoints)
        assertEquals(TalentNodeState.AVAILABLE, tenPointsPresentation.nodes.getValue(deepWounds).state)
        assertTrue(engine.canSpend(tenPoints.current, deepWounds).allowed)

        // and spending Deep Wounds through the session works
        val spent = tenPoints.spend(engine, deepWounds)
        assertTrue(spent.changed)
        assertEquals(11, engine.spentPoints(spent.editing.current))
    }

    /** 11 real points with Deep Wounds allocated (its gate + prerequisite met). */
    private fun sessionWithDeepWounds(): TalentBuildEditingState {
        val base = sessionOf(improvedRend to 3, deflection to 5, improvedCharge to 2)
        val result = base.spend(readyState.engine, deepWounds)
        assertTrue(result.changed)
        return result.editing
    }

    private fun emptyState() = TalentBuildEditingState.start(readyState.engine).current

    // ------------------------------------------------------------------ refunds

    @Test
    fun refundOfARankWithNoBrokenDependentsSucceeds() {
        val session = sessionOf(improvedRend to 3, deflection to 5, improvedCharge to 2)

        // Improved Charge supports nothing here: its rank can come off
        val result = session.refund(readyState.engine, improvedCharge)

        assertTrue(result.changed)
        assertEquals(9, readyState.engine.spentPoints(result.editing.current))
        assertEquals(1, result.editing.current.rankOf(improvedCharge))
    }

    @Test
    fun refundWhichWouldBreakAnAllocatedDependentIsDeniedAndChangesNothing() {
        // Deep Wounds allocated (11 points); refunding Improved Rend would
        // break Deep Wounds' prerequisite — the engine must deny it and the
        // WHOLE build (and history) stays unchanged.
        val session = sessionWithDeepWounds()
        val before = session.current
        val historySize = session.undoHistory.size

        val result = session.refund(readyState.engine, improvedRend)

        assertFalse(result.changed)
        assertEquals(before, result.editing.current, "the whole build stays unchanged")
        assertEquals(historySize, result.editing.undoHistory.size)
        assertTrue(
            result.issues.any { it.code == AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK },
            "typed dependent-block reason preserved",
        )
        assertEquals(
            deepWounds,
            result.issues.first { it.code == AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK }.nodeId,
            "the broken dependent is Deep Wounds itself",
        )
    }

    @Test
    fun theDependentItselfRefundsFreely() {
        // the mirror of the blocked case: removing Deep Wounds (the dependent)
        // is legal planner editing — no cascade, no silent removal of others
        val session = sessionWithDeepWounds()

        val result = session.refund(readyState.engine, deepWounds)

        assertTrue(result.changed)
        assertEquals(10, readyState.engine.spentPoints(result.editing.current))
        // and NOW refunding Improved Rend becomes legal again
        val followUp = result.editing.refund(readyState.engine, improvedRend)
        assertTrue(followUp.changed)
    }

    // ------------------------------------------------------------------ projection honesty

    @Test
    fun actionProjectionIsIndependentOfVisualNodeState() {
        val engine = readyState.engine

        // MAXED Improved Rend denies spending but (with no dependents) allows
        // refunding — TalentNodeState alone must never drive button enabled.
        val maxed = sessionOf(improvedRend to 3)
        val maxedPresentation = presentationOf(maxed)
        assertEquals(TalentNodeState.MAXED, maxedPresentation.nodes.getValue(improvedRend).state)
        val maxedActions = buildTalentNodeActions(engine, maxed.current, maxedPresentation.nodes.getValue(improvedRend))
        assertFalse(maxedActions.canSpend)
        assertTrue(maxedActions.canRefund)

        // with Deep Wounds allocated the same MAXED node blocks the refund
        val withDependent = sessionWithDeepWounds()
        val withDependentActions = buildTalentNodeActions(
            engine,
            withDependent.current,
            presentationOf(withDependent).nodes.getValue(improvedRend),
        )
        assertFalse(withDependentActions.canSpend)
        assertFalse(withDependentActions.canRefund)
        assertTrue(
            withDependentActions.refundIssues.any { it.code == AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK },
        )
    }
}
