package io.github.alexeykravtsov95.talentforge.ui.library

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexeykravtsov95.talentforge.data.builds.*
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SharedPreferences, only in the isolated test application's data directory. */
@RunWith(AndroidJUnit4::class)
class AndroidBuildMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("talentforge_build_library", Context.MODE_PRIVATE)
    private val payload get() = instrumentation.context.assets.open("local-library-v1.json").bufferedReader().use { it.readText() }
    private fun repository(storage: BuildLibraryStorage = AndroidBuildLibraryStorage(context)) =
        LocalBuildRepository(storage, BuildClock { 1900000000000 }, LocalBuildIdGenerator { "fresh-copy" })
    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T = assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    @Before fun seed() {
        check(context.packageName.endsWith(".lib2check")) { "Use -Ptalentforge.applicationIdSuffix=.lib2check" }
        assertTrue(preferences.edit().clear().putString(BuildLibraryStorage.STORAGE_KEY, payload).commit())
    }
    @After fun clear() {
        if (context.packageName.endsWith(".lib2check")) preferences.edit().clear().commit()
    }

    @Test fun realV1SharedPreferencesMigratesAllBuildsAndReopensWithFavorites() {
        val repo = repository()
        val before = loaded(repo.load())
        assertEquals(3, before.size)
        assertTrue(before.all { !it.isFavorite })
        assertEquals(payload, preferences.getString(BuildLibraryStorage.STORAGE_KEY, null))
        loaded(repo.setFavorite("legacy-2", true))
        val after = loaded(repository().load())
        assertEquals(before.map { if (it.localId == "legacy-2") it.copy(isFavorite = true) else it }, after)
        loaded(repo.setFavorite("legacy-2", false))
        assertEquals(before, loaded(repository().load()))
    }

    @Test fun failedCommitRestoresExactV1InMemoryAndOnDiskForFalseAndThrow() {
        for (throws in listOf(false, true)) {
            assertTrue(preferences.edit().putString(BuildLibraryStorage.STORAGE_KEY, payload).commit())
            var first = true
            val failing = object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean {
                            // Reproduce the platform's changed-memory failure, using actual preferences.
                            val result = editor.commit()
                            if (!first) return result
                            first = false
                            if (throws) throw IllegalStateException("injected commit failure")
                            return false
                        }
                    }
                }
            }
            assertIs<BuildLibraryError.StorageWrite>(assertIs<BuildLibraryOutcome.Failed>(repository(AndroidBuildLibraryStorage(failing)).setFavorite("legacy-2", true)).error)
            assertEquals(payload, AndroidBuildLibraryStorage(context).read())
            val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/talentforge_build_library.xml")
            val xml = android.util.Xml.newPullParser()
            file.reader().use { reader ->
                xml.setInput(reader)
                var restored: String? = null
                while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.getAttributeValue(null, "name") == BuildLibraryStorage.STORAGE_KEY) {
                        restored = xml.nextText()
                    }
                    xml.next()
                }
                assertEquals(payload, restored)
            }
        }
    }

    @Test fun corruptOrFutureSharedPreferencesAreNotOverwritten() {
        for (bad in listOf("{", "{\"storageVersion\":99,\"future\":true}")) {
            preferences.edit().putString(BuildLibraryStorage.STORAGE_KEY, bad).commit()
            val repo = repository()
            assertIs<BuildLibraryOutcome.Failed>(repo.load())
            assertIs<BuildLibraryOutcome.Failed>(repo.setFavorite("legacy-1", true))
            assertEquals(bad, AndroidBuildLibraryStorage(context).read())
        }
    }
}
