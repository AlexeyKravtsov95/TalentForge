package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId

/**
 * The datasets one session sees, frozen (client design §4.5): which client builds exist and
 * where each one's files are read from — an installed OTA release (its newest revision) or the
 * app bundle — plus the [product] new drafts are created on.
 *
 * A snapshot is immutable. An editor keeps the snapshot it was opened with for its whole
 * life, so its document, icons, descriptions and localization all come from the same release
 * even if another release is activated meanwhile; activation only publishes a **new**
 * snapshot for screens opened afterwards. For one client build all sources carry identical
 * talent data (contract §6.4 #7); only presentation can differ between revisions.
 */
class DatasetSnapshot(
    /** Where new drafts are created: the effective product (client design §4.4). */
    val product: DatasetBundleId,
    sources: Map<DatasetBundleId, BuildSource>,
    /** Increases with every published snapshot (diagnostics and tests). */
    val generation: Long,
) {
    /** The source of one client build, and the OTA release behind it (`null` = bundled). */
    class BuildSource(val assets: DatasetAssetSource, val releaseId: String?)

    private val byBundle: Map<DatasetBundleId, BuildSource> = sources.toMap()

    init {
        require(product in byBundle) { "the product ${product.clientBuild} has no source" }
    }

    val bundles: Set<DatasetBundleId> get() = byBundle.keys

    fun source(bundle: DatasetBundleId): BuildSource? = byBundle[bundle]

    /** The OTA release the product is read from; `null` while the bundled product is in use. */
    val productReleaseId: String? get() = byBundle.getValue(product).releaseId

    /** Every OTA release this snapshot reads from. */
    val releaseIds: Set<String> get() = byBundle.values.mapNotNullTo(HashSet()) { it.releaseId }
}
