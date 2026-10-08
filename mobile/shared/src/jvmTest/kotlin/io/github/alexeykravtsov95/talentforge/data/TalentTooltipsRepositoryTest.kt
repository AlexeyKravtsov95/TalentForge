package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.tooltips.BundledTalentTooltipsRepository
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltips
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipGapClassification
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipRankStatus
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipSegmentKind
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipsLoadError
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipsOutcome
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Description loading against the real committed 70009 sidecars plus tampered
 * copies that must fail with the typed [TooltipsLoadError] they exist to
 * represent. Nothing here touches the network: assets are plain files.
 */
class TalentTooltipsRepositoryTest {

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

    /** Asset root whose `tooltips/forever/1.60.1.70009` is a (tampered) copy of the sidecars. */
    private fun bundle(tamper: (directory: File) -> Unit = {}): BundledTalentTooltipsRepository {
        val temp = Files.createTempDirectory("talentforge-tooltips").toFile()
        tempDirs += temp
        val root = temp.resolve("tooltips/forever/1.60.1.70009")
        root.mkdirs()
        TestRepoAssets.tooltips70009.listFiles { file: File -> file.name.endsWith(".json") }!!
            .forEach { it.copyTo(root.resolve(it.name)) }
        tamper(root)
        return BundledTalentTooltipsRepository(directoryAssetSource(temp))
    }

    private fun edit(directory: File, fileName: String, old: String, new: String) {
        val file = directory.resolve(fileName)
        val text = file.readText()
        val index = text.indexOf(old)
        assertTrue(index >= 0, "$fileName no longer contains: $old")
        file.writeText(text.substring(0, index) + new + text.substring(index + old.length))
    }

    /** Rewrites the `entries` array of a sidecar (structure edits that text replacement cannot make). */
    private fun editEntries(directory: File, fileName: String, change: (List<JsonObject>) -> List<JsonObject>) {
        val file = directory.resolve(fileName)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val entries = change(root.getValue("entries").jsonArray.map { it.jsonObject })
        file.writeText(JsonObject(root + ("entries" to JsonArray(entries))).toString())
    }

    private suspend fun BundledTalentTooltipsRepository.errorFor(token: String = "warrior"): TooltipsLoadError =
        assertIs<TooltipsOutcome.Failed>(tooltips(document(token))).error

    private suspend fun warrior(): TalentTooltips =
        assertIs<TooltipsOutcome.Loaded<TalentTooltips>>(bundle().tooltips(document("warrior"))).value

    private fun TalentTooltipRank.text(): String = segments.joinToString("") { it.text }

    // ------------------------------------------------------------------ baseline

    @Test
    fun loadsDescriptionsForAllNineBundledClasses() = runTest {
        val repository = bundle()
        var ranks = 0
        for (token in TestRepoAssets.classTokens) {
            val talents = document(token)
            val loaded = assertIs<TooltipsOutcome.Loaded<TalentTooltips>>(repository.tooltips(talents), token).value
            assertEquals(talents.classInfo.token, loaded.classToken)
            assertEquals("1.60.1.70009", loaded.clientBuild)
            for (entry in talents.trees.flatMap { it.nodes }.flatMap { it.entries }) {
                val entryRanks = assertNotNull(loaded.ranksOf(entry.id), "$token entry ${entry.id}")
                assertEquals((1..entry.maxRank).toList(), entryRanks.map { it.rank })
                ranks += entryRanks.size
            }
        }
        assertEquals(1333, ranks)
    }

    @Test
    fun commonWarriorTalentHasAResolvedDescription() = runTest {
        val rank = assertNotNull(warrior().rankOf(130671, 1)) // Mortal Strike
        assertEquals(TooltipRankStatus.RESOLVED, rank.status)
        assertEquals(
            "A vicious strike that deals weapon damage plus 85 and wounds the target, reducing the " +
                "effectiveness of any healing by 50% for 10 sec.",
            rank.text(),
        )
    }

    @Test
    fun improvedRendRanksDifferWhereTheValueChanges() = runTest {
        val tooltips = warrior()
        val texts = (1..3).map { assertNotNull(tooltips.rankOf(130686, it)).text() }
        assertEquals(
            listOf(12, 23, 35).map { "Increases the Bleed damage done by your Rend ability by $it%." },
            texts,
        )
        val values = (1..3).map { rank ->
            assertNotNull(tooltips.rankOf(130686, rank)).segments.filter { it.kind == TooltipSegmentKind.VALUE }
                .map { it.text }
        }
        assertEquals(listOf(listOf("12%"), listOf("23%"), listOf("35%")), values)
    }

    @Test
    fun partialDescriptionExplainsEveryGap() = runTest {
        val bloodthirst = assertNotNull(warrior().rankOf(130660, 1))
        assertEquals(TooltipRankStatus.PARTIAL, bloodthirst.status)
        assertEquals(1, bloodthirst.segments.count { it.kind == TooltipSegmentKind.GAP })
        assertEquals(TooltipGapClassification.CONTEXT_DEPENDENT, bloodthirst.gaps.single().classification)
        assertTrue('$' !in bloodthirst.text())
    }

    @Test
    fun unknownEntryAndRankOutsideTheEntryHaveNoDescription() = runTest {
        val tooltips = warrior()
        assertNull(tooltips.ranksOf(999_999))
        assertNull(tooltips.rankOf(130686, 0))
        assertNull(tooltips.rankOf(130686, 4))
    }

    // ------------------------------------------------------------------ identity

    @Test
    fun anotherBuildsDocumentFindsNoSidecarInsteadOfASubstitute() = runTest {
        val error = bundle().errorFor69977()
        assertEquals(TooltipsLoadError.AssetMissing("tooltips/forever/1.60.1.69977/warrior.json"), error)
    }

    private suspend fun BundledTalentTooltipsRepository.errorFor69977(): TooltipsLoadError =
        assertIs<TooltipsOutcome.Failed>(tooltips(document("warrior", TestRepoAssets.forever69977))).error

    @Test
    fun wrongBuildFails() = runTest {
        val error = bundle {
            edit(it, "warrior.json", "\"clientBuild\": \"1.60.1.70009\"", "\"clientBuild\": \"1.60.1.69977\"")
        }.errorFor()
        assertEquals(
            TooltipsLoadError.BuildMismatch(
                "tooltips/forever/1.60.1.70009/warrior.json",
                expected = "1.60.1.70009",
                actual = "1.60.1.69977",
            ),
            error,
        )
    }

    @Test
    fun wrongClassFails() = runTest {
        val error = bundle { it.resolve("mage.json").copyTo(it.resolve("warrior.json"), overwrite = true) }.errorFor()
        assertIs<TooltipsLoadError.ClassMismatch>(error)
        assertEquals(ClassToken("MAGE"), error.actual)
    }

    @Test
    fun anotherDatasetRevisionFails() = runTest {
        val error = bundle {
            edit(it, "warrior.json", "\"talentDataContentHash\": \"sha256:", "\"talentDataContentHash\": \"sha256:0")
        }.errorFor()
        assertIs<TooltipsLoadError.DatasetRevisionMismatch>(error)
    }

    @Test
    fun unknownEntryFails() = runTest {
        val error = bundle { edit(it, "warrior.json", "\"entryId\": 130686", "\"entryId\": 999999") }.errorFor()
        assertEquals(TooltipsLoadError.UnknownEntry("tooltips/forever/1.60.1.70009/warrior.json", 999_999), error)
    }

    @Test
    fun missingEntryFails() = runTest {
        val error = bundle { dir ->
            editEntries(dir, "warrior.json") { entries ->
                entries.filterNot { it.getValue("entryId") == JsonPrimitive(130686) }
            }
        }.errorFor()
        assertEquals(TooltipsLoadError.MissingEntry("tooltips/forever/1.60.1.70009/warrior.json", 130686), error)
    }

    @Test
    fun duplicateEntryFails() = runTest {
        val error = bundle { dir -> editEntries(dir, "warrior.json") { it + it.first() } }.errorFor()
        assertIs<TooltipsLoadError.DuplicateEntry>(error)
    }

    @Test
    fun entryIdentityMustMatchTheDocument() = runTest {
        val error = bundle { edit(it, "warrior.json", "\"maxRank\": 2,", "\"maxRank\": 3,") }.errorFor()
        assertIs<TooltipsLoadError.EntryIdentityMismatch>(error)
    }

    // ------------------------------------------------------------------ ranks and segments

    @Test
    fun invalidRankFails() = runTest {
        val error = bundle { edit(it, "warrior.json", "\"rank\": 2,", "\"rank\": 5,") }.errorFor()
        assertIs<TooltipsLoadError.InvalidRanks>(error)
        assertEquals(listOf(1, 5), error.ranks)
    }

    @Test
    fun aPartialRankClaimingToBeResolvedFails() = runTest {
        val error = bundle { edit(it, "warrior.json", "\"status\": \"partial\"", "\"status\": \"resolved\"") }
            .errorFor()
        assertIs<TooltipsLoadError.InconsistentRank>(error)
    }

    @Test
    fun rawTemplateSyntaxInATextSegmentFails() = runTest {
        val error = bundle {
            edit(
                it,
                "warrior.json",
                "\"text\": \"Reduces the cooldown of your Intercept ability by \"",
                "\"text\": \"Reduces the cooldown of your Intercept ability by \$/1000;s1 \"",
            )
        }.errorFor()
        assertIs<TooltipsLoadError.InvalidSegment>(error)
    }

    @Test
    fun missingSidecarIsTyped() = runTest {
        val error = bundle { assertTrue(it.resolve("warrior.json").delete()) }.errorFor()
        assertEquals(TooltipsLoadError.AssetMissing("tooltips/forever/1.60.1.70009/warrior.json"), error)
    }

    @Test
    fun malformedSidecarIsInvalidJson() = runTest {
        val error = bundle { edit(it, "warrior.json", "\"kind\": \"text\"", "\"kind\": \"html\"") }.errorFor()
        assertIs<TooltipsLoadError.InvalidJson>(error)
    }

    @Test
    fun unsupportedSchemaVersionIsTyped() = runTest {
        val error = bundle { edit(it, "warrior.json", "\"schemaVersion\": 1", "\"schemaVersion\": 2") }.errorFor()
        assertIs<TooltipsLoadError.UnsupportedSchemaVersion>(error)
        assertEquals(ContractKind.TALENT_TOOLTIPS, error.contract)
    }
}
