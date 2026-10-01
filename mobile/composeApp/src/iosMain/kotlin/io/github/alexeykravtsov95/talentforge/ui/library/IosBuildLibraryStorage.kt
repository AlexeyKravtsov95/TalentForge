package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryReadException
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryWriteException
import platform.Foundation.NSUserDefaults

/**
 * [BuildLibraryStorage] over iOS `NSUserDefaults` (standard user defaults,
 * constructed by the iOS host inside Kotlin — the Swift host stays thin).
 */
class IosBuildLibraryStorage : BuildLibraryStorage {

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun read(): String? = try {
        defaults.stringForKey(BuildLibraryStorage.STORAGE_KEY)
    } catch (e: Exception) {
        throw BuildLibraryReadException(e.message ?: "NSUserDefaults read failed")
    }

    override fun write(payload: String) {
        try {
            defaults.setObject(payload, BuildLibraryStorage.STORAGE_KEY)
        } catch (e: Exception) {
            throw BuildLibraryWriteException(e.message ?: "NSUserDefaults write failed")
        }
    }
}
