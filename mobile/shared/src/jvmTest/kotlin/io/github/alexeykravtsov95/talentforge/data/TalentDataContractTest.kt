package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM contract tests over the committed normalized datasets: every published
 * document and manifest must parse under the strict reader. This is the drift
 * detector between the Kotlin models and the public JSON schemas.
 */
class TalentDataContractTest {

    @Test
    fun parsesAllCommittedClassDocuments() {
        val builds = listOf(
            "1.60.1.69977" to TestRepoAssets.forever69977,
            "1.60.1.70009" to TestRepoAssets.forever70009,
        )
        val parsed = mutableListOf<TalentDataDocument>()
        for ((build, directory) in builds) {
            for (token in TestRepoAssets.classTokens) {
                val file = directory.resolve("$token.json")
                assertTrue(file.isFile, "missing committed document ${file.path}")
                val document = TalentDataContract.decodeTalentDataDocument(file.readBytes())
                assertEquals(GameId("forever"), document.game, file.path)
                assertEquals(build, document.clientBuild, file.path)
                assertEquals(token.uppercase(), document.classInfo.token.value, file.path)
                assertTrue(document.trees.isNotEmpty(), file.path)
                assertTrue(document.trees.all { tree -> tree.nodes.isNotEmpty() }, file.path)
                parsed += document
            }
        }
        assertEquals(18, parsed.size, "expected 9 classes x 2 builds of committed documents")
    }

    @Test
    fun parsesBothCommittedManifests() {
        val manifests = listOf(
            TestRepoAssets.forever69977.resolve("manifest.json"),
            TestRepoAssets.forever70009.resolve("manifest.json"),
        ).map { file ->
            assertTrue(file.isFile, "missing committed manifest ${file.path}")
            TalentDataContract.decodeDatasetManifest(file.readBytes())
        }

        assertEquals(2, manifests.size)
        manifests.forEach { manifest: DatasetManifest ->
            assertEquals(GameId("forever"), manifest.game)
            assertEquals(9, manifest.classes.size)
            assertTrue(manifest.classes.all { it.validation.errors == 0 })
            assertTrue(manifest.classes.all { it.file.endsWith(".json") })
        }
        assertEquals("1.60.1.69977", manifests[0].clientBuild)
        assertEquals("1.60.1.70009", manifests[1].clientBuild)
    }

    @Test
    fun manifestFileInventoryMatchesCommittedDocuments() {
        for (directory in listOf(TestRepoAssets.forever69977, TestRepoAssets.forever70009)) {
            val manifest = TalentDataContract.decodeDatasetManifest(
                directory.resolve("manifest.json").readBytes(),
            )
            val committed = directory.listFiles { file: File -> file.name.endsWith(".json") }
                .map { it.name }
                .filterNot { it == "manifest.json" }
                .toSet()
            assertEquals(committed, manifest.classes.map { it.file }.toSet(), directory.path)
        }
    }
}
