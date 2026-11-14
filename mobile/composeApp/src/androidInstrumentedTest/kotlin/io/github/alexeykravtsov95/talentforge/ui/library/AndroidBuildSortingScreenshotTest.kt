package io.github.alexeykravtsov95.talentforge.ui.library

import android.app.LocaleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.MainActivity
import kotlin.test.assertIs
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

    private fun seedBuilds() {
        var id = 0
        var time = 1_000L
        val repository = LocalBuildRepository(
            AndroidBuildLibraryStorage(context),
            BuildClock { time++ },
            LocalBuildIdGenerator { "m6-lib3-${++id}" },
        )
        for ((token, name) in listOf("WARRIOR" to "Zulu", "MAGE" to "Alpha")) {
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
