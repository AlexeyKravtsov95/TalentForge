package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipsDocument
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipRankStatus
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipSegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

/**
 * JVM contract tests over the committed talent descriptions
 * (`data/tooltips/forever/1.60.1.70009/`): all nine `talent-tooltips.v1`
 * sidecars parse under the strict reader, and representative drift is
 * rejected. This is the drift detector between the Kotlin models and the
 * schema.
 */
class TalentTooltipsContractTest {

    private fun sidecar(token: String = "warrior"): String =
        TestRepoAssets.tooltips70009.resolve("$token.json").readText()

    private fun decode(json: String): TalentTooltipsDocument =
        TalentDataContract.decodeTalentTooltipsDocument(json.encodeToByteArray())

    /** Replaces exactly the first occurrence, so a test can never silently drift nothing. */
    private fun String.replaceOnce(old: String, new: String): String {
        val index = indexOf(old)
        assertTrue(index >= 0, "fixture no longer contains: $old")
        return substring(0, index) + new + substring(index + old.length)
    }

    @Test
    fun parsesAllNineCommittedSidecars() {
        var entries = 0
        var ranks = 0
        for (token in TestRepoAssets.classTokens) {
            val document = decode(sidecar(token))
            assertEquals(1, document.schemaVersion, token)
            assertEquals(GameId("forever"), document.game, token)
            assertEquals("1.60.1.70009", document.clientBuild, token)
            assertEquals(ClassToken(token.uppercase()), document.classToken, token)
            entries += document.entries.size
            ranks += document.entries.sumOf { it.ranks.size }
        }
        assertEquals(469, entries, "talent entries of the nine bundled classes")
        assertEquals(1333, ranks, "rank descriptions of the nine bundled classes")
    }

    @Test
    fun sidecarIsBoundToTheCommittedClassDocument() {
        for (token in TestRepoAssets.classTokens) {
            val talents = TalentDataContract.decodeTalentDataDocument(
                TestRepoAssets.forever70009.resolve("$token.json").readBytes(),
            )
            val tooltips = decode(sidecar(token))
            assertEquals(talents.contentHash, tooltips.talentDataContentHash, token)
            val talentEntries = talents.trees.flatMap { it.nodes }.flatMap { it.entries }
            assertEquals(talentEntries.map { it.id }.toSet(), tooltips.entries.map { it.entryId }.toSet(), token)
        }
    }

    @Test
    fun noCommittedSegmentCarriesTemplateMarkup() {
        for (token in TestRepoAssets.classTokens) {
            for (entry in decode(sidecar(token)).entries) {
                for (rank in entry.ranks) {
                    for (segment in rank.segments) {
                        if (segment.kind == TooltipSegmentKind.GAP) {
                            assertEquals("", segment.text)
                        } else {
                            assertTrue('$' !in segment.text && '|' !in segment.text, "$token ${entry.entryId}")
                        }
                    }
                    if (rank.status == TooltipRankStatus.RESOLVED) assertTrue(rank.gaps.isEmpty())
                }
            }
        }
    }

    @Test
    fun rejectsUnknownFields() {
        assertFailsWith<SerializationException> {
            decode(sidecar().replaceOnce("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"extra\": true,"))
        }
        assertFailsWith<SerializationException> {
            decode(sidecar().replaceOnce("\"maxRank\":", "\"extra\": 0, \"maxRank\":"))
        }
    }

    @Test
    fun rejectsQuotedNumbers() {
        assertFailsWith<SerializationException> {
            decode(sidecar().replaceOnce("\"maxRank\": 2,", "\"maxRank\": \"2\","))
        }
        assertFailsWith<SerializationException> {
            decode(sidecar().replaceOnce("\"rank\": 1,", "\"rank\": \"1\","))
        }
    }

    @Test
    fun rejectsUnknownEnumValues() {
        assertFailsWith<SerializationException> {
            decode(sidecar().replaceOnce("\"kind\": \"text\"", "\"kind\": \"html\""))
        }
        assertFailsWith<SerializationException> {
            decode(sidecar().replaceOnce("\"status\": \"resolved\"", "\"status\": \"approximate\""))
        }
        assertFailsWith<SerializationException> {
            decode(sidecar().replaceOnce("\"category\": \"local-effect-value\"", "\"category\": \"guess\""))
        }
    }

    @Test
    fun rejectsMissingRequiredFields() {
        assertFailsWith<SerializationException> {
            decode(sidecar().replaceOnce("],\n          \"gaps\": []", "]"))
        }
    }

    @Test
    fun unsupportedSchemaVersionIsExplicit() {
        val error = assertFailsWith<UnsupportedContractVersionException> {
            decode(sidecar().replaceOnce("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        }
        assertEquals(ContractKind.TALENT_TOOLTIPS, error.contract)
        assertEquals(2, error.actualVersion)
    }
}
