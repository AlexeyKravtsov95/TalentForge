package io.github.alexeykravtsov95.talentforge.ui.assets

import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import kotlin.coroutines.cancellation.CancellationException

/**
 * [DatasetAssetSource] over the standard Compose Multiplatform resource
 * mechanism. Files live under
 * `composeApp/src/commonMain/composeResources/files/` and are packaged for
 * Android and iOS by the Compose resources pipeline; only bytes cross this
 * boundary — all JSON parsing stays in `shared`.
 */
class ComposeResourceDatasetAssetSource : DatasetAssetSource {
    override suspend fun read(path: String): ByteArray = try {
        Res.readBytes("files/$path")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AssetNotFoundException(path)
    }
}
