package io.github.alexeykravtsov95.talentforge.ui.library

import android.content.Context
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryReadException
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryWriteException

/**
 * [BuildLibraryStorage] over Android `SharedPreferences` (application context,
 * injected by the Android host). One private string cell; `commit()` is
 * synchronous so a failed write surfaces instead of silently deferring.
 */
class AndroidBuildLibraryStorage(context: Context) : BuildLibraryStorage {

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = try {
        preferences.getString(BuildLibraryStorage.STORAGE_KEY, null)
    } catch (e: Exception) {
        throw BuildLibraryReadException(e.message ?: "SharedPreferences read failed")
    }

    override fun write(payload: String) {
        val committed = try {
            preferences.edit()
                .putString(BuildLibraryStorage.STORAGE_KEY, payload)
                .commit()
        } catch (e: Exception) {
            throw BuildLibraryWriteException(e.message ?: "SharedPreferences write failed")
        }
        if (!committed) {
            throw BuildLibraryWriteException("SharedPreferences commit returned false")
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "talentforge_build_library"
    }
}
