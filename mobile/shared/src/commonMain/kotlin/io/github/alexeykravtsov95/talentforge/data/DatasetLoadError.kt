package io.github.alexeykravtsov95.talentforge.data

/** Typed result of a dataset load; failures are never `null` or bare messages. */
sealed interface DatasetOutcome<out T> {
    data class Loaded<out T>(val value: T) : DatasetOutcome<T>

    data class Failed(val error: DatasetLoadError) : DatasetOutcome<Nothing>
}

/**
 * Typed dataset load failures. Program logic branches on the type — parse
 * failure messages are diagnostics for humans, never inputs for decisions.
 */
sealed interface DatasetLoadError {

    /** The asset was not found in the underlying [DatasetAssetSource]. */
    data class AssetMissing(val path: String) : DatasetLoadError

    /** The asset exists but could not be read by the [DatasetAssetSource]. */
    data class AssetUnreadable(val path: String, val detail: String) : DatasetLoadError

    /** The payload is not valid JSON for the contract (drift or corruption). */
    data class InvalidJson(val path: String, val detail: String) : DatasetLoadError

    /** The payload declares a schema version this client does not support. */
    data class UnsupportedSchemaVersion(
        val path: String,
        val contract: ContractKind,
        val actualVersion: Int,
        val supportedVersion: Int,
    ) : DatasetLoadError

    /** The manifest itself could not be loaded while resolving a document. */
    data class ManifestUnavailable(val error: DatasetLoadError) : DatasetLoadError

    /** The manifest has no entry for the requested class token. */
    data class ClassEntryMissing(
        val manifestPath: String,
        val classToken: ClassToken,
    ) : DatasetLoadError

    /**
     * The manifest and the document (or the manifest and the request) disagree
     * about identity. [documentPath] names the offending artifact; it equals
     * [manifestPath] when the manifest itself contradicts the request.
     */
    sealed interface IdentityMismatch : DatasetLoadError {
        val manifestPath: String
        val documentPath: String
    }

    data class GameMismatch(
        override val manifestPath: String,
        override val documentPath: String,
        val expected: GameId,
        val actual: GameId,
    ) : DatasetLoadError.IdentityMismatch

    data class BuildMismatch(
        override val manifestPath: String,
        override val documentPath: String,
        val expected: String,
        val actual: String,
    ) : DatasetLoadError.IdentityMismatch

    data class ClassMismatch(
        override val manifestPath: String,
        override val documentPath: String,
        val expected: ClassToken,
        val actual: ClassToken,
    ) : DatasetLoadError.IdentityMismatch

    data class ContentHashMismatch(
        override val manifestPath: String,
        override val documentPath: String,
        val expected: ContentHash,
        val actual: ContentHash,
    ) : DatasetLoadError.IdentityMismatch

    data class TreeIdsMismatch(
        override val manifestPath: String,
        override val documentPath: String,
        val expected: Set<Int>,
        val actual: Set<Int>,
    ) : DatasetLoadError.IdentityMismatch
}

/** Human-readable rendering used by smoke/debug UIs only. */
fun DatasetLoadError.describe(): String = when (this) {
    is DatasetLoadError.AssetMissing -> "missing dataset asset: $path"
    is DatasetLoadError.AssetUnreadable -> "unreadable dataset asset at $path: $detail"
    is DatasetLoadError.InvalidJson -> "invalid dataset JSON at $path: $detail"
    is DatasetLoadError.UnsupportedSchemaVersion ->
        "unsupported schema version $actualVersion for ${contract.contractName} at $path " +
            "(supported: $supportedVersion)"

    is DatasetLoadError.ManifestUnavailable -> "manifest unavailable: ${error.describe()}"
    is DatasetLoadError.ClassEntryMissing -> "no manifest entry for class ${classToken.value}"
    is DatasetLoadError.GameMismatch ->
        "game mismatch at ${documentPath}: expected ${expected.value}, actual ${actual.value}"

    is DatasetLoadError.BuildMismatch ->
        "client build mismatch at $documentPath: expected $expected, actual $actual"

    is DatasetLoadError.ClassMismatch ->
        "class mismatch at $documentPath: expected ${expected.value}, actual ${actual.value}"

    is DatasetLoadError.ContentHashMismatch ->
        "content hash mismatch at $documentPath: expected ${expected.value}, actual ${actual.value}"

    is DatasetLoadError.TreeIdsMismatch ->
        "tree id mismatch at $documentPath: expected $expected, actual $actual"
}

/** Convenience accessor for callers that already handled failure. */
fun <T> DatasetOutcome<T>.valueOrNull(): T? = (this as? DatasetOutcome.Loaded<T>)?.value

/** Convenience accessor for callers that already handled success. */
fun <T> DatasetOutcome<T>.errorOrNull(): DatasetLoadError? =
    (this as? DatasetOutcome.Failed)?.error
