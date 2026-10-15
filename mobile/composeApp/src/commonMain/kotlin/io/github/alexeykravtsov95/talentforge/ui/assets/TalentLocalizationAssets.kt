package io.github.alexeykravtsov95.talentforge.ui.assets

import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.localization.BundledTalentLocalizationRepository
import io.github.alexeykravtsov95.talentforge.data.localization.LocalizationOutcome
import io.github.alexeykravtsov95.talentforge.data.localization.TalentLocale
import io.github.alexeykravtsov95.talentforge.data.localization.resolveClassTalentText
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltips
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipRankStatus
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipSegment
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipSegmentKind
import io.github.alexeykravtsov95.talentforge.data.localization.LocalizedRank
import io.github.alexeykravtsov95.talentforge.data.localization.LocalizedSegmentKind
import io.github.alexeykravtsov95.talentforge.data.visuals.TalentVisuals
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentNodeLocalization

/**
 * App-level collaborator that loads the verified Russian talent text of a class document
 * (M5.REL.3b.2): the bundled `talent-localization.v1` overlay of the document's exact build
 * and class, checked as a whole by [BundledTalentLocalizationRepository] against the
 * document and the English sidecars the session **already loaded** (each asset is read
 * once), then resolved by entry id ([resolveClassTalentText]) and mapped onto the tree's
 * single-entry nodes — the same conservative rule as icons and descriptions.
 *
 * Presentation only, offline, and never an editor failure: without verified English
 * tooltips and visuals there is nothing to check the overlay against, so it is not loaded;
 * a missing or rejected overlay is [TalentNodeLocalization.Rejected] and the editor shows
 * English. The overlay is loaded whatever the current language, so a language change only
 * re-projects ([io.github.alexeykravtsov95.talentforge.ui.tree.talentNodeDisplayText]).
 */
class TalentLocalizationLoader(assets: DatasetAssetSource) {
    private val repository = BundledTalentLocalizationRepository(assets)

    suspend fun load(
        document: TalentDataDocument,
        tree: TalentTree,
        tooltips: TalentTooltips?,
        visuals: TalentVisuals?,
    ): TalentNodeLocalization {
        if (tooltips == null || visuals == null) return TalentNodeLocalization.None
        val outcome = repository.localization(document, tooltips, visuals, TalentLocale.RU_RU.code)
        if (outcome is LocalizationOutcome.Failed) return TalentNodeLocalization.Rejected(outcome.error)
        val text = resolveClassTalentText(document, tooltips, outcome)
        val names = HashMap<Int, String>()
        val ranks = HashMap<Int, List<TalentTooltipRank>>()
        for (node in tree.nodes) {
            val entry = text.entries[node.entries.singleOrNull()?.id ?: continue] ?: continue
            val name = entry.name
            if (entry.nameLocale != null && name != null) names[node.id] = name
            entry.localizedRanks?.let { localized -> ranks[node.id] = localized.map { it.toDisplayRank() } }
        }
        return TalentNodeLocalization.Loaded(names = names, ranks = ranks)
    }
}

/**
 * A rendered localized rank in the inspector's rank shape: complete (`resolved`, no gaps) with
 * the same text/value segments, so the existing description view, CURRENT / NEXT RANK
 * comparison and changed-value highlighting apply to Russian exactly as to English.
 */
private fun LocalizedRank.toDisplayRank(): TalentTooltipRank = TalentTooltipRank(
    rank = rank,
    status = TooltipRankStatus.RESOLVED,
    segments = segments.map { segment ->
        TooltipSegment(
            kind = when (segment.kind) {
                LocalizedSegmentKind.TEXT -> TooltipSegmentKind.TEXT
                LocalizedSegmentKind.VALUE -> TooltipSegmentKind.VALUE
            },
            text = segment.text,
        )
    },
    gaps = emptyList(),
)
