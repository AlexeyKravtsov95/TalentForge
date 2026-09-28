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
        val document = TalentDataContract.decodeTalentDataDocument(MINIMAL_DOCUMENT_JSON.encodeToByteArray())

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
        val drifted = MINIMAL_DOCUMENT_JSON.replace(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"futureField\": true,",
        )
        assertFailsWith<SerializationException> {
            TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        }
    }

    @Test
    fun rejectsUnknownEnumValue() {
        val drifted = MINIMAL_DOCUMENT_JSON.replace("\"perRankStatus\": \"resolved\"", "\"perRankStatus\": \"bogus\"")
        assertFailsWith<SerializationException> {
            TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        }
    }

    @Test
    fun rejectsMissingRequiredField() {
        val drifted = MINIMAL_DOCUMENT_JSON.replace("\"maxRank\": 3,", "")
        assertFailsWith<SerializationException> {
            TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        }
    }

    @Test
    fun rejectsWrongFieldType() {
        // Non-numeric strings, decimals and objects are incompatible with the
        // integer field and must fail. (A quoted numeric literal such as "3"
        // is accepted by kotlinx.serialization for Int targets by design.)
        for (drifted in listOf(
            MINIMAL_DOCUMENT_JSON.replace("\"maxRank\": 3", "\"maxRank\": \"three\""),
            MINIMAL_DOCUMENT_JSON.replace("\"maxRank\": 3", "\"maxRank\": 3.5"),
            MINIMAL_DOCUMENT_JSON.replace("\"maxRank\": 3", "\"maxRank\": {}"),
            MINIMAL_DOCUMENT_JSON.replace("\"game\": \"forever\"", "\"game\": 7"),
        )) {
            assertFailsWith<SerializationException> {
                TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
            }
        }
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val drifted = MINIMAL_DOCUMENT_JSON.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val error = assertFailsWith<UnsupportedContractVersionException> {
            TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        }
        assertEquals(ContractKind.TALENT_DATA, error.contract)
        assertEquals(2, error.actualVersion)
        assertEquals(1, error.supportedVersion)
    }

    @Test
    fun preservesUnmappedNodeTypeVerbatim() {
        val drifted = MINIMAL_DOCUMENT_JSON.replace("\"type\": \"single\"", "\"type\": \"unknown:99\"")
        val document = TalentDataContract.decodeTalentDataDocument(drifted.encodeToByteArray())
        assertEquals(TalentNodeType.Unknown("unknown:99"), document.trees.single().nodes.single().type)
    }

    @Test
    fun parsesAndRoundTripsBuildFile() {
        val build = TalentDataContract.decodeAllocationBuildFile(BUILD_FILE_JSON.encodeToByteArray())
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
        val suite = TalentDataContract.decodeConformanceSuite(CONFORMANCE_JSON.encodeToByteArray())
        assertEquals(1, suite.scenarios.size)
        assertEquals(1, suite.totalSteps)
        assertEquals(GameId("forever"), suite.game)
        assertTrue(suite.scenarios.single().steps.single().expect.ok)
    }

    @Test
    fun rejectsBuildFileWithUnknownVersion() {
        val drifted = BUILD_FILE_JSON.replace("\"schemaVersion\": 1", "\"schemaVersion\": 3")
        assertFailsWith<UnsupportedContractVersionException> {
            TalentDataContract.decodeAllocationBuildFile(drifted.encodeToByteArray())
        }
    }
}

private val MINIMAL_DOCUMENT_JSON = """
{
  "schemaVersion": 1,
  "game": "forever",
  "clientBuild": "1.60.1.70009",
  "class": { "id": 1, "token": "WARRIOR", "name": "Warrior" },
  "trees": [
    {
      "id": 1117,
      "pointBudget": { "currencyId": 3820, "maxPoints": 51 },
      "layout": { "xStep": 600, "yStep": 600 },
      "sections": [ { "index": 0, "name": null, "nodeIds": [105958] } ],
      "nodes": [
        {
          "id": 105958,
          "sectionIndex": 0,
          "position": { "x": 1020, "y": 2130, "row": 0, "column": 0 },
          "type": "single",
          "flags": 0,
          "maxRank": 3,
          "spellId": 12282,
          "name": "Improved Heroic Strike",
          "prerequisites": [ { "nodeId": 1, "kind": "sufficient" } ],
          "unlockRequirements": [ { "requiredPoints": 5, "countedNodeIds": [1, 2] } ],
          "unresolvedConditionIds": [],
          "entries": [
            {
              "id": 130688,
              "definitionId": 135489,
              "spellId": 12282,
              "name": "Improved Heroic Strike",
              "maxRank": 3,
              "entryType": 1,
              "descriptionTemplate": null,
              "effects": [
                {
                  "index": 0,
                  "perRank": [ { "rank": 1, "value": -10 } ],
                  "perRankStatus": "resolved",
                  "basePoints": -10,
                  "unresolvedReason": null
                }
              ]
            }
          ]
        }
      ],
      "visualOnlyEdges": [ { "fromNodeId": 1, "toNodeId": 2 } ]
    }
  ],
  "contentHash": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  "generatedAt": "2026-01-01T00:00:00Z",
  "provenance": { "source": "wago.tools", "tables": ["TraitNode"] }
}
""".trimIndent()

private val BUILD_FILE_JSON = """
{
  "schemaVersion": 1,
  "game": "forever",
  "clientBuild": "1.60.1.70009",
  "class": "WARRIOR",
  "treeId": 1117,
  "allocations": [ { "nodeId": 105958, "entryId": 130688, "rank": 3 } ]
}
""".trimIndent()

private val CONFORMANCE_JSON = """
{
  "schemaVersion": 1,
  "game": "forever",
  "clientBuild": "1.60.1.70009",
  "datasets": [
    {
      "class": "WARRIOR",
      "file": "data/normalized/forever/1.60.1.70009/warrior.json",
      "contentHash": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
    }
  ],
  "scenarios": [
    {
      "id": "warrior.smoke",
      "class": "WARRIOR",
      "description": "smoke",
      "rules": ["RANK-1"],
      "synthetic": false,
      "mutations": [],
      "initial": [],
      "steps": [
        {
          "op": "spend",
          "nodeId": 105958,
          "entryId": 130688,
          "expect": {
            "ok": true,
            "reasonCodes": [],
            "issues": [],
            "spentPoints": 1,
            "remainingPoints": 50,
            "nodeRank": 1
          }
        }
      ]
    }
  ]
}
""".trimIndent()
