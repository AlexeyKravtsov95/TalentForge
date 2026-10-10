package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * M5.UX.1 tab memory (1A v2 S3: "tab memory — Map<sectionIndex, Camera +
 * userMoved>"): which section tab is open plus one independent camera per
 * section, for ONE opened build.
 *
 * - every section owns its own [TalentTreeViewportState] (policy
 *   [TalentTreeFitPolicy.SECTION]), created on first use and fitted when its
 *   canvas first lays out; switching tabs never touches another section's
 *   camera, so returning to a tab restores its pan/zoom exactly;
 * - it is pure UI state: no BuildState, no allocation, nothing here is ever
 *   reported to the autosave observer;
 * - it is bound to one build by [sessionKey] (the local build id) and
 *   [treeId]; the saved form carries both and a restore for any other build
 *   or tree is discarded (fail-closed), so a different build can never
 *   inherit this build's cameras.
 *
 * Saved through `rememberSaveable` with plain values only (the project's
 * existing state-restoration mechanism — no SavedStateHandle, no new
 * dependency). A restored camera whose canvas size differs (rotation)
 * re-fits on its next layout (1A v2 S5).
 */
@Stable
class TalentTreeSectionCameras(
    val sessionKey: String,
    val treeId: Int,
    initialSelectedSection: Int? = null,
    initialCameras: Map<Int, TalentTreeViewportState> = emptyMap(),
) {

    /** The open section's dataset index; `null` until chosen (the first section then). */
    var selectedSection: Int? by mutableStateOf(initialSelectedSection)

    // Plain map: cameras are created lazily during composition and nobody
    // observes the map itself (each camera's own state is observable).
    private val cameras = HashMap<Int, TalentTreeViewportState>(initialCameras)

    /** The camera of section [index], created (uninitialized) on first access. */
    fun stateOf(index: Int): TalentTreeViewportState =
        cameras.getOrPut(index) { TalentTreeViewportState(initial = null, fitPolicy = TalentTreeFitPolicy.SECTION) }

    /** Section indices that already own a camera (tests and diagnostics). */
    val knownSections: Set<Int> get() = cameras.keys.toSet()

    companion object {
        private const val FORMAT_VERSION = 1

        /** Upper bound of sections a saved payload may carry (defensive; real trees have 3). */
        private const val MAX_SECTIONS = 64

        /**
         * Plain-value saver: `[version, sessionKey, treeId, selected (-1 =
         * none), count, (index, camera floats) × count]`. Restores only for
         * the same [sessionKey]/[treeId]; anything malformed or foreign
         * restores nothing, so the caller starts fresh.
         */
        fun saver(sessionKey: String, treeId: Int) = listSaver<TalentTreeSectionCameras, Any>(
            save = { holder -> encode(holder) },
            restore = { values -> decode(values, sessionKey, treeId) },
        )

        internal fun encode(holder: TalentTreeSectionCameras): List<Any> = buildList {
            add(FORMAT_VERSION)
            add(holder.sessionKey)
            add(holder.treeId)
            add(holder.selectedSection ?: -1)
            val entries = holder.cameras.entries.sortedBy { it.key }
            add(entries.size)
            for ((index, state) in entries) {
                add(index)
                val encoded = TalentTreeViewportState.encode(state)
                // an uninitialized camera is padded so every entry has one fixed width
                addAll(encoded + List(TalentTreeViewportState.SAVED_SIZE - encoded.size) { 0f })
            }
        }

        /** Total fail-closed decoder of [encode]'s list; `null` = start fresh. */
        internal fun decode(values: List<Any>, sessionKey: String, treeId: Int): TalentTreeSectionCameras? {
            if (values.size < HEADER_SIZE) return null
            if (values[0] != FORMAT_VERSION) return null
            if (values[1] != sessionKey || values[2] != treeId) return null
            val selected = values[3] as? Int ?: return null
            val count = values[4] as? Int ?: return null
            val entryWidth = 1 + TalentTreeViewportState.SAVED_SIZE
            if (count < 0 || count > MAX_SECTIONS || values.size != HEADER_SIZE + count * entryWidth) return null
            val restored = LinkedHashMap<Int, TalentTreeViewportState>(count)
            for (entry in 0 until count) {
                val base = HEADER_SIZE + entry * entryWidth
                val index = values[base] as? Int ?: return null
                val floats = ArrayList<Float>(TalentTreeViewportState.SAVED_SIZE)
                for (position in base + 1 until base + entryWidth) {
                    floats += values[position] as? Float ?: return null
                }
                if (index in restored) return null
                restored[index] = TalentTreeViewportState.decode(floats, TalentTreeFitPolicy.SECTION)
            }
            return TalentTreeSectionCameras(
                sessionKey = sessionKey,
                treeId = treeId,
                initialSelectedSection = selected.takeIf { it >= 0 },
                initialCameras = restored,
            )
        }

        private const val HEADER_SIZE = 5
    }
}

/**
 * The section cameras of one opened build, surviving recomposition and
 * configuration change. Keyed by [sessionKey] + [treeId]: a different build
 * gets a fresh holder, never this one's cameras.
 */
@Composable
fun rememberTalentTreeSectionCameras(sessionKey: String, treeId: Int): TalentTreeSectionCameras =
    rememberSaveable(sessionKey, treeId, saver = TalentTreeSectionCameras.saver(sessionKey, treeId)) {
        TalentTreeSectionCameras(sessionKey = sessionKey, treeId = treeId)
    }
