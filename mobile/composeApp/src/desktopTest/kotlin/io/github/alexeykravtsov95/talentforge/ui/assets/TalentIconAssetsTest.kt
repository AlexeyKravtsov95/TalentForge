package io.github.alexeykravtsov95.talentforge.ui.assets

import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.AssetReadException
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.visuals.VisualsLoadError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The bundled icon pipeline through the real Compose Multiplatform resources
 * on the desktop target — the same packaged files the Android and iOS hosts
 * read. Nothing here can reach the network: the only source is the resource
 * bundle (optionally wrapped to record reads or inject one broken asset).
 */
class TalentIconAssetsTest {

    private val classTokens = listOf(
        "DRUID", "HUNTER", "MAGE", "PALADIN", "PRIEST", "ROGUE", "SHAMAN", "WARLOCK", "WARRIOR",
    )

    /** Records every read; [intercept] may answer (or fail) instead of the real bundle. */
    private class RecordingAssets(
        private val delegate: DatasetAssetSource = ComposeResourceDatasetAssetSource(),
        private val intercept: (String) -> ByteArray? = { null },
    ) : DatasetAssetSource {
        val reads = mutableListOf<String>()

        override suspend fun read(path: String): ByteArray {
            reads += path
            return intercept(path) ?: delegate.read(path)
        }

        fun imageReads(): List<String> = reads.filter { it.endsWith(".png") }
    }

    private suspend fun document(assets: DatasetAssetSource, token: String): TalentDataDocument =
        assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(assets)
                .document(DatasetId(GameId("forever"), "1.60.1.70009", ClassToken(token))),
            "failed to load bundled class $token",
        ).value

    private fun iconPath(fileDataId: Int) = "visuals/forever/1.60.1.70009/icons/$fileDataId.png"

    // ------------------------------------------------------------------ baseline

    @Test
    fun everyBundledTalentOfAllNineClassesGetsItsRealDecodedIcon() = runTest {
        val assets = RecordingAssets()
        val loader = TalentIconsLoader(assets)
        var nodes = 0
        for (token in classTokens) {
            val talents = document(assets, token)
            val tree = talents.trees.single()
            val load = loader.load(talents, tree)

            assertNull(load.visualsError, "$token visuals failed: ${load.visualsError}")
            assertTrue(load.failedNodes.isEmpty(), "$token icons failed: ${load.failedNodes}")
            assertEquals(tree.nodes.map { it.id }.toSet(), load.icons.nodeIds, token)
            for (node in tree.nodes) {
                val bitmap = assertNotNull(load.icons.iconOf(node.id), "$token node ${node.id}")
                assertEquals(64, bitmap.width)
                assertEquals(64, bitmap.height)
            }
            nodes += tree.nodes.size
        }
        assertEquals(469, nodes, "talent nodes of the nine bundled classes")
        // Deduplicated by FileDataID across classes: 358 images, each read once.
        assertEquals(358, assets.imageReads().size)
        assertEquals(358, assets.imageReads().toSet().size)
    }

    // ------------------------------------------------------------------ caching

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun repeatedIconRequestIsReadAndDecodedOnce() = runTest {
        val assets = RecordingAssets()
        var decodes = 0
        val source = BundledTalentIconSource(
            assets = assets,
            decode = { bytes ->
                decodes += 1
                bytes.decodeToImageBitmap()
            },
        )
        val first = assertIs<TalentIconLoad.Loaded>(source.icon(iconPath(132155)))
        val second = assertIs<TalentIconLoad.Loaded>(source.icon(iconPath(132155)))
        val third = assertIs<TalentIconLoad.Loaded>(source.icon(iconPath(132155)))

        assertSame(first.bitmap, second.bitmap)
        assertSame(first.bitmap, third.bitmap)
        assertEquals(listOf(iconPath(132155)), assets.reads)
        assertEquals(1, decodes)
    }

    @Test
    fun reopeningAClassThroughTheSameLoaderDecodesNothingAgain() = runTest {
        val assets = RecordingAssets()
        val loader = TalentIconsLoader(assets)
        val warrior = document(assets, "WARRIOR")

        val first = loader.load(warrior, warrior.trees.single())
        val imagesAfterFirst = assets.imageReads().size
        val second = loader.load(warrior, warrior.trees.single())

        assertEquals(53, imagesAfterFirst)
        assertEquals(imagesAfterFirst, assets.imageReads().size, "no image is read twice")
        for (nodeId in first.icons.nodeIds) {
            assertSame(first.icons.iconOf(nodeId), second.icons.iconOf(nodeId))
        }
    }

    @Test
    fun aFailedIconIsNotRetriedOnEveryRequest() = runTest {
        val assets = RecordingAssets(intercept = { path ->
            if (path == iconPath(132155)) throw AssetNotFoundException(path) else null
        })
        val source = BundledTalentIconSource(assets)
        repeat(3) {
            assertEquals(
                TalentIconLoad.Failed(TalentIconFailure.ASSET_MISSING),
                source.icon(iconPath(132155)),
            )
        }
        assertEquals(1, assets.reads.size)
    }

    // ------------------------------------------------------------------ fallback

    @Test
    fun missingUnreadableAndCorruptIconsFailIndividuallyAndEveryOtherIconStillLoads() = runTest {
        // Real Warrior talents: Improved Rend (132155), Deflection (132269), Cruelty (132292).
        val assets = RecordingAssets(intercept = { path ->
            when (path) {
                iconPath(132155) -> throw AssetNotFoundException(path)
                iconPath(132269) -> throw AssetReadException(path)
                iconPath(132292) -> "not an image".encodeToByteArray()
                else -> null
            }
        })
        val warrior = document(assets, "WARRIOR")
        val tree = warrior.trees.single()
        fun nodeIdOf(name: String) = tree.nodes.single { it.name == name }.id

        val load = TalentIconsLoader(assets).load(warrior, tree)

        assertNull(load.visualsError)
        assertEquals(
            mapOf(
                nodeIdOf("Improved Rend") to TalentIconFailure.ASSET_MISSING,
                nodeIdOf("Deflection") to TalentIconFailure.ASSET_UNREADABLE,
                nodeIdOf("Cruelty") to TalentIconFailure.UNDECODABLE,
            ),
            load.failedNodes,
        )
        assertEquals(tree.nodes.size - 3, load.icons.nodeIds.size)
        assertNull(load.icons.iconOf(nodeIdOf("Improved Rend")))
        assertNotNull(load.icons.iconOf(nodeIdOf("Deep Wounds")))
    }

    @Test
    fun emptyImageFileIsUndecodableNotACrash() = runTest {
        val source = BundledTalentIconSource(RecordingAssets(intercept = { ByteArray(0) }))
        assertEquals(
            TalentIconLoad.Failed(TalentIconFailure.UNDECODABLE),
            source.icon(iconPath(132155)),
        )
    }

    @Test
    fun visualsOfAnotherDatasetRevisionYieldNoIconsAndATypedReason() = runTest {
        val assets = RecordingAssets()
        val warrior = document(assets, "WARRIOR")
        val revised = warrior.copy(contentHash = ContentHash("sha256:" + "ab".repeat(32)))

        val load = TalentIconsLoader(assets).load(revised, revised.trees.single())

        assertIs<VisualsLoadError.DatasetRevisionMismatch>(load.visualsError)
        assertTrue(load.icons.nodeIds.isEmpty(), "no icon is shown for mismatched visuals")
        assertTrue(assets.imageReads().isEmpty(), "no image is even read")
    }

    @Test
    fun aBuildWithoutBundledVisualsYieldsNoIconsAndATypedReason() = runTest {
        val assets = RecordingAssets()
        val warrior = document(assets, "WARRIOR")
        val otherBuild = warrior.copy(clientBuild = "1.60.1.69977")

        val load = TalentIconsLoader(assets).load(otherBuild, otherBuild.trees.single())

        assertEquals(
            VisualsLoadError.AssetMissing("visuals/forever/1.60.1.69977/warrior.json"),
            load.visualsError,
        )
        assertTrue(load.icons.nodeIds.isEmpty())
    }
}
