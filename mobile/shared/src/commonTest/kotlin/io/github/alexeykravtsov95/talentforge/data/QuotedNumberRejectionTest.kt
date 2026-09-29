package io.github.alexeykravtsov95.talentforge.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException

/**
 * Quoted numeric literals (`"maxRank": "3"`) violate the wire form wherever a
 * JSON integer/number is required, even though kotlinx.serialization accepts
 * them for numeric targets by design. The contract readers must fail closed
 * for every public contract, in both directions: representative quoted
 * integers are rejected, and legitimate numeric *strings* (schema type
 * string) keep parsing.
 */
class QuotedNumberRejectionTest {

    // ------------------------------------------------------------------
    // talent-data.v1
    // ------------------------------------------------------------------

    @Test
    fun talentDataRejectsQuotedIntegers() {
        for (drifted in listOf(
            ContractFixtures.minimalDocumentJson.replace("\"maxRank\": 3", "\"maxRank\": \"3\""),
            ContractFixtures.minimalDocumentJson.replace("\"flags\": 0", "\"flags\": \"0\""),
            ContractFixtures.minimalDocumentJson.replace("\"id\": 105958", "\"id\": \"105958\""),
            ContractFixtures.minimalDocumentJson.replace("\"rank\": 1", "\"rank\": \"1\""),
            ContractFixtures.minimalDocumentJson.replace(
                "\"pointBudget\": { \"currencyId\": 3820, \"maxPoints\": 51 }",
                "\"pointBudget\": { \"currencyId\": \"3820\", \"maxPoints\": 51 }",
            ),
            ContractFixtures.minimalDocumentJson.replace("\"xStep\": 600", "\"xStep\": \"600\""),
        )) {
            assertFailsWith<SerializationException>("quoted integer must be rejected") {
                TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
            }
        }
    }

    // ------------------------------------------------------------------
    // allocation-build.v1
    // ------------------------------------------------------------------

    @Test
    fun allocationBuildRejectsQuotedIntegers() {
        for (drifted in listOf(
            ContractFixtures.minimalBuildFileJson.replace("\"treeId\": 1117", "\"treeId\": \"1117\""),
            ContractFixtures.minimalBuildFileJson.replace("\"rank\": 3", "\"rank\": \"3\""),
            ContractFixtures.minimalBuildFileJson.replace("\"nodeId\": 105958", "\"nodeId\": \"105958\""),
        )) {
            assertFailsWith<SerializationException>("quoted integer must be rejected") {
                TalentDataContract.decodeAllocationBuildFile(drifted.encodeToByteArray())
            }
        }
    }

    // ------------------------------------------------------------------
    // allocation-conformance.v1
    // ------------------------------------------------------------------

    @Test
    fun conformanceRejectsQuotedIntegers() {
        for (drifted in listOf(
            ContractFixtures.minimalConformanceJson.replace("\"spentPoints\": 1", "\"spentPoints\": \"1\""),
            ContractFixtures.minimalConformanceJson.replace(
                "\"remainingPoints\": 50",
                "\"remainingPoints\": \"50\"",
            ),
            ContractFixtures.minimalConformanceJson.replace("\"nodeId\": 105958", "\"nodeId\": \"105958\""),
            ContractFixtures.minimalConformanceJson.replace("\"nodeRank\": 1", "\"nodeRank\": \"1\""),
        )) {
            assertFailsWith<SerializationException>("quoted integer must be rejected") {
                TalentDataContract.decodeConformanceSuite(drifted.encodeToByteArray())
            }
        }
    }

    // ------------------------------------------------------------------
    // dataset-manifest.v1
    // ------------------------------------------------------------------

    @Test
    fun manifestParsesAndRejectsQuotedIntegers() {
        val manifest = TalentDataContract.decodeDatasetManifest(
            ContractFixtures.minimalManifestJson.encodeToByteArray(),
        )
        assertEquals(1, manifest.classes.size)
        assertEquals(listOf(1117), manifest.classes.single().treeIds)

        for (drifted in listOf(
            ContractFixtures.minimalManifestJson.replace("\"errors\": 0", "\"errors\": \"0\""),
            ContractFixtures.minimalManifestJson.replace("\"treeIds\": [1117]", "\"treeIds\": [\"1117\"]"),
            ContractFixtures.minimalManifestJson.replace("\"warnings\": 0", "\"warnings\": \"0\""),
        )) {
            assertFailsWith<SerializationException>("quoted integer must be rejected") {
                TalentDataContract.decodeDatasetManifest(drifted.encodeToByteArray())
            }
        }
    }

    // ------------------------------------------------------------------
    // numeric strings stay valid where the schema type is string
    // ------------------------------------------------------------------

    @Test
    fun numericLookingStringsRemainValidForStringTypedFields() {
        // clientBuild / contentHash / tokens are strings by schema; none of them
        // may be broken by the quoted-number rejection.
        val document = TalentDataContract.decodeTalentDataDocument(
            ContractFixtures.minimalDocumentJson.encodeToByteArray(),
        )
        assertEquals("1.60.1.70009", document.clientBuild)
        assertEquals("sha256:0000", document.contentHash.value.take(11))

        val suite = TalentDataContract.decodeConformanceSuite(
            ContractFixtures.minimalConformanceJson.replace(
                "\"description\": \"smoke\"",
                "\"description\": \"7\"",
            ).encodeToByteArray(),
        )
        assertEquals("7", suite.scenarios.single().description)

        val build = TalentDataContract.decodeAllocationBuildFile(
            ContractFixtures.minimalBuildFileJson.encodeToByteArray(),
        )
        assertEquals("forever", build.game.value)
    }

    @Test
    fun unquotedNumericPrimitivesStillParse() {
        // The untouched fixtures use real numeric primitives everywhere;
        // parsing them end-to-end is the positive control for this fix.
        assertEquals(
            3,
            TalentDataContract.decodeTalentDataDocument(
                ContractFixtures.minimalDocumentJson.encodeToByteArray(),
            ).trees.single().nodes.single().maxRank,
        )
    }
}
