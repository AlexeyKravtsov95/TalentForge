package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Read access to talent datasets. Pure domain/data contract: no UI types,
 * no platform types, no nulls-as-failure.
 */
interface TalentDatasetRepository {
    /** Loads the manifest of a dataset bundle. */
    suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest>

    /**
     * Loads one class document, verified against the manifest: schema version,
     * game, client build, class token, content hash and tree ids must all agree.
     */
    suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument>
}

/**
 * Repository over a [DatasetAssetSource] following the published bundle layout:
 * `dataset/{game}/{clientBuild}/manifest.json` plus the class documents listed
 * by that manifest. The bundled layout is produced by
 * `scripts/sync_mobile_datasets.py` and must stay byte-identical to the
 * canonical `data/normalized/` tree.
 *
 * The published document `contentHash` is compared against the manifest entry;
 * the Python canonical hash algorithm itself is not part of the consumer
 * contract and is never recomputed here.
 */
class BundledTalentDatasetRepository(
    private val assets: DatasetAssetSource,
) : TalentDatasetRepository {

    override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> {
        val manifestPath = "${bundle.datasetPath()}/$MANIFEST_FILE"

        val bytes = assets.readOrNull(manifestPath)
            ?: return DatasetOutcome.Failed(DatasetLoadError.AssetMissing(manifestPath))

        val outcome = parse(manifestPath, ContractKind.DATASET_MANIFEST) {
            TalentDataContract.decodeDatasetManifest(bytes)
        }
        val manifest = when (outcome) {
            is DatasetOutcome.Loaded -> outcome.value
            is DatasetOutcome.Failed -> return outcome
        }

        if (manifest.game != bundle.game) {
            return DatasetOutcome.Failed(
                DatasetLoadError.GameMismatch(
                    manifestPath = manifestPath,
                    documentPath = manifestPath,
                    expected = bundle.game,
                    actual = manifest.game,
                ),
            )
        }
        if (manifest.clientBuild != bundle.clientBuild) {
            return DatasetOutcome.Failed(
                DatasetLoadError.BuildMismatch(
                    manifestPath = manifestPath,
                    documentPath = manifestPath,
                    expected = bundle.clientBuild,
                    actual = manifest.clientBuild,
                ),
            )
        }
        return DatasetOutcome.Loaded(manifest)
    }

    override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> {
        val bundle = id.bundle()
        val manifestPath = "${bundle.datasetPath()}/$MANIFEST_FILE"

        val manifest = when (val manifestOutcome = manifest(bundle)) {
            is DatasetOutcome.Loaded -> manifestOutcome.value
            is DatasetOutcome.Failed ->
                return DatasetOutcome.Failed(DatasetLoadError.ManifestUnavailable(manifestOutcome.error))
        }

        val entry = manifest.classes.firstOrNull { it.token == id.classToken }
            ?: return DatasetOutcome.Failed(
                DatasetLoadError.ClassEntryMissing(
                    manifestPath = manifestPath,
                    classToken = id.classToken,
                ),
            )

        val documentPath = "${bundle.datasetPath()}/${entry.file}"
        val bytes = assets.readOrNull(documentPath)
            ?: return DatasetOutcome.Failed(DatasetLoadError.AssetMissing(documentPath))

        val document = when (
            val outcome = parse(documentPath, ContractKind.TALENT_DATA) {
                TalentDataContract.decodeTalentDataDocument(bytes)
            }
        ) {
            is DatasetOutcome.Loaded -> outcome.value
            is DatasetOutcome.Failed -> return outcome
        }

        return verifyIdentity(manifest, entry, document, manifestPath, documentPath)
    }

    private fun verifyIdentity(
        manifest: DatasetManifest,
        entry: io.github.alexeykravtsov95.talentforge.data.manifest.ManifestClassEntry,
        document: TalentDataDocument,
        manifestPath: String,
        documentPath: String,
    ): DatasetOutcome<TalentDataDocument> {
        if (document.game != manifest.game) {
            return DatasetOutcome.Failed(
                DatasetLoadError.GameMismatch(
                    manifestPath = manifestPath,
                    documentPath = documentPath,
                    expected = manifest.game,
                    actual = document.game,
                ),
            )
        }
        if (document.clientBuild != manifest.clientBuild) {
            return DatasetOutcome.Failed(
                DatasetLoadError.BuildMismatch(
                    manifestPath = manifestPath,
                    documentPath = documentPath,
                    expected = manifest.clientBuild,
                    actual = document.clientBuild,
                ),
            )
        }
        if (document.classInfo.token != entry.token) {
            return DatasetOutcome.Failed(
                DatasetLoadError.ClassMismatch(
                    manifestPath = manifestPath,
                    documentPath = documentPath,
                    expected = entry.token,
                    actual = document.classInfo.token,
                ),
            )
        }
        if (document.contentHash != entry.contentHash) {
            return DatasetOutcome.Failed(
                DatasetLoadError.ContentHashMismatch(
                    manifestPath = manifestPath,
                    documentPath = documentPath,
                    expected = entry.contentHash,
                    actual = document.contentHash,
                ),
            )
        }
        if (document.trees.map { it.id }.toSet() != entry.treeIds.toSet()) {
            return DatasetOutcome.Failed(
                DatasetLoadError.TreeIdsMismatch(
                    manifestPath = manifestPath,
                    documentPath = documentPath,
                    expected = entry.treeIds.toSet(),
                    actual = document.trees.map { it.id }.toSet(),
                ),
            )
        }
        return DatasetOutcome.Loaded(document)
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
    }
}

private suspend fun DatasetAssetSource.readOrNull(path: String): ByteArray? =
    try {
        read(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AssetSourceException) {
        null
    }

private inline fun <T> parse(
    path: String,
    contract: ContractKind,
    block: () -> T,
): DatasetOutcome<T> = try {
    DatasetOutcome.Loaded(block())
} catch (e: UnsupportedContractVersionException) {
    DatasetOutcome.Failed(
        DatasetLoadError.UnsupportedSchemaVersion(
            path = path,
            contract = e.contract,
            actualVersion = e.actualVersion,
            supportedVersion = e.supportedVersion,
        ),
    )
} catch (e: SerializationException) {
    DatasetOutcome.Failed(
        DatasetLoadError.InvalidJson(path = path, detail = e.message ?: "malformed JSON"),
    )
} catch (e: IllegalArgumentException) {
    DatasetOutcome.Failed(
        DatasetLoadError.InvalidJson(path = path, detail = e.message ?: "malformed JSON"),
    )
}
