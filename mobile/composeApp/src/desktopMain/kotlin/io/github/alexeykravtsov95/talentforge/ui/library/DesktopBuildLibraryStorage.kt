package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryReadException
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryWriteException
import java.util.prefs.Preferences

/**
 * [BuildLibraryStorage] over the JVM's built-in `java.util.prefs` (desktop
 * host). One string node; `flush()` is synchronous so a failed write surfaces.
 */
class DesktopBuildLibraryStorage : BuildLibraryStorage {

    private val preferences = Preferences.userRoot().node(NODE_PATH)

    override fun read(): String? = try {
        preferences.get(BuildLibraryStorage.STORAGE_KEY, null)
    } catch (e: Exception) {
        throw BuildLibraryReadException(e.message ?: "Preferences read failed")
    }

    override fun write(payload: String) {
        try {
            preferences.put(BuildLibraryStorage.STORAGE_KEY, payload)
            preferences.flush()
        } catch (e: Exception) {
            throw BuildLibraryWriteException(e.message ?: "Preferences write failed")
        }
    }

    private companion object {
        const val NODE_PATH = "/io/github/alexeykravtsov95/talentforge/buildLibrary"
    }
}
