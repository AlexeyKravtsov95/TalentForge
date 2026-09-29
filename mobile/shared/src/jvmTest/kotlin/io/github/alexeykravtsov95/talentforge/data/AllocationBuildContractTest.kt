package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** allocation-build.v1 serialization contract on a representative fixture. */
class AllocationBuildContractTest {

    private val fixture = """
        {
          "schemaVersion": 1,
          "game": "forever",
          "clientBuild": "1.60.1.70009",
          "class": "WARRIOR",
          "treeId": 1117,
          "allocations": [
            { "nodeId": 105958, "entryId": 130688, "rank": 3 },
            { "nodeId": 105959, "entryId": 130689, "rank": 2 },
            { "nodeId": 105960, "entryId": 130690, "rank": 5 }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesRepresentativeFixture() {
        val build = TalentDataContract.decodeAllocationBuildFile(fixture.encodeToByteArray())

        assertEquals(GameId("forever"), build.game)
        assertEquals("1.60.1.70009", build.clientBuild)
        assertEquals(ClassToken("WARRIOR"), build.classToken)
        assertEquals(1117, build.treeId)
        assertEquals(3, build.allocations.size)
        assertEquals(130688, build.allocations.first().entryId)
        assertEquals(5, build.allocations.last().rank)
    }

    @Test
    fun roundTripsThroughJson() {
        val original = TalentDataContract.decodeAllocationBuildFile(fixture.encodeToByteArray())

        val encoded = TalentDataContract.json.encodeToString(BuildFile.serializer(), original)
        assertContains(encoded, "\"class\":\"WARRIOR\"")
        val reparsed = TalentDataContract.decodeAllocationBuildFile(encoded.encodeToByteArray())

        assertEquals(original, reparsed)
    }

    @Test
    fun roundTripsEmptyAllocationsDefault() {
        val withoutAllocations = """
            {
              "schemaVersion": 1,
              "game": "forever",
              "clientBuild": "1.60.1.70009",
              "class": "WARRIOR",
              "treeId": 1117
            }
        """.trimIndent()
        val build = TalentDataContract.decodeAllocationBuildFile(withoutAllocations.encodeToByteArray())
        assertEquals(emptyList(), build.allocations)

        val encoded = TalentDataContract.json.encodeToString(BuildFile.serializer(), build)
        val reparsed = TalentDataContract.decodeAllocationBuildFile(encoded.encodeToByteArray())
        assertEquals(build, reparsed)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val drifted = fixture.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertFailsWith<UnsupportedContractVersionException> {
            TalentDataContract.decodeAllocationBuildFile(drifted.encodeToByteArray())
        }
    }
}
