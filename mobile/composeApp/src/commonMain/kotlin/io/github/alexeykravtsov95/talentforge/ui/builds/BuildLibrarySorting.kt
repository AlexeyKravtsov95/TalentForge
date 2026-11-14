package io.github.alexeykravtsov95.talentforge.ui.builds

import io.github.alexeykravtsov95.talentforge.data.ClassToken

/**
 * Display-only ordering. Never reads or writes a repository: the names passed in
 * [BuildLibrarySearchEntry] are the same localized titles/classes shown by the UI.
 */
internal enum class BuildLibrarySortMode {
    LAST_EDITED,
    NAME,
    CLASS,
}

internal data class BuildLibraryGroup(
    val classToken: ClassToken,
    val className: String,
    val entries: List<BuildLibrarySearchEntry>,
)

/** Total ordering, with opaque localId as the final tie-breaker in every mode. */
internal fun sortBuildLibrary(
    entries: List<BuildLibrarySearchEntry>,
    mode: BuildLibrarySortMode,
): List<BuildLibrarySearchEntry> {
    val comparator = when (mode) {
        BuildLibrarySortMode.LAST_EDITED ->
            compareByDescending<BuildLibrarySearchEntry> { it.card.record.updatedAt }
                .thenBy { it.card.record.localId }

        BuildLibrarySortMode.NAME ->
            compareBy<BuildLibrarySearchEntry> { it.title.lowercase() }
                .thenByDescending { it.card.record.updatedAt }
                .thenBy { it.card.record.localId }

        BuildLibrarySortMode.CLASS ->
            compareBy<BuildLibrarySearchEntry> { it.className.lowercase() }
                .thenBy { it.card.record.build.classToken.value }
                .thenBy { it.title.lowercase() }
                .thenByDescending { it.card.record.updatedAt }
                .thenBy { it.card.record.localId }
    }
    return entries.sortedWith(comparator)
}

/**
 * Group by stable game class tokens, never translated labels (two labels can
 * coincide). Group headers follow the localized class names; the selected
 * sort still applies within every group. Empty groups are never produced.
 */
internal fun groupBuildLibrary(
    entries: List<BuildLibrarySearchEntry>,
    mode: BuildLibrarySortMode,
): List<BuildLibraryGroup> =
    sortBuildLibrary(entries, mode)
        .groupBy { it.card.record.build.classToken }
        .map { (token, matches) -> BuildLibraryGroup(token, matches.first().className, matches) }
        .sortedWith(
            compareBy<BuildLibraryGroup> { it.className.lowercase() }
                .thenBy { it.classToken.value },
        )
