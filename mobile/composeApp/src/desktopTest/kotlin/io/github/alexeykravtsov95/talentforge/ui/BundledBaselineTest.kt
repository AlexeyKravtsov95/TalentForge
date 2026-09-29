package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Baseline runtime verification: the packaged 1.60.1.70009 bundle loads through
 * the real Compose Multiplatform resource pipeline on the desktop target —
 * the same source the Android and iOS hosts use.
 */
class BundledBaselineTest {

    private val bundle = DatasetBundleId(GameId("forever"), "1.60.1.70009")

    @Test
    fun manifestContainsNineClasses() = runTest {
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
    fun loadsEveryBaselineDocumentViaManifest() = runTest {
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
