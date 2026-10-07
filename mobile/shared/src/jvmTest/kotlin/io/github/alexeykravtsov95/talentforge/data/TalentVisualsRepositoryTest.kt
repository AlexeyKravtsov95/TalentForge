package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Visuals loading against the real committed 70009 visuals plus tampered
 * copies that must fail with the typed [VisualsLoadError] they exist to
 * represent. Nothing here touches the network: assets are plain files.
 */
class TalentVisualsRepositoryTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach(File::deleteRecursively)
    }

    private fun document(token: String, build: File = TestRepoAssets.forever70009): TalentDataDocument =
        TalentDataContract.decodeTalentDataDocument(build.resolve("$token.json").readBytes())

    private fun directoryAssetSource(root: File): DatasetAssetSource =
        object : DatasetAssetSource {
            override suspend fun read(path: String): ByteArray {
                val file = root.resolve(path)
                if (!file.isFile) throw AssetNotFoundException(path)
                return file.readBytes()
            }
        }

    /** Asset root whose `visuals/forever/1.60.1.70009` is a (tampered) copy of the JSON files. */
    private fun bundle(tamper: (directory: File) -> Unit = {}): BundledTalentVisualsRepository {
        val temp = Files.createTempDirectory("talentforge-visuals").toFile()
        tempDirs += temp
        val root = temp.resolve("visuals/forever/1.60.1.70009")
        root.mkdirs()
        TestRepoAssets.visuals70009.listFiles { file: File -> file.name.endsWith(".json") }!!
            .forEach { it.copyTo(root.resolve(it.name)) }
        tamper(root)
        return BundledTalentVisualsRepository(directoryAssetSource(temp))
    }

    private fun edit(directory: File, fileName: String, old: String, new: String) {
        val file = directory.resolve(fileName)
        val text = file.readText()
        val index = text.indexOf(old)
        assertTrue(index >= 0, "$fileName no longer contains: $old")
        file.writeText(text.substring(0, index) + new + text.substring(index + old.length))
    }

    private suspend fun BundledTalentVisualsRepository.errorFor(token: String = "warrior"): VisualsLoadError =
        assertIs<VisualsOutcome.Failed>(visuals(document(token))).error

    // ------------------------------------------------------------------ baseline

    @Test
    fun loadsVisualsForAllNineBundledClasses() = runTest {
        val repository = bundle()
        var entries = 0
        val icons = HashSet<Int>()
        for (token in TestRepoAssets.classTokens) {
            val talents = document(token)
            val loaded = assertIs<VisualsOutcome.Loaded<TalentVisuals>>(repository.visuals(talents), token).value
            assertEquals(talents.classInfo.token, loaded.classToken)
            assertEquals("1.60.1.70009", loaded.clientBuild)
            for (entry in talents.trees.flatMap { it.nodes }.flatMap { it.entries }) {
                val icon = assertNotNull(loaded.iconFileDataIdOf(entry.id), "$token entry ${entry.id}")
                assertEquals(
                    "visuals/forever/1.60.1.70009/icons/$icon.png",
                    loaded.iconAssetPathOf(icon),
                )
                entries += 1
            }
            icons += loaded.iconFileDataIds
        }
        assertEquals(469, entries)
        assertEquals(358, icons.size)
    }

    @Test
    fun knownTalentResolvesToItsRealIcon() = runTest {
        val loaded = assertIs<VisualsOutcome.Loaded<TalentVisuals>>(bundle().visuals(document("warrior"))).value
        // Improved Rend: entry 130686 -> spell 12286 -> SpellMisc.SpellIconFileDataID 132155.
        assertEquals(132155, loaded.iconFileDataIdOf(130686))
        assertNull(loaded.iconFileDataIdOf(999_999), "an id that is not an entry of the class has no icon")
        assertNull(loaded.iconAssetPathOf(424_242), "an icon the class does not use has no path")
    }

    // ------------------------------------------------------------------ assets / parsing

    @Test
    fun missingSidecarFailsWithTypeAssetMissing() = runTest {
        val error = bundle { assertTrue(it.resolve("warrior.json").delete()) }.errorFor()
        assertEquals(
            VisualsLoadError.AssetMissing("visuals/forever/1.60.1.70009/warrior.json"),
            error,
        )
    }

    @Test
    fun missingRegistryFailsWithTypeAssetMissing() = runTest {
        val error = bundle { assertTrue(it.resolve("icons.json").delete()) }.errorFor()
        assertEquals(VisualsLoadError.AssetMissing("visuals/forever/1.60.1.70009/icons.json"), error)
    }

    @Test
    fun unreadableAssetFailsWithTypeAssetUnreadable() = runTest {
        val repository = BundledTalentVisualsRepository(
            object : DatasetAssetSource {
                override suspend fun read(path: String): ByteArray = throw AssetReadException(path)
            },
        )
        assertIs<VisualsLoadError.AssetUnreadable>(repository.errorFor())
    }

    @Test
    fun unknownFieldFailsWithTypeInvalidJson() = runTest {
        val error = bundle {
            edit(it, "warrior.json", "\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"atlas\": \"a.png\",")
        }.errorFor()
        assertIs<VisualsLoadError.InvalidJson>(error)
    }

    @Test
    fun quotedNumberFailsWithTypeInvalidJson() = runTest {
        val error = bundle {
            edit(it, "warrior.json", "\"iconFileDataId\": 132307", "\"iconFileDataId\": \"132307\"")
        }.errorFor()
        assertIs<VisualsLoadError.InvalidJson>(error)
    }

    @Test
    fun wrongSchemaVersionFailsWithTypeUnsupportedSchemaVersion() = runTest {
        val sidecar = bundle {
            edit(it, "warrior.json", "\"schemaVersion\": 1,", "\"schemaVersion\": 2,")
        }.errorFor()
        assertEquals(
            ContractKind.TALENT_VISUALS,
            assertIs<VisualsLoadError.UnsupportedSchemaVersion>(sidecar).contract,
        )
        val registry = bundle {
            edit(it, "icons.json", "\"schemaVersion\": 1,", "\"schemaVersion\": 2,")
        }.errorFor()
        assertEquals(
            ContractKind.TALENT_ICONS,
            assertIs<VisualsLoadError.UnsupportedSchemaVersion>(registry).contract,
        )
    }

    // ------------------------------------------------------------------ identity

    @Test
    fun visualsOfAnotherGameFail() = runTest {
        val error = bundle { edit(it, "warrior.json", "\"game\": \"forever\"", "\"game\": \"retail\"") }.errorFor()
        val mismatch = assertIs<VisualsLoadError.GameMismatch>(error)
        assertEquals(GameId("forever"), mismatch.expected)
        assertEquals(GameId("retail"), mismatch.actual)
    }

    @Test
    fun visualsOfAnotherBuildFail() = runTest {
        val error = bundle {
            edit(it, "warrior.json", "\"clientBuild\": \"1.60.1.70009\"", "\"clientBuild\": \"1.60.1.69977\"")
        }.errorFor()
        val mismatch = assertIs<VisualsLoadError.BuildMismatch>(error)
        assertEquals("1.60.1.70009", mismatch.expected)
        assertEquals("1.60.1.69977", mismatch.actual)
    }

    @Test
    fun visualsOfAnotherClassFail() = runTest {
        // The Mage sidecar placed where the Warrior one is expected.
        val error = bundle {
            it.resolve("mage.json").copyTo(it.resolve("warrior.json"), overwrite = true)
        }.errorFor("warrior")
        val mismatch = assertIs<VisualsLoadError.ClassMismatch>(error)
        assertEquals(ClassToken("WARRIOR"), mismatch.expected)
        assertEquals(ClassToken("MAGE"), mismatch.actual)
    }

    @Test
    fun visualsAreNeverSubstitutedAcrossDatasetRevisions() = runTest {
        // The real 1.60.1.69977 Warrior document has another clientBuild AND
        // another contentHash; the 70009 visuals placed under its build
        // directory must still be refused.
        val older = document("warrior", TestRepoAssets.forever69977)
        val temp = Files.createTempDirectory("talentforge-visuals").toFile()
        tempDirs += temp
        val root = temp.resolve("visuals/forever/1.60.1.69977")
        root.mkdirs()
        TestRepoAssets.visuals70009.resolve("warrior.json").copyTo(root.resolve("warrior.json"))
        TestRepoAssets.visuals70009.resolve("icons.json").copyTo(root.resolve("icons.json"))
        val repository = BundledTalentVisualsRepository(directoryAssetSource(temp))
        assertIs<VisualsLoadError.BuildMismatch>(
            assertIs<VisualsOutcome.Failed>(repository.visuals(older)).error,
        )

        // Same build, same class, another dataset revision: bound by contentHash.
        val current = document("warrior")
        val revised = current.copy(contentHash = ContentHash("sha256:" + "ab".repeat(32)))
        val error = assertIs<VisualsOutcome.Failed>(bundle().visuals(revised)).error
        val mismatch = assertIs<VisualsLoadError.DatasetRevisionMismatch>(error)
        assertEquals(revised.contentHash, mismatch.expected)
        assertEquals(current.contentHash, mismatch.actual)
    }

    @Test
    fun registryOfAnotherBuildFails() = runTest {
        val error = bundle {
            edit(it, "icons.json", "\"clientBuild\": \"1.60.1.70009\"", "\"clientBuild\": \"1.60.1.69977\"")
        }.errorFor()
        assertEquals("visuals/forever/1.60.1.70009/icons.json", assertIs<VisualsLoadError.BuildMismatch>(error).path)
    }

    // ------------------------------------------------------------------ entries

    @Test
    fun duplicateEntryIdFails() = runTest {
        val error = bundle {
            edit(it, "warrior.json", "\"entryId\": 130657", "\"entryId\": 130656")
        }.errorFor()
        assertEquals(130656, assertIs<VisualsLoadError.DuplicateEntry>(error).entryId)
    }

    @Test
    fun unknownTalentEntryFails() = runTest {
        val error = bundle {
            edit(it, "warrior.json", "\"entryId\": 130656", "\"entryId\": 999999")
        }.errorFor()
        assertEquals(999999, assertIs<VisualsLoadError.UnknownEntry>(error).entryId)
    }

    @Test
    fun entryOfAnotherClassIsAnUnknownEntry() = runTest {
        // A real Mage entry id is not a Warrior talent entry.
        val mageEntryId = document("mage").trees.first().nodes.first().entries.first().id
        val error = bundle {
            edit(it, "warrior.json", "\"entryId\": 130656", "\"entryId\": $mageEntryId")
        }.errorFor()
        assertEquals(mageEntryId, assertIs<VisualsLoadError.UnknownEntry>(error).entryId)
    }

    @Test
    fun talentEntryWithoutVisualFails() = runTest {
        val firstEntry = """
            |    {
            |      "entryId": 130656,
            |      "definitionId": 135457,
            |      "spellId": 20504,
            |      "displaySpellId": 20504,
            |      "iconFileDataId": 132307
            |    },
            |
        """.trimMargin()
        val error = bundle { edit(it, "warrior.json", firstEntry, "") }.errorFor()
        assertEquals(130656, assertIs<VisualsLoadError.MissingEntry>(error).entryId)
    }

    @Test
    fun entryContradictingTheDatasetSpellFails() = runTest {
        val error = bundle {
            edit(it, "warrior.json", "\"spellId\": 20504", "\"spellId\": 772")
        }.errorFor()
        assertEquals(130656, assertIs<VisualsLoadError.EntryIdentityMismatch>(error).entryId)
    }

    @Test
    fun zeroOrNegativeIconFileDataIdFails() = runTest {
        for (value in listOf("0", "-1")) {
            val error = bundle {
                edit(it, "warrior.json", "\"iconFileDataId\": 132307", "\"iconFileDataId\": $value")
            }.errorFor()
            val invalid = assertIs<VisualsLoadError.NonPositiveValue>(error)
            assertEquals("iconFileDataId", invalid.field)
            assertEquals(value.toInt(), invalid.value)
        }
    }

    // ------------------------------------------------------------------ registry

    @Test
    fun iconMissingFromTheRegistryFails() = runTest {
        val error = bundle {
            edit(it, "icons.json", "\"fileDataId\": 132307", "\"fileDataId\": 7")
            edit(it, "icons.json", "\"file\": \"icons/132307.png\"", "\"file\": \"icons/7.png\"")
        }.errorFor()
        assertEquals(132307, assertIs<VisualsLoadError.UnregisteredIcon>(error).fileDataId)
    }

    @Test
    fun duplicateRegistryKeyFails() = runTest {
        val error = bundle {
            edit(it, "icons.json", "\"fileDataId\": 132090", "\"fileDataId\": 132089")
            edit(it, "icons.json", "\"file\": \"icons/132090.png\"", "\"file\": \"icons/132089.png\"")
        }.errorFor()
        assertEquals(132089, assertIs<VisualsLoadError.DuplicateIcon>(error).fileDataId)
    }

    @Test
    fun registryFileOutsideTheCanonicalNameFails() = runTest {
        for (file in listOf(
            "icons/../../dataset/forever/1.60.1.70009/warrior.json",
            "https://wago.tools/api/casc/132089",
            "/icons/132089.png",
            "icons/132090.png",
            "icons/132089.blp",
        )) {
            val error = bundle {
                edit(it, "icons.json", "\"file\": \"icons/132089.png\"", "\"file\": \"$file\"")
            }.errorFor()
            val invalid = assertIs<VisualsLoadError.InvalidIconFile>(error, file)
            assertEquals(132089, invalid.fileDataId)
            assertEquals(file, invalid.file)
        }
    }
}
