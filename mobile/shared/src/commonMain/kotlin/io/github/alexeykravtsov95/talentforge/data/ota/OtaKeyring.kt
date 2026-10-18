package io.github.alexeykravtsov95.talentforge.data.ota

/**
 * Port: ECDSA over NIST P-256 with SHA-256 (`ecdsa-p256-sha256`, contract §3), implemented by
 * the platform (`java.security` on Android and the JVM, Security.framework on iOS). The math
 * is the only thing delegated: which keys are trusted, what the signed message is and how a
 * result is used is decided in [CatalogVerifier].
 *
 * Implementations return `false` — never throw — for a signature that does not verify. The
 * key is always a validated 91-byte P-256 SubjectPublicKeyInfo ([P256.isSubjectPublicKeyInfo])
 * and the signature always strict ASN.1 DER ([P256.isStrictDerSignature]): both are checked
 * here first, so the platforms never disagree about malformed input.
 */
fun interface SignatureVerifier {
    fun verifyEcdsaP256Sha256(publicKeySpki: ByteArray, message: ByteArray, derSignature: ByteArray): Boolean
}

/** One pinned public key (contract §8). */
class OtaPublicKey(val keyId: String, spki: ByteArray) {
    private val bytes = spki.copyOf()

    /** The 91-byte DER SubjectPublicKeyInfo. */
    val spki: ByteArray get() = bytes.copyOf()

    /** The 65-byte X9.63 uncompressed point — what iOS `SecKeyCreateWithData` expects. */
    val x963Point: ByteArray get() = bytes.copyOfRange(bytes.size - 65, bytes.size)
}

/** Why a keyring entry cannot be pinned. */
enum class KeyringProblem { BAD_KEY_ID, UNSUPPORTED_ALGORITHM, NOT_BASE64, NOT_A_P256_KEY, DUPLICATE_KEY_ID }

class InvalidKeyringException(val keyId: String, val problem: KeyringProblem) :
    IllegalArgumentException("keyring entry $keyId: $problem")

/**
 * The keys an app build pins for its channel (contract §8). Built only from compile-time
 * configuration (or, in tests, the fixture keyring) — never from a downloaded document.
 */
class OtaKeyring private constructor(private val byId: Map<String, OtaPublicKey>) {

    val keyIds: Set<String> get() = byId.keys

    fun key(keyId: String): OtaPublicKey? = byId[keyId]

    val isEmpty: Boolean get() = byId.isEmpty()

    /** `test-` ids are reserved for fixtures; an app keyring must never contain one (§8). */
    val containsTestKey: Boolean get() = byId.keys.any { it.startsWith(TEST_KEY_PREFIX) }

    data class Entry(val keyId: String, val algorithm: String, val publicKeySpki: String)

    companion object {
        const val ALGORITHM: String = "ecdsa-p256-sha256"
        const val TEST_KEY_PREFIX: String = "test-"

        val EMPTY: OtaKeyring = OtaKeyring(emptyMap())

        /** Validates every entry like the reference's `load_keyring`; throws [InvalidKeyringException]. */
        fun of(entries: List<Entry>): OtaKeyring {
            val keys = LinkedHashMap<String, OtaPublicKey>()
            for (entry in entries) {
                if (!OtaContract.KEY_ID.matches(entry.keyId)) throw InvalidKeyringException(entry.keyId, KeyringProblem.BAD_KEY_ID)
                if (entry.algorithm != ALGORITHM) {
                    throw InvalidKeyringException(entry.keyId, KeyringProblem.UNSUPPORTED_ALGORITHM)
                }
                val spki = CanonicalBase64.decodeOrNull(entry.publicKeySpki)
                    ?: throw InvalidKeyringException(entry.keyId, KeyringProblem.NOT_BASE64)
                if (!P256.isSubjectPublicKeyInfo(spki)) throw InvalidKeyringException(entry.keyId, KeyringProblem.NOT_A_P256_KEY)
                if (entry.keyId in keys) throw InvalidKeyringException(entry.keyId, KeyringProblem.DUPLICATE_KEY_ID)
                keys[entry.keyId] = OtaPublicKey(entry.keyId, spki)
            }
            return OtaKeyring(keys)
        }
    }
}

/** P-256 encodings shared by every platform verifier. */
object P256 {
    /** DER prefix of an id-ecPublicKey / prime256v1 SubjectPublicKeyInfo with a 65-byte BIT STRING. */
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    )

    const val SPKI_LENGTH: Int = 91

    /** Exactly the 91-byte SPKI of an uncompressed P-256 point (`04 || X || Y`). */
    fun isSubjectPublicKeyInfo(spki: ByteArray): Boolean {
        if (spki.size != SPKI_LENGTH) return false
        for (i in SPKI_PREFIX.indices) if (spki[i] != SPKI_PREFIX[i]) return false
        return spki[SPKI_PREFIX.size] == 0x04.toByte()
    }

    /**
     * `SEQUENCE { r INTEGER, s INTEGER }` in strict DER: definite short-form lengths, minimal
     * positive integers of 1..33 bytes, nothing after the sequence. OpenSSL (the reference's
     * backend) rejects every other encoding; checking it here keeps Android, iOS and the JVM
     * from disagreeing about a lenient BER spelling.
     */
    fun isStrictDerSignature(der: ByteArray): Boolean {
        if (der.size < 8 || der.size > 72) return false
        if (der[0] != 0x30.toByte()) return false
        val sequenceLength = der[1].toInt() and 0xFF
        if (sequenceLength >= 0x80 || sequenceLength != der.size - 2) return false
        var i = 2
        repeat(2) {
            if (i + 2 > der.size || der[i] != 0x02.toByte()) return false
            val length = der[i + 1].toInt() and 0xFF
            if (length !in 1..33 || i + 2 + length > der.size) return false
            val first = der[i + 2].toInt() and 0xFF
            if (first >= 0x80) return false // negative
            if (first == 0x00 && (length == 1 || (der[i + 3].toInt() and 0xFF) < 0x80)) return false // zero or non-minimal
            i += 2 + length
        }
        return i == der.size
    }
}

/**
 * DSSE v1 pre-authentication encoding — the exact signed message (contract §3):
 * `"DSSEv1" SP LEN(type) SP type SP LEN(body) SP body`.
 */
fun preAuthenticationEncoding(payloadType: String, payload: ByteArray): ByteArray {
    val type = payloadType.encodeToByteArray()
    val header = "DSSEv1 ${type.size} ".encodeToByteArray() + type + " ${payload.size} ".encodeToByteArray()
    return header + payload
}
