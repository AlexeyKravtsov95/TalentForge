package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.InstallAttempt
import io.github.alexeykravtsov95.talentforge.data.ota.InstallAttemptCodec
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.OtaState
import io.github.alexeykravtsov95.talentforge.data.ota.OtaStateCodec
import io.github.alexeykravtsov95.talentforge.data.ota.StagingEntry

/**
 * The on-disk layout of the OTA store (client design §3) over an [OtaFileStore]:
 *
 * ```
 * v1/state.json                       the OtaState — the single commit point (atomic replace)
 * v1/catalog/<channel>/<hex>.payload  accepted catalog payloads, named by their SHA-256
 * v1/releases/<id>/release.json       an installed, verified release (never modified)
 * v1/releases/<id>/<release paths>
 * v1/staging/<id>/attempt.json        an install in progress (never read for the UI)
 * v1/staging/<id>/release.json, <paths>, <path>.part
 * v1/quarantine/<id>.<n>/             a release that failed a read-time check
 * v1/trash/<id>.<n>/                  a directory being deleted (rename first, then delete)
 * ```
 *
 * **Catalog payload and state stay consistent across a crash.** A payload file is named by
 * the SHA-256 of its bytes and written (synced, atomically) *before* the `state.json` that
 * names it; payloads the state does not name are deleted only *after* a state write
 * committed. A crash between the two writes therefore leaves the old state with its old
 * payload still on disk (plus an orphan payload that recovery removes) — the state never
 * refers to a payload that is missing or to bytes of another sequence, and the releases the
 * last accepted catalog listed stay known without the network. This replaces the single
 * mutable `catalog/<channel>.payload` file of the design sketch, whose two writes could not
 * be made atomic together (PR #42 review).
 *
 * The saved-build library is not in this store and this class has no handle to it.
 */
class DatasetCache(val store: OtaFileStore, val game: String, val channel: OtaChannel) {

    // ------------------------------------------------------------ paths

    val statePath: String get() = "$ROOT/state.json"
    val stateTempPath: String get() = "$statePath.tmp"
    private val catalogDir: String get() = "$ROOT/catalog/${channel.wire}"
    val releasesDir: String get() = "$ROOT/releases"
    val stagingDir: String get() = "$ROOT/staging"
    val quarantineDir: String get() = "$ROOT/quarantine"
    val trashDir: String get() = "$ROOT/trash"

    fun catalogPayloadPath(payloadSha256: String): String = "$catalogDir/${payloadSha256.removePrefix("sha256:")}.payload"

    fun releaseDir(releaseId: String): String = "$releasesDir/$releaseId"

    fun releaseFile(releaseId: String, path: String): String = "${releaseDir(releaseId)}/$path"

    fun releaseManifestPath(releaseId: String): String = "${releaseDir(releaseId)}/$RELEASE_MANIFEST"

    fun stagingFor(releaseId: String): String = "$stagingDir/$releaseId"

    fun stagingFile(releaseId: String, path: String): String = "${stagingFor(releaseId)}/$path"

    fun attemptPath(releaseId: String): String = "${stagingFor(releaseId)}/$ATTEMPT"

    fun stagingManifestPath(releaseId: String): String = "${stagingFor(releaseId)}/$RELEASE_MANIFEST"

    // ------------------------------------------------------------ state

    sealed interface StateRead {
        data object Absent : StateRead

        /** Unreadable or invalid: "no OTA state" — never permission to delete anything (contract §7.2). */
        data object Unreadable : StateRead

        data class Valid(val state: OtaState) : StateRead
    }

    fun readState(): StateRead {
        val bytes = store.readOrNull(statePath) ?: return StateRead.Absent
        val state = OtaStateCodec.decodeOrNull(bytes, game, channel) ?: return StateRead.Unreadable
        return StateRead.Valid(state)
    }

    /** The commit point of every OTA transition: an atomic replace of `state.json`. */
    fun writeState(state: OtaState) {
        store.replaceAtomically(statePath, OtaStateCodec.encode(state, game, channel))
    }

    // ------------------------------------------------------------ catalog payloads

    /** Writes an accepted payload under its own hash (idempotent); must precede the state naming it. */
    fun writeCatalogPayload(payloadSha256: String, payload: ByteArray) {
        val path = catalogPayloadPath(payloadSha256)
        if (store.readOrNull(path)?.contentEquals(payload) == true) return
        store.replaceAtomically(path, payload)
    }

    fun readCatalogPayload(payloadSha256: String): ByteArray? = store.readOrNull(catalogPayloadPath(payloadSha256))

    /** Deletes payload files other than [keepSha256] — only after a state naming [keepSha256] committed. */
    fun deleteOtherCatalogPayloads(keepSha256: String) {
        val keep = catalogPayloadPath(keepSha256).substringAfterLast('/')
        for (entry in store.list(catalogDir)) {
            if (entry.name != keep) store.delete("$catalogDir/${entry.name}")
        }
    }

    // ------------------------------------------------------------ releases and staging

    fun installedDirectoryNames(): List<String> = store.list(releasesDir).filter { it.isDirectory }.map { it.name }

    fun readReleaseManifest(releaseId: String): ByteArray? = store.readOrNull(releaseManifestPath(releaseId))

    fun stagingEntries(): List<StagingEntry> = store.list(stagingDir).filter { it.isDirectory }.map {
        StagingEntry(it.name, readAttempt(it.name))
    }

    fun stagingExists(releaseId: String): Boolean = store.isDirectory(stagingFor(releaseId))

    fun readAttempt(releaseId: String): InstallAttempt? = InstallAttemptCodec.decodeOrNull(store.readOrNull(attemptPath(releaseId)))

    fun writeAttempt(attempt: InstallAttempt) {
        store.replaceAtomically(attemptPath(attempt.releaseId), InstallAttemptCodec.encode(attempt))
    }

    /** Every `*.part` file below [directory] (relative paths from the store root). */
    fun partFiles(directory: String = stagingDir): List<String> {
        val found = ArrayList<String>()
        fun walk(dir: String) {
            for (entry in store.list(dir)) {
                val path = "$dir/${entry.name}"
                if (entry.isDirectory) walk(path) else if (entry.name.endsWith(PART_SUFFIX)) found += path
            }
        }
        walk(directory)
        return found
    }

    // ------------------------------------------------------------ deletion (rename first, then delete)

    /** Moves [path] into `trash/` atomically and deletes it there; a crash leaves only `trash/`, cleaned at the next start. */
    fun deleteViaTrash(path: String) {
        if (!store.exists(path)) return
        val target = freeName(trashDir, path.substringAfterLast('/'))
        store.rename(path, target)
        store.delete(target)
    }

    /** Moves a broken release out of `releases/` for diagnostics (client design §4.1, §4.6). */
    fun quarantine(releaseId: String) {
        val source = releaseDir(releaseId)
        if (!store.exists(source)) return
        store.rename(source, freeName(quarantineDir, releaseId))
    }

    fun clearTrash() = store.delete(trashDir)

    fun clearQuarantine() = store.delete(quarantineDir)

    private fun freeName(directory: String, base: String): String {
        val taken = store.list(directory).map { it.name }.toSet()
        var n = 0
        while ("$base.$n" in taken) n++
        return "$directory/$base.$n"
    }

    companion object {
        const val ROOT: String = "v1"
        const val RELEASE_MANIFEST: String = "release.json"
        const val ATTEMPT: String = "attempt.json"
        const val PART_SUFFIX: String = ".part"
    }
}
