package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app-level autosave coordinator (M5.1 review fix): the ONE save pipeline
 * of the whole app, owned ABOVE any single destination so that leaving the
 * editor can never cancel an in-flight or pending save — its consumer runs in
 * the app's own scope and keeps persisting across destination changes.
 *
 * Invariants:
 * - submissions are FIFO with monotonic generations; rapid mutations may
 *   coalesce to the newest queued state (a save confirms every generation ≤
 *   its own);
 * - [flush] suspends until every submission made BEFORE the call is confirmed
 *   persisted (or has failed), so navigation can guarantee the newest current
 *   BuildState reached storage — or visibly stay put with the retryable error;
 * - a failed write keeps the failure visible (keyed by localId, exposed via
 *   [lastOutcome]) and does not advance persistence; a later successful save
 *   (retry or new mutation) clears it;
 * - the in-memory BuildState never rolls back and no duplicate record is ever
 *   created (`saveBuild` preserves record identity);
 * - selection/pan/zoom never reach this class: only the editor's single
 *   onBuildStateChanged path submits.
 *
 * Threading: a main-thread API (Compose click handlers and the app scope) —
 * [submit]/[flush] are called from the composition, the consumer runs in
 * [scope] and hops to [Dispatchers.Default] only for the storage write.
 */
class BuildAutosaveCoordinator(
    private val repository: LocalBuildRepository,
    scope: CoroutineScope,
) {

    private val queue = Channel<SaveRequest>(Channel.UNLIMITED)
    private val processed = MutableStateFlow(0L)
    private val outcomeState = MutableStateFlow<SaveResult?>(null)

    /** Main-thread confined: submissions before the current flush target. */
    private var submittedCount = 0L

    /** The outcome of the most recent save attempt (null before the first). */
    val lastOutcome: StateFlow<SaveResult?> = outcomeState.asStateFlow()

    init {
        scope.launch { consumeForever() }
    }

    /** Enqueues the current build of [localId]; rapid submissions may coalesce to the newest. */
    fun submit(localId: String, build: BuildFile) {
        submittedCount += 1
        queue.trySend(SaveRequest(localId = localId, build = build, generation = submittedCount))
    }

    /**
     * Waits until every submission made before this call is confirmed
     * persisted (or failed). Returns `null` when nothing was ever submitted,
     * otherwise the outcome of the save that satisfied the wait — a failed
     * [SaveResult] means the newest state is NOT on storage and the caller
     * (back navigation) must not proceed silently.
     */
    suspend fun flush(): SaveResult? {
        val target = submittedCount
        if (target == 0L) return null
        processed.first { it >= target }
        return outcomeState.value
    }

    private suspend fun consumeForever() {
        // scope-owned loop: scope cancellation cancels the suspended receive
        while (true) {
            var request = queue.receive()
            // coalesce: anything already queued supersedes what we hold — a
            // newer generation confirms every submission ≤ its own
            while (true) {
                request = queue.tryReceive().getOrNull() ?: break
            }
            val outcome = withContext(Dispatchers.Default) {
                repository.saveBuild(request.localId, request.build)
            }
            outcomeState.value = SaveResult(localId = request.localId, outcome = outcome)
            processed.value = request.generation
        }
    }

    private data class SaveRequest(val localId: String, val build: BuildFile, val generation: Long)

    /** The last save attempt's outcome, keyed by the record it belongs to. */
    data class SaveResult(val localId: String, val outcome: BuildLibraryOutcome<*>) {
        val failed: Boolean get() = outcome is BuildLibraryOutcome.Failed
    }
}
