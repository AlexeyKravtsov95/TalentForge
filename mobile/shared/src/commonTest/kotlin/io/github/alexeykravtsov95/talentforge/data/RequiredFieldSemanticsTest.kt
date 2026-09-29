package io.github.alexeykravtsov95.talentforge.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Required-field semantics must match the JSON Schemas exactly: every property
 * listed in a schema's `required` array is mandatory on the wire even when it
 * is nullable or normally empty, while only schema-defaulted/optional fields
 * may be absent.
 */
class RequiredFieldSemanticsTest {

    // ------------------------------------------------------------------
    // talent-data.v1: required nullable fields
    // ------------------------------------------------------------------

    @Test
    fun talentDataRejectsMissingRequiredNullableFields() {
        for (path in listOf(
            listOf("trees", 0, "pointBudget"),
            listOf("trees", 0, "sections", 0, "name"),
            listOf("trees", 0, "nodes", 0, "sectionIndex"),
            listOf("trees", 0, "nodes", 0, "spellId"),
            listOf("trees", 0, "nodes", 0, "name"),
            listOf("trees", 0, "nodes", 0, "entries", 0, "name"),
            listOf("trees", 0, "nodes", 0, "entries", 0, "descriptionTemplate"),
            listOf("trees", 0, "nodes", 0, "entries", 0, "effects", 0, "perRank"),
            listOf("trees", 0, "nodes", 0, "entries", 0, "effects", 0, "basePoints"),
        )) {
            assertFailsWith<SerializationException>(message = "expected $path to be required") {
                TalentDataContract.decodeTalentDataDocument(documentWithout(path))
            }
        }
    }

    // ------------------------------------------------------------------
    // talent-data.v1: required list fields
    // ------------------------------------------------------------------

    @Test
    fun talentDataRejectsMissingRequiredListFields() {
        for (path in listOf(
            listOf("trees", 0, "visualOnlyEdges"),
            listOf("trees", 0, "nodes", 0, "prerequisites"),
            listOf("trees", 0, "nodes", 0, "unlockRequirements"),
            listOf("trees", 0, "nodes", 0, "unresolvedConditionIds"),
            listOf("trees", 0, "nodes", 0, "entries"),
            listOf("trees", 0, "nodes", 0, "entries", 0, "effects"),
            listOf("trees", 0, "nodes", 0, "unlockRequirements", 0, "countedNodeIds"),
            listOf("provenance", "tables"),
        )) {
            assertFailsWith<SerializationException>(message = "expected $path to be required") {
                TalentDataContract.decodeTalentDataDocument(documentWithout(path))
            }
        }
    }

    // ------------------------------------------------------------------
    // allocation-conformance.v1: required list fields on Expectation
    // ------------------------------------------------------------------

    @Test
    fun conformanceRejectsMissingRequiredExpectationFields() {
        for (path in listOf(
            listOf("scenarios", 0, "steps", 0, "expect", "reasonCodes"),
            listOf("scenarios", 0, "steps", 0, "expect", "issues"),
        )) {
            assertFailsWith<SerializationException>(message = "expected $path to be required") {
                TalentDataContract.decodeConformanceSuite(conformanceWithout(path))
            }
        }
        for (path in listOf(
            listOf("scenarios", 0, "rules"),
            listOf("scenarios", 0, "steps"),
        )) {
            assertFailsWith<SerializationException>(message = "expected $path to be required") {
                TalentDataContract.decodeConformanceSuite(conformanceWithout(path))
            }
        }
    }

    // ------------------------------------------------------------------
    // schema-optional fields stay optional
    // ------------------------------------------------------------------

    @Test
    fun talentDataAcceptsAbsentOptionalFields() {
        for (path in listOf(
            listOf("schemaVersion"), // const + default 1
            listOf("trees", 0, "nodes", 0, "entries", 0, "effects", 0, "unresolvedReason"), // default null
            listOf("trees", 0, "nodes", 0, "unlockRequirements", 0, "kind"), // const + default
        )) {
            val document = TalentDataContract.decodeTalentDataDocument(documentWithout(path))
            assertEquals(1, document.schemaVersion)
            assertEquals(
                io.github.alexeykravtsov95.talentforge.data.talentdata.UnlockRequirementKind.POINTS_SPENT,
                document.trees[0].nodes[0].unlockRequirements[0].kind,
            )
        }
    }

    @Test
    fun buildFileAcceptsAbsentAllocationsDefaultedBySchema() {
        val build = TalentDataContract.decodeAllocationBuildFile(
            withoutPath(
                ContractFixtures.minimalBuildFileJson,
                listOf("allocations"), // schema declares "default": []
            ),
        )
        assertEquals(emptyList(), build.allocations)
    }

    @Test
    fun conformanceAcceptsAbsentOptionalFields() {
        for (path in listOf(
            listOf("schemaVersion"), // const + default 1
            listOf("scenarios", 0, "steps", 0, "expect", "nodeRank"), // default null
            listOf("scenarios", 0, "mutations"), // optional, not in required
            listOf("scenarios", 0, "initial"), // optional, not in required
            listOf("scenarios", 0, "steps", 0, "nodeId"), // default null
        )) {
            val suite = TalentDataContract.decodeConformanceSuite(conformanceWithout(path))
            assertEquals(1, suite.scenarios.size)
        }
    }

    @Test
    fun expectedIssueRequiresOnlyCode() {
        val suite = TalentDataContract.decodeConformanceSuite(
            """
            {
              "schemaVersion": 1,
              "game": "forever",
              "clientBuild": "1.60.1.70009",
              "datasets": [],
              "scenarios": [
                {
                  "id": "s",
                  "class": "WARRIOR",
                  "description": "d",
                  "rules": [],
                  "synthetic": false,
                  "steps": [
                    {
                      "op": "validate",
                      "expect": {
                        "ok": false,
                        "reasonCodes": ["X"],
                        "issues": [ { "code": "SOME_CODE" } ],
                        "spentPoints": 0,
                        "remainingPoints": 51
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent().encodeToByteArray(),
        )
        val issue = suite.scenarios.single().steps.single().expect.issues.single()
        assertEquals("SOME_CODE", issue.code)
        assertEquals(null, issue.nodeId)
        assertEquals(emptyList(), issue.relatedNodeIds)
        assertEquals(null, issue.cause)
    }
}

/** Removes one key/index chain from the minimal document fixture. */
private fun documentWithout(path: List<Any>): ByteArray =
    withoutPath(ContractFixtures.minimalDocumentJson, path)

private fun conformanceWithout(path: List<Any>): ByteArray =
    withoutPath(ContractFixtures.minimalConformanceJson, path)

private fun withoutPath(json: String, path: List<Any>): ByteArray {
    val element = removePath(Json.parseToJsonElement(json), path)
    return TalentDataContract.json
        .encodeToString(JsonElement.serializer(), element)
        .encodeToByteArray()
}

private fun removePath(element: JsonElement, segments: List<Any>): JsonElement {
    if (segments.isEmpty()) return element
    val head = segments.first()
    val rest = segments.drop(1)
    return when (head) {
        is String -> {
            val obj = assertIs<JsonObject>(element)
            assertTrue(obj.containsKey(head), "fixture has no key '$head'")
            val map = obj.toMutableMap()
            if (rest.isEmpty()) {
                map.remove(head)
            } else {
                map[head] = removePath(obj.getValue(head), rest)
            }
            JsonObject(map)
        }

        is Int -> {
            val array = assertIs<JsonArray>(element)
            assertTrue(head < array.size, "fixture has no index $head")
            val list = array.toMutableList()
            if (rest.isEmpty()) {
                list.removeAt(head)
            } else {
                list[head] = removePath(array[head], rest)
            }
            JsonArray(list)
        }

        else -> error("unsupported path segment: $head")
    }
}
