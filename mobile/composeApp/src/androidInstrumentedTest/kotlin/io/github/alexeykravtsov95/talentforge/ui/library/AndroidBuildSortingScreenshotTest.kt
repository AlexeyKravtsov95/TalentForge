package io.github.alexeykravtsov95.talentforge.ui.library

import android.app.LocaleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.LocaleList
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildClock
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildIdGenerator
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.MainActivity
import kotlin.test.assertIs
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android emulator evidence for M6.LIB.3, produced during the
 * isolated Android instrumentation CI. Uses real Compose UI, bundled data
 * and local storage; it neither changes app behavior nor mocks a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBuildSortingScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun favoritesKeepScrollAndSurviveActivityRestartInEnglishAndRussian() {
        check(context.packageName.endsWith(".lib2check")) { "Requires the isolated .lib2check package" }
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val savedLocales = localeManager.applicationLocales
        val savedDefault = LocaleList.getDefault()
        val preferences = context.getSharedPreferences("talentforge_build_library", Context.MODE_PRIVATE)
        try {
            for (ru in listOf(false, true)) {
                val locale = LocaleList.forLanguageTags(if (ru) "ru-RU" else "en-US")
                LocaleList.setDefault(locale)
                localeManager.applicationLocales = locale
                check(preferences.edit().clear().commit())
                seedBuilds((0..11).map { "WARRIOR" to "Build %02d".format(it) })
                val repository = LocalBuildRepository(
                    AndroidBuildLibraryStorage(context), BuildClock { 0L }, LocalBuildIdGenerator { "unused" },
                )
                val before = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository.load()).value
                val add = if (ru) "Добавить в избранное: Build 02" else "Add to Favorites: Build 02"
                val remove = if (ru) "Убрать из избранного: Build 02" else "Remove from Favorites: Build 02"
                val link = if (ru) "Все билды\u00A012" else "All builds 12"
                var activity = instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                try {
                    click(text = link)
                    // Home can also expose the latest build title during navigation;
                    // only All Builds exposes its favorite control once Ready.
                    awaitFavorite(add.replace("Build 02", "Build 11"))
                    scrollToDescription(add)
                    val bounds = Rect().also { awaitNode(description = add).getBoundsInScreen(it) }
                    click(description = add)
                    val starred = awaitFavorite(remove)
                    assertEquals(
                        before.map { if (it.name == "Build 02") it.copy(isFavorite = true) else it },
                        assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository.load()).value,
                    )
                    assertEquals(bounds, Rect().also(starred::getBoundsInScreen), "favorite must preserve the visible row position")
                    click(description = remove)
                    assertEquals(bounds, Rect().also(awaitFavorite(add)::getBoundsInScreen))
                    assertEquals(before, assertIs<BuildLibraryOutcome.Loaded<*>>(repository.load()).value)
                    click(description = add)
                    awaitFavorite(remove)
                    saveScreenshot("android-${if (ru) "ru" else "en"}-favorite-scroll-preserved.png")

                    // A new Activity/App scope must render the persisted favorite.
                    instrumentation.runOnMainSync { activity.finish() }
                    instrumentation.waitForIdleSync()
                    activity = instrumentation.startActivitySync(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    click(text = link)
                    click(text = if (ru) "Избранное" else "Favorites")
                    click(description = remove)
                    awaitText(if (ru) "В избранном пока пусто" else "No favorites yet")
                    val deadline = SystemClock.uptimeMillis() + 20_000
                    while (assertIs<BuildLibraryOutcome.Loaded<*>>(repository.load()).value != before &&
                        SystemClock.uptimeMillis() < deadline
                    ) SystemClock.sleep(50)
                    assertEquals(before, assertIs<BuildLibraryOutcome.Loaded<*>>(repository.load()).value)
                    saveScreenshot("android-${if (ru) "ru" else "en"}-favorites-empty.png")
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally {
            preferences.edit().clear().commit()
            localeManager.applicationLocales = savedLocales
            LocaleList.setDefault(savedDefault)
        }
    }

    private fun scrollToDescription(description: String) {
        repeat(20) {
            if (findNode(null, description)?.isVisibleToUser == true) return
            automation.clearCache()
            val root = automation.rootInActiveWindow ?: error("No active window")
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            nodes.add(root)
            var scrolled = false
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    scrolled = true
                    break
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::addLast)
            }
            check(scrolled) { "Cannot scroll to $description" }
            SystemClock.sleep(200)
        }
        error("Could not make $description visible")
    }

    /** A changed star is optimistic; enabled means the coordinator has finished its write. */
    private fun awaitFavorite(description: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            findNode(null, description)?.takeIf { it.isEnabled }?.let { return it }
            SystemClock.sleep(50)
        }
        error("Favorite write did not settle: $description")
    }

    @Test
    fun captureGroupedLibraryInEnglishAndRussian() {
        check(context.packageName.endsWith(".lib2check")) {
            "Screenshots require the isolated .lib2check package"
        }
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val savedLocales = localeManager.applicationLocales
        val savedDefault = LocaleList.getDefault()
        val preferences = context.getSharedPreferences("talentforge_build_library", Context.MODE_PRIVATE)
        try {
            for ((tag, suffix, classSort, groupToggle, sortingDescription, mage, warrior) in listOf(
                ScreenshotLocale(
                    "en-US", "en", "Class", "Group by class",
                    "Sort and grouping: Last edited, grouping off", "Mage", "Warrior",
                ),
                ScreenshotLocale(
                    "ru-RU", "ru", "По классу", "Группировать по классам",
                    "Сортировка и группировка: Последние изменения, группировка выключена", "Маг", "Воин",
                ),
            )) {
                val locale = LocaleList.forLanguageTags(tag)
                LocaleList.setDefault(locale)
                localeManager.applicationLocales = locale
                check(preferences.edit().clear().commit())
                seedBuilds()
                val activity = instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                try {
                    instrumentation.waitForIdleSync()
                    click(text = if (suffix == "ru") "Все билды\u00A02" else "All builds 2")
                    click(description = sortingDescription)
                    click(text = classSort)
                    click(description = if (suffix == "ru") {
                        "Сортировка и группировка: По классу, группировка выключена"
                    } else {
                        "Sort and grouping: Class, grouping off"
                    })
                    click(text = groupToggle)
                    awaitText("$mage · 1")
                    awaitText("$warrior · 1")
                    SystemClock.sleep(500)
                    saveScreenshot("android-$suffix-393dp-sort-class-grouped.png")
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally {
            preferences.edit().clear().commit()
            localeManager.applicationLocales = savedLocales
            LocaleList.setDefault(savedDefault)
        }
    }

    private fun seedBuilds(builds: List<Pair<String, String>> = listOf("WARRIOR" to "Zulu", "MAGE" to "Alpha")) {
        var id = 0
        var time = 1_000L
        val repository = LocalBuildRepository(
            AndroidBuildLibraryStorage(context),
            BuildClock { time++ },
            LocalBuildIdGenerator { "m6-lib3-${++id}" },
        )
        for ((token, name) in builds) {
            val file = BuildFile(
                game = GameId("forever"),
                clientBuild = "1.60.1.70450",
                classToken = ClassToken(token),
                treeId = 1,
            )
            val created = assertIs<BuildLibraryOutcome.Loaded<*>>(repository.createDraft(file)).value
                as io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
            assertIs<BuildLibraryOutcome.Loaded<*>>(repository.renameBuild(created.localId, name))
        }
    }

    private data class ScreenshotLocale(
        val tag: String,
        val suffix: String,
        val classSort: String,
        val groupToggle: String,
        val sortingDescription: String,
        val mage: String,
        val warrior: String,
    )

    private fun findNode(text: String?, description: String?): AccessibilityNodeInfo? {
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        val candidates = ArrayDeque<AccessibilityNodeInfo>()
        candidates.add(root)
        while (candidates.isNotEmpty()) {
            val current = candidates.removeFirst()
            if ((text != null && current.text?.toString() == text) ||
                (description != null && current.contentDescription?.toString() == description)
            ) {
                return current
            }
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let(candidates::addLast)
            }
        }
        return null
    }

    private fun awaitNode(text: String? = null, description: String? = null): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            findNode(text, description)?.let { return it }
            SystemClock.sleep(200)
        }
        error("Accessibility node not found: text=$text, description=$description")
    }

    private fun awaitText(text: String) {
        awaitNode(text = text)
    }

    private fun click(text: String? = null, description: String? = null) {
        val node = awaitNode(text, description)
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                SystemClock.sleep(350)
                return
            }
            candidate = candidate.parent
        }
        error("No clickable ancestor: text=$text, description=$description")
    }

    private fun saveScreenshot(fileName: String) {
        val bitmap = checkNotNull(automation.takeScreenshot()) { "Android UiAutomation screenshot unavailable" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TalentForge-M6-LIB3")
        }
        val uri = checkNotNull(
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "Could not create emulator screenshot" }
        context.contentResolver.openOutputStream(uri)!!.use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
