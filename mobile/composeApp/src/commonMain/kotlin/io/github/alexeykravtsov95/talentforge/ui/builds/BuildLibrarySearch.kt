package io.github.alexeykravtsov95.talentforge.ui.builds

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.ui.library.BuildCardModel

/** Localized presentation strings resolved by the same functions as the visible card. Never stored. */
internal data class BuildLibrarySearchEntry(
    val card: BuildCardModel,
    val title: String,
    val className: String,
)

/** Pure, stable intersection: never sorts, changes a record, or touches a repository. */
internal fun filterBuildLibrary(
    entries: List<BuildLibrarySearchEntry>,
    query: String,
    selectedClass: ClassToken?,
): List<BuildLibrarySearchEntry> {
    val text = query.trim()
    return entries.filter { entry ->
        (selectedClass == null || entry.card.record.build.classToken == selectedClass) &&
            (text.isEmpty() || entry.title.contains(text, ignoreCase = true) ||
                entry.className.contains(text, ignoreCase = true))
    }
}
