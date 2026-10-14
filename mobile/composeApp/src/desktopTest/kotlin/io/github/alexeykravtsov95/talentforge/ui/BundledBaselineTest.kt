package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import io.github.alexeykravtsov95.talentforge.ui.library.BUNDLE_70245
import io.github.alexeykravtsov95.talentforge.ui.library.PREVIOUS_PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.REFERENCE_BUNDLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Bundled runtime verification: every packaged bundle — the 1.60.1.70009
 * reference baseline, the retained 1.60.1.70245 and 1.60.1.70338 datasets and
 * the 1.60.1.70374 product dataset — loads through the
 * real Compose Multiplatform resource pipeline on the desktop target, the
 * same source the Android and iOS hosts use.
 */
class BundledBaselineTest {

    private val bundles = ALL_BUNDLES

    @Test
    fun bundlesAreTheReferenceBaselineTheRetainedAndTheCurrentProductDatasets() {
        assertEquals("1.60.1.70009", REFERENCE_BUNDLE.clientBuild)
        assertEquals("1.60.1.70245", BUNDLE_70245.clientBuild)
        assertEquals("1.60.1.70338", PREVIOUS_PRODUCT_BUNDLE.clientBuild)
        assertEquals("1.60.1.70374", PRODUCT_BUNDLE.clientBuild)
        assertEquals(setOf(REFERENCE_BUNDLE.game), ALL_BUNDLES.map { it.game }.toSet())
    }

    @Test
    fun manifestContainsNineClasses() = runTest {
        for (bundle in bundles) assertNineClasses(bundle)
    }

    private suspend fun assertNineClasses(bundle: DatasetBundleId) {
        val outcome = repository().manifest(bundle)

        val manifest = assertIs<DatasetOutcome.Loaded<DatasetManifest>>(outcome).value
        assertEquals(9, manifest.classes.size)
        assertEquals(
            setOf(
                "DRUID", "HUNTER", "MAGE", "PALADIN", "PRIEST",
                "ROGUE", "SHAMAN", "WARLOCK", "WARRIOR",
            ),
            manifest.classes.map { it.token.value }.toSet(),
        )
    }

    @Test
    fun loadsEveryBundledDocumentViaManifest() = runTest {
        for (bundle in bundles) assertEveryDocumentLoads(bundle)
    }

    private suspend fun assertEveryDocumentLoads(bundle: DatasetBundleId) {
        val repository = repository()
        val manifest = assertIs<DatasetOutcome.Loaded<DatasetManifest>>(
            repository.manifest(bundle),
        ).value

        assertEquals(9, manifest.classes.size)
        for (entry in manifest.classes) {
            val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
                repository.document(
                    DatasetId(
                        game = manifest.game,
                        clientBuild = manifest.clientBuild,
                        classToken = ClassToken(entry.token.value),
                    ),
                ),
                "failed to load bundled class ${entry.token}",
            ).value

            assertEquals(entry.token, document.classInfo.token)
            assertEquals(entry.contentHash, document.contentHash)
            assertEquals(entry.treeIds.toSet(), document.trees.map { it.id }.toSet())
            assertTrue(document.trees.all { it.nodes.isNotEmpty() })
        }
    }

    private fun repository() = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
}
