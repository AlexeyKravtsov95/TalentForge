package io.github.alexeykravtsov95.talentforge.ui.tree

/**
 * M5.UX.2a: one real measurement of the portrait inspector — which talent it
 * showed, in which presentation, and the height (dp) of the canvas bottom it
 * covers (the whole bar including its 14 dp insets, or the whole sheet).
 */
data class TalentInspectorMeasurement(
    val nodeId: Int,
    val expanded: Boolean,
    val coveredDp: Float,
)

/**
 * Decides when a **measured** inspector height should move the camera
 * (M5.UX.2a). Pure state machine, no Compose: the screen feeds it every new
 * measurement and requests a B3 08 minimal reveal (`panToReveal` — scale
 * untouched, no-op while the node is visible) only when it answers `true`.
 *
 * One *presentation* is a (talent, compact/expanded) pair. Rules:
 * - a new presentation reveals once, on its first measurement: opening the
 *   bar, expanding (B3 08 "Expanding pans the canvas the minimum amount
 *   needed, and only when the node would be covered");
 * - collapsing the sheet of the same talent never reveals (B3 08
 *   "Collapsing leaves the canvas where it is");
 * - a presentation that its caller already placed (Show centring, the
 *   cross-section Back chip — see [suppressNextOpen]) does not reveal on open;
 * - within a presentation, the inspector growing by more than
 *   [GROWTH_EPSILON_DP] (a description line wrapped after a spend) reveals
 *   again — but only while the user has not moved the camera since
 *   (`gestureEpoch`), so a deliberate pan/zoom is never fought; shrinking
 *   never moves the camera.
 *
 * Termination: a reveal pans the canvas and never changes the inspector's
 * height, and a presentation answers `true` at most once per strictly larger
 * height, so measure → reveal can never loop.
 */
class TalentInspectorRevealTracker {

    private data class Presentation(
        val nodeId: Int,
        val expanded: Boolean,
        val coveredDp: Float,
        val gestureEpoch: Int,
    )

    private var current: Presentation? = null
    private var suppressedNodeId: Int? = null

    /** The inspector is hidden (no selection, other layout): the next presentation is an opening. */
    fun reset() {
        current = null
        suppressedNodeId = null
    }

    /** The next presentation of [nodeId] is positioned by its caller and must not reveal on open. */
    fun suppressNextOpen(nodeId: Int) {
        suppressedNodeId = nodeId
    }

    /**
     * Feeds one measurement; returns whether the camera should now reveal
     * [measurement]'s talent above `coveredDp`. [gestureEpoch] is the active
     * camera's [TalentTreeViewportState.gestureEpoch].
     */
    fun onMeasured(measurement: TalentInspectorMeasurement, gestureEpoch: Int): Boolean {
        val covered = measurement.coveredDp
        if (!covered.isFinite() || covered <= 0f) return false
        val previous = current
        if (previous != null &&
            previous.nodeId == measurement.nodeId &&
            previous.expanded == measurement.expanded
        ) {
            if (covered <= previous.coveredDp + GROWTH_EPSILON_DP) return false
            // remember the larger height either way; once the user moved the camera
            // during this presentation (the epoch only grows), never fight it again
            current = previous.copy(coveredDp = covered)
            return gestureEpoch == previous.gestureEpoch
        }
        val collapsing = previous != null &&
            previous.nodeId == measurement.nodeId &&
            previous.expanded &&
            !measurement.expanded
        val suppressed = suppressedNodeId == measurement.nodeId
        suppressedNodeId = null
        current = Presentation(measurement.nodeId, measurement.expanded, covered, gestureEpoch)
        return !collapsing && !suppressed
    }

    companion object {
        /** Sub-dp layout noise (rounding of px heights) never counts as growth. */
        const val GROWTH_EPSILON_DP: Float = 0.5f
    }
}
