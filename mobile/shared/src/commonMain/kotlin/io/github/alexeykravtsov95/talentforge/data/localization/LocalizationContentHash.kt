package io.github.alexeykravtsov95.talentforge.data.localization

import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.sha256Hex
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
