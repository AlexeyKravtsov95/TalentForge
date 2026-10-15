package io.github.alexeykravtsov95.talentforge.share

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.allocation.NodeAllocation
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Test-only helpers: hand-crafted payloads sealed with a CORRECT checksum, so
 * the decoder's payload rules can be exercised past the integrity check.
 */
internal object ShareCodeTestSupport {

    fun build(
        clientBuild: String = "1.60.1.70374",
        classToken: String = "WARRIOR",
        treeId: Int = 1117,
        allocations: List<NodeAllocation> = emptyList(),
        game: String = "forever",
        schemaVersion: Int = 1,
    ): BuildFile = BuildFile(
        schemaVersion = schemaVersion,
        game = GameId(game),
        clientBuild = clientBuild,
        classToken = ClassToken(classToken),
        treeId = treeId,
        allocations = allocations,
    )

    fun encoded(build: BuildFile): String =
        assertIs<ShareCodeEncoding.Encoded>(BuildShareCode.encode(build), "encode $build").code

    fun decoded(code: String): BuildFile =
        when (val decoding = BuildShareCode.decode(code)) {
            is ShareCodeDecoding.Decoded -> decoding.build
            is ShareCodeDecoding.Failed -> fail("decode failed with ${decoding.error} for $code")
        }

    fun decodeError(code: String): ShareCodeDecodeError =
        assertIs<ShareCodeDecoding.Failed>(BuildShareCode.decode(code), "expected a failure for $code").error

    fun encodeError(build: BuildFile): ShareCodeEncodeError =
        assertIs<ShareCodeEncoding.Failed>(BuildShareCode.encode(build), "expected a failure for $build").error

    /** `prefix ‖ base32(payload ‖ crc32be(prefix ‖ payload))` — a code with a valid checksum. */
    fun seal(payload: ByteArray, prefix: String = BuildShareCode.PREFIX): String {
        val crc = ShareCodeCrc32.of(prefix.encodeToByteArray() + payload)
        val checksum = ByteArray(4) { index -> (crc ushr (8 * (3 - index))).toByte() }
        return prefix + ShareCodeBase32.encode(payload + checksum)
    }

    fun payload(block: ShareCodeWriter.() -> Unit): ByteArray = ShareCodeWriter().apply(block).toByteArray()

    /** Header of a forever / 1.60.1.70374 / WARRIOR / tree 1117 code, then the allocation count. */
    fun ShareCodeWriter.header(
        count: Long,
        schemaVersion: Long = 1,
        game: String = "forever",
        clientBuild: String = "1.60.1.70374",
        classToken: String = "WARRIOR",
        treeId: Long = 1117,
    ) {
        varint(schemaVersion)
        ascii(game)
        ascii(clientBuild)
        ascii(classToken)
        varint(treeId)
        varint(count)
    }

    /** One allocation in wire form (deltas relative to the previous allocation). */
    fun ShareCodeWriter.allocation(nodeDelta: Long, offsetDelta: Long, rank: Int) {
        varint(nodeDelta)
        if (rank in 1..7) {
            varint((zigzag(offsetDelta) shl 3) or rank.toLong())
        } else {
            varint(zigzag(offsetDelta) shl 3)
            varint((rank - 8).toLong())
        }
    }
}
