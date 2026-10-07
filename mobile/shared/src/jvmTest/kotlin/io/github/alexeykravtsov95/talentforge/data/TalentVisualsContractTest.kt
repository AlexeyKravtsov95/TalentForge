package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.visuals.TalentIconImageFormat
import io.github.alexeykravtsov95.talentforge.data.visuals.TalentIconSourceFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

/**
 * JVM contract tests over the committed talent visuals
 * (`data/visuals/forever/1.60.1.70009/`): all nine `talent-visuals.v1`
 * sidecars and the `talent-icons.v1` registry must parse under the strict
 * reader, and every representative drift must be rejected. This is the drift
 * detector between the Kotlin models and the two sidecar schemas.
 */
class TalentVisualsContractTest {

    private fun sidecar(token: String = "warrior"): String =
        TestRepoAssets.visuals70009.resolve("$token.json").readText()

    private fun registry(): String = TestRepoAssets.visuals70009.resolve("icons.json").readText()

    private fun decodeSidecar(json: String) =
        TalentDataContract.decodeTalentVisualsDocument(json.encodeToByteArray())

    private fun decodeRegistry(json: String) =
        TalentDataContract.decodeTalentIconRegistry(json.encodeToByteArray())

    /** Replaces exactly one occurrence, so a test can never silently drift nothing. */
    private fun String.replaceOnce(old: String, new: String): String {
        val index = indexOf(old)
        assertTrue(index >= 0, "fixture no longer contains: $old")
        return substring(0, index) + new + substring(index + old.length)
    }

    @Test
    fun parsesAllNineCommittedSidecars() {
        var entries = 0
        val icons = HashSet<Int>()
        for (token in TestRepoAssets.classTokens) {
            val document = decodeSidecar(sidecar(token))
            assertEquals(1, document.schemaVersion, token)
            assertEquals(GameId("forever"), document.game, token)
            assertEquals("1.60.1.70009", document.clientBuild, token)
            assertEquals(ClassToken(token.uppercase()), document.classToken, token)
            assertTrue(document.entries.isNotEmpty(), token)
            assertEquals(document.entries.map { it.entryId }.sorted(), document.entries.map { it.entryId }, token)
            assertTrue(document.entries.all { it.iconFileDataId > 0 }, token)
            entries += document.entries.size
            icons += document.entries.map { it.iconFileDataId }
        }
        assertEquals(469, entries, "talent entries of the nine bundled classes")
        assertEquals(358, icons.size, "distinct icon FileDataIDs")
    }

    @Test
    fun sidecarIsBoundToTheCommittedClassDocument() {
        for (token in TestRepoAssets.classTokens) {
            val talents = TalentDataContract.decodeTalentDataDocument(
                TestRepoAssets.forever70009.resolve("$token.json").readBytes(),
            )
            val visuals = decodeSidecar(sidecar(token))
            assertEquals(talents.contentHash, visuals.talentDataContentHash, token)
            val talentEntryIds = talents.trees.flatMap { it.nodes }.flatMap { it.entries }.map { it.id }.toSet()
            assertEquals(talentEntryIds, visuals.entries.map { it.entryId }.toSet(), token)
        }
    }

    @Test
    fun parsesTheCommittedIconRegistry() {
        val parsed = decodeRegistry(registry())
        assertEquals(1, parsed.schemaVersion)
        assertEquals(GameId("forever"), parsed.game)
        assertEquals("1.60.1.70009", parsed.clientBuild)
        assertEquals(TalentIconImageFormat.PNG, parsed.imageFormat)
        assertEquals(358, parsed.icons.size)
        assertEquals(parsed.icons.map { it.fileDataId }.sorted(), parsed.icons.map { it.fileDataId })
        for (icon in parsed.icons) {
            assertEquals("icons/${icon.fileDataId}.png", icon.file)
            assertEquals(64, icon.width)
            assertEquals(64, icon.height)
            assertEquals(TalentIconSourceFormat.BLP2, icon.sourceFormat)
            val file = TestRepoAssets.visuals70009.resolve(icon.file)
            assertTrue(file.isFile, "registry file missing: ${icon.file}")
            assertEquals(icon.byteLength.toLong(), file.length(), icon.file)
        }
    }

    @Test
    fun sidecarRejectsUnknownAndMissingFields() {
        val json = sidecar()
        for (drifted in listOf(
            json.replaceOnce("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\n  \"atlas\": \"icons.png\","),
            json.replaceOnce("\"iconFileDataId\": 132307", "\"iconFileDataId\": 132307, \"assetKey\": \"132307\""),
            json.replaceOnce("\"source\": \"wago.tools\",", "\"source\": \"wago.tools\", \"url\": \"https://x\","),
            json.replaceOnce("\"schemaVersion\": 1,", ""),
            json.replaceOnce("\"game\": \"forever\",", ""),
            json.replaceOnce("\"classToken\": \"WARRIOR\",", ""),
            json.replaceOnce("\"displaySpellId\": 20504,", ""),
        )) {
            assertFailsWith<SerializationException> { decodeSidecar(drifted) }
        }
    }

    @Test
    fun sidecarRejectsWrongWireTypes() {
        val json = sidecar()
        for (drifted in listOf(
            json.replaceOnce("\"schemaVersion\": 1,", "\"schemaVersion\": \"1\","),
            json.replaceOnce("\"entryId\": 130656", "\"entryId\": \"130656\""),
            json.replaceOnce("\"iconFileDataId\": 132307", "\"iconFileDataId\": \"132307\""),
            json.replaceOnce("\"iconFileDataId\": 132307", "\"iconFileDataId\": 132307.5"),
            json.replaceOnce("\"iconFileDataId\": 132307", "\"iconFileDataId\": null"),
            json.replaceOnce("\"spellId\": 20504", "\"spellId\": true"),
            json.replaceOnce("\"clientBuild\": \"1.60.1.70009\"", "\"clientBuild\": 70009"),
        )) {
            assertFailsWith<SerializationException> { decodeSidecar(drifted) }
        }
    }

    @Test
    fun sidecarRejectsUnsupportedSchemaVersion() {
        val error = assertFailsWith<UnsupportedContractVersionException> {
            decodeSidecar(sidecar().replaceOnce("\"schemaVersion\": 1,", "\"schemaVersion\": 2,"))
        }
        assertEquals(ContractKind.TALENT_VISUALS, error.contract)
        assertEquals(2, error.actualVersion)
    }

    @Test
    fun registryRejectsDriftAndUnknownClosedValues() {
        val json = registry()
        for (drifted in listOf(
            json.replaceOnce("\"imageFormat\": \"png\"", "\"imageFormat\": \"webp\""),
            json.replaceOnce("\"sourceFormat\": \"BLP2\"", "\"sourceFormat\": \"PNG\""),
            json.replaceOnce("\"fileDataId\": 132089", "\"fileDataId\": \"132089\""),
            json.replaceOnce("\"width\": 64", "\"width\": \"64\""),
            json.replaceOnce("\"fileDataId\": 132089,", "\"fileDataId\": 132089, \"url\": \"https://x\","),
            json.replaceOnce("\"imageFormat\": \"png\",", ""),
            json.replaceOnce("\"file\": \"icons/132089.png\",", ""),
        )) {
            assertFailsWith<SerializationException> { decodeRegistry(drifted) }
        }
        val error = assertFailsWith<UnsupportedContractVersionException> {
            decodeRegistry(json.replaceOnce("\"schemaVersion\": 1,", "\"schemaVersion\": 3,"))
        }
        assertEquals(ContractKind.TALENT_ICONS, error.contract)
    }
}
