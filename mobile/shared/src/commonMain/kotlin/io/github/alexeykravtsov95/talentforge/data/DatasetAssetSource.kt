package io.github.alexeykravtsov95.talentforge.data

/**
 * Platform-agnostic access to bundled dataset assets. `shared` knows nothing
 * about Android AssetManager, iOS bundles or Compose resources — concrete
 * sources live in the platform/UI layer and only deliver bytes.
 */
interface DatasetAssetSource {
    /**
     * Reads the asset at [path] (forward-slash separated, relative to the
     * dataset root).
     *
     * @throws AssetSourceException when the asset cannot be read; subclasses
     * carry the concrete reason ([AssetNotFoundException] for missing assets,
     * [AssetReadException] for other read failures).
     */
    suspend fun read(path: String): ByteArray
}

/** Base type for asset access failures surfaced by a [DatasetAssetSource]. */
sealed class AssetSourceException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** The requested asset does not exist in the source. */
class AssetNotFoundException(val path: String) :
    AssetSourceException("dataset asset not found: $path")

/** The asset exists but could not be read (I/O error, corrupt packaging, …). */
class AssetReadException(
    val path: String,
    cause: Throwable? = null,
) : AssetSourceException(
    "failed to read dataset asset: $path" + (cause?.let { ": ${it.message}" } ?: ""),
    cause,
)
