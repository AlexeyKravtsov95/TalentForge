package io.github.alexeykravtsov95.talentforge.ui.builds

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.ui.library.BuildCardModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BuildLibrarySortingTest {
    private fun entry(
        id: String,
        title: String,
        token: String = "WARRIOR",
        className: String = "Warrior",
        edited: Long = 0L,
        opened: Long? = null,
        favorite: Boolean = false,
        persistedName: String? = title,
    ): BuildLibrarySearchEntry {
        val record = LocalBuildRecord(
            localId = id,
            name = persistedName,
            createdAt = 1L,
            updatedAt = edited,
            lastOpenedAt = opened,
            build = BuildFile(
                game = GameId("forever"),
                clientBuild = "1.60.1.70450",
                classToken = ClassToken(token),
                treeId = 1,
            ),
            isFavorite = favorite,
        )
        return BuildLibrarySearchEntry(BuildCardModel(record, className, 0, 51), title, className)
    }

    private fun ids(entries: List<BuildLibrarySearchEntry>) = entries.map { it.card.record.localId }

    @Test
    fun newestEditedWinsEvenWhenOpenedTimestampsDisagreeAndTiesUseLocalId() {
        val items = listOf(
            entry("c", "Older", edited = 5L, opened = 10_000L),
            entry("b", "Newer B", edited = 20L, opened = null),
            entry("a", "Newer A", edited = 20L, opened = 1L),
        )
        assertEquals(listOf("a", "b", "c"), ids(sortBuildLibrary(items, BuildLibrarySortMode.LAST_EDITED)))
        assertEquals(
            ids(sortBuildLibrary(items, BuildLibrarySortMode.LAST_EDITED)),
            ids(sortBuildLibrary(items.reversed(), BuildLibrarySortMode.LAST_EDITED)),
        )
    }

    @Test
    fun nameSortUsesDisplayTitleAndStableTimestampAndIdTies() {
        val items = listOf(
            entry("z", "Beta", edited = 300L),
            entry("b", "alpha", edited = 100L),
            entry("c", "Alpha", edited = 200L),
            entry("a", "ALPHA", edited = 200L),
        )
        assertEquals(listOf("a", "c", "b", "z"), ids(sortBuildLibrary(items, BuildLibrarySortMode.NAME)))
        assertEquals(
            ids(sortBuildLibrary(items, BuildLibrarySortMode.NAME)),
            ids(sortBuildLibrary(items.reversed(), BuildLibrarySortMode.NAME)),
        )
    }

    @Test
    fun allNineClassesGroupByStableTokenButSortUsingLocalizedLabels() {
        val classes = listOf(
            Triple("WARRIOR", "Warrior", "Воин"),
            Triple("PALADIN", "Paladin", "Паладин"),
            Triple("HUNTER", "Hunter", "Охотник"),
            Triple("ROGUE", "Rogue", "Разбойник"),
            Triple("PRIEST", "Priest", "Жрец"),
            Triple("SHAMAN", "Shaman", "Шаман"),
            Triple("MAGE", "Mage", "Маг"),
            Triple("WARLOCK", "Warlock", "Чернокнижник"),
            Triple("DRUID", "Druid", "Друид"),
        )
        for (russian in listOf(false, true)) {
            val entries = classes.mapIndexed { index, (token, en, ru) ->
                entry("id-$index", "Draft", token, if (russian) ru else en, edited = index.toLong())
            }
            val expected = entries.sortedBy { it.className.lowercase() }.map { it.card.record.build.classToken }
            val groups = groupBuildLibrary(entries, BuildLibrarySortMode.CLASS)
            assertEquals(9, groups.size)
            assertEquals(expected, groups.map { it.classToken })
            assertTrue(groups.all { it.entries.size == 1 })
            assertEquals(expected, sortBuildLibrary(entries, BuildLibrarySortMode.CLASS).map { it.card.record.build.classToken })
        }
    }

    @Test
    fun localizedUnnamedDraftTitlesDriveNameSortWithoutPersistingAName() {
        for ((first, second) in listOf("Mage build" to "Warrior build", "Билд воина" to "Билд мага")) {
            val one = entry("one", first, persistedName = null)
            val two = entry("two", second, persistedName = null, edited = 2L)
            val expected = listOf(one, two).sortedBy { it.title.lowercase() }
            assertEquals(ids(expected), ids(sortBuildLibrary(listOf(two, one), BuildLibrarySortMode.NAME)))
            assertTrue(listOf(one, two).all { it.card.record.name == null })
        }
    }

    @Test
    fun selectedOrderAppliesWithinEveryGroupAndEmptyGroupsNeverAppear() {
        val entries = listOf(
            entry("m2", "A", "MAGE", "Mage", edited = 1),
            entry("m1", "Z", "MAGE", "Mage", edited = 2),
            entry("w2", "C", edited = 4),
            entry("w1", "B", edited = 3),
        )
        for (mode in BuildLibrarySortMode.entries) {
            val groups = groupBuildLibrary(entries, mode)
            assertEquals(listOf("Mage", "Warrior"), groups.map { it.className })
            groups.forEach { group ->
                assertEquals(
                    ids(sortBuildLibrary(entries.filter { it.card.record.build.classToken == group.classToken }, mode)),
                    ids(group.entries),
                )
            }
        }
        assertTrue(groupBuildLibrary(emptyList(), BuildLibrarySortMode.NAME).isEmpty())
        assertEquals(1, groupBuildLibrary(entries.take(1), BuildLibrarySortMode.CLASS).size)
    }

    @Test
    fun searchClassFavoriteAndSortAreComposedAndRenameDuplicateDeleteReevaluate() {
        val original = listOf(
            entry("a", "Raid Arms", edited = 1, favorite = true),
            entry("b", "Frost", "MAGE", "Mage", edited = 3, favorite = true),
            entry("c", "Raid Defense", edited = 2, favorite = false),
        )
        fun filtered(items: List<BuildLibrarySearchEntry>) =
            sortBuildLibrary(filterBuildLibrary(items, "raid", ClassToken("WARRIOR"), true), BuildLibrarySortMode.NAME)
        assertEquals(listOf("a"), ids(filtered(original)))
        val renamed = original.map { if (it.card.record.localId == "a") it.copy(title = "Duel", card = it.card.copy(record = it.card.record.copy(name = "Duel"))) else it }
        assertTrue(filtered(renamed).isEmpty())
        val duplicates = renamed + entry("d", "Raid Arms (copy)", edited = 9)
        assertEquals(emptyList(), ids(filtered(duplicates))) // copy is not a favorite
        val deleted = duplicates.filterNot { it.card.record.localId == "a" }
        assertEquals(listOf("b", "c", "d"), ids(deleted))
        assertEquals(3, deleted.size)
    }

    @Test
    fun sizesAndSortModesNeverMutateRecordsOrChangeTheObjects() {
        for (size in listOf(0, 1, 10, 100)) {
            val entries = (0 until size).map {
                entry("id-$it", "Build $it", if (it % 2 == 0) "MAGE" else "WARRIOR",
                    if (it % 2 == 0) "Маг" else "Воин", edited = (it % 5).toLong())
            }
            val snapshot = entries.map { it.card.record.copy() }
            for (mode in BuildLibrarySortMode.entries) {
                val sorted = sortBuildLibrary(entries, mode)
                assertEquals(size, sorted.size)
                sorted.forEach { sortedEntry ->
                    assertSame(entries.first { it.card.record.localId == sortedEntry.card.record.localId }, sortedEntry)
                }
                val groups = groupBuildLibrary(entries, mode)
                assertEquals(size, groups.sumOf { it.entries.size })
                assertEquals(snapshot, entries.map { it.card.record })
            }
        }
    }
}
