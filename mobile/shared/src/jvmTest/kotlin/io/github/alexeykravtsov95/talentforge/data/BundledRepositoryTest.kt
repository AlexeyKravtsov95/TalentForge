package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val WARRIOR_70009 = DatasetId(GameId("forever"), "1.60.1.70009", ClassToken("WARRIOR"))
private val BUNDLE_70009 = DatasetBundleId(GameId("forever"), "1.60.1.70009")

/**
 * Repository integrity checks against the real committed 70009 bundle plus
 * tampered copies that must fail with the typed [DatasetLoadError] they exist
 * to represent.
 */
class BundledRepositoryTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach(File::deleteRecursively)
    }

    /** Repository whose asset root maps `dataset/...` onto the canonical data tree. */
    private fun canonicalRepository(): TalentDatasetRepository =
        BundledTalentDatasetRepository(canonicalAssetSource())

    private fun canonicalAssetSource(): DatasetAssetSource =
        object : DatasetAssetSource {
            override suspend fun read(path: String): ByteArray {
                val file = TestRepoAssets.repoRoot
                    .resolve("data/normalized")
                    .resolve(path.removePrefix("dataset/"))
                if (!file.isFile) throw AssetNotFoundException(path)
                return file.readBytes()
            }
        }

    /** Copies the canonical bundle, applies [tamper], and roots a repository at it. */
    private fun tamperedBundle(tamper: (directory: File) -> Unit): TalentDatasetRepository {
        val temp = Files.createTempDirectory("talentforge-bundle").toFile()
        tempDirs += temp
        val bundleRoot = temp.resolve("dataset/forever/1.60.1.70009")
        TestRepoAssets.forever70009.copyRecursively(bundleRoot, overwrite = true)
        tamper(bundleRoot)
        return BundledTalentDatasetRepository(directoryAssetSource(temp))
    }

    private fun directoryAssetSource(root: File): DatasetAssetSource =
        object : DatasetAssetSource {
            override suspend fun read(path: String): ByteArray {
                val file = root.resolve(path)
                if (!file.isFile) throw AssetNotFoundException(path)
                return file.readBytes()
            }
        }

    private fun editJson(directory: File, fileName: String, transform: (String) -> String) {
        val file = directory.resolve(fileName)
        file.writeText(transform(file.readText()))
    }

    @Test
    fun loadsValidDocumentAndVerifiesIdentity() = runTest {
        val outcome = canonicalRepository().document(WARRIOR_70009)

        val loaded = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(outcome)
        assertEquals(ClassToken("WARRIOR"), loaded.value.classInfo.token)
        assertEquals("1.60.1.70009", loaded.value.clientBuild)
        assertTrue(loaded.value.trees.isNotEmpty())
    }

    @Test
    fun loadsBaselineManifest() = runTest {
        val outcome = canonicalRepository().manifest(BUNDLE_70009)

        val loaded = assertIs<DatasetOutcome.Loaded<DatasetManifest>>(outcome)
        assertEquals(9, loaded.value.classes.size)
        assertTrue(loaded.value.classes.all { it.validation.errors == 0 })
    }

    @Test
    fun missingDocumentFailsWithTypeAssetMissing() = runTest {
        val repository = tamperedBundle { directory ->
            assertTrue(directory.resolve("warrior.json").delete())
        }
        val error = assertIs<DatasetOutcome.Failed>(repository.document(WARRIOR_70009)).error
        val missing = assertIs<DatasetLoadError.AssetMissing>(error)
        assertTrue("warrior.json" in missing.path)
    }

    @Test
    fun missingManifestFailsWithTypeAssetMissing() = runTest {
        val repository = tamperedBundle { directory ->
            assertTrue(directory.resolve("manifest.json").delete())
        }
        val error = assertIs<DatasetOutcome.Failed>(repository.manifest(BUNDLE_70009)).error
        assertIs<DatasetLoadError.AssetMissing>(error)
    }

    @Test
    fun unknownClassFailsWithTypeClassEntryMissing() = runTest {
        val repository = tamperedBundle { }
        val outcome = repository.document(WARRIOR_70009.copy(classToken = ClassToken("MONK")))
        val missing = assertIs<DatasetLoadError.ClassEntryMissing>(
            assertIs<DatasetOutcome.Failed>(outcome).error,
        )
        assertEquals(ClassToken("MONK"), missing.classToken)
    }

    @Test
    fun tamperedManifestGameFailsWithTypeGameMismatch() = runTest {
        val repository = tamperedBundle { directory ->
            editJson(directory, "manifest.json") { json ->
                json.replace("\"game\": \"forever\"", "\"game\": \"retail\"")
            }
        }
        val mismatch = assertIs<DatasetLoadError.GameMismatch>(
            assertIs<DatasetOutcome.Failed>(repository.manifest(BUNDLE_70009)).error,
        )
        assertEquals(GameId("forever"), mismatch.expected)
        assertEquals(GameId("retail"), mismatch.actual)
    }

    @Test
    fun tamperedManifestBuildFailsWithTypeBuildMismatch() = runTest {
        val repository = tamperedBundle { directory ->
            editJson(directory, "manifest.json") { json ->
                json.replace("\"clientBuild\": \"1.60.1.70009\"", "\"clientBuild\": \"1.60.1.69977\"")
            }
        }
        val mismatch = assertIs<DatasetLoadError.BuildMismatch>(
            assertIs<DatasetOutcome.Failed>(repository.manifest(BUNDLE_70009)).error,
        )
        assertEquals("1.60.1.70009", mismatch.expected)
        assertEquals("1.60.1.69977", mismatch.actual)
    }

    @Test
    fun tamperedDocumentGameFailsWithTypeGameMismatch() = runTest {
        val repository = tamperedBundle { directory ->
            editJson(directory, "warrior.json") { json ->
                json.replace("\"game\": \"forever\"", "\"game\": \"retail\"")
            }
        }
        val mismatch = assertIs<DatasetLoadError.GameMismatch>(
            assertIs<DatasetOutcome.Failed>(repository.document(WARRIOR_70009)).error,
        )
        assertEquals(GameId("retail"), mismatch.actual)
    }

    @Test
    fun tamperedDocumentBuildFailsWithTypeBuildMismatch() = runTest {
        val repository = tamperedBundle { directory ->
            editJson(directory, "warrior.json") { json ->
                json.replace("\"clientBuild\": \"1.60.1.70009\"", "\"clientBuild\": \"1.60.1.69977\"")
            }
        }
        assertIs<DatasetLoadError.BuildMismatch>(
            assertIs<DatasetOutcome.Failed>(repository.document(WARRIOR_70009)).error,
        )
    }

    @Test
    fun swappedDocumentFailsWithTypeClassMismatch() = runTest {
        val repository = tamperedBundle { directory ->
            val druid = directory.resolve("druid.json").readText()
            directory.resolve("druid.json").writeText(directory.resolve("hunter.json").readText())
            directory.resolve("hunter.json").writeText(druid)
        }
        val mismatch = assertIs<DatasetLoadError.ClassMismatch>(
            assertIs<DatasetOutcome.Failed>(
                repository.document(WARRIOR_70009.copy(classToken = ClassToken("DRUID"))),
            ).error,
        )
        assertEquals(ClassToken("DRUID"), mismatch.expected)
        assertEquals(ClassToken("HUNTER"), mismatch.actual)
    }

    @Test
    fun tamperedContentHashFailsWithTypeContentHashMismatch() = runTest {
        val repository = tamperedBundle { directory ->
            editJson(directory, "manifest.json") { json ->
                json.replace(
                    "\"contentHash\": \"sha256:cab5b5103c6a8f7f4dd6cc70bd727f640e95a16d49e9619133cd21299844528e\"",
                    "\"contentHash\": \"sha256:0000000000000000000000000000000000000000000000000000000000000000\"",
                )
            }
        }
        val mismatch = assertIs<DatasetLoadError.ContentHashMismatch>(
            assertIs<DatasetOutcome.Failed>(repository.document(WARRIOR_70009)).error,
        )
        assertTrue(mismatch.expected.value.startsWith("sha256:0000"))
    }

    @Test
    fun tamperedTreeIdsFailsWithTypeTreeIdsMismatch() = runTest {
        val repository = tamperedBundle { directory ->
            editJson(directory, "manifest.json") { json ->
                Regex("\"treeIds\": \\[\\s*1117\\s*]").replace(json, "\"treeIds\": [9999]")
            }
        }
        assertIs<DatasetLoadError.TreeIdsMismatch>(
            assertIs<DatasetOutcome.Failed>(repository.document(WARRIOR_70009)).error,
        )
    }

    @Test
    fun tamperedSchemaVersionFailsWithTypeUnsupportedSchemaVersion() = runTest {
        val repository = tamperedBundle { directory ->
            editJson(directory, "warrior.json") { json ->
                json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
            }
        }
        val unsupported = assertIs<DatasetLoadError.UnsupportedSchemaVersion>(
            assertIs<DatasetOutcome.Failed>(repository.document(WARRIOR_70009)).error,
        )
        assertEquals(2, unsupported.actualVersion)
        assertEquals(ContractKind.TALENT_DATA, unsupported.contract)
    }

    @Test
    fun corruptDocumentFailsWithTypeInvalidJson() = runTest {
        val repository = tamperedBundle { directory ->
            directory.resolve("warrior.json").writeText("{ not json")
        }
        assertIs<DatasetLoadError.InvalidJson>(
            assertIs<DatasetOutcome.Failed>(repository.document(WARRIOR_70009)).error,
        )
    }
}
