package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.ota.DatasetSnapshot

/**
 * The [DatasetAssetSource] of one [DatasetSnapshot] (client design §4.5): every path of the
 * bundled layout — `dataset|visuals|tooltips|localization/<game>/<build>/…` — names its client
 * build, and the read is delegated to that build's source in the snapshot (an installed OTA
 * release or the bundle). The existing repositories therefore work unchanged on any mix of
 * bundled and downloaded builds. A build the snapshot does not have is an
 * [AssetNotFoundException], exactly as today for a build that is not bundled.
 */
class RoutingDatasetAssetSource(val snapshot: DatasetSnapshot) : DatasetAssetSource {

    override suspend fun read(path: String): ByteArray {
        val bundle = bundleOf(path) ?: throw AssetNotFoundException(path)
        val source = snapshot.source(bundle) ?: throw AssetNotFoundException(path)
        return source.assets.read(path)
    }

    companion object {
        private val ROOTS = setOf("dataset", "visuals", "tooltips", "localization")

        /** The client build a bundled-layout path belongs to, or `null` for any other path. */
        fun bundleOf(path: String): DatasetBundleId? {
            val parts = path.split('/')
            if (parts.size < 4 || parts[0] !in ROOTS || parts[1].isEmpty() || parts[2].isEmpty()) return null
            return DatasetBundleId(GameId(parts[1]), parts[2])
        }
    }
}
