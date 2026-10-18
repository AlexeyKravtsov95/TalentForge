package io.github.alexeykravtsov95.talentforge.data

/**
 * SHA-256 (FIPS 180-4) in pure Kotlin, identical on every target.
 *
 * One implementation serves the localization `contentHash` (M5.REL.3b.2) and every OTA hash
 * (blob addresses, release manifests, catalog payloads — `docs/ota/ota-contract-v1.md` §2
 * rule 7). [Sha256] is the streaming form; [sha256Hex] and [sha256Ref] hash one array.
 */
class Sha256 {
    private val h = INITIAL.copyOf()
    private val block = ByteArray(64)
    private var blockLength = 0
    private var totalLength = 0L
    private val w = IntArray(64)
    private var finished = false

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Sha256 {
        check(!finished) { "digest already computed" }
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "range outside the array" }
        var index = offset
        val end = offset + length
        totalLength += length
        if (blockLength > 0) {
            val take = minOf(64 - blockLength, length)
            data.copyInto(block, blockLength, index, index + take)
            blockLength += take
            index += take
            if (blockLength == 64) {
                compress(block, 0)
                blockLength = 0
            }
        }
        while (end - index >= 64) {
            compress(data, index)
            index += 64
        }
        if (index < end) {
            data.copyInto(block, 0, index, end)
            blockLength = end - index
        }
        return this
    }

    /** The 32-byte digest; the instance cannot be updated afterwards. */
    fun digest(): ByteArray {
        check(!finished) { "digest already computed" }
        finished = true
        val bitLength = totalLength * 8
        block[blockLength++] = 0x80.toByte()
        if (blockLength > 56) {
            block.fill(0, blockLength, 64)
            compress(block, 0)
            blockLength = 0
        }
        block.fill(0, blockLength, 56)
        for (i in 0 until 8) block[63 - i] = (bitLength ushr (8 * i)).toByte()
        compress(block, 0)
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    /** The digest as lower-case hex. */
    fun digestHex(): String = digest().toLowerHex()

    private fun compress(data: ByteArray, offset: Int) {
        for (t in 0 until 16) {
            val o = offset + t * 4
            w[t] = ((data[o].toInt() and 0xFF) shl 24) or ((data[o + 1].toInt() and 0xFF) shl 16) or
                ((data[o + 2].toInt() and 0xFF) shl 8) or (data[o + 3].toInt() and 0xFF)
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

    private companion object {
        val INITIAL = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
            0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )

        val K = intArrayOf(
            0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
            -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
            -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
            -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
        )
    }
}

/** SHA-256 of [data] as lower-case hex. */
fun sha256Hex(data: ByteArray): String = Sha256().update(data).digestHex()

/** `sha256:<64 lower-case hex>` — the form every OTA document uses, and a blob's address. */
fun sha256Ref(data: ByteArray): String = "sha256:" + sha256Hex(data)

private const val HEX = "0123456789abcdef"

internal fun ByteArray.toLowerHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val v = byte.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0xF])
    }
    return out.toString()
}
