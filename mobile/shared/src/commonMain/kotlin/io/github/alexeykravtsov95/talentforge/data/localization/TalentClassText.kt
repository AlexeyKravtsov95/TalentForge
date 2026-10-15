package io.github.alexeykravtsov95.talentforge.data.localization

import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltips

/**
 * What one talent entry shows. Exactly one of [englishRanks] / [localizedRanks] is set: a rank
 * is never assembled from two languages.
 */
data class TalentEntryText(
    val entryId: Int,
    /** `null` exactly when neither the overlay nor the English dataset has a name. */
    val name: String?,
    /** The locale of [name]; `null` = the English dataset name. */
    val nameLocale: TalentLocale?,
    /** The locale of the description; `null` = the English sidecar ranks. */
    val descriptionLocale: TalentLocale?,
    /** The English sidecar ranks, unchanged (status, gaps, missing-value markers). */
    val englishRanks: List<TalentTooltipRank>?,
    /** The complete localized ranks `1..maxRank`. */
    val localizedRanks: List<LocalizedRank>?,
)

/** The display text of every talent entry of a class, by `TalentEntry.id`. */
data class TalentClassText(
    val entries: Map<Int, TalentEntryText>,
    /** Why a requested overlay was not applied (`null` = applied, or English was requested). */
    val overlayError: LocalizationLoadError?,
)

/**
 * The display text of every entry of [document] — the Kotlin port of the Python reference
 * `talent_data.localization.display.resolve_class_text` (docs/talent-localization.md §5):
 *
 * - [overlay] `null` (English requested): every entry shows the English dataset name and the
 *   English sidecar ranks;
 * - a [LocalizationOutcome.Failed] overlay (missing, malformed, stale, tampered …) is applied
 *   **not at all**: every entry shows English and [TalentClassText.overlayError] says why;
 * - a verified overlay: the name is the overlay text when `translated`, else the English name;
 *   the description is the overlay ranks when `rendered`, else the English ranks unchanged.
 *
 * Resolution is by `entryId` only — never by spell id or a guessed node mapping (two entries
 * may share a spell: Hunter *Lightning Reflexes*, Priest *Holy Specialization*). English
 * tooltips or an overlay verified for another document are a programming error, not a fallback.
 */
fun resolveClassTalentText(
    document: TalentDataDocument,
    tooltips: TalentTooltips,
    overlay: LocalizationOutcome<TalentLocalization>?,
): TalentClassText {
    require(
        tooltips.game == document.game && tooltips.clientBuild == document.clientBuild &&
            tooltips.classToken == document.classInfo.token &&
            tooltips.talentDataContentHash == document.contentHash,
    ) { "the English tooltips do not match the dataset" }
    val localization = (overlay as? LocalizationOutcome.Loaded)?.value
    if (localization != null) {
        require(
            localization.game == document.game && localization.clientBuild == document.clientBuild &&
                localization.classToken == document.classInfo.token &&
                localization.talentDataContentHash == document.contentHash,
        ) { "the localization was verified for another dataset" }
    }
    val entries = LinkedHashMap<Int, TalentEntryText>()
    for (tree in document.trees) {
        for (node in tree.nodes) {
            for (talent in node.entries) {
                val mine = localization?.entryOf(talent.id)
                val translated = mine != null && mine.name.status == LocalizedNameStatus.TRANSLATED
                val rendered = mine != null && mine.description.status == LocalizedDescriptionStatus.RENDERED
                entries[talent.id] = TalentEntryText(
                    entryId = talent.id,
                    name = if (translated) mine.name.text else talent.name,
                    nameLocale = if (translated) localization.locale else null,
                    descriptionLocale = if (rendered) localization.locale else null,
                    englishRanks = if (rendered) {
                        null
                    } else {
                        requireNotNull(tooltips.ranksOf(talent.id)) { "no English ranks for entry ${talent.id}" }
                    },
                    localizedRanks = if (rendered) mine.description.ranks else null,
                )
            }
        }
    }
    return TalentClassText(entries = entries, overlayError = (overlay as? LocalizationOutcome.Failed)?.error)
}
