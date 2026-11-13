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

class BuildLibrarySearchTest {
    private fun entry(id: Int, title: String, className: String, token: String = "WARRIOR") =
        BuildLibrarySearchEntry(
            BuildCardModel(
                LocalBuildRecord("opaque-$id", title, 0L, id.toLong(), null,
                    BuildFile(game = GameId("forever"), clientBuild = "1.60.1.70450", classToken = ClassToken(token), treeId = 1)),
                className, 0, 51,
            ),
            title,
            className,
        )

    @Test
    fun emptyAndWhitespaceQueriesKeepOneTenAndOneHundredRecordsInTheirOriginalOrder() {
        for (size in listOf(1, 10, 100)) {
            val entries = (size downTo 1).map { entry(it, "Build $it", "Warrior") }
            for (query in listOf("", " ", "\t\n")) {
                val result = filterBuildLibrary(entries, query, null)
                assertEquals(entries, result)
                result.zip(entries).forEach { (actual, original) -> assertSame(original, actual) }
            }
        }
        assertTrue(filterBuildLibrary(emptyList(), "", null).isEmpty())
    }

    @Test
    fun aHundredBuildsNarrowByNameAndClassWithoutReorderingTheMatches() {
        val entries = (100 downTo 1).map {
            entry(it, "Build $it", if (it % 2 == 0) "Mage" else "Warrior", if (it % 2 == 0) "MAGE" else "WARRIOR")
        }
        assertEquals(
            listOf(100, 18, 16, 14, 12, 10).map { "opaque-$it" },
            filterBuildLibrary(entries, "Build 1", ClassToken("MAGE")).map { it.card.record.localId },
        )
    }

    @Test
    fun titleAndClassSearchAreTrimmedCaseInsensitiveAndPreserveTheRepositoryOrder() {
        val entries = listOf(entry(9, "Raid Fury", "Warrior"), entry(8, "Frost", "Mage", "MAGE"), entry(2, "Raid Arms", "Warrior"))
        assertEquals(listOf(entries[0], entries[2]), filterBuildLibrary(entries, "  rAiD ", null))
        assertEquals(listOf(entries[0], entries[2]), filterBuildLibrary(entries, "WaRrIoR", null))
        assertEquals(listOf(entries[1]), filterBuildLibrary(entries, " frost ", null))
        assertTrue(filterBuildLibrary(entries, "missing", null).isEmpty())
    }

    @Test
    fun russianDisplayCopyAndClassFilterIntersectWithoutChangingRecords() {
        val entries = listOf(entry(1, "Билд воина", "Воин"), entry(2, "Билд мага", "Маг", "MAGE"), entry(3, "Рейд", "Маг", "MAGE"))
        assertEquals(listOf(entries[0]), filterBuildLibrary(entries, " БИЛД ВОИНА ", null))
        assertEquals(listOf(entries[1], entries[2]), filterBuildLibrary(entries, " мАГ ", null))
        assertEquals(listOf(entries[1]), filterBuildLibrary(entries, "билд", ClassToken("MAGE")))
        assertEquals(listOf(entries[1], entries[2]), filterBuildLibrary(entries, "", ClassToken("MAGE")))
        assertTrue(filterBuildLibrary(entries, "воин", ClassToken("MAGE")).isEmpty())
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.card.record.updatedAt })
        assertTrue(entries.all { it.card.record.lastOpenedAt == null })
    }
    @Test
    fun searchClassAndFavoritesIntersectStablyAcrossVersionsAndLibrarySizes() {
        for (size in listOf(0, 1, 10, 100)) {
            val entries = (size downTo 1).map { index ->
                val entry = entry(index, "Raid $index", "Warrior")
                entry.copy(card = entry.card.copy(record = entry.card.record.copy(
                    isFavorite = index % 2 == 1,
                    build = entry.card.record.build.copy(clientBuild = if (index % 3 == 0) "1.60.1.70374" else "1.60.1.70450"),
                )))
            }
            val expected = entries.filter { it.card.record.isFavorite && it.title.startsWith("Raid 1") }
            assertEquals(expected, filterBuildLibrary(entries, "raid 1", ClassToken("WARRIOR"), true))
            assertTrue(filterBuildLibrary(entries, "Raid", ClassToken("MAGE"), true).isEmpty())
        }
    }

}
