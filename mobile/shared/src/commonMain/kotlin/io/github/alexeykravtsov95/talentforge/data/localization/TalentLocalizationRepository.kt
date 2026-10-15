package io.github.alexeykravtsov95.talentforge.data.localization

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
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltips
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipRankStatus
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipSegmentKind
import io.github.alexeykravtsov95.talentforge.data.visuals.TalentVisuals
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException

/** Typed result of a localization load; failures are never `null` or bare messages. */
sealed interface LocalizationOutcome<out T> {
    data class Loaded<out T>(val value: T) : LocalizationOutcome<T>

    data class Failed(val error: LocalizationLoadError) : LocalizationOutcome<Nothing>
}

/**
 * Typed reasons a `talent-localization.v1` overlay is not applied. Every one of them makes the
 * consumer show English for the **whole** class (docs/talent-localization.md §5); they are
 * diagnostics, never user-facing copy. [path] names the bundled asset.
 */
sealed interface LocalizationLoadError {
    val path: String

    /** The requested locale code is not a supported overlay locale (English is never an overlay). */
    data class UnsupportedLocale(override val path: String, val localeCode: String) : LocalizationLoadError

    data class AssetMissing(override val path: String) : LocalizationLoadError

    data class AssetUnreadable(override val path: String, val detail: String) : LocalizationLoadError

    /** Not valid JSON for the contract: unknown/missing field, wrong wire type, unknown enum … */
    data class InvalidJson(override val path: String, val detail: String) : LocalizationLoadError

    data class UnsupportedSchemaVersion(
        override val path: String,
        val contract: ContractKind,
        val actualVersion: Int,
        val supportedVersion: Int,
    ) : LocalizationLoadError

    /** A value violates the schema's pattern/range rules (e.g. a non-positive id, a malformed hash). */
    data class InvalidValue(override val path: String, val field: String, val value: String) :
        LocalizationLoadError

    data class LocaleMismatch(override val path: String, val expected: TalentLocale, val actual: TalentLocale) :
        LocalizationLoadError

    data class GameMismatch(override val path: String, val expected: GameId, val actual: GameId) :
        LocalizationLoadError

    data class BuildMismatch(override val path: String, val expected: String, val actual: String) :
        LocalizationLoadError

    data class ClassMismatch(override val path: String, val expected: ClassToken, val actual: ClassToken) :
        LocalizationLoadError

    /** The English tooltips or visuals handed in were verified for another class document. */
    data class EnglishInputMismatch(override val path: String, val input: EnglishInput) : LocalizationLoadError

    /** The overlay belongs to another `talent-data.v1` revision (stale dataset hash). */
    data class DatasetRevisionMismatch(
        override val path: String,
        val expected: ContentHash,
        val actual: ContentHash,
    ) : LocalizationLoadError

    /** The overlay was checked against another English `talent-tooltips.v1` (stale tooltips hash). */
    data class TooltipsRevisionMismatch(
        override val path: String,
        val expected: ContentHash,
        val actual: ContentHash,
    ) : LocalizationLoadError

    /** The recomputed canonical hash differs from the published `contentHash` (tampered or stale). */
    data class ContentHashMismatch(
        override val path: String,
        val expected: ContentHash,
        val actual: ContentHash,
    ) : LocalizationLoadError

    /** `entries` is not ordered by `entryId` ([entryId] is the first out-of-order one). */
    data class EntriesNotOrdered(override val path: String, val entryId: Int) : LocalizationLoadError

    data class DuplicateEntry(override val path: String, val entryId: Int) : LocalizationLoadError

    /** The overlay names a talent entry the class document does not contain. */
    data class UnknownEntry(override val path: String, val entryId: Int) : LocalizationLoadError

    /** A talent entry of the class document has no overlay entry. */
    data class MissingEntry(override val path: String, val entryId: Int) : LocalizationLoadError

    /** node/definition/spell/textSpell/maxRank of an entry contradicts the dataset. */
    data class EntryIdentityMismatch(
        override val path: String,
        val entryId: Int,
        val field: String,
        val expected: Int,
        val actual: Int,
    ) : LocalizationLoadError

    /** A name's text contradicts its status (text ⇔ `translated`, translated text has Cyrillic). */
    data class InvalidName(override val path: String, val entryId: Int, val status: LocalizedNameStatus) :
        LocalizationLoadError

    /** `rendered` without exactly ranks `1..maxRank`, or ranks on a non-rendered description. */
    data class InvalidRanks(
        override val path: String,
        val entryId: Int,
        val status: LocalizedDescriptionStatus,
        val ranks: List<Int>,
    ) : LocalizationLoadError

    /** A rank without segments, or a segment that is empty or carries template markup (`$ | \r`). */
    data class InvalidSegment(override val path: String, val entryId: Int, val rank: Int) : LocalizationLoadError

    /** A rendered rank claims more than the English sidecar verified (English rank not resolved). */
    data class EnglishRankNotResolved(override val path: String, val entryId: Int, val rank: Int) :
        LocalizationLoadError

    /** The numbers shown by a rendered rank differ from the English rank's. */
    data class NumberMismatch(
        override val path: String,
        val entryId: Int,
        val rank: Int,
        val localized: List<String>,
        val english: List<String>,
    ) : LocalizationLoadError

    /** A rendered value has a decimal part — the ruRU decimal separator is unverified. */
    data class DecimalValue(override val path: String, val entryId: Int, val rank: Int) : LocalizationLoadError
}

/** The English presentation input an overlay is checked against. */
enum class EnglishInput {
    TOOLTIPS,
    VISUALS,
}

/** Human-readable rendering for diagnostics only — never an input for decisions, never UI copy. */
fun LocalizationLoadError.describe(): String = when (this) {
    is LocalizationLoadError.UnsupportedLocale -> "unsupported localization locale '$localeCode' ($path)"
    is LocalizationLoadError.AssetMissing -> "missing localization asset: $path"
    is LocalizationLoadError.AssetUnreadable -> "unreadable localization asset at $path: $detail"
    is LocalizationLoadError.InvalidJson -> "invalid localization JSON at $path: $detail"
    is LocalizationLoadError.UnsupportedSchemaVersion ->
        "unsupported schema version $actualVersion for ${contract.contractName} at $path " +
            "(supported: $supportedVersion)"

    is LocalizationLoadError.InvalidValue -> "invalid $field '$value' at $path"
    is LocalizationLoadError.LocaleMismatch -> "locale mismatch at $path: expected ${expected.code}, actual ${actual.code}"
    is LocalizationLoadError.GameMismatch -> "game mismatch at $path: expected ${expected.value}, actual ${actual.value}"
    is LocalizationLoadError.BuildMismatch -> "client build mismatch at $path: expected $expected, actual $actual"
    is LocalizationLoadError.ClassMismatch -> "class mismatch at $path: expected ${expected.value}, actual ${actual.value}"
    is LocalizationLoadError.EnglishInputMismatch -> "the English ${input.name.lowercase()} do not belong to the dataset ($path)"
    is LocalizationLoadError.DatasetRevisionMismatch ->
        "overlay at $path belongs to talent-data ${actual.value}, dataset is ${expected.value}"

    is LocalizationLoadError.TooltipsRevisionMismatch ->
        "overlay at $path was checked against tooltips ${actual.value}, the English tooltips are ${expected.value}"

    is LocalizationLoadError.ContentHashMismatch ->
        "contentHash at $path does not match the document (stale or tampered): published ${actual.value}, " +
            "computed ${expected.value}"

    is LocalizationLoadError.EntriesNotOrdered -> "entries at $path are not ordered by entryId (at $entryId)"
    is LocalizationLoadError.DuplicateEntry -> "duplicate entryId $entryId at $path"
    is LocalizationLoadError.UnknownEntry -> "entryId $entryId at $path is not a talent entry of the dataset"
    is LocalizationLoadError.MissingEntry -> "talent entry $entryId has no overlay entry at $path"
    is LocalizationLoadError.EntryIdentityMismatch ->
        "entry $entryId at $path has $field $actual, dataset says $expected"

    is LocalizationLoadError.InvalidName -> "entry $entryId at $path has an invalid ${status.name.lowercase()} name"
    is LocalizationLoadError.InvalidRanks ->
        "entry $entryId at $path is ${status.name.lowercase()} with ranks $ranks"

    is LocalizationLoadError.InvalidSegment -> "entry $entryId rank $rank at $path has an invalid segment"
    is LocalizationLoadError.EnglishRankNotResolved ->
        "entry $entryId rank $rank at $path is rendered but the English rank is not resolved"

    is LocalizationLoadError.NumberMismatch ->
        "entry $entryId rank $rank at $path shows numbers $localized, English shows $english"

    is LocalizationLoadError.DecimalValue -> "entry $entryId rank $rank at $path shows a decimal value"
}

/**
 * A verified overlay of one class document. Only constructed by
 * [BundledTalentLocalizationRepository] after every identity and integrity check passed, so
 * it is applied whole: every talent entry of the document has exactly one entry here.
 */
class TalentLocalization internal constructor(
    val game: GameId,
    val clientBuild: String,
    val classToken: ClassToken,
    val locale: TalentLocale,
    /** The class document revision the overlay was verified against. */
    val talentDataContentHash: ContentHash,
    private val entriesById: Map<Int, LocalizedTalentEntry>,
) {
    /** The overlay entry of a talent entry; `null` for an id that is not an entry of the class. */
    fun entryOf(entryId: Int): LocalizedTalentEntry? = entriesById[entryId]

    val entryIds: Set<Int> get() = entriesById.keys
}

/** Bundled location of a bundle's overlays of one locale: `localization/{game}/{clientBuild}/{locale}`. */
fun DatasetBundleId.localizationPath(locale: TalentLocale): String =
    "localization/${game.value}/$clientBuild/${locale.code}"

/**
 * Loads the localized talent text of an already loaded class document from the bundled layout
 * produced by `scripts/sync_mobile_localization.py`:
 * `localization/{game}/{clientBuild}/{locale}/{class}.json` (`talent-localization.v1`).
 *
 * Offline (bundled assets only) and fail-closed — the runtime form of the Python
 * `check_localization_document`. The overlay is accepted only when it names exactly the
 * document's game, client build, class and the requested locale; links to exactly the
 * document's `contentHash` and the verified English tooltips' `contentHash`; carries the
 * `contentHash` **recomputed** here from its canonical JSON; covers every talent entry once,
 * in `entryId` order, with the dataset's node/definition/spell/maxRank and the visuals'
 * display spell as `textSpellId`; obeys the name/description rules; and every rendered rank
 * shows exactly the numbers of its resolved English rank, none of them decimal. Any failure is
 * a typed [LocalizationLoadError] and nothing of the overlay is applied. There is no
 * cross-build, cross-class or cross-locale substitution.
 */
class BundledTalentLocalizationRepository(
    private val assets: DatasetAssetSource,
) {

    /**
     * The verified overlay of [document] in [localeCode] (case-exact, e.g. `ruRU`). [tooltips]
     * and [visuals] are the document's own verified English sidecars.
     */
    suspend fun localization(
        document: TalentDataDocument,
        tooltips: TalentTooltips,
        visuals: TalentVisuals,
        localeCode: String,
    ): LocalizationOutcome<TalentLocalization> {
        val bundle = DatasetBundleId(game = document.game, clientBuild = document.clientBuild)
        val fileName = "${document.classInfo.token.value.lowercase()}.json"
        val locale = TalentLocale.ofCode(localeCode)
            ?: return LocalizationOutcome.Failed(
                LocalizationLoadError.UnsupportedLocale(
                    path = "localization/${document.game.value}/${document.clientBuild}/$localeCode/$fileName",
                    localeCode = localeCode,
                ),
            )
        val path = "${bundle.localizationPath(locale)}/$fileName"
        val overlay = when (val outcome = read(path)) {
            is LocalizationOutcome.Loaded -> outcome.value
            is LocalizationOutcome.Failed -> return outcome
        }
        verify(overlay, document, tooltips, visuals, locale, path)?.let { return LocalizationOutcome.Failed(it) }
        return LocalizationOutcome.Loaded(
            TalentLocalization(
                game = document.game,
                clientBuild = document.clientBuild,
                classToken = document.classInfo.token,
                locale = locale,
                talentDataContentHash = document.contentHash,
                entriesById = overlay.entries.associateBy { it.entryId },
            ),
        )
    }

    private fun verify(
        overlay: TalentLocalizationDocument,
        document: TalentDataDocument,
        tooltips: TalentTooltips,
        visuals: TalentVisuals,
        locale: TalentLocale,
        path: String,
    ): LocalizationLoadError? {
        verifyValues(overlay, path)?.let { return it }

        // identity first: a foreign or stale overlay is never looked into
        if (overlay.locale != locale) return LocalizationLoadError.LocaleMismatch(path, locale, overlay.locale)
        if (overlay.game != document.game) {
            return LocalizationLoadError.GameMismatch(path, expected = document.game, actual = overlay.game)
        }
        if (overlay.clientBuild != document.clientBuild) {
            return LocalizationLoadError.BuildMismatch(path, expected = document.clientBuild, actual = overlay.clientBuild)
        }
        if (overlay.classToken != document.classInfo.token) {
            return LocalizationLoadError.ClassMismatch(path, expected = document.classInfo.token, actual = overlay.classToken)
        }
        if (!tooltips.belongsTo(document)) return LocalizationLoadError.EnglishInputMismatch(path, EnglishInput.TOOLTIPS)
        if (!visuals.belongsTo(document)) return LocalizationLoadError.EnglishInputMismatch(path, EnglishInput.VISUALS)
        if (overlay.talentDataContentHash != document.contentHash) {
            return LocalizationLoadError.DatasetRevisionMismatch(
                path,
                expected = document.contentHash,
                actual = overlay.talentDataContentHash,
            )
        }
        if (overlay.tooltipsContentHash != tooltips.contentHash) {
            return LocalizationLoadError.TooltipsRevisionMismatch(
                path,
                expected = tooltips.contentHash,
                actual = overlay.tooltipsContentHash,
            )
        }
        val computed = localizationContentHash(overlay)
        if (computed != overlay.contentHash) {
            return LocalizationLoadError.ContentHashMismatch(path, expected = computed, actual = overlay.contentHash)
        }

        // the entry set: every talent entry of the document exactly once, in entryId order
        val nodeOf = HashMap<Int, Int>()
        val talents = document.trees
            .flatMap { tree -> tree.nodes }
            .flatMap { node -> node.entries.map { entry -> nodeOf[entry.id] = node.id; entry } }
            .associateBy { it.id }
        val seen = HashSet<Int>(overlay.entries.size)
        var previous: Int? = null
        for (entry in overlay.entries) {
            if (!seen.add(entry.entryId)) return LocalizationLoadError.DuplicateEntry(path, entry.entryId)
            if (previous != null && entry.entryId < previous) {
                return LocalizationLoadError.EntriesNotOrdered(path, entry.entryId)
            }
            previous = entry.entryId
            val talent = talents[entry.entryId] ?: return LocalizationLoadError.UnknownEntry(path, entry.entryId)
            for ((field, expected, actual) in listOf(
                Triple("nodeId", nodeOf.getValue(talent.id), entry.nodeId),
                Triple("definitionId", talent.definitionId, entry.definitionId),
                Triple("spellId", talent.spellId, entry.spellId),
                Triple("maxRank", talent.maxRank, entry.maxRank),
                Triple("textSpellId", visuals.displaySpellIdOf(talent.id) ?: 0, entry.textSpellId),
            )) {
                if (expected != actual) {
                    return LocalizationLoadError.EntryIdentityMismatch(path, entry.entryId, field, expected, actual)
                }
            }
            verifyEntryText(entry, tooltips, path)?.let { return it }
        }
        val missing = talents.keys.filterNot { it in seen }.minOrNull()
        if (missing != null) return LocalizationLoadError.MissingEntry(path, missing)
        return null
    }

    /** The JSON Schema's pattern and range rules (Python's pydantic constraints). */
    private fun verifyValues(overlay: TalentLocalizationDocument, path: String): LocalizationLoadError? {
        fun invalid(field: String, value: Any) = LocalizationLoadError.InvalidValue(path, field, value.toString())
        if (!BUILD.matches(overlay.clientBuild)) return invalid("clientBuild", overlay.clientBuild)
        if (!CLASS_TOKEN.matches(overlay.classToken.value)) return invalid("classToken", overlay.classToken)
        for ((field, hash) in listOf(
            "talentDataContentHash" to overlay.talentDataContentHash,
            "tooltipsContentHash" to overlay.tooltipsContentHash,
            "contentHash" to overlay.contentHash,
        )) {
            if (!SHA256.matches(hash.value)) return invalid(field, hash)
        }
        for (entry in overlay.entries) {
            for ((field, value) in listOf(
                "entryId" to entry.entryId,
                "nodeId" to entry.nodeId,
                "definitionId" to entry.definitionId,
                "spellId" to entry.spellId,
                "textSpellId" to entry.textSpellId,
                "maxRank" to entry.maxRank,
            )) {
                if (value <= 0) return invalid("entries[${entry.entryId}].$field", value)
            }
        }
        for (table in overlay.provenance.tables) {
            if (table.table.isEmpty()) return invalid("provenance.tables.table", "")
            if (table.url.isEmpty()) return invalid("provenance.tables.url", "")
        }
        return null
    }

    /** Name and description rules, then every rendered rank against its English rank. */
    private fun verifyEntryText(
        entry: LocalizedTalentEntry,
        tooltips: TalentTooltips,
        path: String,
    ): LocalizationLoadError? {
        val name = entry.name
        val nameValid = when (name.status) {
            LocalizedNameStatus.TRANSLATED -> !name.text.isNullOrEmpty() && CYRILLIC.containsMatchIn(name.text)
            LocalizedNameStatus.UNTRANSLATED, LocalizedNameStatus.MISSING -> name.text == null
        }
        if (!nameValid) return LocalizationLoadError.InvalidName(path, entry.entryId, name.status)

        val description = entry.description
        val ranks = description.ranks.map { it.rank }
        val ranksValid = if (description.status == LocalizedDescriptionStatus.RENDERED) {
            ranks == (1..entry.maxRank).toList()
        } else {
            ranks.isEmpty()
        }
        if (!ranksValid) return LocalizationLoadError.InvalidRanks(path, entry.entryId, description.status, ranks)

        for (rank in description.ranks) {
            val segmentsValid = rank.segments.isNotEmpty() && rank.segments.all { segment ->
                segment.text.isNotEmpty() && MARKUP_CHARACTERS.none { it in segment.text }
            }
            if (!segmentsValid) return LocalizationLoadError.InvalidSegment(path, entry.entryId, rank.rank)
            val english = tooltips.rankOf(entry.entryId, rank.rank)
            if (english == null || english.status != TooltipRankStatus.RESOLVED) {
                return LocalizationLoadError.EnglishRankNotResolved(path, entry.entryId, rank.rank)
            }
            val ours = rank.segments.filter { it.kind == LocalizedSegmentKind.VALUE }.map { it.text }
            val theirs = english.segments.filter { it.kind == TooltipSegmentKind.VALUE }.map { it.text }
            val localizedNumbers = numbersOf(ours)
            val englishNumbers = numbersOf(theirs)
            if (localizedNumbers != englishNumbers) {
                return LocalizationLoadError.NumberMismatch(
                    path,
                    entry.entryId,
                    rank.rank,
                    localized = localizedNumbers,
                    english = englishNumbers,
                )
            }
            if (ours.any { DECIMAL.containsMatchIn(it) }) {
                return LocalizationLoadError.DecimalValue(path, entry.entryId, rank.rank)
            }
        }
        return null
    }

    private suspend fun read(path: String): LocalizationOutcome<TalentLocalizationDocument> {
        val bytes = try {
            assets.read(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetNotFoundException) {
            return LocalizationOutcome.Failed(LocalizationLoadError.AssetMissing(path))
        } catch (e: AssetReadException) {
            return LocalizationOutcome.Failed(LocalizationLoadError.AssetUnreadable(path, e.message ?: "read failure"))
        }
        return try {
            LocalizationOutcome.Loaded(TalentDataContract.decodeTalentLocalizationDocument(bytes))
        } catch (e: UnsupportedContractVersionException) {
            LocalizationOutcome.Failed(
                LocalizationLoadError.UnsupportedSchemaVersion(
                    path = path,
                    contract = e.contract,
                    actualVersion = e.actualVersion,
                    supportedVersion = e.supportedVersion,
                ),
            )
        } catch (e: SerializationException) {
            LocalizationOutcome.Failed(LocalizationLoadError.InvalidJson(path, e.message ?: "malformed JSON"))
        } catch (e: IllegalArgumentException) {
            LocalizationOutcome.Failed(LocalizationLoadError.InvalidJson(path, e.message ?: "malformed JSON"))
        }
    }

    private companion object {
        /** Characters that only exist in template markup (same set as the Python contract). */
        val MARKUP_CHARACTERS = listOf('$', '|', '\r')
        val BUILD = Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
        val CLASS_TOKEN = Regex("[A-Z]+")
        val SHA256 = Regex("sha256:[0-9a-f]{64}")

        /** Python `talent_data.models.localization.CYRILLIC`: U+0400–U+04FF. */
        val CYRILLIC = Regex("[Ѐ-ӿ]")

        /** Python `pipeline._NUMBER` / `_DECIMAL`, applied to value segments only. */
        val NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?")
        val DECIMAL = Regex("[0-9]\\.[0-9]")

        /** The numbers shown by [texts], as a sorted multiset (Python `Counter` equality). */
        fun numbersOf(texts: List<String>): List<String> =
            texts.flatMap { text -> NUMBER.findAll(text).map { it.value }.toList() }.sorted()

        fun TalentTooltips.belongsTo(document: TalentDataDocument): Boolean =
            game == document.game && clientBuild == document.clientBuild &&
                classToken == document.classInfo.token && talentDataContentHash == document.contentHash

        fun TalentVisuals.belongsTo(document: TalentDataDocument): Boolean =
            game == document.game && clientBuild == document.clientBuild &&
                classToken == document.classInfo.token && talentDataContentHash == document.contentHash
    }
}
