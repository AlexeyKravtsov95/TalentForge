package io.github.alexeykravtsov95.talentforge.ui.assets

import androidx.compose.ui.graphics.ImageBitmap
import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.AssetReadException
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentTree
import io.github.alexeykravtsov95.talentforge.data.visuals.BundledTalentVisualsRepository
import io.github.alexeykravtsov95.talentforge.data.visuals.TalentVisuals
import io.github.alexeykravtsov95.talentforge.data.visuals.VisualsLoadError
import io.github.alexeykravtsov95.talentforge.data.visuals.VisualsOutcome
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentNodeIcons
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Outcome of loading one bundled icon image; a failure is a value, never a crash. */
sealed interface TalentIconLoad {
    data class Loaded(val bitmap: ImageBitmap) : TalentIconLoad

    data class Failed(val reason: TalentIconFailure) : TalentIconLoad
}

/** Why a bundled icon image could not be shown (the node keeps its monogram tile). */
enum class TalentIconFailure {
    /** The image is not in the bundle. */
    ASSET_MISSING,

    /** The image exists but could not be read. */
    ASSET_UNREADABLE,

    /** The bytes are not a decodable image (corrupt or not an image at all). */
    UNDECODABLE,
}

/**
 * Decoded talent icons from the bundled Compose resources — offline by
 * construction: the only input is a [DatasetAssetSource], which reads packaged
 * files; nothing here can reach the network.
 *
 * Each image is read and decoded **at most once** per source: outcomes
 * (including failures) are cached by asset path, which already carries the
 * game and client build, so an icon of one build can never answer for another.
 * Loading happens when an editor session opens, off the UI thread — never
 * during composition or inside a draw pass.
 */
class BundledTalentIconSource(
    private val assets: DatasetAssetSource,
    private val decode: (ByteArray) -> ImageBitmap = ::decodeBundledIcon,
) {
    private val mutex = Mutex()
    private val cache = HashMap<String, TalentIconLoad>()

    /** The icon stored at [assetPath] (forward slashes, relative to the bundled asset root). */
    suspend fun icon(assetPath: String): TalentIconLoad = mutex.withLock {
        cache[assetPath] ?: load(assetPath).also { cache[assetPath] = it }
    }

    private suspend fun load(assetPath: String): TalentIconLoad {
        val bytes = try {
            assets.read(assetPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetNotFoundException) {
            return TalentIconLoad.Failed(TalentIconFailure.ASSET_MISSING)
        } catch (e: AssetReadException) {
            return TalentIconLoad.Failed(TalentIconFailure.ASSET_UNREADABLE)
        }
        val bitmap = try {
            decode(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Platform decoders signal an undecodable payload differently
            // (exception types vary by platform); all of them mean the same
            // thing here: this one icon falls back to its monogram.
            return TalentIconLoad.Failed(TalentIconFailure.UNDECODABLE)
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return TalentIconLoad.Failed(TalentIconFailure.UNDECODABLE)
        }
        return TalentIconLoad.Loaded(bitmap)
    }
}

@OptIn(ExperimentalResourceApi::class)
private fun decodeBundledIcon(bytes: ByteArray): ImageBitmap = bytes.decodeToImageBitmap()

/**
 * The icons of one opened tree, plus what could not be shown and why. The
 * failures are typed diagnostics only: a node without an icon renders its
 * monogram tile, and allocation is never affected by a visual failure.
 */
data class TalentNodeIconsLoad(
    val icons: TalentNodeIcons,
    /** The class's visuals could not be loaded/verified at all (every node falls back). */
    val visualsError: VisualsLoadError?,
    /** Nodes whose own icon image failed, by node id. */
    val failedNodes: Map<Int, TalentIconFailure>,
    /**
     * The verified visuals sidecar itself (`null` on failure): its display spell ids check a
     * localization overlay's `textSpellId` without reading the asset again (M5.REL.3b.2).
     */
    val visuals: TalentVisuals? = null,
)

/**
 * App-level collaborator that resolves and decodes the icons of a class
 * document's tree: `talent-visuals.v1` + `talent-icons.v1` through
 * [BundledTalentVisualsRepository] (strict, identity-checked against the
 * document), then the images through one shared [BundledTalentIconSource].
 *
 * A node shows an icon only when it has exactly one entry — the dataset
 * publishes no rule for which entry represents a multi-entry node, so such a
 * node keeps its monogram rather than an arbitrarily chosen icon.
 */
class TalentIconsLoader(
    assets: DatasetAssetSource,
    private val source: BundledTalentIconSource = BundledTalentIconSource(assets),
) {
    private val visualsRepository = BundledTalentVisualsRepository(assets)

    suspend fun load(document: TalentDataDocument, tree: TalentTree): TalentNodeIconsLoad {
        val visuals = when (val outcome = visualsRepository.visuals(document)) {
            is VisualsOutcome.Failed ->
                return TalentNodeIconsLoad(TalentNodeIcons.None, outcome.error, emptyMap())

            is VisualsOutcome.Loaded -> outcome.value
        }
        val bitmaps = HashMap<Int, ImageBitmap>(tree.nodes.size)
        val failures = HashMap<Int, TalentIconFailure>()
        for (node in tree.nodes) {
            val entry = node.entries.singleOrNull() ?: continue
            val assetPath = visuals.iconFileDataIdOf(entry.id)
                ?.let(visuals::iconAssetPathOf)
                ?: continue
            when (val icon = source.icon(assetPath)) {
                is TalentIconLoad.Loaded -> bitmaps[node.id] = icon.bitmap
                is TalentIconLoad.Failed -> failures[node.id] = icon.reason
            }
        }
        return TalentNodeIconsLoad(TalentNodeIcons(bitmaps), visualsError = null, failedNodes = failures, visuals = visuals)
    }
}
