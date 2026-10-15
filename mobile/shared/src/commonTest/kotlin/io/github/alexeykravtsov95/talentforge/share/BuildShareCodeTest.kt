package io.github.alexeykravtsov95.talentforge.share

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.allocation
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.build
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.decodeError
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.decoded
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.encodeError
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.encoded
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.header
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.payload
import io.github.alexeykravtsov95.talentforge.share.ShareCodeTestSupport.seal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `talentforge-share.v1` codec (common: runs on JVM and on the iOS simulator).
 *
 * The golden vectors were produced by an independent implementation of the
 * docs/build-sharing.md specification (Python, zlib.crc32), not by this
 * codec: identical text on every target is the cross-platform guarantee.
 */
class BuildShareCodeTest {

    private val warriorPartial = build(
        clientBuild = "1.60.1.70009",
        allocations = listOf(
            NodeAllocation(105954, 130684, 3),
            NodeAllocation(105956, 130686, 2),
            NodeAllocation(105957, 130687, 2),
            NodeAllocation(105958, 130688, 3),
        ),
    )

    private val rankEscape = build(
        clientBuild = "1.60.1.70338",
        classToken = "MAGE",
        treeId = 1112,
        allocations = listOf(NodeAllocation(105762, 130491, 5), NodeAllocation(105800, 105799, 200)),
    )

    // ------------------------------------------------------------------ golden vectors

    @Test
    fun goldenVectorsAreByteExactOnEveryPlatform() {
        val vectors = mapOf(
            build() to GOLDEN_EMPTY,
            warriorPartial to GOLDEN_WARRIOR_PARTIAL,
            rankEscape to GOLDEN_RANK_ESCAPE,
        )
        for ((build, code) in vectors) {
            assertEquals(code, encoded(build), "encode $build")
            assertEquals(build, decoded(code), "decode $code")
        }
    }

    // ------------------------------------------------------------------ round trip / determinism

    @Test
    fun emptyAndPartialBuildsRoundTripExactly() {
        for (build in listOf(build(), warriorPartial, rankEscape)) {
            val code = encoded(build)
            assertTrue(code.startsWith("TF1"), code)
            assertTrue(code.all { it in ShareCodeBase32.ALPHABET }, code)
            assertEquals(build, decoded(code))
        }
    }

    @Test
    fun theSameAllocationAlwaysProducesTheSameCode() {
        val shuffled = warriorPartial.copy(allocations = warriorPartial.allocations.shuffled(Random(7)).reversed())
        assertEquals(encoded(warriorPartial), encoded(warriorPartial))
        assertEquals(encoded(warriorPartial), encoded(shuffled))
        // decoding yields the canonical (ascending node id) order
        assertEquals(warriorPartial.allocations, decoded(encoded(shuffled)).allocations)
    }

    @Test
    fun theEncoderNeverModifiesItsInput() {
        val allocations = mutableListOf(
            NodeAllocation(105958, 130688, 3),
            NodeAllocation(105954, 130684, 3),
            NodeAllocation(105957, 130687, 2),
        )
        val snapshot = allocations.toList()
        val build = warriorPartial.copy(allocations = allocations)
        encoded(build)
        assertEquals(snapshot, allocations)
        assertEquals(snapshot, build.allocations)
    }

    @Test
    fun everyIdentityFieldIsPreservedAndDistinguishesCodes() {
        val base = warriorPartial
        val variants = listOf(
            base.copy(clientBuild = "1.60.1.70374"),
            base.copy(classToken = ClassToken("warrior")),
            base.copy(game = GameId("forever-ptr")),
            base.copy(treeId = 1118),
            base.copy(allocations = base.allocations.dropLast(1)),
            base.copy(allocations = base.allocations.map { if (it.nodeId == 105958) it.copy(rank = 2) else it }),
            base.copy(allocations = base.allocations.map { if (it.nodeId == 105958) it.copy(entryId = 130689) else it }),
        )
        val codes = (variants + base).map { encoded(it) }
        assertEquals(codes.size, codes.toSet().size, "every identity/allocation difference changes the code")
        for (variant in variants) assertEquals(variant, decoded(encoded(variant)))
    }

    @Test
    fun ranksAboveTheInlineRangeUseTheEscapeAndRoundTrip() {
        for (rank in listOf(1, 7, 8, 9, 127, 200, BuildShareCode.MAX_RANK)) {
            val build = build(allocations = listOf(NodeAllocation(105926, 130656, rank)))
            assertEquals(build, decoded(encoded(build)), "rank $rank")
        }
    }

    @Test
    fun negativeAndExtremeEntryOffsetsRoundTrip() {
        val build = build(
            allocations = listOf(
                NodeAllocation(1, Int.MAX_VALUE, 1),
                NodeAllocation(2, 1, 2),
                NodeAllocation(Int.MAX_VALUE - 1, 3, 3),
                NodeAllocation(Int.MAX_VALUE, Int.MAX_VALUE - 1, 4),
            ),
            treeId = Int.MAX_VALUE,
        )
        assertEquals(build, decoded(encoded(build)))
    }

    @Test
    fun surroundingClipboardWhitespaceIsIgnoredButInnerWhitespaceIsNot() {
        val code = encoded(warriorPartial)
        assertEquals(warriorPartial, decoded("  \n\t$code\r\n "))
        val split = code.substring(0, 10) + " " + code.substring(10)
        assertEquals(ShareCodeDecodeError.InvalidCharacter(10, ' '), decodeError(split))
    }

    // ------------------------------------------------------------------ envelope rejection

    @Test
    fun emptyForeignAndUnknownVersionInputsAreRejectedTyped() {
        val body = encoded(warriorPartial).drop(3)
        assertEquals(ShareCodeDecodeError.Empty, decodeError(""))
        assertEquals(ShareCodeDecodeError.Empty, decodeError(" \n\t "))
        assertEquals(ShareCodeDecodeError.NotAShareCode, decodeError("hello"))
        assertEquals(ShareCodeDecodeError.NotAShareCode, decodeError("https://example.invalid/b/TF1ABC"))
        assertEquals(ShareCodeDecodeError.NotAShareCode, decodeError(encoded(warriorPartial).lowercase()))
        assertEquals(ShareCodeDecodeError.NotAShareCode, decodeError("TF-$body"))
        assertEquals(ShareCodeDecodeError.Truncated, decodeError("TF"))
        assertEquals(ShareCodeDecodeError.Truncated, decodeError("TF1"))
        assertEquals(ShareCodeDecodeError.UnsupportedFormatVersion('2'), decodeError("TF2$body"))
        assertEquals(ShareCodeDecodeError.UnsupportedFormatVersion('0'), decodeError("TF0ABC"))
        assertEquals(ShareCodeDecodeError.UnsupportedFormatVersion('Z'), decodeError("TFZ"))
    }

    @Test
    fun charactersOutsideTheAlphabetAreRejectedWithTheirPosition() {
        val code = encoded(warriorPartial)
        val position = 3 + code.length / 2
        for (bad in listOf('I', 'L', 'O', 'U', 'a', 'z', '-', '_', '=', '+', '/', '!', 'é', 'Ж', ' ', '​')) {
            val corrupted = code.substring(0, position) + bad + code.substring(position + 1)
            assertEquals(ShareCodeDecodeError.InvalidCharacter(position, bad), decodeError(corrupted), "char $bad")
        }
    }

    @Test
    fun excessivelyLongInputIsRejectedBeforeDecoding() {
        val atLimit = "TF1" + "0".repeat(BuildShareCode.MAX_CODE_LENGTH - 3)
        assertTrue(decodeError(atLimit) !is ShareCodeDecodeError.TooLong)
        assertEquals(
            ShareCodeDecodeError.TooLong(BuildShareCode.MAX_CODE_LENGTH + 1, BuildShareCode.MAX_CODE_LENGTH),
            decodeError(atLimit + "0"),
        )
        val huge = "TF1" + "Z".repeat(1_000_000)
        assertEquals(ShareCodeDecodeError.TooLong(huge.length, BuildShareCode.MAX_INPUT_LENGTH), decodeError(huge))
        // surrounding whitespace counts towards the raw-input cap, not towards the code length
        val padded = " ".repeat(BuildShareCode.MAX_INPUT_LENGTH) + encoded(warriorPartial)
        assertEquals(ShareCodeDecodeError.TooLong(padded.length, BuildShareCode.MAX_INPUT_LENGTH), decodeError(padded))
        val tolerated = " ".repeat(1000) + encoded(warriorPartial) + "\n".repeat(1000)
        assertEquals(warriorPartial, decoded(tolerated))
    }

    @Test
    fun nonCanonicalBase32IsAnInvalidEncoding() {
        val body = encoded(warriorPartial).drop(3)
        // a body length no byte count can produce (8n + 1, 3 or 6 characters)
        val impossible = (1..8).map { body + "0".repeat(it) }.first { it.length % 8 in setOf(1, 3, 6) }
        assertEquals(ShareCodeDecodeError.InvalidEncoding, decodeError("TF1$impossible"))
        // non-zero padding bits in the last character (a 1-byte body has 2 padding bits)
        val oneByte = ShareCodeBase32.encode(byteArrayOf(0x55))
        val tampered = oneByte.dropLast(1) + ShareCodeBase32.ALPHABET[ShareCodeBase32.valueOf(oneByte.last()) xor 1]
        assertEquals(ShareCodeDecodeError.InvalidEncoding, decodeError("TF1$tampered"))
        assertEquals(null, ShareCodeBase32.decode(tampered))
    }

    @Test
    fun everySingleCharacterCorruptionIsDetected() {
        val code = encoded(warriorPartial)
        for (position in 3 until code.length) {
            for (shift in listOf(1, 13, 31)) {
                val replacement = ShareCodeBase32.ALPHABET[(ShareCodeBase32.valueOf(code[position]) + shift) % 32]
                val corrupted = code.substring(0, position) + replacement + code.substring(position + 1)
                val error = decodeError(corrupted)
                assertTrue(
                    error is ShareCodeDecodeError.ChecksumMismatch ||
                        (position == code.length - 1 && error == ShareCodeDecodeError.InvalidEncoding),
                    "position $position → $error",
                )
            }
        }
    }

    @Test
    fun aChecksumComputedOverAnotherPrefixIsAMismatch() {
        val payload = payload { header(count = 0) }
        assertEquals(build(), decoded(seal(payload)))
        val foreignSeal = "TF1" + seal(payload, prefix = "TF9").drop(3)
        val error = assertIs<ShareCodeDecodeError.ChecksumMismatch>(decodeError(foreignSeal))
        assertNotEquals(error.stored, error.computed)
    }

    @Test
    fun truncatedCodesNeverDecode() {
        val code = encoded(warriorPartial)
        for (length in 0 until code.length) {
            assertIs<ShareCodeDecoding.Failed>(BuildShareCode.decode(code.substring(0, length)), "length $length")
        }
        // a correctly sealed payload that stops in the middle of the allocation list
        val cut = payload { header(count = 2); allocation(105954, 24730, 3) }
        assertEquals(ShareCodeDecodeError.Truncated, decodeError(seal(cut)))
        // nothing but a checksum
        assertEquals(ShareCodeDecodeError.Truncated, decodeError("TF1" + ShareCodeBase32.encode(ByteArray(4))))
    }

    // ------------------------------------------------------------------ payload rejection (valid checksum)

    @Test
    fun unsupportedBuildSchemaVersionsAreRejected() {
        assertEquals(
            ShareCodeViolation.UnsupportedBuildSchemaVersion(actual = 2, supported = 1),
            decodeError(seal(payload { header(count = 0, schemaVersion = 2) })),
        )
        assertEquals(
            ShareCodeViolation.UnsupportedBuildSchemaVersion(actual = 0, supported = 1),
            decodeError(seal(payload { header(count = 0, schemaVersion = 0) })),
        )
    }

    @Test
    fun malformedIdentityFieldsAreRejected() {
        assertEquals(
            ShareCodeViolation.InvalidIdentity(ShareCodeField.GAME),
            decodeError(seal(payload { header(count = 0, game = "") })),
        )
        assertEquals(
            ShareCodeViolation.InvalidIdentity(ShareCodeField.CLIENT_BUILD),
            decodeError(seal(payload { header(count = 0, clientBuild = "1.60 1") })),
        )
        assertEquals(
            ShareCodeViolation.InvalidIdentity(ShareCodeField.CLASS),
            decodeError(seal(payload { header(count = 0, classToken = "W".repeat(33)) })),
        )
        val nonAscii = payload {
            varint(1); ascii("forever"); ascii("1.60.1.70374")
            varint(2); byte(0xC3); byte(0x9F)
            varint(1117); varint(0)
        }
        assertEquals(ShareCodeViolation.InvalidIdentity(ShareCodeField.CLASS), decodeError(seal(nonAscii)))
        // identity strings can never become `.`/`..` segments of the dataset asset path
        for (traversal in listOf(".", "..", ".hidden", "-1.60", "_x")) {
            assertEquals(
                ShareCodeViolation.InvalidIdentity(ShareCodeField.CLIENT_BUILD),
                decodeError(seal(payload { header(count = 0, clientBuild = traversal) })),
                traversal,
            )
            assertEquals(
                ShareCodeViolation.InvalidIdentity(ShareCodeField.GAME),
                encodeError(build(game = traversal)),
                traversal,
            )
        }
        // a declared length beyond the payload
        val short = payload { varint(1); varint(20); ascii("forever") }
        assertEquals(ShareCodeDecodeError.Truncated, decodeError(seal(short)))
    }

    @Test
    fun invalidNumbersAreRejected() {
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.TREE_ID, 0),
            decodeError(seal(payload { header(count = 0, treeId = 0) })),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.TREE_ID, Int.MAX_VALUE + 1L),
            decodeError(seal(payload { header(count = 0, treeId = Int.MAX_VALUE + 1L) })),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.NODE_ID, 0),
            decodeError(seal(payload { header(count = 1); allocation(0, 1, 1) })),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.NODE_ID, Int.MAX_VALUE + 1L),
            decodeError(seal(payload { header(count = 1); allocation(Int.MAX_VALUE + 1L, 1, 1) })),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.ENTRY_ID, 0),
            decodeError(seal(payload { header(count = 1); allocation(105926, -105926, 1) })),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.RANK, 256),
            decodeError(seal(payload { header(count = 1); allocation(105926, 24730, 256) })),
        )
        assertEquals(
            ShareCodeViolation.TooManyAllocations(count = 129, max = BuildShareCode.MAX_ALLOCATIONS),
            decodeError(seal(payload { header(count = 129) })),
        )
    }

    @Test
    fun nonMinimalAndOversizedVarintsAreMalformed() {
        // schemaVersion 1 written as 0x81 0x00 (over-long)
        val overLong = payload { byte(0x81); byte(0x00) }
        assertEquals(ShareCodeDecodeError.MalformedNumber, decodeError(seal(overLong)))
        // a nine-byte varint in the first allocation
        val oversized = payload { header(count = 1); repeat(8) { byte(0xFF) }; byte(0x01) }
        assertEquals(ShareCodeDecodeError.MalformedNumber, decodeError(seal(oversized)))
    }

    @Test
    fun duplicateNodesAndEntriesAreRejected() {
        val duplicateNode = payload { header(count = 2); allocation(105954, 24730, 3); allocation(0, 0, 1) }
        assertEquals(ShareCodeViolation.DuplicateNode(105954), decodeError(seal(duplicateNode)))
        // node 105955 reuses entry 130684 (offset delta -1)
        val duplicateEntry = payload { header(count = 2); allocation(105954, 24730, 3); allocation(1, -1, 1) }
        assertEquals(ShareCodeViolation.DuplicateEntry(130684), decodeError(seal(duplicateEntry)))
    }

    @Test
    fun trailingBytesAreRejected() {
        val trailing = payload { header(count = 0); byte(0) }
        assertEquals(ShareCodeDecodeError.TrailingData(1), decodeError(seal(trailing)))
    }

    // ------------------------------------------------------------------ encoder rejection

    @Test
    fun theEncoderRefusesInvalidBuildsInsteadOfNormalizingThem() {
        assertEquals(
            ShareCodeViolation.UnsupportedBuildSchemaVersion(actual = 2, supported = 1),
            encodeError(build(schemaVersion = 2)),
        )
        assertEquals(
            ShareCodeViolation.DuplicateNode(105954),
            encodeError(build(allocations = listOf(NodeAllocation(105954, 130684, 3), NodeAllocation(105954, 130684, 1)))),
        )
        assertEquals(
            ShareCodeViolation.DuplicateEntry(130684),
            encodeError(build(allocations = listOf(NodeAllocation(105954, 130684, 3), NodeAllocation(105955, 130684, 1)))),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.RANK, 0),
            encodeError(build(allocations = listOf(NodeAllocation(105954, 130684, 0)))),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.RANK, 256),
            encodeError(build(allocations = listOf(NodeAllocation(105954, 130684, 256)))),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.NODE_ID, -5),
            encodeError(build(allocations = listOf(NodeAllocation(-5, 130684, 1)))),
        )
        assertEquals(
            ShareCodeViolation.NumberOutOfRange(ShareCodeField.ENTRY_ID, 0),
            encodeError(build(allocations = listOf(NodeAllocation(105954, 0, 1)))),
        )
        assertEquals(ShareCodeViolation.NumberOutOfRange(ShareCodeField.TREE_ID, 0), encodeError(build(treeId = 0)))
        assertEquals(ShareCodeViolation.InvalidIdentity(ShareCodeField.CLASS), encodeError(build(classToken = "WAR RIOR")))
        assertEquals(
            ShareCodeViolation.InvalidIdentity(ShareCodeField.CLIENT_BUILD),
            encodeError(build(clientBuild = "1.60.1.7037٤")),
        )
        assertEquals(ShareCodeViolation.InvalidIdentity(ShareCodeField.GAME), encodeError(build(game = "f".repeat(33))))
        assertEquals(
            ShareCodeViolation.TooManyAllocations(count = 129, max = BuildShareCode.MAX_ALLOCATIONS),
            encodeError(build(allocations = (1..129).map { NodeAllocation(it, it + 1000, 1) })),
        )
    }

    @Test
    fun theEncoderRefusesCodesLongerThanTheDecoderAccepts() {
        // 128 allocations whose entry offsets swing by ±2^30 and whose ranks need the escape
        val allocations = (1..BuildShareCode.MAX_ALLOCATIONS).map { node ->
            NodeAllocation(node, if (node % 2 == 0) node + (1 shl 30) else node + 1, 200)
        }
        val error = assertIs<ShareCodeEncodeError.CodeTooLong>(encodeError(build(allocations = allocations)))
        assertTrue(error.length > BuildShareCode.MAX_CODE_LENGTH)
        assertEquals(BuildShareCode.MAX_CODE_LENGTH, error.max)
        // every node of a real-sized (54-node) tree allocated still fits a quarter of the limit
        val realistic = build(allocations = (0 until 54).map { NodeAllocation(105926 + it, 130656 + it, 5) })
        assertTrue(encoded(realistic).length <= BuildShareCode.MAX_CODE_LENGTH / 4, encoded(realistic))
    }

    // ------------------------------------------------------------------ robustness

    @Test
    fun randomAndMutatedInputsNeverThrowAndAcceptedCodesAreCanonical() {
        val random = Random(20261015)
        val seeds = listOf(encoded(build()), encoded(warriorPartial), encoded(rankEscape))
        repeat(3000) { iteration ->
            val input = when (iteration % 3) {
                0 -> "TF1" + CharArray(random.nextInt(0, 80)) { ShareCodeBase32.ALPHABET[random.nextInt(32)] }
                    .concatToString()
                1 -> CharArray(random.nextInt(0, 40)) { random.nextInt(0, 0x500).toChar() }.concatToString()
                else -> seeds[random.nextInt(seeds.size)].toCharArray().also { chars ->
                    repeat(random.nextInt(1, 4)) {
                        chars[random.nextInt(3, chars.size)] = ShareCodeBase32.ALPHABET[random.nextInt(32)]
                    }
                }.concatToString()
            }
            val result = BuildShareCode.decode(input)
            if (result is ShareCodeDecoding.Decoded) {
                // one build ↔ one code: anything accepted re-encodes to exactly itself
                assertEquals(input, encoded(result.build), "accepted input must be canonical")
            }
        }
    }

    @Test
    fun decodingInvertsEncodingForRandomStructurallyValidBuilds() {
        val random = Random(42)
        repeat(300) {
            val nodes = List(random.nextInt(0, 60)) { random.nextInt(1, 200_000) }.distinct().sorted()
            val allocations = nodes.mapIndexed { index, node ->
                NodeAllocation(node, 300_000 + index * random.nextInt(1, 50) + index, random.nextInt(1, 10))
            }.distinctBy { it.entryId }
            val build = build(allocations = allocations)
            val code = encoded(build)
            assertEquals(build, decoded(code))
            assertEquals(code, encoded(decoded(code)))
        }
    }

    private companion object {
        const val GOLDEN_EMPTY = "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6WT0ENT1A994JKTJVM400ZP1AGF0"
        const val GOLDEN_WARRIOR_PARTIAL = "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1G60WGENT1A994JKTJVM409RNV0THS6602080G4083SRW7T38"
        const val GOLDEN_RANK_ESCAPE = "TF1043PCVVJCNV6AWGC64Q3CC1E64Q3EC1K6CW08KA18X2XG202MAX0D5CK30K9H4RRR00XR58858"
    }
}
