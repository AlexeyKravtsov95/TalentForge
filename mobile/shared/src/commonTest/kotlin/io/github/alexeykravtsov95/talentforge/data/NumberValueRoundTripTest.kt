package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.talentdata.NumberValue
import io.github.alexeykravtsov95.talentforge.data.talentdata.RankValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer

/**
 * NumberValue must serialize as a JSON number — never a quoted string — and
 * round-trip through the strict reader while preserving the exact wire form
 * (integer, decimal, exponent).
 */
class NumberValueRoundTripTest {

    private fun encode(value: NumberValue): String =
        TalentDataContract.json.encodeToString(NumberValue.serializer(), value)

    private fun decode(json: String): NumberValue =
        TalentDataContract.json.decodeFromString(NumberValue.serializer(), json)

    private fun roundTripsThroughRankValue(json: String) {
        val rankJson = """{"rank":1,"value":$json}"""
        val original = TalentDataContract.json
            .decodeFromString(RankValue.serializer(), rankJson)
        val reEncoded = TalentDataContract.json
            .encodeToString(RankValue.serializer(), original)
        assertEquals(rankJson, reEncoded)
        assertEquals(
            original,
            TalentDataContract.json.decodeFromString(RankValue.serializer(), reEncoded),
        )
    }

    @Test
    fun integerValueSerializesAsUnquotedNumber() {
        assertEquals("42", encode(NumberValue.Integral(42)))
        roundTripsThroughRankValue("42")
    }

    @Test
    fun negativeIntegerSerializesAsUnquotedNumber() {
        assertEquals("-10", encode(NumberValue.Integral(-10)))
        roundTripsThroughRankValue("-10")
    }

    @Test
    fun decimalValuePreservesItsExactLiteralUnquoted() {
        assertEquals("1.50", encode(NumberValue.Decimal("1.50")))
        roundTripsThroughRankValue("1.50")
        assertEquals(NumberValue.Decimal("1.50"), decode("1.50"))
    }

    @Test
    fun exponentFormIsPreservedUnquoted() {
        assertEquals("2.5e3", encode(NumberValue.Decimal("2.5e3")))
        roundTripsThroughRankValue("2.5e3")
        assertEquals(NumberValue.Decimal("2.5e3"), decode("2.5e3"))
        assertEquals(NumberValue.Decimal("1.2E-4"), decode("1.2E-4"))
    }

    @Test
    fun reEncodedNumbersStayNumbersOnTheWire() {
        for (literal in listOf("0", "-7", "1024", "0.5", "-3.75", "1e3", "6.022e23")) {
            val encoded = encode(decode(literal))
            assertEquals(literal, encoded, "wire form must be preserved for $literal")
        }
    }

    @Test
    fun stringsAndBooleansAreNeverAcceptedAsNumbers() {
        for (literal in listOf("\"42\"", "true", "false", "null")) {
            assertFailsWith<SerializationException>("$literal must not parse as a number") {
                decode(literal)
            }
        }
    }
}
