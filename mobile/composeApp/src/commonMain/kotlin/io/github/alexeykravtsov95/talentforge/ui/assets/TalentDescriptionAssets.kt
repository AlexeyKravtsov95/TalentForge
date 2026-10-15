package io.github.alexeykravtsov95.talentforge.ui.assets

import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree
import io.github.alexeykravtsov95.talentforge.data.tooltips.BundledTalentTooltipsRepository
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltips
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipsLoadError
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipsOutcome
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentNodeDescriptions

/** The descriptions of one opened tree, plus why they are unavailable when they are. */
data class TalentNodeDescriptionsLoad(
    val descriptions: TalentNodeDescriptions,
    /** The class's tooltips could not be loaded/verified (every inspector shows "unavailable"). */
    val error: TooltipsLoadError?,
    /**
     * The verified English sidecar itself (`null` on failure), so the localization overlay is
     * checked against it without reading the asset again (M5.REL.3b.2).
     */
    val tooltips: TalentTooltips? = null,
)

/**
 * App-level collaborator that loads the verified `talent-tooltips.v1` sidecar of
 * a class document (strict, identity-checked against the document, offline:
 * bundled resources only) and maps it onto the tree's nodes.
 *
 * Like icons, a node gets a description only when it has exactly one entry —
 * the dataset publishes no rule for which entry represents a multi-entry node.
 * Descriptions are presentation only: a failure never affects allocation or
 * whether a build opens.
 */
class TalentDescriptionsLoader(assets: DatasetAssetSource) {
    private val repository = BundledTalentTooltipsRepository(assets)

    suspend fun load(document: TalentDataDocument, tree: TalentTree): TalentNodeDescriptionsLoad {
        val tooltips = when (val outcome = repository.tooltips(document)) {
            is TooltipsOutcome.Failed ->
                return TalentNodeDescriptionsLoad(TalentNodeDescriptions.Unavailable, outcome.error)

            is TooltipsOutcome.Loaded -> outcome.value
        }
        val byNodeId = HashMap<Int, List<TalentTooltipRank>>(tree.nodes.size)
        for (node in tree.nodes) {
            val entry = node.entries.singleOrNull() ?: continue
            tooltips.ranksOf(entry.id)?.let { byNodeId[node.id] = it }
        }
        return TalentNodeDescriptionsLoad(TalentNodeDescriptions.Loaded(byNodeId), error = null, tooltips = tooltips)
    }
}
