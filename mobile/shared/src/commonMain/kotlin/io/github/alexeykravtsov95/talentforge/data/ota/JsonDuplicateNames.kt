package io.github.alexeykravtsov95.talentforge.data.ota

/**
 * The duplicate-member-name scanner of `docs/ota/ota-contract-v1.md` §2.1, run over the raw
 * bytes of every OTA document and of every JSON file of a release **before** anything decodes
 * them.
 *
 * kotlinx.serialization exposes no token stream and its `JsonObject` is a map, so a repeated
 * name is already gone once a tree exists, and the typed decoder's behaviour on duplicates is
 * not part of any contract (`KotlinxDuplicateBehaviourTest` records it; nothing relies on it).
 * This lexer walks the bytes instead: it tracks strings, `{`/`}`/`[`/`]` nesting and the
 * member-name position of each open object, unescapes every name (`\"`, `\\`, `\/`, `\b`,
 * `\f`, `\n`, `\r`, `\t`, `\uXXXX` including surrogate pairs) and keeps one name set per open
 * object. Names are compared after unescaping, as UTF-16 strings — the same equality as
 * Python's `str` once its decoder has joined surrogate pairs.
 *
 * It also checks what the Python reference's `strict_json` rejects before any model sees the
 * document, so that no leniency of the Kotlin JSON library can ever matter: a UTF-8 byte order
 * mark, malformed UTF-8 (overlong forms, surrogates, code points above U+10FFFF), the RFC 8259
 * grammar (literals, number syntax, escapes, raw control characters in strings, trailing
 * content), and `NaN` / `Infinity`. kotlinx' tree reader, for one, accepts unquoted literals
 * such as `abc`. Nesting is bounded ([MAX_DEPTH]) and the scan is iterative, so a hostile
 * document cannot overflow the stack.
 *
 * It builds no values and is not a JSON parser: the strict decoder still checks shapes and
 * types afterwards.
 */
object JsonDuplicateNames {

    /** Deeper than any TalentForge contract (≤ 8 levels); the reference fails far deeper. */
    const val MAX_DEPTH: Int = 256

    fun scan(bytes: ByteArray): JsonScan {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return JsonScan.Rejected(JsonViolation.BYTE_ORDER_MARK, 0)
        }
        val utf8Error = firstInvalidUtf8(bytes)
        if (utf8Error >= 0) return JsonScan.Rejected(JsonViolation.INVALID_UTF8, utf8Error)
        return Lexer(bytes).run()
    }

    private class Frame(val isObject: Boolean) {
        val names: HashSet<String>? = if (isObject) HashSet() else null
    }

    private enum class Expect { VALUE, VALUE_OR_END, NAME, NAME_OR_END, COLON, COMMA_OR_END, DONE }

    private class Failure(val violation: JsonViolation, val offset: Int) : Exception()

    private class Lexer(private val b: ByteArray) {
        private val n = b.size
        private val stack = ArrayList<Frame>()

        fun run(): JsonScan = try {
            lex()
            JsonScan.Valid
        } catch (failure: Failure) {
            JsonScan.Rejected(failure.violation, failure.offset)
        }

        private fun fail(violation: JsonViolation, at: Int): Nothing = throw Failure(violation, at)

        private fun lex() {
            var i = 0
            var expect = Expect.VALUE
            while (true) {
                i = skipWhitespace(i)
                when (expect) {
                    Expect.DONE -> {
                        if (i != n) fail(JsonViolation.TRAILING_CONTENT, i)
                        return
                    }

                    Expect.VALUE, Expect.VALUE_OR_END -> {
                        if (i >= n) fail(JsonViolation.SYNTAX, i)
                        val c = b[i].toInt()
                        if (expect == Expect.VALUE_OR_END && c == ']'.code) {
                            stack.removeAt(stack.lastIndex)
                            i++
                            expect = afterValue()
                            continue
                        }
                        when (c) {
                            '{'.code -> {
                                push(Frame(isObject = true), i)
                                i++
                                expect = Expect.NAME_OR_END
                            }

                            '['.code -> {
                                push(Frame(isObject = false), i)
                                i++
                                expect = Expect.VALUE_OR_END
                            }

                            '"'.code -> {
                                i = string(i, names = null)
                                expect = afterValue()
                            }

                            't'.code -> {
                                i = literal(i, TRUE)
                                expect = afterValue()
                            }

                            'f'.code -> {
                                i = literal(i, FALSE)
                                expect = afterValue()
                            }

                            'n'.code -> {
                                i = literal(i, NULL)
                                expect = afterValue()
                            }

                            'N'.code, 'I'.code -> fail(JsonViolation.NON_STANDARD_LITERAL, i)

                            else -> {
                                if (c == '-'.code || c in '0'.code..'9'.code) {
                                    i = number(i)
                                    expect = afterValue()
                                } else {
                                    fail(JsonViolation.SYNTAX, i)
                                }
                            }
                        }
                    }

                    Expect.NAME, Expect.NAME_OR_END -> {
                        if (i >= n) fail(JsonViolation.SYNTAX, i)
                        if (expect == Expect.NAME_OR_END && b[i] == '}'.code.toByte()) {
                            stack.removeAt(stack.lastIndex)
                            i++
                            expect = afterValue()
                            continue
                        }
                        if (b[i] != '"'.code.toByte()) fail(JsonViolation.SYNTAX, i)
                        i = string(i, names = stack.last().names)
                        expect = Expect.COLON
                    }

                    Expect.COLON -> {
                        if (i >= n || b[i] != ':'.code.toByte()) fail(JsonViolation.SYNTAX, i)
                        i++
                        expect = Expect.VALUE
                    }

                    Expect.COMMA_OR_END -> {
                        if (i >= n) fail(JsonViolation.SYNTAX, i)
                        val frame = stack.last()
                        val c = b[i].toInt()
                        when {
                            c == ','.code -> {
                                i++
                                expect = if (frame.isObject) Expect.NAME else Expect.VALUE
                            }

                            (frame.isObject && c == '}'.code) || (!frame.isObject && c == ']'.code) -> {
                                stack.removeAt(stack.lastIndex)
                                i++
                                expect = afterValue()
                            }

                            else -> fail(JsonViolation.SYNTAX, i)
                        }
                    }
                }
            }
        }

        private fun push(frame: Frame, at: Int) {
            if (stack.size >= MAX_DEPTH) fail(JsonViolation.NESTING_TOO_DEEP, at)
            stack.add(frame)
        }

        private fun afterValue(): Expect = if (stack.isEmpty()) Expect.DONE else Expect.COMMA_OR_END

        private fun skipWhitespace(from: Int): Int {
            var i = from
            while (i < n) {
                val c = b[i].toInt()
                if (c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) i++ else break
            }
            return i
        }

        private fun literal(at: Int, expected: ByteArray): Int {
            if (at + expected.size > n) fail(JsonViolation.SYNTAX, at)
            for (k in expected.indices) {
                if (b[at + k] != expected[k]) fail(JsonViolation.SYNTAX, at)
            }
            return at + expected.size
        }

        /** `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?` — the next token is checked by the caller. */
        private fun number(at: Int): Int {
            var i = at
            if (b[i] == '-'.code.toByte()) {
                i++
                if (i < n && (b[i] == 'I'.code.toByte() || b[i] == 'N'.code.toByte())) {
                    fail(JsonViolation.NON_STANDARD_LITERAL, at)
                }
            }
            if (i >= n || !isDigit(b[i])) fail(JsonViolation.SYNTAX, i)
            if (b[i] == '0'.code.toByte()) {
                i++
            } else {
                while (i < n && isDigit(b[i])) i++
            }
            if (i < n && b[i] == '.'.code.toByte()) {
                i++
                if (i >= n || !isDigit(b[i])) fail(JsonViolation.SYNTAX, i)
                while (i < n && isDigit(b[i])) i++
            }
            if (i < n && (b[i] == 'e'.code.toByte() || b[i] == 'E'.code.toByte())) {
                i++
                if (i < n && (b[i] == '+'.code.toByte() || b[i] == '-'.code.toByte())) i++
                if (i >= n || !isDigit(b[i])) fail(JsonViolation.SYNTAX, i)
                while (i < n && isDigit(b[i])) i++
            }
            return i
        }

        /**
         * One string token starting at the opening quote; returns the index after the closing
         * quote. With [names] (a member name) the unescaped name is added to the object's set,
         * and a name already present is a [JsonViolation.DUPLICATE_MEMBER_NAME].
         */
        private fun string(at: Int, names: HashSet<String>?): Int {
            var i = at + 1
            val decoded = if (names != null) StringBuilder() else null
            var segmentStart = i
            while (true) {
                if (i >= n) fail(JsonViolation.SYNTAX, at)
                val c = b[i].toInt() and 0xFF
                when {
                    c == '"'.code -> {
                        if (decoded != null) {
                            decoded.append(b.decodeToString(segmentStart, i))
                            if (!names!!.add(decoded.toString())) fail(JsonViolation.DUPLICATE_MEMBER_NAME, at)
                        }
                        return i + 1
                    }

                    c == '\\'.code -> {
                        decoded?.append(b.decodeToString(segmentStart, i))
                        if (i + 1 >= n) fail(JsonViolation.SYNTAX, at)
                        when (b[i + 1].toInt()) {
                            '"'.code -> decoded?.append('"')
                            '\\'.code -> decoded?.append('\\')
                            '/'.code -> decoded?.append('/')
                            'b'.code -> decoded?.append('\b')
                            'f'.code -> decoded?.append('\u000C')
                            'n'.code -> decoded?.append('\n')
                            'r'.code -> decoded?.append('\r')
                            't'.code -> decoded?.append('\t')
                            'u'.code -> {
                                if (i + 6 > n) fail(JsonViolation.INVALID_ESCAPE, i)
                                var unit = 0
                                for (k in 2..5) {
                                    val digit = hexValue(b[i + k])
                                    if (digit < 0) fail(JsonViolation.INVALID_ESCAPE, i)
                                    unit = unit * 16 + digit
                                }
                                // UTF-16 units: a surrogate pair written as two escapes becomes the
                                // same string as the raw character, exactly like Python's decoder.
                                decoded?.append(unit.toChar())
                                i += 4
                            }

                            else -> fail(JsonViolation.INVALID_ESCAPE, i)
                        }
                        i += 2
                        segmentStart = i
                    }

                    c < 0x20 -> fail(JsonViolation.CONTROL_CHARACTER, i)

                    else -> i++
                }
            }
        }

        private fun isDigit(byte: Byte): Boolean = byte >= '0'.code.toByte() && byte <= '9'.code.toByte()

        private fun hexValue(byte: Byte): Int = when (val c = byte.toInt()) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }

    private val TRUE = "true".encodeToByteArray()
    private val FALSE = "false".encodeToByteArray()
    private val NULL = "null".encodeToByteArray()

    /** Index of the first byte of a malformed UTF-8 sequence, or -1 (RFC 3629, like Python's codec). */
    internal fun firstInvalidUtf8(bytes: ByteArray): Int {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                continue
            }
            val length: Int
            val min: Int
            when (b0) {
                in 0xC2..0xDF -> { length = 2; min = 0x80 }
                in 0xE0..0xEF -> { length = 3; min = 0x800 }
                in 0xF0..0xF4 -> { length = 4; min = 0x10000 }
                else -> return i
            }
            if (i + length > n) return i
            var cp = b0 and (0xFF ushr (length + 1))
            for (k in 1 until length) {
                val bk = bytes[i + k].toInt() and 0xFF
                if (bk and 0xC0 != 0x80) return i
                cp = (cp shl 6) or (bk and 0x3F)
            }
            if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return i
            i += length
        }
        return -1
    }
}

/** Outcome of [JsonDuplicateNames.scan]. */
sealed interface JsonScan {
    data object Valid : JsonScan

    /** [offset] is the byte offset of the offending token (diagnostics only). */
    data class Rejected(val violation: JsonViolation, val offset: Int) : JsonScan
}

/** Why a document is not strict JSON (`docs/ota/ota-contract-v1.md` §2 rules 1, 3, 4). */
enum class JsonViolation {
    BYTE_ORDER_MARK,
    INVALID_UTF8,
    SYNTAX,
    TRAILING_CONTENT,
    CONTROL_CHARACTER,
    INVALID_ESCAPE,
    NON_STANDARD_LITERAL,
    DUPLICATE_MEMBER_NAME,
    NESTING_TOO_DEEP,
}
