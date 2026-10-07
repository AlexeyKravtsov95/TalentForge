package io.github.alexeykravtsov95.talentforge.data.visuals

import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.AssetReadException
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.ContractKind
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.UnsupportedContractVersionException
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException

/** Typed result of a visuals load; failures are never `null` or bare messages. */
sealed interface VisualsOutcome<out T> {
    data class Loaded<out T>(val value: T) : VisualsOutcome<T>

    data class Failed(val error: VisualsLoadError) : VisualsOutcome<Nothing>
}

/**
 * Typed failures of loading the talent visuals of one class document. Program
 * logic branches on the type; [path] names the offending bundled asset.
 */
sealed interface VisualsLoadError {
    val path: String

    data class AssetMissing(override val path: String) : VisualsLoadError

    data class AssetUnreadable(override val path: String, val detail: String) : VisualsLoadError

    /** Not valid JSON for the contract: unknown/missing field, wrong wire type, … */
    data class InvalidJson(override val path: String, val detail: String) : VisualsLoadError

    data class UnsupportedSchemaVersion(
        override val path: String,
        val contract: ContractKind,
        val actualVersion: Int,
        val supportedVersion: Int,
    ) : VisualsLoadError

    data class GameMismatch(
        override val path: String,
        val expected: GameId,
        val actual: GameId,
    ) : VisualsLoadError

    data class BuildMismatch(
        override val path: String,
        val expected: String,
        val actual: String,
    ) : VisualsLoadError

    data class ClassMismatch(
        override val path: String,
        val expected: ClassToken,
        val actual: ClassToken,
    ) : VisualsLoadError

    /** The sidecar was resolved for another revision of the class dataset. */
    data class DatasetRevisionMismatch(
        override val path: String,
        val expected: ContentHash,
        val actual: ContentHash,
    ) : VisualsLoadError

    /** A numeric identity that the contract requires to be positive is not. */
    data class NonPositiveValue(
        override val path: String,
        val field: String,
        val value: Int,
    ) : VisualsLoadError

    data class DuplicateEntry(override val path: String, val entryId: Int) : VisualsLoadError

    /** The sidecar names a talent entry the class document does not contain. */
    data class UnknownEntry(override val path: String, val entryId: Int) : VisualsLoadError

    /** A talent entry of the class document has no visual (the contract covers every entry). */
    data class MissingEntry(override val path: String, val entryId: Int) : VisualsLoadError

    /** The sidecar's definition/spell of an entry contradicts the class document. */
    data class EntryIdentityMismatch(override val path: String, val entryId: Int) : VisualsLoadError

    data class DuplicateIcon(override val path: String, val fileDataId: Int) : VisualsLoadError

    /** A registry `file` that is not exactly `icons/<fileDataId>.png`. */
    data class InvalidIconFile(
        override val path: String,
        val fileDataId: Int,
        val file: String,
    ) : VisualsLoadError

    /** An entry uses an icon the build's registry does not contain. */
    data class UnregisteredIcon(override val path: String, val fileDataId: Int) : VisualsLoadError
}

/** Human-readable rendering for diagnostics only — never an input for decisions. */
fun VisualsLoadError.describe(): String = when (this) {
    is VisualsLoadError.AssetMissing -> "missing visuals asset: $path"
    is VisualsLoadError.AssetUnreadable -> "unreadable visuals asset at $path: $detail"
    is VisualsLoadError.InvalidJson -> "invalid visuals JSON at $path: $detail"
    is VisualsLoadError.UnsupportedSchemaVersion ->
        "unsupported schema version $actualVersion for ${contract.contractName} at $path " +
            "(supported: $supportedVersion)"

    is VisualsLoadError.GameMismatch ->
        "game mismatch at $path: expected ${expected.value}, actual ${actual.value}"

    is VisualsLoadError.BuildMismatch ->
        "client build mismatch at $path: expected $expected, actual $actual"

    is VisualsLoadError.ClassMismatch ->
        "class mismatch at $path: expected ${expected.value}, actual ${actual.value}"

    is VisualsLoadError.DatasetRevisionMismatch ->
        "visuals at $path were resolved for dataset ${actual.value}, loaded dataset is ${expected.value}"

    is VisualsLoadError.NonPositiveValue -> "$field must be positive at $path, found $value"
    is VisualsLoadError.DuplicateEntry -> "duplicate entryId $entryId at $path"
    is VisualsLoadError.UnknownEntry -> "entryId $entryId at $path is not a talent entry of the dataset"
    is VisualsLoadError.MissingEntry -> "talent entry $entryId has no visual at $path"
    is VisualsLoadError.EntryIdentityMismatch ->
        "entry $entryId at $path contradicts the dataset's definition/spell"

    is VisualsLoadError.DuplicateIcon -> "duplicate fileDataId $fileDataId at $path"
    is VisualsLoadError.InvalidIconFile -> "icon $fileDataId at $path has an invalid file '$file'"
    is VisualsLoadError.UnregisteredIcon -> "icon $fileDataId is not in the registry at $path"
}

/**
 * The verified visuals of one class document: which icon each talent entry
 * shows and where that icon's bundled image lives. Only constructed by
 * [BundledTalentVisualsRepository] after every identity/integrity check passed.
 */
class TalentVisuals internal constructor(
    val game: GameId,
    val clientBuild: String,
    val classToken: ClassToken,
    private val iconByEntryId: Map<Int, Int>,
    private val assetPathByIcon: Map<Int, String>,
) {
    /** Icon FileDataID of a talent entry; `null` for an id that is not an entry of the class. */
    fun iconFileDataIdOf(entryId: Int): Int? = iconByEntryId[entryId]

    /**
     * Asset path (relative to the bundled asset root, forward slashes) of an
     * icon used by this class; `null` for any other FileDataID.
     */
    fun iconAssetPathOf(fileDataId: Int): String? = assetPathByIcon[fileDataId]

    /** Distinct icons used by the class. */
    val iconFileDataIds: Set<Int> get() = assetPathByIcon.keys
}

/** Bundled location of a bundle's visuals: `visuals/{game}/{clientBuild}`. */
fun DatasetBundleId.visualsPath(): String = "visuals/${game.value}/$clientBuild"

/**
 * Loads the talent visuals that belong to an already loaded class document
 * from the bundled layout produced by `scripts/sync_mobile_visuals.py`:
 * `visuals/{game}/{clientBuild}/{class}.json` (`talent-visuals.v1`) and
 * `visuals/{game}/{clientBuild}/icons.json` (`talent-icons.v1`).
 *
 * Fail-closed and build-pinned: the sidecar must name exactly the document's
 * game, client build, class token **and** published `contentHash`
 * (`talentDataContentHash`), so visuals are never substituted across builds or
 * dataset revisions. It must cover every talent entry of the document and
 * nothing else, without duplicates, and every icon it uses must be registered
 * under its canonical `icons/<fileDataId>.png` file. The Python canonical
 * hashes are compared as published strings and never recomputed here.
 */
class BundledTalentVisualsRepository(
    private val assets: DatasetAssetSource,
) {

    suspend fun visuals(document: TalentDataDocument): VisualsOutcome<TalentVisuals> {
        val bundle = DatasetBundleId(game = document.game, clientBuild = document.clientBuild)
        val root = bundle.visualsPath()
        val sidecarPath = "$root/${document.classInfo.token.value.lowercase()}.json"
        val registryPath = "$root/$REGISTRY_FILE"

        val sidecar = when (
            val outcome = read(sidecarPath) { TalentDataContract.decodeTalentVisualsDocument(it) }
        ) {
            is VisualsOutcome.Loaded -> outcome.value
            is VisualsOutcome.Failed -> return outcome
        }
        verifySidecar(sidecar, document, sidecarPath)?.let { return VisualsOutcome.Failed(it) }

        val registry = when (
            val outcome = read(registryPath) { TalentDataContract.decodeTalentIconRegistry(it) }
        ) {
            is VisualsOutcome.Loaded -> outcome.value
            is VisualsOutcome.Failed -> return outcome
        }
        val files = when (val outcome = verifyRegistry(registry, bundle, registryPath)) {
            is VisualsOutcome.Loaded -> outcome.value
            is VisualsOutcome.Failed -> return outcome
        }

        val iconByEntryId = LinkedHashMap<Int, Int>(sidecar.entries.size)
        val assetPathByIcon = LinkedHashMap<Int, String>()
        for (entry in sidecar.entries) {
            val file = files[entry.iconFileDataId]
                ?: return VisualsOutcome.Failed(
                    VisualsLoadError.UnregisteredIcon(registryPath, entry.iconFileDataId),
                )
            iconByEntryId[entry.entryId] = entry.iconFileDataId
            assetPathByIcon[entry.iconFileDataId] = "$root/$file"
        }
        return VisualsOutcome.Loaded(
            TalentVisuals(
                game = document.game,
                clientBuild = document.clientBuild,
                classToken = document.classInfo.token,
                iconByEntryId = iconByEntryId,
                assetPathByIcon = assetPathByIcon,
            ),
        )
    }

    private fun verifySidecar(
        sidecar: TalentVisualsDocument,
        document: TalentDataDocument,
        path: String,
    ): VisualsLoadError? {
        if (sidecar.game != document.game) {
            return VisualsLoadError.GameMismatch(path, expected = document.game, actual = sidecar.game)
        }
        if (sidecar.clientBuild != document.clientBuild) {
            return VisualsLoadError.BuildMismatch(
                path,
                expected = document.clientBuild,
                actual = sidecar.clientBuild,
            )
        }
        if (sidecar.classToken != document.classInfo.token) {
            return VisualsLoadError.ClassMismatch(
                path,
                expected = document.classInfo.token,
                actual = sidecar.classToken,
            )
        }
        if (sidecar.talentDataContentHash != document.contentHash) {
            return VisualsLoadError.DatasetRevisionMismatch(
                path,
                expected = document.contentHash,
                actual = sidecar.talentDataContentHash,
            )
        }

        val talentEntries = document.trees
            .flatMap { tree -> tree.nodes }
            .flatMap { node -> node.entries }
            .associateBy { it.id }
        val seen = HashSet<Int>(sidecar.entries.size)
        for (entry in sidecar.entries) {
            for ((field, value) in listOf(
                "entryId" to entry.entryId,
                "definitionId" to entry.definitionId,
                "spellId" to entry.spellId,
                "displaySpellId" to entry.displaySpellId,
                "iconFileDataId" to entry.iconFileDataId,
            )) {
                if (value <= 0) return VisualsLoadError.NonPositiveValue(path, field, value)
            }
            if (!seen.add(entry.entryId)) return VisualsLoadError.DuplicateEntry(path, entry.entryId)
            val talent = talentEntries[entry.entryId]
                ?: return VisualsLoadError.UnknownEntry(path, entry.entryId)
            if (talent.definitionId != entry.definitionId || talent.spellId != entry.spellId) {
                return VisualsLoadError.EntryIdentityMismatch(path, entry.entryId)
            }
        }
        val missing = talentEntries.keys.filterNot { it in seen }.minOrNull()
        if (missing != null) return VisualsLoadError.MissingEntry(path, missing)
        return null
    }

    /** `fileDataId -> file` of a registry that belongs to [bundle] and is internally consistent. */
    private fun verifyRegistry(
        registry: TalentIconRegistry,
        bundle: DatasetBundleId,
        path: String,
    ): VisualsOutcome<Map<Int, String>> {
        if (registry.game != bundle.game) {
            return VisualsOutcome.Failed(
                VisualsLoadError.GameMismatch(path, expected = bundle.game, actual = registry.game),
            )
        }
        if (registry.clientBuild != bundle.clientBuild) {
            return VisualsOutcome.Failed(
                VisualsLoadError.BuildMismatch(
                    path,
                    expected = bundle.clientBuild,
                    actual = registry.clientBuild,
                ),
            )
        }
        val files = HashMap<Int, String>(registry.icons.size)
        for (icon in registry.icons) {
            if (icon.fileDataId <= 0) {
                return VisualsOutcome.Failed(
                    VisualsLoadError.NonPositiveValue(path, "fileDataId", icon.fileDataId),
                )
            }
            for ((field, value) in listOf(
                "width" to icon.width,
                "height" to icon.height,
                "byteLength" to icon.byteLength,
                "sourceByteLength" to icon.sourceByteLength,
            )) {
                if (value <= 0) {
                    return VisualsOutcome.Failed(VisualsLoadError.NonPositiveValue(path, field, value))
                }
            }
            // The contract fixes the file name; anything else (another
            // directory, a URL, a traversal) is refused, never resolved.
            if (icon.file != "$ICON_DIRECTORY/${icon.fileDataId}.png") {
                return VisualsOutcome.Failed(
                    VisualsLoadError.InvalidIconFile(path, icon.fileDataId, icon.file),
                )
            }
            if (files.put(icon.fileDataId, icon.file) != null) {
                return VisualsOutcome.Failed(VisualsLoadError.DuplicateIcon(path, icon.fileDataId))
            }
        }
        return VisualsOutcome.Loaded(files)
    }

    private suspend fun <T> read(path: String, decode: (ByteArray) -> T): VisualsOutcome<T> {
        val bytes = try {
            assets.read(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetNotFoundException) {
            return VisualsOutcome.Failed(VisualsLoadError.AssetMissing(path))
        } catch (e: AssetReadException) {
            return VisualsOutcome.Failed(
                VisualsLoadError.AssetUnreadable(path = path, detail = e.message ?: "read failure"),
            )
        }
        return try {
            VisualsOutcome.Loaded(decode(bytes))
        } catch (e: UnsupportedContractVersionException) {
            VisualsOutcome.Failed(
                VisualsLoadError.UnsupportedSchemaVersion(
                    path = path,
                    contract = e.contract,
                    actualVersion = e.actualVersion,
                    supportedVersion = e.supportedVersion,
                ),
            )
        } catch (e: SerializationException) {
            VisualsOutcome.Failed(VisualsLoadError.InvalidJson(path, e.message ?: "malformed JSON"))
        } catch (e: IllegalArgumentException) {
            VisualsOutcome.Failed(VisualsLoadError.InvalidJson(path, e.message ?: "malformed JSON"))
        }
    }

    private companion object {
        const val REGISTRY_FILE = "icons.json"
        const val ICON_DIRECTORY = "icons"
    }
}
