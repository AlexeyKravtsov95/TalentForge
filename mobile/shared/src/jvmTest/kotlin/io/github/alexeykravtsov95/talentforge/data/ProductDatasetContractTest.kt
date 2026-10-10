package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.tooltips.BundledTalentTooltipsRepository
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltips
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipsLoadError
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipsOutcome
import io.github.alexeykravtsov95.talentforge.data.visuals.BundledTalentVisualsRepository
import io.github.alexeykravtsov95.talentforge.data.visuals.TalentVisuals
import io.github.alexeykravtsov95.talentforge.data.visuals.VisualsLoadError
import io.github.alexeykravtsov95.talentforge.data.visuals.VisualsOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PRODUCT = DatasetBundleId(GameId("forever"), "1.60.1.70338")

/**
 * M5.DATA.2: the committed product dataset 1.60.1.70338 — documents, manifest,
 * `talent-visuals.v1` / `talent-icons.v1` and `talent-tooltips.v1` — parses
 * under the strict readers, passes every repository identity check, and is
 * supported by the Kotlin allocation engine exactly like the reference
 * baseline (only Rogue Venom stays unresolved). Another build's sidecars —
 * even those of the previous product dataset 1.60.1.70245 — can never stand
 * in for it: they fail with a typed build mismatch.
 */
class ProductDatasetContractTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach(File::deleteRecursively)
    }

    /** Asset root mapping `dataset/`, `visuals/`, `tooltips/` onto the committed data tree. */
    private fun committedAssets(): DatasetAssetSource =
        object : DatasetAssetSource {
            override suspend fun read(path: String): ByteArray {
                val data = TestRepoAssets.repoRoot.resolve("data")
                val file = when {
                    path.startsWith("dataset/") -> data.resolve("normalized").resolve(path.removePrefix("dataset/"))
                    else -> data.resolve(path)
                }
                if (!file.isFile) throw AssetNotFoundException(path)
                return file.readBytes()
            }
        }

    private fun directoryAssetSource(root: File): DatasetAssetSource =
        object : DatasetAssetSource {
            override suspend fun read(path: String): ByteArray {
                val file = root.resolve(path)
                if (!file.isFile) throw AssetNotFoundException(path)
                return file.readBytes()
            }
        }

    private fun productDocument(token: String): TalentDataDocument =
        TalentDataContract.decodeTalentDataDocument(TestRepoAssets.forever70338.resolve("$token.json").readBytes())

    @Test
    fun everyProductDocumentLoadsThroughTheVerifyingRepository() = runTest {
        val repository = BundledTalentDatasetRepository(committedAssets())
        val manifest = assertIs<DatasetOutcome.Loaded<DatasetManifest>>(repository.manifest(PRODUCT)).value
        assertEquals("1.60.1.70338", manifest.clientBuild)
        assertEquals(TestRepoAssets.classTokens.map { it.uppercase() }.toSet(), manifest.classes.map { it.token.value }.toSet())

        var nodes = 0
        for (entry in manifest.classes) {
            val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
                repository.document(DatasetId(PRODUCT.game, PRODUCT.clientBuild, entry.token)),
            ).value
            assertEquals(entry.contentHash, document.contentHash)
            assertEquals(51, document.trees.single().pointBudget?.maxPoints, entry.token.value)
            nodes += document.trees.single().nodes.size
        }
        assertEquals(469, nodes)
    }

    @Test
    fun theKotlinEngineSupportsTheProductDatasetLikeTheReferenceBaseline() {
        for (token in TestRepoAssets.classTokens) {
            val engine = TalentAllocationEngine(productDocument(token))
            val expected = if (token == "rogue") setOf(VENOM) else emptySet()
            assertEquals(expected, engine.unsupportedNodes().keys, token)
            assertTrue(engine.validate(engine.emptyState()).valid, token)
        }
    }

    @Test
    fun productVisualsAndTooltipsAreBoundToTheProductDocuments() = runTest {
        val visuals = BundledTalentVisualsRepository(committedAssets())
        val tooltips = BundledTalentTooltipsRepository(committedAssets())
        var icons = 0
        for (token in TestRepoAssets.classTokens) {
            val document = productDocument(token)
            val loadedVisuals = assertIs<VisualsOutcome.Loaded<TalentVisuals>>(visuals.visuals(document), token).value
            assertEquals("1.60.1.70338", loadedVisuals.clientBuild)
            for (fileDataId in loadedVisuals.iconFileDataIds) {
                val path = loadedVisuals.iconAssetPathOf(fileDataId)
                assertTrue(path != null && path.startsWith("visuals/forever/1.60.1.70338/icons/"), "$token $path")
            }
            icons += loadedVisuals.iconFileDataIds.size
            val loadedTooltips = assertIs<TooltipsOutcome.Loaded<TalentTooltips>>(tooltips.tooltips(document), token).value
            assertEquals("1.60.1.70338", loadedTooltips.clientBuild)
        }
        assertTrue(icons >= 358, "every class resolves its own icons ($icons class-icon pairs)")
    }

    @Test
    fun previousProductSidecarsCannotStandInForTheProductBuild() = runTest {
        // a hand-copied 70245 visuals/tooltips tree placed where 70338's belongs
        val temp = Files.createTempDirectory("talentforge-substitution").toFile()
        tempDirs += temp
        TestRepoAssets.visuals70245.copyRecursively(temp.resolve("visuals/forever/1.60.1.70338"))
        TestRepoAssets.tooltips70245.copyRecursively(temp.resolve("tooltips/forever/1.60.1.70338"))
        val assets = directoryAssetSource(temp)
        val document = productDocument("warrior")

        val visualsError = assertIs<VisualsOutcome.Failed>(BundledTalentVisualsRepository(assets).visuals(document)).error
        val visualsMismatch = assertIs<VisualsLoadError.BuildMismatch>(visualsError)
        assertEquals("1.60.1.70338", visualsMismatch.expected)
        assertEquals("1.60.1.70245", visualsMismatch.actual)

        val tooltipsError = assertIs<TooltipsOutcome.Failed>(BundledTalentTooltipsRepository(assets).tooltips(document)).error
        val tooltipsMismatch = assertIs<TooltipsLoadError.BuildMismatch>(tooltipsError)
        assertEquals("1.60.1.70338", tooltipsMismatch.expected)
        assertEquals("1.60.1.70245", tooltipsMismatch.actual)
    }

    private companion object {
        /** Rogue "Venom": its TraitCond 50984 stays unresolved in every committed build. */
        const val VENOM = 105712
    }
}
