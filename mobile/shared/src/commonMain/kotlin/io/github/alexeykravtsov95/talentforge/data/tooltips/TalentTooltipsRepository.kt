package io.github.alexeykravtsov95.talentforge.data.tooltips

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

/** Typed result of a tooltips load; failures are never `null` or bare messages. */
sealed interface TooltipsOutcome<out T> {
    data class Loaded<out T>(val value: T) : TooltipsOutcome<T>

    data class Failed(val error: TooltipsLoadError) : TooltipsOutcome<Nothing>
}

/**
 * Typed failures of loading the talent descriptions of one class document.
 * Program logic branches on the type; [path] names the offending bundled asset.
 */
sealed interface TooltipsLoadError {
    val path: String

    data class AssetMissing(override val path: String) : TooltipsLoadError

    data class AssetUnreadable(override val path: String, val detail: String) : TooltipsLoadError

    /** Not valid JSON for the contract: unknown/missing field, wrong wire type, unknown enum … */
    data class InvalidJson(override val path: String, val detail: String) : TooltipsLoadError

    data class UnsupportedSchemaVersion(
        override val path: String,
        val contract: ContractKind,
        val actualVersion: Int,
        val supportedVersion: Int,
    ) : TooltipsLoadError

    data class GameMismatch(override val path: String, val expected: GameId, val actual: GameId) :
        TooltipsLoadError

    data class BuildMismatch(override val path: String, val expected: String, val actual: String) :
        TooltipsLoadError

    data class ClassMismatch(
        override val path: String,
        val expected: ClassToken,
        val actual: ClassToken,
    ) : TooltipsLoadError

    /** The sidecar was rendered for another revision of the class dataset. */
    data class DatasetRevisionMismatch(
        override val path: String,
        val expected: ContentHash,
        val actual: ContentHash,
    ) : TooltipsLoadError

    data class NonPositiveValue(override val path: String, val field: String, val value: Int) :
        TooltipsLoadError

    data class DuplicateEntry(override val path: String, val entryId: Int) : TooltipsLoadError

    /** The sidecar names a talent entry the class document does not contain. */
    data class UnknownEntry(override val path: String, val entryId: Int) : TooltipsLoadError

    /** A talent entry of the class document has no description (the contract covers every entry). */
    data class MissingEntry(override val path: String, val entryId: Int) : TooltipsLoadError

    /** The sidecar's definition/spell/maxRank of an entry contradicts the class document. */
    data class EntryIdentityMismatch(override val path: String, val entryId: Int) : TooltipsLoadError

    /** The ranks of an entry are not exactly `1..maxRank` in order. */
    data class InvalidRanks(override val path: String, val entryId: Int, val ranks: List<Int>) :
        TooltipsLoadError

    /** A rank's status contradicts its segments/gaps (e.g. a "resolved" rank with a gap). */
    data class InconsistentRank(override val path: String, val entryId: Int, val rank: Int) :
        TooltipsLoadError

    /**
     * A text/value segment carries template markup (`$`, `|`, `\r`) or is empty,
     * or a gap segment carries text. Raw template syntax is never accepted as
     * presentation text.
     */
    data class InvalidSegment(override val path: String, val entryId: Int, val rank: Int) :
        TooltipsLoadError
}

/** Human-readable rendering for diagnostics only — never an input for decisions. */
fun TooltipsLoadError.describe(): String = when (this) {
    is TooltipsLoadError.AssetMissing -> "missing tooltips asset: $path"
    is TooltipsLoadError.AssetUnreadable -> "unreadable tooltips asset at $path: $detail"
    is TooltipsLoadError.InvalidJson -> "invalid tooltips JSON at $path: $detail"
    is TooltipsLoadError.UnsupportedSchemaVersion ->
        "unsupported schema version $actualVersion for ${contract.contractName} at $path " +
            "(supported: $supportedVersion)"

    is TooltipsLoadError.GameMismatch ->
        "game mismatch at $path: expected ${expected.value}, actual ${actual.value}"

    is TooltipsLoadError.BuildMismatch ->
        "client build mismatch at $path: expected $expected, actual $actual"

    is TooltipsLoadError.ClassMismatch ->
        "class mismatch at $path: expected ${expected.value}, actual ${actual.value}"

    is TooltipsLoadError.DatasetRevisionMismatch ->
        "tooltips at $path were rendered for dataset ${actual.value}, loaded dataset is ${expected.value}"

    is TooltipsLoadError.NonPositiveValue -> "$field must be positive at $path, found $value"
    is TooltipsLoadError.DuplicateEntry -> "duplicate entryId $entryId at $path"
    is TooltipsLoadError.UnknownEntry -> "entryId $entryId at $path is not a talent entry of the dataset"
    is TooltipsLoadError.MissingEntry -> "talent entry $entryId has no description at $path"
    is TooltipsLoadError.EntryIdentityMismatch ->
        "entry $entryId at $path contradicts the dataset's definition/spell/maxRank"

    is TooltipsLoadError.InvalidRanks -> "entry $entryId at $path has ranks $ranks"
    is TooltipsLoadError.InconsistentRank -> "entry $entryId rank $rank at $path has an inconsistent status"
    is TooltipsLoadError.InvalidSegment -> "entry $entryId rank $rank at $path has an invalid segment"
}

/**
 * The verified descriptions of one class document. Only constructed by
 * [BundledTalentTooltipsRepository] after every identity/integrity check
 * passed, so every [TalentTooltipRank] it hands out is free of template markup.
 */
class TalentTooltips internal constructor(
    val game: GameId,
    val clientBuild: String,
    val classToken: ClassToken,
    /** The class document revision these descriptions were verified against. */
    val talentDataContentHash: ContentHash,
    /** The sidecar's own published `contentHash` (what a localization overlay links to). */
    val contentHash: ContentHash,
    private val ranksByEntryId: Map<Int, List<TalentTooltipRank>>,
) {
    /** All ranks `1..maxRank` of a talent entry; `null` for an id that is not an entry of the class. */
    fun ranksOf(entryId: Int): List<TalentTooltipRank>? = ranksByEntryId[entryId]

    /** One rank of an entry; `null` for an unknown entry or a rank outside `1..maxRank`. */
    fun rankOf(entryId: Int, rank: Int): TalentTooltipRank? = ranksByEntryId[entryId]?.getOrNull(rank - 1)
}

/** Bundled location of a bundle's tooltips: `tooltips/{game}/{clientBuild}`. */
fun DatasetBundleId.tooltipsPath(): String = "tooltips/${game.value}/$clientBuild"

/**
 * Loads the talent descriptions that belong to an already loaded class document
 * from the bundled layout produced by `scripts/sync_mobile_tooltips.py`:
 * `tooltips/{game}/{clientBuild}/{class}.json` (`talent-tooltips.v1`).
 *
 * Fail-closed and build-pinned: the sidecar must name exactly the document's
 * game, client build, class token **and** published `contentHash`
 * (`talentDataContentHash`), so descriptions are never substituted across
 * builds or dataset revisions. It must cover every talent entry of the
 * document and nothing else, every entry must list exactly ranks `1..maxRank`,
 * and no presentation segment may carry template markup. The Python canonical
 * hashes are compared as published strings and never recomputed here.
 */
class BundledTalentTooltipsRepository(
    private val assets: DatasetAssetSource,
) {

    suspend fun tooltips(document: TalentDataDocument): TooltipsOutcome<TalentTooltips> {
        val bundle = DatasetBundleId(game = document.game, clientBuild = document.clientBuild)
        val path = "${bundle.tooltipsPath()}/${document.classInfo.token.value.lowercase()}.json"
        val sidecar = when (val outcome = read(path)) {
            is TooltipsOutcome.Loaded -> outcome.value
            is TooltipsOutcome.Failed -> return outcome
        }
        verify(sidecar, document, path)?.let { return TooltipsOutcome.Failed(it) }
        return TooltipsOutcome.Loaded(
            TalentTooltips(
                game = document.game,
                clientBuild = document.clientBuild,
                classToken = document.classInfo.token,
                talentDataContentHash = document.contentHash,
                contentHash = sidecar.contentHash,
                ranksByEntryId = sidecar.entries.associate { it.entryId to it.ranks },
            ),
        )
    }

    private fun verify(
        sidecar: TalentTooltipsDocument,
        document: TalentDataDocument,
        path: String,
    ): TooltipsLoadError? {
        if (sidecar.game != document.game) {
            return TooltipsLoadError.GameMismatch(path, expected = document.game, actual = sidecar.game)
        }
        if (sidecar.clientBuild != document.clientBuild) {
            return TooltipsLoadError.BuildMismatch(path, expected = document.clientBuild, actual = sidecar.clientBuild)
        }
        if (sidecar.classToken != document.classInfo.token) {
            return TooltipsLoadError.ClassMismatch(
                path,
                expected = document.classInfo.token,
                actual = sidecar.classToken,
            )
        }
        if (sidecar.talentDataContentHash != document.contentHash) {
            return TooltipsLoadError.DatasetRevisionMismatch(
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
                "maxRank" to entry.maxRank,
            )) {
                if (value <= 0) return TooltipsLoadError.NonPositiveValue(path, field, value)
            }
            if (!seen.add(entry.entryId)) return TooltipsLoadError.DuplicateEntry(path, entry.entryId)
            val talent = talentEntries[entry.entryId]
                ?: return TooltipsLoadError.UnknownEntry(path, entry.entryId)
            if (talent.definitionId != entry.definitionId ||
                talent.spellId != entry.spellId ||
                talent.maxRank != entry.maxRank
            ) {
                return TooltipsLoadError.EntryIdentityMismatch(path, entry.entryId)
            }
            val ranks = entry.ranks.map { it.rank }
            if (ranks != (1..entry.maxRank).toList()) {
                return TooltipsLoadError.InvalidRanks(path, entry.entryId, ranks)
            }
            for (rank in entry.ranks) {
                verifyRank(rank, entry.entryId, path)?.let { return it }
            }
        }
        val missing = talentEntries.keys.filterNot { it in seen }.minOrNull()
        if (missing != null) return TooltipsLoadError.MissingEntry(path, missing)
        return null
    }

    private fun verifyRank(rank: TalentTooltipRank, entryId: Int, path: String): TooltipsLoadError? {
        for (segment in rank.segments) {
            val valid = when (segment.kind) {
                TooltipSegmentKind.GAP -> segment.text.isEmpty()
                TooltipSegmentKind.TEXT, TooltipSegmentKind.VALUE ->
                    segment.text.isNotEmpty() && MARKUP_CHARACTERS.none { it in segment.text }
            }
            if (!valid) return TooltipsLoadError.InvalidSegment(path, entryId, rank.rank)
        }
        val gapSegments = rank.segments.count { it.kind == TooltipSegmentKind.GAP }
        val shown = rank.segments.count { it.kind != TooltipSegmentKind.GAP }
        val consistent = when (rank.status) {
            TooltipRankStatus.RESOLVED -> rank.gaps.isEmpty() && gapSegments == 0 && shown > 0
            TooltipRankStatus.PARTIAL -> rank.gaps.isNotEmpty() && gapSegments == rank.gaps.size && shown > 0
            TooltipRankStatus.UNRESOLVED -> rank.segments.isEmpty() && rank.gaps.isNotEmpty()
        }
        return if (consistent) null else TooltipsLoadError.InconsistentRank(path, entryId, rank.rank)
    }

    private suspend fun read(path: String): TooltipsOutcome<TalentTooltipsDocument> {
        val bytes = try {
            assets.read(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetNotFoundException) {
            return TooltipsOutcome.Failed(TooltipsLoadError.AssetMissing(path))
        } catch (e: AssetReadException) {
            return TooltipsOutcome.Failed(TooltipsLoadError.AssetUnreadable(path, e.message ?: "read failure"))
        }
        return try {
            TooltipsOutcome.Loaded(TalentDataContract.decodeTalentTooltipsDocument(bytes))
        } catch (e: UnsupportedContractVersionException) {
            TooltipsOutcome.Failed(
                TooltipsLoadError.UnsupportedSchemaVersion(
                    path = path,
                    contract = e.contract,
                    actualVersion = e.actualVersion,
                    supportedVersion = e.supportedVersion,
                ),
            )
        } catch (e: SerializationException) {
            TooltipsOutcome.Failed(TooltipsLoadError.InvalidJson(path, e.message ?: "malformed JSON"))
        } catch (e: IllegalArgumentException) {
            TooltipsOutcome.Failed(TooltipsLoadError.InvalidJson(path, e.message ?: "malformed JSON"))
        }
    }

    private companion object {
        /** Characters that only exist in template markup (same set as the Python contract). */
        val MARKUP_CHARACTERS = listOf('$', '|', '\r')
    }
}
