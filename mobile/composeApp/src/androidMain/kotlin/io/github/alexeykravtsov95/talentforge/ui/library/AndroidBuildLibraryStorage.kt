package io.github.alexeykravtsov95.talentforge.ui.library

import android.content.Context
import android.content.SharedPreferences
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryReadException
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryWriteException

/**
 * [BuildLibraryStorage] over Android `SharedPreferences` (application context,
 * injected by the Android host). One private string cell; `commit()` is
 * synchronous so a failed write surfaces instead of silently deferring.
 */
class AndroidBuildLibraryStorage internal constructor(
    private val preferences: SharedPreferences,
) : BuildLibraryStorage {

    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    override fun read(): String? = synchronized(preferences) { try {
        preferences.getString(BuildLibraryStorage.STORAGE_KEY, null)
    } catch (e: Exception) {
        throw BuildLibraryReadException(e.message ?: "SharedPreferences read failed")
    } }

    override fun write(payload: String) = synchronized(preferences) {
        // commit() updates SharedPreferences' memory BEFORE reporting a disk
        // failure. Restore the prior cell on false/throw so a retry/reload cannot
        // mistake a failed migration for a confirmed write.
        val previous = try {
            preferences.getString(BuildLibraryStorage.STORAGE_KEY, null)
        } catch (e: Exception) {
            throw BuildLibraryWriteException(e.message ?: "SharedPreferences snapshot failed")
        }
        val failure = try {
            if (preferences.edit().putString(BuildLibraryStorage.STORAGE_KEY, payload).commit()) null
            else "SharedPreferences commit returned false"
        } catch (e: Exception) {
            e.message ?: "SharedPreferences write failed"
        }
        if (failure != null) {
            val restored = try {
                val editor = preferences.edit()
                if (previous == null) editor.remove(BuildLibraryStorage.STORAGE_KEY)
                else editor.putString(BuildLibraryStorage.STORAGE_KEY, previous)
                editor.commit()
            } catch (_: Exception) {
                false
            }
            throw BuildLibraryWriteException(if (restored) failure else "$failure; restoring the previous payload also failed")
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "talentforge_build_library"
    }
}
