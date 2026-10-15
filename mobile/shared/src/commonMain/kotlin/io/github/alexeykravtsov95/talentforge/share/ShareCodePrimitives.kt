package io.github.alexeykravtsov95.talentforge.share

/**
 * Byte-level building blocks of `talentforge-share.v1` (docs/build-sharing.md):
 * the text alphabet, unsigned LEB128 varints, zigzag and CRC-32. Pure common
 * Kotlin — no platform API, no locale-sensitive formatting — so the codec
 * produces identical bytes and text on JVM, Android and iOS.
 */
internal object ShareCodeBase32 {

    /**
     * Crockford's Base32 digit set: `0-9` and `A-Z` without `I`, `L`, `O`, `U`.
     * Only these 32 upper-case characters are accepted — no lower case, no
     * separators, no padding.
     */
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val VALUES: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { value, char -> table[char.code] = value }
    }

    /** The 5-bit value of [char], or -1 when it is not an alphabet character. */
    fun valueOf(char: Char): Int = if (char.code < VALUES.size) VALUES[char.code] else -1

    /** RFC 4648 bit order (most significant bit first); the last group is zero-padded. */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return out.toString()
    }

    /**
     * Decodes characters already checked against [ALPHABET]. Returns `null` for
     * a non-canonical text: a length no byte count produces, or non-zero
     * padding bits (either would let two different texts mean the same bytes).
     */
    fun decode(text: CharSequence): ByteArray? {
        val remainder = text.length % 8
        if (remainder == 1 || remainder == 3 || remainder == 6) {
            return null
        }
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in text) {
            buffer = (buffer shl 5) or valueOf(char)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[index++] = (buffer shr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return if (buffer == 0) out else null
    }
}

/** CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320) — the zlib/PNG checksum. */
internal object ShareCodeCrc32 {

    private val TABLE: IntArray = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray): Long {
        var crc = -1
        for (byte in bytes) {
            crc = TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv().toLong() and 0xFFFF_FFFFL
    }
}

internal fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)

internal fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

/** Growing byte sink for the payload writer. */
internal class ShareCodeWriter {
    private var bytes = ByteArray(64)
    private var size: Int = 0

    fun byte(value: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
        bytes[size++] = value.toByte()
    }

    /** Unsigned LEB128, minimal length. [value] must be non-negative. */
    fun varint(value: Long) {
        var rest = value
        while (true) {
            val low = (rest and 0x7F).toInt()
            rest = rest ushr 7
            if (rest == 0L) {
                byte(low)
                return
            }
            byte(low or 0x80)
        }
    }

    /** Length-prefixed ASCII string (the caller has checked the character set). */
    fun ascii(value: String) {
        varint(value.length.toLong())
        for (char in value) byte(char.code)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}

/** Why a payload read stopped; mapped onto typed decode errors by the codec. */
internal enum class ShareCodeReadFailure { TRUNCATED, MALFORMED_NUMBER }

internal class ShareCodeReadException(val failure: ShareCodeReadFailure) : Exception(failure.name)

/** Strict cursor over a decoded payload. */
internal class ShareCodeReader(private val bytes: ByteArray) {
    private var position = 0

    val remaining: Int get() = bytes.size - position

    fun byte(): Int {
        if (position >= bytes.size) throw ShareCodeReadException(ShareCodeReadFailure.TRUNCATED)
        return bytes[position++].toInt() and 0xFF
    }

    /**
     * Unsigned LEB128 of at most [MAX_VARINT_BYTES] bytes in minimal form: an
     * over-long encoding (a trailing zero group) is rejected so that every
     * number has exactly one byte representation.
     */
    fun varint(): Long {
        var result = 0L
        var shift = 0
        var count = 0
        while (true) {
            val byte = byte()
            count += 1
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) {
                if (count > 1 && byte == 0) {
                    throw ShareCodeReadException(ShareCodeReadFailure.MALFORMED_NUMBER)
                }
                return result
            }
            if (count == MAX_VARINT_BYTES) {
                throw ShareCodeReadException(ShareCodeReadFailure.MALFORMED_NUMBER)
            }
            shift += 7
        }
    }

    fun bytes(count: Int): ByteArray {
        if (count > remaining) throw ShareCodeReadException(ShareCodeReadFailure.TRUNCATED)
        val slice = bytes.copyOfRange(position, position + count)
        position += count
        return slice
    }

    private companion object {
        /** 8 × 7 = 56 bits: every TF1 field fits, and no Long can overflow. */
        const val MAX_VARINT_BYTES = 8
    }
}
