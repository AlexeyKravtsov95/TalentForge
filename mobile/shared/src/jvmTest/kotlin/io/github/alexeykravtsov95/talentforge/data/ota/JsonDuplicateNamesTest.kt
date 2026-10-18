package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The duplicate-name scanner of contract §2.1 (test-plan §3): duplicates at every depth and in
 * both orders, names equal only after unescaping, look-alikes that differ, structural
 * characters inside strings, bounded depth — and the differential test against the Python
 * reference's `strict_json` on the committed generated vectors.
 */
class JsonDuplicateNamesTest {

    private fun scan(text: String): JsonScan = JsonDuplicateNames.scan(text.encodeToByteArray())

    private fun violation(text: String): JsonViolation? = (scan(text) as? JsonScan.Rejected)?.violation

    @Test
    fun theScannerAgreesWithTheReferenceOnEveryGeneratedDocument() {
        val file = TestRepoAssets.repoRoot.resolve("tests/fixtures/ota_client/strict-json-vectors.json")
        val vectors = Json.parseToJsonElement(file.readText()).jsonObject.getValue("vectors").jsonArray
        assertTrue(vectors.size > 400)
        val disagreements = vectors.map { it.jsonObject }.filter { vector ->
            val document = Base64.getDecoder().decode(vector.getValue("document").jsonPrimitive.content)
            val valid = JsonDuplicateNames.scan(document) == JsonScan.Valid
            valid != vector.getValue("valid").jsonPrimitive.boolean
        }.map { it.getValue("name").jsonPrimitive.content }
        assertEquals(emptyList(), disagreements)
    }

    @Test
    fun duplicatesAreFoundAtEveryDepthInEveryOrderWhateverTheValues() {
        for (text in listOf(
            """{"a":1,"a":2}""",
            """{"a":1,"a":1}""",
            """{"b":{"x":[1]},"a":0,"b":{"x":[1]}}""",
            """{"x":{"y":{"a":null,"a":null}}}""",
            """[{"ok":1},{"k":1,"k":"1"}]""",
            """{"releases":[{"releaseId":"a"},{"releaseId":"b","releaseId":"b"}]}""",
        )) {
            assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation(text), text)
        }
        // the same name in sibling objects or at another depth is not a duplicate
        assertEquals(JsonScan.Valid, scan("""{"a":{"a":1},"b":{"a":2},"c":[{"a":3},{"a":4}]}"""))
    }

    @Test
    fun namesAreComparedAfterUnescaping() {
        assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation("""{"activeReleaseId":1,"activeRelease\u0049d":2}"""))
        assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation("""{"a/b":1,"a\/b":2}"""))
        assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation("""{"\uD83D\uDE00":1,"😀":2}"""))
        assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation("""{"\ud83d\ude00":1,"\uD83D\uDE00":2}"""))
        assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation("""{"é":1,"\u00e9":2}"""))
        assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation("""{"\"q":1,"\u0022q":2}"""))
        assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation("""{"a\nb":1,"a\u000ab":2}"""))
        // look-alikes that differ are distinct names
        assertEquals(JsonScan.Valid, scan("""{"a":1,"a ":2,"A":3,"\u0061\u0020b":4,"\ud83d":5,"\ude00":6}"""))
    }

    @Test
    fun structuralCharactersInsideStringsAreNotStructure() {
        assertEquals(JsonScan.Valid, scan("""{"a{":"}","a}":"{","a:":":","a,":",","a\"":"\"\\","[":"]"}"""))
    }

    @Test
    fun strictJsonRulesOfTheReference() {
        assertEquals(JsonViolation.BYTE_ORDER_MARK, (JsonDuplicateNames.scan(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '{'.code.toByte(), '}'.code.toByte())) as JsonScan.Rejected).violation)
        assertEquals(JsonViolation.INVALID_UTF8, (JsonDuplicateNames.scan(byteArrayOf('"'.code.toByte(), 0xC0.toByte(), 0x80.toByte(), '"'.code.toByte())) as JsonScan.Rejected).violation)
        assertEquals(JsonViolation.NON_STANDARD_LITERAL, violation("""{"a":NaN}"""))
        assertEquals(JsonViolation.NON_STANDARD_LITERAL, violation("""{"a":-Infinity}"""))
        assertEquals(JsonViolation.SYNTAX, violation("""{"a":abc}"""))
        assertEquals(JsonViolation.SYNTAX, violation("""{"a":01}"""))
        assertEquals(JsonViolation.SYNTAX, violation("""{"a":1,}"""))
        assertEquals(JsonViolation.CONTROL_CHARACTER, violation("{\"a\":\"\u0001\"}"))
        assertEquals(JsonViolation.INVALID_ESCAPE, violation("""{"a":"\x"}"""))
        assertEquals(JsonViolation.TRAILING_CONTENT, violation("""{} {}"""))
        assertEquals(JsonViolation.SYNTAX, violation(""))
        assertEquals(JsonScan.Valid, scan(""" {"n":-0.5e+10,"t":true,"f":false,"z":null,"s":"\b\f\n\r\t\u20AC"} """))
    }

    @Test
    fun nestingIsBoundedWithoutRecursion() {
        val ok = "[".repeat(JsonDuplicateNames.MAX_DEPTH) + "]".repeat(JsonDuplicateNames.MAX_DEPTH)
        assertEquals(JsonScan.Valid, scan(ok))
        val deep = "[".repeat(JsonDuplicateNames.MAX_DEPTH + 1) + "]".repeat(JsonDuplicateNames.MAX_DEPTH + 1)
        assertEquals(JsonViolation.NESTING_TOO_DEEP, violation(deep))
        // a hostile 1 MiB of brackets is rejected without a stack overflow
        assertEquals(JsonViolation.NESTING_TOO_DEEP, violation("[".repeat(1 shl 20)))
        assertEquals(JsonViolation.NESTING_TOO_DEEP, violation("{\"a\":".repeat(100_000)))
    }

    @Serializable
    private data class Probe(val a: Int)

    /**
     * What kotlinx.serialization does on its own with a repeated name — recorded so that a
     * library update that changes it is noticed. The client depends on it in neither
     * direction: [JsonDuplicateNames] runs first and rejects the document.
     */
    @Test
    fun kotlinxSerializationAloneKeepsTheLastValue() {
        val json = Json { ignoreUnknownKeys = false; isLenient = false }
        val tree = json.parseToJsonElement("""{"a":1,"a":2}""").jsonObject
        assertEquals(setOf("a"), tree.keys)
        assertEquals("2", tree.getValue("a").jsonPrimitive.content)
        assertEquals(Probe(2), json.decodeFromString(Probe.serializer(), """{"a":1,"a":2}"""))
        // and the scanner is what rejects it
        assertEquals(JsonViolation.DUPLICATE_MEMBER_NAME, violation("""{"a":1,"a":2}"""))
    }
}
