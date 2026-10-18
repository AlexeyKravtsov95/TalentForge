package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildLibraryCodec
import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogVerifier
import io.github.alexeykravtsov95.talentforge.data.ota.DatasetSnapshot
import io.github.alexeykravtsov95.talentforge.data.ota.OtaClock
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.SignatureVerifier
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentLocalizationLoader
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The OTA client of one app process: every component wired over the platform ports, with
 * startup recovery already done — [open] runs `recover()` synchronously (local files only,
 * no network), so the first screen is built from the recovered state. Nothing starts a check
 * until the app calls [OtaUpdateCoordinator.onAppStarted] after its first frame.
 *
 * Exists only when the platform's configuration is enabled; with OTA disabled the app has
 * no runtime at all and behaves exactly as before.
 */
class OtaRuntime private constructor(
    val config: OtaConfig,
    val activation: DatasetActivationManager,
    val downloads: DatasetDownloadManager,
    val coordinator: OtaUpdateCoordinator,
    val resolver: HistoricalDatasetResolver,
    val leases: SnapshotLeases,
    val recovery: RecoveryReport,
) {
    /** The snapshot new sessions are created with. */
    val snapshot: StateFlow<DatasetSnapshot> get() = activation.snapshot

    val status: StateFlow<OtaStatus> get() = coordinator.status

    companion object {
        fun open(
            config: OtaConfig,
            store: OtaFileStore,
            fetcher: BlobFetcher,
            signatures: SignatureVerifier,
            clock: OtaClock,
            bundle: BundleInventory,
            /** The saved-build library payload, read-only (the OTA client never writes it). */
            libraryPayload: () -> String?,
            scope: CoroutineScope,
        ): OtaRuntime {
            val cache = DatasetCache(store, config.game, config.channel)
            val verifier = CatalogVerifier(config.keyring, signatures, config.game, config.channel)
            val activation = DatasetActivationManager(cache, bundle, verifier, scope)
            val recovery = activation.recover()
            val downloads = DatasetDownloadManager(config, fetcher, cache, activation, bundle, clock)
            val leases = SnapshotLeases()
            val coordinator = OtaUpdateCoordinator(
                config = config,
                catalog = RemoteDatasetCatalog(config, fetcher, verifier, activation, clock),
                downloads = downloads,
                activation = activation,
                cache = cache,
                bundle = bundle,
                clock = clock,
                leases = leases,
                libraryBuilds = { libraryClientBuilds(config.game, libraryPayload) },
                scope = scope,
            )
            return OtaRuntime(config, activation, downloads, coordinator, HistoricalDatasetResolver(config, activation, downloads), leases, recovery)
        }
    }
}

/**
 * The client builds the saved-build library references (for retention), read through the
 * storage's read function and the library's own pure codec — never through a repository that
 * could write. `null` when the payload cannot be read or decoded: retention then deletes no
 * installed release (client design §5.3).
 */
fun libraryClientBuilds(game: String, read: () -> String?): Set<String>? {
    val payload = try {
        read()
    } catch (_: Exception) {
        return null
    } ?: return emptySet()
    val records = try {
        LocalBuildLibraryCodec.decode(payload)
    } catch (_: Exception) {
        return null
    }
    return records.filter { it.build.game.value == game }.mapTo(HashSet()) { it.build.clientBuild }
}

/**
 * The dataset collaborators of one session — the repository, icons, descriptions and
 * localization loaders over one source, and the product new drafts are created on. Each screen
 * takes the [SessionDatasets] current when it opens and keeps it (client design §4.5); loaders
 * cache decoded assets per session, so an activation never mixes two releases in one editor.
 */
class SessionDatasets(
    val product: DatasetBundleId,
    val assets: DatasetAssetSource,
    /** The snapshot behind these collaborators; `null` with OTA disabled (bundled data only). */
    val snapshot: DatasetSnapshot?,
) {
    val datasets: TalentDatasetRepository = BundledTalentDatasetRepository(assets)
    val icons: TalentIconsLoader = TalentIconsLoader(assets)
    val descriptions: TalentDescriptionsLoader = TalentDescriptionsLoader(assets)
    val localization: TalentLocalizationLoader = TalentLocalizationLoader(assets)

    companion object {
        /** OTA disabled: today's wiring, unchanged — the bundled resources and [PRODUCT_BUNDLE]. */
        fun bundled(assets: DatasetAssetSource): SessionDatasets = SessionDatasets(PRODUCT_BUNDLE, assets, snapshot = null)

        fun of(snapshot: DatasetSnapshot): SessionDatasets =
            SessionDatasets(snapshot.product, RoutingDatasetAssetSource(snapshot), snapshot)
    }
}
