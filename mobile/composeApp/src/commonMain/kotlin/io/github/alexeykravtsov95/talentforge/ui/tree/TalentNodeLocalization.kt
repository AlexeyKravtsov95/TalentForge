package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.runtime.Immutable
import io.github.alexeykravtsov95.talentforge.data.localization.LocalizationLoadError
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage

/**
 * The verified Russian talent text of one opened tree, by node id (M5.REL.3b.2) — a
 * presentation projection next to [TalentNodeDescriptions] and [TalentNodeIcons], never part
 * of allocation, persistence or the dataset (the [io.github.alexeykravtsov95.talentforge
 * .data.talentdata.TalentDataDocument] keeps its English names). Built once when the editor
 * session opens (see `TalentLocalizationLoader`); composition only picks it by language.
 */
@Immutable
sealed interface TalentNodeLocalization {
    /** No overlay was requested (lower-level callers and tests): English everywhere. */
    data object None : TalentNodeLocalization

    /**
     * The class's overlay is missing or was rejected as a whole: English everywhere. The typed
     * [error] is a diagnostic only — it is never shown as copy.
     */
    data class Rejected(val error: LocalizationLoadError) : TalentNodeLocalization

    /**
     * Verified Russian text of the single-entry nodes that have it: [names] where the overlay
     * name is `translated`, [ranks] (complete ranks `1..maxRank`) where the description is
     * `rendered`. A node absent from a map shows its English name / description; a multi-entry
     * node is never localized (the dataset defines no representative entry).
     */
    data class Loaded(
        val names: Map<Int, String>,
        val ranks: Map<Int, List<TalentTooltipRank>>,
    ) : TalentNodeLocalization
}

/** What the editor shows for its talents in one language: name overrides and descriptions. */
@Immutable
data class TalentNodeDisplayText(
    /** Localized names by node id; a node absent here shows the dataset's English name. */
    val names: Map<Int, String>,
    val descriptions: TalentNodeDescriptions,
)

/**
 * The talent text of [language]: English ([TalentNodeDescriptions] unchanged, no name
 * overrides) unless the language is Russian **and** the overlay was verified. Russian then
 * replaces the names it translates and the whole rank list of each rendered description —
 * every other node keeps its English description unchanged (a Russian name over an English
 * description is the honest state of an entry whose description failed a gate). A rank is
 * never mixed from two languages, and descriptions that failed to load stay unavailable.
 */
fun talentNodeDisplayText(
    language: AppLanguage,
    descriptions: TalentNodeDescriptions,
    localization: TalentNodeLocalization,
): TalentNodeDisplayText {
    if (language != AppLanguage.RUSSIAN || localization !is TalentNodeLocalization.Loaded) {
        return TalentNodeDisplayText(names = emptyMap(), descriptions = descriptions)
    }
    val localized = when (descriptions) {
        is TalentNodeDescriptions.Loaded -> TalentNodeDescriptions.Loaded(
            descriptions.nodeIds.associateWith { nodeId ->
                localization.ranks[nodeId] ?: descriptions.ranksOf(nodeId).orEmpty()
            },
        )

        TalentNodeDescriptions.None, TalentNodeDescriptions.Unavailable -> descriptions
    }
    return TalentNodeDisplayText(names = localization.names, descriptions = localized)
}
