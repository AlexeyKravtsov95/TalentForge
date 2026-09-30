package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssue
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation

/**
 * The M4.3 in-memory build-editing session: **which immutable [BuildState] is
 * current**, plus its undo and redo histories.
 *
 * This is the UI/session ownership layer only — every allocation transition is
 * still computed by the M3B [TalentAllocationEngine] ([spend]/[refund] and the
 * engine's own `emptyState` for [reset]); this class never touches ranks,
 * gates, prerequisites or `withAllocation` itself. It lives in `composeApp`
 * because only the UI consumes it (no cross-consumer domain reason exists).
 *
 * History semantics (deterministic):
 * - a **successful** spend/refund/Max/reset pushes the *previous* state onto
 *   the undo stack, makes the engine's result current, and clears redo;
 * - a **denied** mutation changes nothing at all (state, both histories);
 * - *undo* moves the current state to the redo stack and restores the last
 *   undo entry; *redo* is the mirror image;
 * - after an undo, the next successful mutation clears redo;
 * - reset is ONE atomic history step (never one entry per refunded rank) and a
 *   no-op — with no history — when the build is already empty.
 *
 * Selection, viewport, inspector expansion and Show navigation are NOT build
 * history entries; they are presentation state owned by the screen. The
 * histories are intentionally unbounded: a Forever-tree editing session is
 * tiny, so there is no generic command framework and no persistence here.
 */
data class TalentBuildEditingState(
    val current: BuildState,
    val undoHistory: List<BuildState> = emptyList(),
    val redoHistory: List<BuildState> = emptyList(),
) {
    val canUndo: Boolean get() = undoHistory.isNotEmpty()

    val canRedo: Boolean get() = redoHistory.isNotEmpty()

    // ------------------------------------------------------------------ mutations (engine-routed)

    /**
     * Purchases one rank of [nodeId] through `engine.spend`. Denied by the
     * engine → this exact session is returned unchanged with the typed issues.
     */
    fun spend(engine: TalentAllocationEngine, nodeId: Int): TalentBuildEditResult {
        val result = engine.spend(current, nodeId)
        return if (result.allowed) {
            committed(result.state, result.decision.issues)
        } else {
            TalentBuildEditResult(this, changed = false, issues = result.decision.issues)
        }
    }

    /**
     * Removes one rank of [nodeId] through `engine.refund` (planner-plan
     * editing: REFUND-1, no cascade, dependents never silently removed).
     * Denied by the engine → this exact session is returned unchanged.
     */
    fun refund(engine: TalentAllocationEngine, nodeId: Int): TalentBuildEditResult {
        val result = engine.refund(current, nodeId)
        return if (result.allowed) {
            committed(result.state, result.decision.issues)
        } else {
            TalentBuildEditResult(this, changed = false, issues = result.decision.issues)
        }
    }

    /**
     * The expanded inspector's `Max` action (UX/UI spec 03: "Max in the
     * expanded sheet"): applies `engine.spend` repeatedly until the engine
     * denies the next rank (max rank reached, budget exhausted, …). However
     * many ranks that is, it is recorded as **one** atomic history entry —
     * undo restores the exact pre-Max build.
     *
     * If not a single rank could be spent, nothing changes and the engine's
     * typed denial of the first attempt is reported.
     */
    fun spendToMax(engine: TalentAllocationEngine, nodeId: Int): TalentBuildEditResult {
        var state = current
        var spent = false
        var denial = emptyList<AllocationIssue>()
        while (true) {
            val result = engine.spend(state, nodeId)
            if (!result.allowed) {
                denial = result.decision.issues
                break
            }
            spent = true
            state = result.state
        }
        return if (spent) {
            committed(state, emptyList())
        } else {
            TalentBuildEditResult(this, changed = false, issues = denial)
        }
    }

    /**
     * Reset Talents (UX/UI spec 03/09: recoverable, immediate, undoable): the
     * current build becomes the engine's real `emptyState()`. One atomic
     * history entry; already-empty → a no-op that creates no history.
     */
    fun reset(engine: TalentAllocationEngine): TalentBuildEditResult {
        val empty = engine.emptyState()
        if (current == empty) {
            return TalentBuildEditResult(this, changed = false, issues = emptyList())
        }
        return committed(empty, emptyList())
    }

    private fun committed(
        after: BuildState,
        issues: List<AllocationIssue>,
    ): TalentBuildEditResult = TalentBuildEditResult(
        editing = TalentBuildEditingState(
            current = after,
            undoHistory = undoHistory + current,
            redoHistory = emptyList(),
        ),
        changed = true,
        issues = issues,
    )

    // ------------------------------------------------------------------ history navigation (pure)

    /** Restores the previous build; `null` when there is nothing to undo. */
    fun undo(): TalentBuildEditingState? {
        val previous = undoHistory.lastOrNull() ?: return null
        return TalentBuildEditingState(
            current = previous,
            undoHistory = undoHistory.dropLast(1),
            redoHistory = redoHistory + current,
        )
    }

    /** Re-applies the next build; `null` when there is nothing to redo. */
    fun redo(): TalentBuildEditingState? {
        val next = redoHistory.lastOrNull() ?: return null
        return TalentBuildEditingState(
            current = next,
            undoHistory = undoHistory + current,
            redoHistory = redoHistory.dropLast(1),
        )
    }

    companion object {

        /** A session that starts from the engine's real empty state. */
        fun start(engine: TalentAllocationEngine): TalentBuildEditingState =
            TalentBuildEditingState(current = engine.emptyState())

        /**
         * Saveable form of the whole session (current + both histories) as one
         * flat `IntArray` of allocation triples — only simple
         * allocation/history values are serialized, never the engine.
         */
        /**
         * Saveable form of the whole session (current + both histories) as one
         * flat `IntArray` of allocation triples — only simple
         * allocation/history values are serialized, never the engine.
         * (`listSaver` itself is typed `Saver<T, Any>`: it repacks the list.)
         */
        val Saver: Saver<TalentBuildEditingState, Any> = listSaver(
            save = { session: TalentBuildEditingState -> serialize(session) },
            restore = { values: List<IntArray> -> deserialize(values) },
        )

        /** The session as Bundle-saveable allocation triples (current + both histories). */
        internal fun serialize(session: TalentBuildEditingState): List<IntArray> =
            buildList<IntArray> {
                add(stateSaveableOf(session.current))
                add(intArrayOf(session.undoHistory.size))
                for (state in session.undoHistory) add(stateSaveableOf(state))
                add(intArrayOf(session.redoHistory.size))
                for (state in session.redoHistory) add(stateSaveableOf(state))
            }

        /** Rebuilds a session from [serialize]'s output; `null` on any corruption. */
        internal fun deserialize(values: List<IntArray>): TalentBuildEditingState? {
            val iterator = values.iterator()
            fun next(): IntArray {
                check(iterator.hasNext()) { "corrupt editing-state save" }
                return iterator.next()
            }
            val current = stateOf(next()) ?: return null
            val undoCount = next()[0]
            val undo = buildList {
                repeat(undoCount) {
                    add(stateOf(next()) ?: return null)
                }
            }
            val redoCount = next()[0]
            val redo = buildList {
                repeat(redoCount) {
                    add(stateOf(next()) ?: return null)
                }
            }
            return TalentBuildEditingState(current = current, undoHistory = undo, redoHistory = redo)
        }

        /** [treeId, allocationCount, nodeId, entryId, rank, …] (zero-rank rows dropped). */
        private fun stateSaveableOf(state: BuildState): IntArray = intArrayOf(
            state.treeId,
            state.allocations.size,
        ) + state.allocations.flatMap { allocation ->
            listOf(allocation.nodeId, allocation.entryId, allocation.rank)
        }.toIntArray()

        /** Rebuilds a state from [stateSaveableOf]'s layout; `null` on any corruption. */
        private fun stateOf(values: IntArray): BuildState? {
            if (values.size < 2) return null
            val treeId = values[0]
            val count = values[1]
            if (values.size != 2 + count * 3) return null
            val allocations = ArrayList<NodeAllocation>(count)
            for (index in 0 until count) {
                val base = 2 + index * 3
                val rank = values[base + 2]
                if (rank <= 0) return null
                allocations += NodeAllocation(
                    nodeId = values[base],
                    entryId = values[base + 1],
                    rank = rank,
                )
            }
            return BuildState.of(treeId, allocations)
        }
    }
}

/**
 * Outcome of one editing action: the (possibly unchanged) session, whether the
 * build actually changed, and the engine's typed issues. On `changed == false`
 * the session is the exact input session and [issues] explains the denial
 * (tests/debugging consume the typed codes; the UI maps them to short
 * user-facing copy itself and never shows raw engine diagnostics).
 */
data class TalentBuildEditResult(
    val editing: TalentBuildEditingState,
    val changed: Boolean,
    val issues: List<AllocationIssue>,
)
