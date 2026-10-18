package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.describe
import io.github.alexeykravtsov95.talentforge.data.localization.BundledTalentLocalizationRepository
import io.github.alexeykravtsov95.talentforge.data.localization.LocalizationOutcome
import io.github.alexeykravtsov95.talentforge.data.localization.TalentLocale
import io.github.alexeykravtsov95.talentforge.data.localization.describe
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.tooltips.BundledTalentTooltipsRepository
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipsOutcome
import io.github.alexeykravtsov95.talentforge.data.tooltips.describe
import io.github.alexeykravtsov95.talentforge.data.visuals.BundledTalentVisualsRepository
import io.github.alexeykravtsov95.talentforge.data.visuals.VisualsOutcome
import io.github.alexeykravtsov95.talentforge.data.visuals.describe

/**
 * The last step of contract §6.4: a staged release is read through the app's **own** existing
 * repositories — the same readers every editor session uses — for every class of its dataset
 * manifest: the talent document (identity against the manifest), visuals and icon registry,
 * tooltips, and the localization overlay of every locale the app supports (`ruRU`, including
 * its own content-hash recomputation). Any typed failure rejects the release with
 * `CONTENT_INVALID`: a release is never activated on the strength of hashes alone.
 */
object ReleaseValidator {

    /** The locales a data-level-1 app reads (contract §9). */
    val SUPPORTED_LOCALES: List<TalentLocale> = listOf(TalentLocale.RU_RU)

    suspend fun validate(assets: DatasetAssetSource, dataset: DatasetManifest): OtaResult<Unit> {
        val bundle = DatasetBundleId(dataset.game, dataset.clientBuild)
        val documents = BundledTalentDatasetRepository(assets)
        val visualsRepository = BundledTalentVisualsRepository(assets)
        val tooltipsRepository = BundledTalentTooltipsRepository(assets)
        val localizationRepository = BundledTalentLocalizationRepository(assets)

        fun invalid(detail: String) = OtaResult.Err(OtaFailure.ContentInvalid("app validation: $detail"))

        val manifest = documents.manifest(bundle)
        if (manifest is DatasetOutcome.Failed) return invalid(manifest.error.describe())
        for (entry in dataset.classes) {
            val document = when (val outcome = documents.document(DatasetId(dataset.game, dataset.clientBuild, entry.token))) {
                is DatasetOutcome.Failed -> return invalid(outcome.error.describe())
                is DatasetOutcome.Loaded -> outcome.value
            }
            val visuals = when (val outcome = visualsRepository.visuals(document)) {
                is VisualsOutcome.Failed -> return invalid(outcome.error.describe())
                is VisualsOutcome.Loaded -> outcome.value
            }
            val tooltips = when (val outcome = tooltipsRepository.tooltips(document)) {
                is TooltipsOutcome.Failed -> return invalid(outcome.error.describe())
                is TooltipsOutcome.Loaded -> outcome.value
            }
            for (locale in SUPPORTED_LOCALES) {
                val outcome = localizationRepository.localization(document, tooltips, visuals, locale.code)
                if (outcome is LocalizationOutcome.Failed) return invalid(outcome.error.describe())
            }
        }
        return OtaResult.Ok(Unit)
    }
}
