package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.builds.*
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.ShellLibraryFixtures
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.share.rememberPlatformBuildCodeSharing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real screen, coordinator, v2 repository and bundled datasets; only storage I/O is controlled. */
@RunWith(Parameterized::class)
class SeamlessFavoritesUiTest(private val language: AppLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun languages() = AppLanguage.entries.map { arrayOf(it) }
    }

    @get:Rule val locale = AppLanguageRule(language)
    @get:Rule val ui = createComposeRule()
    private val ru get() = language == AppLanguage.RUSSIAN
    private fun copy(en: String, ru: String) = if (this.ru) ru else en
    private val backing = FakeBuildLibraryStorage()
    private val storage = ObservedStorage(backing)
    private val assetReads = AtomicInteger()
    private val clock = FixedClock()
    private val restart = mutableStateOf(0)
    private var initialReads = 0
    private var initialAssets = 0
    private var initialWrites = 0
    private var coordinator: BuildAutosaveCoordinator? = null

    @After fun releaseWrite() { storage.release.countDown() }

    private fun records() = LocalBuildLibraryCodec.decode(backing.payload!!)
    private fun star(name: String, favorite: Boolean) = ui.onNodeWithContentDescription(
        if (favorite) copy("Remove from Favorites: $name", "Убрать из избранного: $name")
        else copy("Add to Favorites: $name", "Добавить в избранное: $name"),
    )
    private fun awaitReady() = ui.waitUntil(10_000) {
        ui.onAllNodesWithContentDescription(copy("Search builds", "Поиск билдов")).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitSaved(name: String, favorite: Boolean) = ui.waitUntil(10_000) {
        ui.onAllNodes(hasContentDescription(
            if (favorite) copy("Remove from Favorites: $name", "Убрать из избранного: $name")
            else copy("Add to Favorites: $name", "Добавить в избранное: $name"),
        ) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun start(favorites: Set<String> = emptySet(), many: Boolean = false) {
        val builds = if (many) (0..19).map { ShellLibraryFixtures.Build("WARRIOR", name = "Build %02d".format(it)) }
        else listOf(
            ShellLibraryFixtures.Build("WARRIOR", name = "Raid Alpha"),
            ShellLibraryFixtures.Build("WARRIOR", name = "Raid Zulu"),
            ShellLibraryFixtures.Build("MAGE", name = "Frost"),
        )
        ShellLibraryFixtures.persist(backing, clock, builds)
        backing.payload = LocalBuildLibraryCodec.encode(records().map { it.copy(isFavorite = it.name in favorites) })
        initialWrites = backing.writeCount
        ui.setContent {
            TalentForgeTheme {
                Box(Modifier.size(393.dp, 852.dp)) {
                    key(restart.value) {
                        val scope = rememberCoroutineScope()
                        val repository = remember { LocalBuildRepository(storage, clock, SequentialIds()) }
                        val autosave = remember { BuildAutosaveCoordinator(repository, scope) }
                        coordinator = autosave
                        val datasets = remember {
                            val assets = ComposeResourceDatasetAssetSource()
                            BundledTalentDatasetRepository(object : DatasetAssetSource {
                                override suspend fun read(path: String): ByteArray {
                                    assetReads.incrementAndGet()
                                    return assets.read(path)
                                }
                            })
                        }
                        AllBuildsScreen(repository, datasets, clock::nowMillis, {}, {}, {}, {},
                            rememberPlatformBuildCodeSharing(), autosave)
                    }
                }
            }
        }
        awaitReady()
        initialReads = storage.reads.get()
        initialAssets = assetReads.get()
        assertTrue(initialAssets > 0, "the initial model must resolve real dataset metadata")
    }

    private fun assertNoReload(operations: Int = 1) {
        ui.waitForIdle()
        ui.onNodeWithText(copy("Loading your builds…", "Загрузка билдов…")).assertDoesNotExist()
        // setFavorite's read–modify–write reads once. loadBuildsModel would add another read.
        assertEquals(initialReads + operations, storage.reads.get(), "no repeated loadBuildsModel")
        assertEquals(initialAssets, assetReads.get(), "no dataset/manifest reread or metadata resolution")
    }

    @Test fun addingFavoriteNeverReloadsOrChangesOtherRecords() {
        start()
        val before = records()
        star("Raid Alpha", false).performScrollTo().performClick()
        awaitSaved("Raid Alpha", true)
        assertNoReload()
        assertEquals(before.map { if (it.name == "Raid Alpha") it.copy(isFavorite = true) else it }, records())
        assertEquals(initialWrites + 1, backing.writeCount)
    }

    @Test fun removingFavoriteNeverReloadsOrChangesOtherRecords() {
        start(setOf("Raid Alpha"))
        val before = records()
        star("Raid Alpha", true).performScrollTo().performClick()
        awaitSaved("Raid Alpha", false)
        assertNoReload()
        assertEquals(before.map { if (it.name == "Raid Alpha") it.copy(isFavorite = false) else it }, records())
        assertEquals(initialWrites + 1, backing.writeCount)
    }

    @Test fun starRespondsBeforeStorageConfirmsAndRapidTapsCannotConflict() {
        start()
        storage.block = true
        star("Raid Alpha", false).performScrollTo()
        // Keep the stale callback too: the guard must work before a recomposition disables the target.
        val click = star("Raid Alpha", false).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        ui.runOnIdle { repeat(5) { click() } }
        ui.waitUntil(10_000) { storage.entered.count == 0L }
        star("Raid Alpha", true).assertIsOn().assertIsNotEnabled()
        star("Raid Zulu", false).assertIsOff().assertIsNotEnabled()
        assertEquals(1, storage.attempts.get())
        assertTrue(records().none { it.isFavorite }, "pending UI must not perform a second write")
        ui.runOnIdle { repeat(5) { click() } }
        storage.release.countDown()
        awaitSaved("Raid Alpha", true)
        assertNoReload()
        assertEquals(1, storage.attempts.get())
        assertEquals(initialWrites + 1, backing.writeCount)
    }

    @Test fun scrollOffsetAndVisibleRowStayFixedWhenMembershipDoesNotChange() {
        start(many = true)
        star("Build 03", false).performScrollTo()
        val scroll = ui.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val offset = scroll.value()
        val bounds = ui.onNodeWithText("Build 03").fetchSemanticsNode().boundsInRoot
        assertTrue(offset > 0f)
        star("Build 03", false).performClick()
        awaitSaved("Build 03", true)
        assertNoReload()
        assertEquals(offset, ui.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        assertEquals(bounds, ui.onNodeWithText("Build 03").fetchSemanticsNode().boundsInRoot)
        star("Build 03", true).performClick()
        awaitSaved("Build 03", false)
        assertNoReload(2)
        assertEquals(offset, ui.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
    }

    @Test fun favoritesOnlyRemovesRowAndCanIncludeItAgainWithoutReloading() {
        start(setOf("Raid Alpha"))
        ui.onNodeWithText(copy("Favorites", "Избранное")).performScrollTo().performClick()
        star("Raid Alpha", true).performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText(copy("No favorites yet", "В избранном пока пусто")).fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { backing.writeCount == initialWrites + 1 }
        ui.onNodeWithText("Raid Alpha").assertDoesNotExist()
        assertNoReload()
        ui.onNodeWithText(copy("Favorites", "Избранное")).performScrollTo().performClick()
        awaitSaved("Raid Alpha", false)
        star("Raid Alpha", false).performScrollTo().performClick()
        awaitSaved("Raid Alpha", true)
        ui.onNodeWithText(copy("Favorites", "Избранное")).performScrollTo().performClick()
        ui.onNodeWithText("Raid Alpha").assertExists()
        ui.onNodeWithText("Frost").assertDoesNotExist()
        assertNoReload(2)
    }

    private fun ordering(name: Boolean = false, grouped: Boolean = false) = ui.onNodeWithContentDescription(
        copy("Sort and grouping: ${if (name) "Name" else "Last edited"}, grouping ${if (grouped) "on" else "off"}",
            "Сортировка и группировка: ${if (name) "По названию" else "Последние изменения"}, группировка ${if (grouped) "включена" else "выключена"}"),
    )

    @Test fun groupingUpdatesOnlyTheAffectedClassCount() {
        start(setOf("Raid Alpha", "Raid Zulu", "Frost"))
        ordering().performScrollTo().performClick()
        ui.onNodeWithText(copy("Group by class", "Группировать по классам")).performClick()
        ui.onNodeWithText(copy("Favorites", "Избранное")).performScrollTo().performClick()
        ui.onNodeWithText(copy("Warrior · 2", "Воин · 2")).assertExists()
        star("Raid Alpha", true).performScrollTo().performClick()
        ui.waitUntil(10_000) { backing.writeCount == initialWrites + 1 }
        awaitSaved("Raid Zulu", true)
        ui.onNodeWithText(copy("Warrior · 1", "Воин · 1")).assertExists()
        ui.onNodeWithText(copy("Mage · 1", "Маг · 1")).assertExists()
        ui.onNodeWithText(copy("Warrior · 2", "Воин · 2")).assertDoesNotExist()
        assertNoReload()
    }

    @Test fun searchClassFavoritesAndNameSortingKeepTheirIntersection() {
        start(setOf("Raid Alpha", "Raid Zulu", "Frost"))
        ordering().performScrollTo().performClick()
        ui.onNodeWithText(copy("Name", "По названию")).performClick()
        ui.onNodeWithContentDescription(copy("Search builds", "Поиск билдов")).performScrollTo().performTextReplacement("Raid")
        ui.onNodeWithContentDescription(copy("Filter by class: All classes", "Фильтр по классу: Все классы")).performScrollTo().performClick()
        ui.onNodeWithText(copy("Warrior", "Воин")).performScrollTo().performClick()
        ui.onNodeWithText(copy("Favorites", "Избранное")).performScrollTo().performClick()
        assertTrue(ui.onNodeWithText("Raid Alpha").fetchSemanticsNode().boundsInRoot.top < ui.onNodeWithText("Raid Zulu").fetchSemanticsNode().boundsInRoot.top)
        star("Raid Alpha", true).performScrollTo().performClick()
        ui.waitUntil(10_000) { backing.writeCount == initialWrites + 1 }
        awaitSaved("Raid Zulu", true)
        ui.onNodeWithText("Raid Alpha").assertDoesNotExist()
        ui.onNodeWithText("Frost").assertDoesNotExist()
        ui.onNodeWithText(copy("Favorites", "Избранное")).assertIsOn()
        ordering(name = true).assertExists()
        ui.onNodeWithContentDescription(copy("Filter by class: Warrior", "Фильтр по классу: Воин")).assertExists()
        ui.onNodeWithContentDescription(copy("Search builds", "Поиск билдов")).assertTextContains("Raid")
        assertNoReload()
    }

    private val failure get() = copy("Could not update favorites. Your build is unchanged.", "Не удалось обновить избранное. Билд не изменён.")
    private fun awaitFailure() = ui.waitUntil(10_000) { ui.onAllNodesWithText(failure).fetchSemanticsNodes().isNotEmpty() }

    @Test fun failedAddRestoresConfirmedStateAndRetryPersistsExactlyOnce() {
        start()
        val before = backing.payload
        backing.failWrites = true
        star("Raid Alpha", false).performScrollTo().performClick()
        awaitFailure()
        star("Raid Alpha", false).assertIsOff().assertIsEnabled()
        assertEquals(before, backing.payload)
        assertEquals(initialWrites, backing.writeCount)
        assertNoReload()
        backing.failWrites = false
        ui.onNodeWithText(copy("Retry", "Повторить")).performClick()
        awaitSaved("Raid Alpha", true)
        ui.onNodeWithText(failure).assertDoesNotExist()
        assertEquals(initialWrites + 1, backing.writeCount)
        assertNoReload(2)
    }

    @Test fun failedRemovalRestoresTheRowAndGroupCountInFavoritesOnly() {
        start(setOf("Raid Alpha", "Raid Zulu"))
        ordering().performScrollTo().performClick()
        ui.onNodeWithText(copy("Group by class", "Группировать по классам")).performClick()
        ui.onNodeWithText(copy("Favorites", "Избранное")).performScrollTo().performClick()
        val before = backing.payload
        storage.block = true
        backing.failWrites = true
        star("Raid Alpha", true).performScrollTo().performClick()
        ui.waitUntil(10_000) { storage.entered.count == 0L }
        ui.onNodeWithText("Raid Alpha").assertDoesNotExist()
        ui.onNodeWithText(copy("Warrior · 1", "Воин · 1")).assertExists()
        storage.release.countDown()
        awaitFailure()
        star("Raid Alpha", true).assertIsOn().assertIsEnabled()
        ui.onNodeWithText(copy("Warrior · 2", "Воин · 2")).assertExists()
        assertEquals(before, backing.payload)
        assertNoReload()
    }

    @Test fun pendingAutosaveFailureRefusesFavoriteAndRetryUsesConfirmedRecord() {
        start()
        val target = records().first { it.name == "Raid Alpha" }
        backing.failWrites = true
        ui.runOnIdle { coordinator!!.submit(target.localId, target.build) }
        ui.waitUntil(10_000) { coordinator!!.lastOutcome.value?.failed == true }
        val reads = storage.reads.get()
        val attempts = storage.attempts.get()
        star("Raid Alpha", false).performScrollTo().performClick()
        val pending = copy("Save your latest talent changes before updating favorites.", "Сохраните последние изменения талантов перед изменением избранного.")
        ui.waitUntil(10_000) { ui.onAllNodesWithText(pending).fetchSemanticsNodes().isNotEmpty() }
        star("Raid Alpha", false).assertIsOff()
        assertEquals(reads, storage.reads.get())
        assertEquals(attempts, storage.attempts.get(), "favorite must not write after a failed prerequisite save")
        backing.failWrites = false
        clock.advanceBy(1000)
        ui.runOnIdle { coordinator!!.submit(target.localId, target.build) }
        ui.waitUntil(10_000) { coordinator!!.lastOutcome.value?.failed == false }
        val saved = records().first { it.localId == target.localId }
        ui.onNodeWithText(copy("Retry", "Повторить")).performClick()
        awaitSaved("Raid Alpha", true)
        assertEquals(saved.copy(isFavorite = true), records().first { it.localId == target.localId })
        // Saved.record includes the just-flushed timestamp; keeping the pre-write
        // optimistic record would leave Alpha below Zulu in Last edited order.
        assertTrue(ui.onNodeWithText("Raid Alpha").fetchSemanticsNode().boundsInRoot.top < ui.onNodeWithText("Raid Zulu").fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithText(pending).assertDoesNotExist()
        assertEquals(initialAssets, assetReads.get())
    }

    @Test fun recreatingTheAppScopeReadsThePersistedFavoriteWithoutAnotherWrite() {
        start()
        star("Raid Alpha", false).performScrollTo().performClick()
        awaitSaved("Raid Alpha", true)
        val confirmed = records()
        val writes = backing.writeCount
        ui.runOnIdle { restart.value++ }
        awaitReady()
        awaitSaved("Raid Alpha", true)
        assertEquals(confirmed, records())
        assertEquals(writes, backing.writeCount)
        star("Raid Alpha", true).performScrollTo().performClick()
        awaitSaved("Raid Alpha", false)
        ui.runOnIdle { restart.value++ }
        awaitReady()
        awaitSaved("Raid Alpha", false)
        assertTrue(records().none { it.isFavorite })
        assertEquals(writes + 1, backing.writeCount)
    }

    private class ObservedStorage(private val backing: BuildLibraryStorage) : BuildLibraryStorage {
        val reads = AtomicInteger()
        val attempts = AtomicInteger()
        @Volatile var block = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun read(): String? { reads.incrementAndGet(); return backing.read() }
        override fun write(payload: String) {
            attempts.incrementAndGet()
            if (block) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "test did not release the write" }
            }
            backing.write(payload)
        }
    }
}
