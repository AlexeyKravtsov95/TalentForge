package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.AssetReadException
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaReleaseManifest
import io.github.alexeykravtsov95.talentforge.data.sha256Ref

/**
 * A [DatasetAssetSource] over the files of one verified release (client design §4.6), at the
 * release paths the app's bundled resources use — so the existing repositories read an OTA
 * release exactly as they read the bundle.
 *
 * Every read is checked: the path must be listed in the release manifest (otherwise
 * [AssetNotFoundException], exactly as for a bundled file that does not exist), and the bytes
 * must have the listed length and SHA-256. A mismatch is an [AssetReadException] **and** is
 * reported through [onCorrupt], so the activation manager can quarantine the release and point
 * new snapshots elsewhere; the open session shows its existing typed "unavailable" state for
 * that asset. Nothing on disk is trusted because it was verified once.
 */
class ReleaseAssetSource(
    private val store: OtaFileStore,
    /** `v1/releases/<id>` for an installed release, `v1/staging/<id>` while validating one. */
    private val directory: String,
    val releaseId: String,
    manifest: OtaReleaseManifest,
    private val onCorrupt: (releaseId: String, path: String) -> Unit = { _, _ -> },
) : DatasetAssetSource {

    private val files = manifest.files.associateBy { it.path }

    override suspend fun read(path: String): ByteArray {
        val file = files[path] ?: throw AssetNotFoundException(path)
        val bytes = try {
            store.readOrNull("$directory/$path")
        } catch (e: OtaIoException) {
            throw AssetReadException(path, e)
        }
        if (bytes == null || bytes.size.toLong() != file.byteLength || sha256Ref(bytes) != file.sha256) {
            onCorrupt(releaseId, path)
            throw AssetReadException(path)
        }
        return bytes
    }
}
