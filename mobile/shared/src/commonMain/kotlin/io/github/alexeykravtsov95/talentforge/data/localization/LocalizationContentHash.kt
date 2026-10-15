package io.github.alexeykravtsov95.talentforge.data.localization

import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `contentHash` a `talent-localization.v1` document must carry, **recomputed** from the
 * decoded document — the self-reported value is never trusted.
 *
 * The same procedure as the Python producer (`talent_data.models.localization
 * .expected_content_hash` → `talent_data.models.visuals.content_hash`): every top-level field
 * except `contentHash` and `provenance`, serialized as
 * `json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`, UTF-8
 * encoded, SHA-256, rendered `sha256:<lower-case hex>`. Cross-language vectors pin it
 * (tests/fixtures/localization/content-hash-vectors.json, checked by Python and Kotlin).
 */
fun localizationContentHash(document: TalentLocalizationDocument): ContentHash {
    val encoded = TalentDataContract.json.encodeToJsonElement(TalentLocalizationDocument.serializer(), document)
    val payload = JsonObject((encoded as JsonObject).filterKeys { it !in UNHASHED_FIELDS })
    return ContentHash("sha256:" + sha256Hex(canonicalJson(payload).encodeToByteArray()))
}

/** Fields the localization `contentHash` does not cover (Python `_UNHASHED_FIELDS`). */
private val UNHASHED_FIELDS = setOf("contentHash", "provenance")

/**
 * Python's `json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)` for
 * the JSON values a TalentForge contract contains: objects (keys sorted by code point),
 * arrays, strings, integers, booleans and `null`. A non-integral number has no stable
 * cross-language form here and is rejected rather than approximated.
 */
fun canonicalJson(element: JsonElement): String = StringBuilder().also { it.appendCanonical(element) }.toString()

private fun StringBuilder.appendCanonical(element: JsonElement) {
    when (element) {
        is JsonObject -> {
            append('{')
            element.keys.sortedWith(CODE_POINT_ORDER).forEachIndexed { index, key ->
                if (index > 0) append(',')
                appendString(key)
                append(':')
                appendCanonical(element.getValue(key))
            }
            append('}')
        }

        is JsonArray -> {
            append('[')
            element.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendCanonical(item)
            }
            append(']')
        }

        JsonNull -> append("null")
        is JsonPrimitive -> when {
            element.isString -> appendString(element.content)
            element.content == "true" || element.content == "false" -> append(element.content)
            INTEGER.matches(element.content) -> append(element.content)
            else -> throw IllegalArgumentException("no canonical form for the number ${element.content}")
        }
    }
}

private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

/** Python's `str` ordering: by Unicode code point (UTF-16 order differs only above U+FFFF). */
private val CODE_POINT_ORDER = Comparator<String> { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = codePointAt(a, i)
        val cb = codePointAt(b, j)
        if (ca != cb) return@Comparator ca.compareTo(cb)
        i += if (ca > 0xFFFF) 2 else 1
        j += if (cb > 0xFFFF) 2 else 1
    }
    (a.length - i).compareTo(b.length - j)
}

private fun codePointAt(s: String, index: Int): Int {
    val high = s[index]
    if (high.isHighSurrogate() && index + 1 < s.length && s[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (s[index + 1].code - 0xDC00)
    }
    return high.code
}

/** Python `py_encode_basestring` (ensure_ascii=False): only `"`, `\` and C0 controls are escaped. */
private fun StringBuilder.appendString(value: String) {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c.code < 0x20) {
                append("\\u00")
                append(HEX[c.code shr 4])
                append(HEX[c.code and 0xF])
            } else {
                append(c)
            }
        }
    }
    append('"')
}

private const val HEX = "0123456789abcdef"

/** SHA-256 (FIPS 180-4) of [data] as lower-case hex — pure Kotlin, identical on every target. */
fun sha256Hex(data: ByteArray): String {
    val h = intArrayOf(
        0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
        0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
    )
    val bitLength = data.size.toLong() * 8
    val padded = ByteArray(((data.size + 8) / 64 + 1) * 64)
    data.copyInto(padded)
    padded[data.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
    }
    val w = IntArray(64)
    for (block in padded.indices step 64) {
        for (t in 0 until 16) {
            val o = block + t * 4
            w[t] = ((padded[o].toInt() and 0xFF) shl 24) or ((padded[o + 1].toInt() and 0xFF) shl 16) or
                ((padded[o + 2].toInt() and 0xFF) shl 8) or (padded[o + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = hh + s1 + ch + K[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            hh = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }
    val out = StringBuilder(64)
    for (word in h) {
        for (shift in intArrayOf(28, 24, 20, 16, 12, 8, 4, 0)) out.append(HEX[(word ushr shift) and 0xF])
    }
    return out.toString()
}

private val K = intArrayOf(
    0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
    -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
    -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
    -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
)
