package io.github.alexeykravtsov95.talentforge.data.ota

/**
 * Standard padded base64 (RFC 4648 §4) with exactly one spelling per byte string
 * (`docs/ota/ota-contract-v1.md` §3): no whitespace, no missing or extra padding, no non-zero
 * padding bits. The Python reference decodes with `validate=True` and compares the
 * re-encoding; [decodeOrNull] accepts exactly the texts whose re-encoding is themselves.
 */
object CanonicalBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val VALUES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[v ushr 18]).append(ALPHABET[(v ushr 12) and 63])
                .append(ALPHABET[(v ushr 6) and 63]).append(ALPHABET[v and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val v = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[v ushr 18]).append(ALPHABET[(v ushr 12) and 63]).append("==")
            }

            2 -> {
                val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[v ushr 18]).append(ALPHABET[(v ushr 12) and 63])
                    .append(ALPHABET[(v ushr 6) and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** The bytes of a canonical encoding, or `null` for anything else. */
    fun decodeOrNull(text: String): ByteArray? {
        if (text.length % 4 != 0) return null
        if (text.isEmpty()) return ByteArray(0)
        val padding = when {
            text.endsWith("==") -> 2
            text.endsWith("=") -> 1
            else -> 0
        }
        val dataLength = text.length - padding
        val out = ByteArray(text.length / 4 * 3 - padding)
        var o = 0
        var i = 0
        while (i < text.length) {
            var v = 0
            for (k in 0 until 4) {
                val index = i + k
                val digit = if (index >= dataLength) {
                    0
                } else {
                    val c = text[index].code
                    if (c >= 128 || VALUES[c] < 0) return null
                    VALUES[c]
                }
                v = (v shl 6) or digit
            }
            for (shift in intArrayOf(16, 8, 0)) {
                val byte = (v ushr shift).toByte()
                if (o < out.size) {
                    out[o++] = byte
                } else if (byte.toInt() != 0) {
                    return null // non-zero padding bits: another spelling of the same bytes
                }
            }
            i += 4
        }
        return out
    }
}

/**
 * An instant on the UTC time line with microsecond resolution — the parsed form of the
 * contract's timestamps (§2 rule 6). [parseOrNull] accepts `YYYY-MM-DDTHH:MM:SS`, an optional
 * fraction of 1–6 digits, and a zero offset (`Z`, `+00:00`, `-00:00`). Anything else — no
 * offset, another offset, a malformed date — is `null`. The reference (pydantic) accepts a
 * few more spellings; the publisher writes `YYYY-MM-DDTHH:MM:SSZ`, and a client may be
 * stricter than the reference, never more lenient.
 */
data class UtcInstant(val epochSecond: Long, val microsecond: Int) : Comparable<UtcInstant> {
    init {
        require(microsecond in 0..999_999) { "microsecond out of range" }
    }

    override fun compareTo(other: UtcInstant): Int =
        compareValuesBy(this, other, UtcInstant::epochSecond, UtcInstant::microsecond)

    /** `this − other` in microseconds. */
    fun microsSince(other: UtcInstant): Long =
        (epochSecond - other.epochSecond) * 1_000_000L + (microsecond - other.microsecond)

    fun plusSeconds(seconds: Long): UtcInstant = UtcInstant(epochSecond + seconds, microsecond)

    /** `YYYY-MM-DDTHH:MM:SSZ` (the fraction is dropped; used for client-private records). */
    fun toIsoSeconds(): String {
        val days = floorDiv(epochSecond, 86_400L)
        val secondOfDay = epochSecond - days * 86_400L
        val (year, month, day) = civilFromDays(days)
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}T" +
            "${two(secondOfDay / 3600)}:${two(secondOfDay % 3600 / 60)}:${two(secondOfDay % 60)}Z"
    }

    override fun toString(): String = toIsoSeconds()

    companion object {
        private val PATTERN = Regex(
            "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,6}))?(Z|[+-]00:00)",
        )

        fun ofEpochMillis(millis: Long): UtcInstant {
            val second = floorDiv(millis, 1000L)
            return UtcInstant(second, ((millis - second * 1000L) * 1000L).toInt())
        }

        fun parseOrNull(text: String): UtcInstant? {
            val match = PATTERN.matchEntire(text) ?: return null
            val g = match.groupValues
            val year = g[1].toLong()
            val month = g[2].toInt()
            val day = g[3].toInt()
            val hour = g[4].toInt()
            val minute = g[5].toInt()
            val second = g[6].toInt()
            if (year < 1 || month !in 1..12 || day < 1 || day > daysInMonth(year, month)) return null
            if (hour > 23 || minute > 59 || second > 59) return null
            val micros = if (g[7].isEmpty()) 0 else g[7].padEnd(6, '0').toInt()
            val days = daysFromCivil(year, month, day)
            return UtcInstant(days * 86_400L + hour * 3600L + minute * 60L + second, micros)
        }

        private fun daysInMonth(year: Long, month: Int): Int = when (month) {
            2 -> if (year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        // H. Hinnant's civil-calendar algorithms (proleptic Gregorian).
        private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
            val y = if (month <= 2) year - 1 else year
            val era = floorDiv(y, 400L)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        private fun civilFromDays(days: Long): Triple<Long, Long, Long> {
            val z = days + 719_468
            val era = floorDiv(z, 146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = doy - (153 * mp + 2) / 5 + 1
            val month = if (mp < 10) mp + 3 else mp - 9
            val year = yoe + era * 400 + if (month <= 2) 1 else 0
            return Triple(year, month, day)
        }

        private fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if (a % b != 0L && (a < 0) != (b < 0)) q - 1 else q
        }
    }
}

/**
 * A client build `a.b.c.d` (§2 rule 8): four non-negative decimal integers compared
 * numerically, component by component (`1.60.1.70450` > `1.60.1.70374`).
 */
data class ClientBuildVersion(val parts: List<Long>) : Comparable<ClientBuildVersion> {
    override fun compareTo(other: ClientBuildVersion): Int {
        for (i in 0 until 4) {
            val c = parts[i].compareTo(other.parts[i])
            if (c != 0) return c
        }
        return 0
    }

    companion object {
        private val PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")

        fun parseOrNull(text: String): ClientBuildVersion? {
            if (!PATTERN.matches(text)) return null
            val parts = text.split('.').map { it.toLongOrNull() ?: return null }
            return ClientBuildVersion(parts)
        }

        fun parse(text: String): ClientBuildVersion =
            requireNotNull(parseOrNull(text)) { "not a client build: $text" }
    }
}

/** A release id `<clientBuild>-r<revision>` (§2 rule 9), ordered by (clientBuild, revision). */
data class ReleaseId(val clientBuild: String, val revision: Int) : Comparable<ReleaseId> {
    val value: String get() = "$clientBuild-r$revision"

    override fun compareTo(other: ReleaseId): Int {
        val byBuild = ClientBuildVersion.parse(clientBuild).compareTo(ClientBuildVersion.parse(other.clientBuild))
        return if (byBuild != 0) byBuild else revision.compareTo(other.revision)
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+-r[1-9][0-9]{0,5}")

        fun parseOrNull(text: String): ReleaseId? {
            if (!PATTERN.matches(text)) return null
            val separator = text.lastIndexOf("-r")
            val build = text.substring(0, separator)
            ClientBuildVersion.parseOrNull(build) ?: return null
            return ReleaseId(build, text.substring(separator + 2).toInt())
        }
    }
}

/** The number of Unicode code points — pydantic's notion of a string's length. */
internal fun String.codePointLength(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}
