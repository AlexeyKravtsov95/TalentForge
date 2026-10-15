package io.github.alexeykravtsov95.talentforge.share

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation

/** Which part of the portable build a share-code violation refers to. */
enum class ShareCodeField {
    GAME,
    CLIENT_BUILD,
    CLASS,
    TREE_ID,
    NODE_ID,
    ENTRY_ID,
    RANK,
}

/** Outcome of [BuildShareCode.encode]. */
sealed interface ShareCodeEncoding {
    data class Encoded(val code: String) : ShareCodeEncoding

    data class Failed(val error: ShareCodeEncodeError) : ShareCodeEncoding
}

/** Outcome of [BuildShareCode.decode]. */
sealed interface ShareCodeDecoding {
    /** A structurally valid build. Not yet checked against any dataset (see [prepareBuildImport]). */
    data class Decoded(val build: BuildFile) : ShareCodeDecoding

    data class Failed(val error: ShareCodeDecodeError) : ShareCodeDecoding
}

/** Typed reasons the encoder refuses a build. Never a message to parse. */
sealed interface ShareCodeEncodeError {

    /** The encoded text would exceed [BuildShareCode.MAX_CODE_LENGTH]. */
    data class CodeTooLong(val length: Int, val max: Int) : ShareCodeEncodeError
}

/** Typed reasons the decoder rejects a text. Never a message to parse. */
sealed interface ShareCodeDecodeError {

    /** Nothing but whitespace. */
    data object Empty : ShareCodeDecodeError

    /**
     * The raw input is longer than [BuildShareCode.MAX_INPUT_LENGTH], or the
     * trimmed code is longer than [BuildShareCode.MAX_CODE_LENGTH]; [max] is
     * the limit that was exceeded.
     */
    data class TooLong(val length: Int, val max: Int) : ShareCodeDecodeError

    /** The text does not start with the `TF` share-code prefix. */
    data object NotAShareCode : ShareCodeDecodeError

    /** A `TF` code of a format version this client does not read (e.g. `TF2…`). */
    data class UnsupportedFormatVersion(val version: Char) : ShareCodeDecodeError

    /** A character outside the share-code alphabet; [index] counts from the first prefix character. */
    data class InvalidCharacter(val index: Int, val character: Char) : ShareCodeDecodeError

    /** Alphabet characters that are not a canonical encoding (impossible length or non-zero padding bits). */
    data object InvalidEncoding : ShareCodeDecodeError

    /** The data ends before the format says it does. */
    data object Truncated : ShareCodeDecodeError

    /** The CRC-32 stored in the code differs from the one computed over it: accidental corruption. */
    data class ChecksumMismatch(val stored: Long, val computed: Long) : ShareCodeDecodeError

    /** A number is not in minimal unsigned LEB128 form or is longer than the format allows. */
    data object MalformedNumber : ShareCodeDecodeError

    /** Bytes remain after the last allocation. */
    data class TrailingData(val byteCount: Int) : ShareCodeDecodeError
}

/**
 * Violations of the portable build structure. The writer and the reader
 * enforce exactly the same rules, so everything the encoder emits decodes, and
 * nothing the decoder rejects can be produced by the encoder.
 */
sealed interface ShareCodeViolation : ShareCodeEncodeError, ShareCodeDecodeError {

    /** The build declares an `allocation-build` schema version other than v1. */
    data class UnsupportedBuildSchemaVersion(val actual: Long, val supported: Int) : ShareCodeViolation

    /**
     * An identity string is empty, too long, outside `[A-Za-z0-9._-]` or does
     * not start with a letter or digit (so it can never be a `.`/`..` path
     * segment of a dataset lookup).
     */
    data class InvalidIdentity(val field: ShareCodeField) : ShareCodeViolation

    /** An id outside `1..Int.MAX_VALUE` or a rank outside `1..MAX_RANK`. */
    data class NumberOutOfRange(val field: ShareCodeField, val value: Long) : ShareCodeViolation

    /** The node is allocated more than once. */
    data class DuplicateNode(val nodeId: Int) : ShareCodeViolation

    /** The same entry is assigned to more than one allocation. */
    data class DuplicateEntry(val entryId: Int) : ShareCodeViolation

    /** More allocations than [BuildShareCode.MAX_ALLOCATIONS]. */
    data class TooManyAllocations(val count: Long, val max: Int) : ShareCodeViolation
}

/**
 * `talentforge-share.v1`: the portable, human-copyable text form of an
 * `allocation-build.v1` [BuildFile] (specification: docs/build-sharing.md).
 *
 * ```
 * code    = "TF1" base32(payload ‖ crc32be("TF1" ‖ payload))
 * payload = schemaVersion game clientBuild class treeId count allocation*
 * ```
 *
 * The codec is dataset-independent and purely structural: a decoded build is
 * well-formed, not necessarily valid — [prepareBuildImport] resolves its exact
 * dataset and runs the allocation engine. Encoding is canonical (allocations
 * in ascending node id order, minimal varints, zero padding bits, upper-case
 * alphabet), so a build has exactly one code and every accepted code
 * re-encodes to itself. Neither direction normalizes invalid data: a
 * duplicate, an out-of-range number or a malformed field is a typed failure.
 */
object BuildShareCode {

    /** Public name of the wire format. */
    const val FORMAT: String = "talentforge-share.v1"

    /** Fixed prefix of every v1 code: `TF` plus the format version digit. */
    const val PREFIX: String = "TF1"

    /** The only `allocation-build` schema version v1 codes carry. */
    const val SUPPORTED_BUILD_SCHEMA_VERSION: Int = 1

    /** Upper bound of a code's length in characters, prefix included. */
    const val MAX_CODE_LENGTH: Int = 1024

    /**
     * Upper bound of the raw input, surrounding whitespace included. Checked
     * before anything else, so an oversized paste is refused without being
     * scanned or copied.
     */
    const val MAX_INPUT_LENGTH: Int = 4096

    /** Upper bound of the allocation count. */
    const val MAX_ALLOCATIONS: Int = 128

    /** Upper bound of each identity string (game, client build, class token). */
    const val MAX_IDENTITY_LENGTH: Int = 32

    /** Upper bound of a single allocation's rank. */
    const val MAX_RANK: Int = 255

    private const val CHECKSUM_BYTES = 4
    private const val RANK_BITS = 3
    private const val RANK_INLINE_MAX = 7 // ranks 1..7 live in the low bits; 0 escapes to an explicit rank
    private const val RANK_ESCAPE_BASE = 8
    private val PREFIX_BYTES: ByteArray = PREFIX.encodeToByteArray()

    /**
     * Encodes [build]. The input is only read (the allocation list is copied
     * before sorting). Fails with a typed [ShareCodeEncodeError] instead of
     * dropping, merging or clamping anything.
     */
    fun encode(build: BuildFile): ShareCodeEncoding {
        if (build.schemaVersion != SUPPORTED_BUILD_SCHEMA_VERSION) {
            return encodeFailure(
                ShareCodeViolation.UnsupportedBuildSchemaVersion(
                    actual = build.schemaVersion.toLong(),
                    supported = SUPPORTED_BUILD_SCHEMA_VERSION,
                ),
            )
        }
        identityViolation(ShareCodeField.GAME, build.game.value)?.let { return encodeFailure(it) }
        identityViolation(ShareCodeField.CLIENT_BUILD, build.clientBuild)?.let { return encodeFailure(it) }
        identityViolation(ShareCodeField.CLASS, build.classToken.value)?.let { return encodeFailure(it) }
        idViolation(ShareCodeField.TREE_ID, build.treeId.toLong())?.let { return encodeFailure(it) }

        val allocations = build.allocations.toList()
        if (allocations.size > MAX_ALLOCATIONS) {
            return encodeFailure(
                ShareCodeViolation.TooManyAllocations(count = allocations.size.toLong(), max = MAX_ALLOCATIONS),
            )
        }
        val ordered = allocations.sortedBy { it.nodeId }

        val writer = ShareCodeWriter()
        writer.varint(build.schemaVersion.toLong())
        writer.ascii(build.game.value)
        writer.ascii(build.clientBuild)
        writer.ascii(build.classToken.value)
        writer.varint(build.treeId.toLong())
        writer.varint(ordered.size.toLong())

        val entries = HashSet<Int>(ordered.size)
        var previousNode = 0L
        var previousOffset = 0L
        for (allocation in ordered) {
            val nodeId = allocation.nodeId.toLong()
            idViolation(ShareCodeField.NODE_ID, nodeId)?.let { return encodeFailure(it) }
            if (nodeId == previousNode) {
                return encodeFailure(ShareCodeViolation.DuplicateNode(allocation.nodeId))
            }
            idViolation(ShareCodeField.ENTRY_ID, allocation.entryId.toLong())?.let { return encodeFailure(it) }
            rankViolation(allocation.rank.toLong())?.let { return encodeFailure(it) }
            if (!entries.add(allocation.entryId)) {
                return encodeFailure(ShareCodeViolation.DuplicateEntry(allocation.entryId))
            }

            val offset = allocation.entryId.toLong() - nodeId
            val inlineRank = if (allocation.rank <= RANK_INLINE_MAX) allocation.rank else 0
            writer.varint(nodeId - previousNode)
            writer.varint((zigzag(offset - previousOffset) shl RANK_BITS) or inlineRank.toLong())
            if (inlineRank == 0) {
                writer.varint((allocation.rank - RANK_ESCAPE_BASE).toLong())
            }
            previousNode = nodeId
            previousOffset = offset
        }

        val payload = writer.toByteArray()
        val checksum = ShareCodeCrc32.of(PREFIX_BYTES + payload)
        val body = ByteArray(payload.size + CHECKSUM_BYTES)
        payload.copyInto(body)
        for (index in 0 until CHECKSUM_BYTES) {
            body[payload.size + index] = (checksum ushr (8 * (CHECKSUM_BYTES - 1 - index))).toByte()
        }
        val code = PREFIX + ShareCodeBase32.encode(body)
        if (code.length > MAX_CODE_LENGTH) {
            return ShareCodeEncoding.Failed(ShareCodeEncodeError.CodeTooLong(code.length, MAX_CODE_LENGTH))
        }
        return ShareCodeEncoding.Encoded(code)
    }

    /**
     * Decodes [text] into a structurally valid [BuildFile] or a typed
     * [ShareCodeDecodeError]. Leading and trailing ASCII whitespace (space,
     * tab, CR, LF — what a clipboard or messenger usually adds) is ignored;
     * anything else outside the alphabet is rejected. Never throws for any
     * input.
     */
    fun decode(text: String): ShareCodeDecoding {
        if (text.length > MAX_INPUT_LENGTH) {
            return decodeFailure(ShareCodeDecodeError.TooLong(text.length, MAX_INPUT_LENGTH))
        }
        val code = text.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        if (code.isEmpty()) return decodeFailure(ShareCodeDecodeError.Empty)
        if (code.length > MAX_CODE_LENGTH) {
            return decodeFailure(ShareCodeDecodeError.TooLong(code.length, MAX_CODE_LENGTH))
        }
        if (!code.startsWith(PREFIX.substring(0, 2))) return decodeFailure(ShareCodeDecodeError.NotAShareCode)
        if (code.length < PREFIX.length) return decodeFailure(ShareCodeDecodeError.Truncated)
        val version = code[PREFIX.length - 1]
        if (version != PREFIX.last()) {
            val versionLike = version in '0'..'9' || version in 'A'..'Z'
            return decodeFailure(
                if (versionLike) ShareCodeDecodeError.UnsupportedFormatVersion(version)
                else ShareCodeDecodeError.NotAShareCode,
            )
        }

        for (index in PREFIX.length until code.length) {
            if (ShareCodeBase32.valueOf(code[index]) < 0) {
                return decodeFailure(ShareCodeDecodeError.InvalidCharacter(index, code[index]))
            }
        }
        val body = ShareCodeBase32.decode(code.subSequence(PREFIX.length, code.length))
            ?: return decodeFailure(ShareCodeDecodeError.InvalidEncoding)
        if (body.size <= CHECKSUM_BYTES) return decodeFailure(ShareCodeDecodeError.Truncated)

        val payload = body.copyOfRange(0, body.size - CHECKSUM_BYTES)
        var stored = 0L
        for (index in payload.size until body.size) {
            stored = (stored shl 8) or (body[index].toLong() and 0xFF)
        }
        val computed = ShareCodeCrc32.of(PREFIX_BYTES + payload)
        if (stored != computed) {
            return decodeFailure(ShareCodeDecodeError.ChecksumMismatch(stored = stored, computed = computed))
        }

        return try {
            readPayload(ShareCodeReader(payload))
        } catch (e: ShareCodeReadException) {
            decodeFailure(
                when (e.failure) {
                    ShareCodeReadFailure.TRUNCATED -> ShareCodeDecodeError.Truncated
                    ShareCodeReadFailure.MALFORMED_NUMBER -> ShareCodeDecodeError.MalformedNumber
                },
            )
        }
    }

    private fun readPayload(reader: ShareCodeReader): ShareCodeDecoding {
        val schemaVersion = reader.varint()
        if (schemaVersion != SUPPORTED_BUILD_SCHEMA_VERSION.toLong()) {
            return decodeFailure(
                ShareCodeViolation.UnsupportedBuildSchemaVersion(
                    actual = schemaVersion,
                    supported = SUPPORTED_BUILD_SCHEMA_VERSION,
                ),
            )
        }
        val game = readIdentity(reader, ShareCodeField.GAME) ?: return identityFailure(ShareCodeField.GAME)
        val clientBuild = readIdentity(reader, ShareCodeField.CLIENT_BUILD)
            ?: return identityFailure(ShareCodeField.CLIENT_BUILD)
        val classToken = readIdentity(reader, ShareCodeField.CLASS) ?: return identityFailure(ShareCodeField.CLASS)
        val treeId = reader.varint()
        idViolation(ShareCodeField.TREE_ID, treeId)?.let { return decodeFailure(it) }

        val count = reader.varint()
        if (count > MAX_ALLOCATIONS) {
            return decodeFailure(ShareCodeViolation.TooManyAllocations(count = count, max = MAX_ALLOCATIONS))
        }
        val allocations = ArrayList<NodeAllocation>(count.toInt())
        val entries = HashSet<Int>(count.toInt())
        var previousNode = 0L
        var previousOffset = 0L
        repeat(count.toInt()) { index ->
            val nodeDelta = reader.varint()
            if (nodeDelta == 0L) {
                return decodeFailure(
                    if (index == 0) ShareCodeViolation.NumberOutOfRange(ShareCodeField.NODE_ID, 0)
                    else ShareCodeViolation.DuplicateNode(previousNode.toInt()),
                )
            }
            val nodeId = previousNode + nodeDelta
            idViolation(ShareCodeField.NODE_ID, nodeId)?.let { return decodeFailure(it) }

            val head = reader.varint()
            val offset = previousOffset + unzigzag(head ushr RANK_BITS)
            val entryId = nodeId + offset
            idViolation(ShareCodeField.ENTRY_ID, entryId)?.let { return decodeFailure(it) }

            val inlineRank = (head and RANK_INLINE_MAX.toLong()).toInt()
            val rank = if (inlineRank != 0) inlineRank.toLong() else RANK_ESCAPE_BASE + reader.varint()
            rankViolation(rank)?.let { return decodeFailure(it) }
            if (!entries.add(entryId.toInt())) {
                return decodeFailure(ShareCodeViolation.DuplicateEntry(entryId.toInt()))
            }

            allocations += NodeAllocation(nodeId = nodeId.toInt(), entryId = entryId.toInt(), rank = rank.toInt())
            previousNode = nodeId
            previousOffset = offset
        }
        if (reader.remaining > 0) {
            return decodeFailure(ShareCodeDecodeError.TrailingData(reader.remaining))
        }
        return ShareCodeDecoding.Decoded(
            BuildFile(
                schemaVersion = schemaVersion.toInt(),
                game = GameId(game),
                clientBuild = clientBuild,
                classToken = ClassToken(classToken),
                treeId = treeId.toInt(),
                allocations = allocations,
            ),
        )
    }

    /** The identity string, or `null` when its length or characters violate the format. */
    private fun readIdentity(reader: ShareCodeReader, field: ShareCodeField): String? {
        val length = reader.varint()
        if (length < 1 || length > MAX_IDENTITY_LENGTH) return null
        val bytes = reader.bytes(length.toInt())
        val value = CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
        return value.takeIf { identityViolation(field, it) == null }
    }

    private fun identityFailure(field: ShareCodeField): ShareCodeDecoding =
        decodeFailure(ShareCodeViolation.InvalidIdentity(field))

    private fun identityViolation(field: ShareCodeField, value: String): ShareCodeViolation? {
        val valid = value.length in 1..MAX_IDENTITY_LENGTH &&
            value.all(::isIdentityChar) &&
            value[0].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        return if (valid) null else ShareCodeViolation.InvalidIdentity(field)
    }

    private fun isIdentityChar(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '.' || char == '_' || char == '-'

    private fun idViolation(field: ShareCodeField, value: Long): ShareCodeViolation? =
        if (value in 1L..Int.MAX_VALUE.toLong()) null else ShareCodeViolation.NumberOutOfRange(field, value)

    private fun rankViolation(rank: Long): ShareCodeViolation? =
        if (rank in 1L..MAX_RANK.toLong()) null else ShareCodeViolation.NumberOutOfRange(ShareCodeField.RANK, rank)

    private fun encodeFailure(error: ShareCodeEncodeError): ShareCodeEncoding = ShareCodeEncoding.Failed(error)

    private fun decodeFailure(error: ShareCodeDecodeError): ShareCodeDecoding = ShareCodeDecoding.Failed(error)
}
