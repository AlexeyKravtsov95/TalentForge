package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.talentdata.NumberValue
import io.github.alexeykravtsov95.talentforge.data.talentdata.PerRankStatus
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNodeType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

/**
 * Inline contract parsing smoke tests that run on every target (JVM, Android
 * unit, iOS) to prove the strict serialization stack itself is portable. The
 * full committed-dataset contract tests run on the JVM.
 */
class ContractParsingSmokeTest {

    @Test
    fun parsesMinimalTalentDataDocument() {
        val document = TalentDataContract.decodeTalentDataDocument(ContractFixtures.minimalDocumentJson.encodeToByteArray())

        assertEquals(TalentDataContract.SUPPORTED_VERSION, document.schemaVersion)
        assertEquals(GameId("forever"), document.game)
        assertEquals("1.60.1.70009", document.clientBuild)
        assertEquals(ClassToken("WARRIOR"), document.classInfo.token)
        assertEquals(1, document.trees.size)

        val node = document.trees.single().nodes.single()
        assertEquals(TalentNodeType.Single, node.type)
        assertEquals(3, node.maxRank)

        val effect = node.entries.single().effects.single()
        assertEquals(PerRankStatus.RESOLVED, effect.perRankStatus)
        val rankValue = effect.perRank!!.single()
        assertEquals(1, rankValue.rank)
        assertEquals(NumberValue.Integral(-10), rankValue.value)
        assertEquals(NumberValue.Integral(-10), effect.basePoints)
    }

    @Test
    fun rejectsUnknownProperty() {
        val drifted = ContractFixtures.minimalDocumentJson.replace(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"futureField\": true,",
        )
        assertFailsWith<SerializationException> {
            TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        }
    }

    @Test
    fun rejectsUnknownEnumValue() {
        val drifted = ContractFixtures.minimalDocumentJson.replace("\"perRankStatus\": \"resolved\"", "\"perRankStatus\": \"bogus\"")
        assertFailsWith<SerializationException> {
            TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        }
    }

    @Test
    fun rejectsMissingRequiredField() {
        val drifted = ContractFixtures.minimalDocumentJson.replace("\"maxRank\": 3,", "")
        assertFailsWith<SerializationException> {
            TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        }
    }

    @Test
    fun rejectsWrongFieldType() {
        // Non-numeric strings, quoted numeric literals, decimals and objects
        // are all incompatible with the integer field and must fail.
        for (drifted in listOf(
            ContractFixtures.minimalDocumentJson.replace("\"maxRank\": 3", "\"maxRank\": \"three\""),
            ContractFixtures.minimalDocumentJson.replace("\"maxRank\": 3", "\"maxRank\": \"3\""),
            ContractFixtures.minimalDocumentJson.replace("\"maxRank\": 3", "\"maxRank\": 3.5"),
            ContractFixtures.minimalDocumentJson.replace("\"maxRank\": 3", "\"maxRank\": {}"),
            ContractFixtures.minimalDocumentJson.replace("\"game\": \"forever\"", "\"game\": 7"),
        )) {
            assertFailsWith<SerializationException> {
                TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
            }
        }
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val drifted = ContractFixtures.minimalDocumentJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val error = assertFailsWith<UnsupportedContractVersionException> {
            TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        }
        assertEquals(ContractKind.TALENT_DATA, error.contract)
        assertEquals(2, error.actualVersion)
        assertEquals(1, error.supportedVersion)
    }

    @Test
    fun preservesUnmappedNodeTypeVerbatim() {
        val drifted = ContractFixtures.minimalDocumentJson.replace("\"type\": \"single\"", "\"type\": \"unknown:99\"")
        val document = TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        assertEquals(TalentNodeType.Unknown("unknown:99"), document.trees.single().nodes.single().type)
    }

    @Test
    fun parsesAndRoundTripsBuildFile() {
        val build = TalentDataContract.decodeAllocationBuildFile(ContractFixtures.minimalBuildFileJson.encodeToByteArray())
        assertEquals(ClassToken("WARRIOR"), build.classToken)
        assertEquals(1117, build.treeId)
        assertEquals(1, build.allocations.size)

        val encoded = TalentDataContract.json.encodeToString(BuildFile.serializer(), build)
        assertContains(encoded, "\"class\":\"WARRIOR\"")
        val reparsed = TalentDataContract.decodeAllocationBuildFile(encoded.encodeToByteArray())
        assertEquals(build, reparsed)
    }

    @Test
    fun parsesConformanceSuite() {
        val suite = TalentDataContract.decodeConformanceSuite(ContractFixtures.minimalConformanceJson.encodeToByteArray())
        assertEquals(1, suite.scenarios.size)
        assertEquals(1, suite.totalSteps)
        assertEquals(GameId("forever"), suite.game)
        assertTrue(suite.scenarios.single().steps.single().expect.ok)
    }

    @Test
    fun rejectsBuildFileWithUnknownVersion() {
        val drifted = ContractFixtures.minimalBuildFileJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 3")
        assertFailsWith<UnsupportedContractVersionException> {
            TalentDataContract.decodeAllocationBuildFile(drifted.encodeToByteArray())
        }
    }
}
