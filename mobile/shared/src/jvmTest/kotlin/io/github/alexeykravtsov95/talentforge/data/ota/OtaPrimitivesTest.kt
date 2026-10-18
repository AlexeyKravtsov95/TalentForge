package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.Sha256
import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaPrimitivesTest {

    @Test
    fun canonicalBase64RoundTripsAndAgreesWithTheJdk() {
        val random = Random(7)
        for (size in 0..70) {
            val bytes = random.nextBytes(size)
            val text = CanonicalBase64.encode(bytes)
            assertEquals(Base64.getEncoder().encodeToString(bytes), text)
            assertContentEquals(bytes, CanonicalBase64.decodeOrNull(text))
        }
    }

    @Test
    fun onlyTheCanonicalSpellingIsAccepted() {
        assertNotNull(CanonicalBase64.decodeOrNull("QQ=="))
        assertNull(CanonicalBase64.decodeOrNull("QR=="), "non-zero padding bits")
        assertContentEquals("AB".encodeToByteArray(), CanonicalBase64.decodeOrNull("QUI="))
        assertNull(CanonicalBase64.decodeOrNull("QUJ="), "non-zero padding bits")
        assertNull(CanonicalBase64.decodeOrNull("QQ"), "missing padding")
        assertNull(CanonicalBase64.decodeOrNull("QQ==="), "extra padding")
        assertNull(CanonicalBase64.decodeOrNull("Q Q=="), "whitespace")
        assertNull(CanonicalBase64.decodeOrNull("QQ==\n"), "line break")
        assertNull(CanonicalBase64.decodeOrNull("Q-_="), "url-safe alphabet")
        assertNull(CanonicalBase64.decodeOrNull("A==="), "padding inside")
        assertNull(CanonicalBase64.decodeOrNull("=AAA"), "padding first")
    }

    @Test
    fun timestampsMustBeZeroOffsetInstants() {
        val z = UtcInstant.parseOrNull("2026-10-18T00:00:00Z")!!
        assertEquals(z, UtcInstant.parseOrNull("2026-10-18T00:00:00+00:00"))
        assertEquals(z, UtcInstant.parseOrNull("2026-10-18T00:00:00-00:00"))
        assertEquals("2026-10-18T00:00:00Z", z.toIsoSeconds())
        assertEquals(java.time.Instant.parse("2026-10-18T00:00:00Z").epochSecond, z.epochSecond)
        assertEquals(500_000, UtcInstant.parseOrNull("2026-10-18T00:00:00.5Z")!!.microsecond)
        assertNull(UtcInstant.parseOrNull("2026-10-18T00:00:00"), "naive timestamp")
        assertNull(UtcInstant.parseOrNull("2026-10-18T02:00:00+02:00"), "non-UTC offset")
        assertNull(UtcInstant.parseOrNull("2026-02-30T00:00:00Z"), "no such day")
        assertNull(UtcInstant.parseOrNull("2026-10-18T24:00:00Z"), "hour 24")
        assertNull(UtcInstant.parseOrNull("2026-10-18T00:00:60Z"), "leap second")
        assertNull(UtcInstant.parseOrNull("2026-10-18 00:00:00Z"), "space separator")
        assertNull(UtcInstant.parseOrNull("2026-10-18T00:00:00.1234567Z"), "more than microseconds")
        assertNotNull(UtcInstant.parseOrNull("2028-02-29T23:59:59Z"))
        assertEquals(
            java.time.Instant.parse("2026-12-02T00:00:00Z").epochSecond,
            UtcInstant.parseOrNull("2026-12-02T00:00:00Z")!!.epochSecond,
        )
        val millis = java.time.Instant.parse("2026-10-18T00:00:00Z").toEpochMilli() + 123
        assertEquals("2026-10-18T00:00:00Z", UtcInstant.ofEpochMillis(millis).toIsoSeconds())
    }

    @Test
    fun clientBuildsAndReleaseIdsCompareNumerically() {
        assertTrue(ClientBuildVersion.parse("1.60.1.70450") > ClientBuildVersion.parse("1.60.1.70374"))
        assertTrue(ClientBuildVersion.parse("1.60.10.0") > ClientBuildVersion.parse("1.60.9.99999"))
        assertNull(ClientBuildVersion.parseOrNull("1.60.1"))
        val ids = listOf("1.60.1.70450-r2", "1.60.1.70374-r10", "1.60.1.70374-r9", "1.60.1.70450-r1").map { ReleaseId.parseOrNull(it)!! }
        assertEquals(listOf("1.60.1.70374-r9", "1.60.1.70374-r10", "1.60.1.70450-r1", "1.60.1.70450-r2"), ids.sorted().map { it.value })
        assertNull(ReleaseId.parseOrNull("1.60.1.70450-r0"))
        assertNull(ReleaseId.parseOrNull("1.60.1.70450-r01"))
        assertNull(ReleaseId.parseOrNull("1.60.1.70450-r1000000"))
    }

    @Test
    fun theFixtureKeyringIsTestOnlyAndValid() {
        val keyring = OtaFixtures.keyring
        assertEquals(setOf("test-primary", "test-backup"), keyring.keyIds)
        assertTrue(keyring.containsTestKey)
        for (id in keyring.keyIds) {
            val key = keyring.key(id)!!
            assertTrue(P256.isSubjectPublicKeyInfo(key.spki))
            assertEquals(65, key.x963Point.size)
            assertEquals(0x04.toByte(), key.x963Point[0])
        }
    }

    @Test
    fun keyringEntriesAreValidatedLikeTheReference() {
        val spki = OtaFixtures.keyring.key("test-primary")!!.spki
        val good = CanonicalBase64.encode(spki)
        assertEquals(KeyringProblem.UNSUPPORTED_ALGORITHM, assertFailsWith<InvalidKeyringException> { OtaKeyring.of(listOf(OtaKeyring.Entry("k-one", "ed25519", good))) }.problem)
        assertEquals(KeyringProblem.NOT_BASE64, assertFailsWith<InvalidKeyringException> { OtaKeyring.of(listOf(OtaKeyring.Entry("k-one", OtaKeyring.ALGORITHM, "$good "))) }.problem)
        assertEquals(KeyringProblem.NOT_A_P256_KEY, assertFailsWith<InvalidKeyringException> { OtaKeyring.of(listOf(OtaKeyring.Entry("k-one", OtaKeyring.ALGORITHM, CanonicalBase64.encode(spki.copyOf(90))))) }.problem)
        assertEquals(KeyringProblem.DUPLICATE_KEY_ID, assertFailsWith<InvalidKeyringException> { OtaKeyring.of(List(2) { OtaKeyring.Entry("k-one", OtaKeyring.ALGORITHM, good) }) }.problem)
        assertEquals(KeyringProblem.BAD_KEY_ID, assertFailsWith<InvalidKeyringException> { OtaKeyring.of(listOf(OtaKeyring.Entry("K", OtaKeyring.ALGORITHM, good))) }.problem)
        assertFalse(OtaKeyring.of(listOf(OtaKeyring.Entry("tf-prod-2026a", OtaKeyring.ALGORITHM, good))).containsTestKey)
    }

    @Test
    fun onlyStrictDerSignaturesReachThePlatform() {
        val der = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)
        assertTrue(P256.isStrictDerSignature(der))
        assertTrue(P256.isStrictDerSignature(byteArrayOf(0x30, 0x07, 0x02, 0x02, 0x00, 0x80.toByte(), 0x02, 0x01, 0x02)))
        assertFalse(P256.isStrictDerSignature(byteArrayOf(0x30, 0x07, 0x02, 0x02, 0x00, 0x01, 0x02, 0x01, 0x02)), "non-minimal integer")
        assertFalse(P256.isStrictDerSignature(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x81.toByte(), 0x02, 0x01, 0x02)), "negative integer")
        assertFalse(P256.isStrictDerSignature(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x02)), "zero")
        assertFalse(P256.isStrictDerSignature(der + 0x00), "trailing byte")
        assertFalse(P256.isStrictDerSignature(byteArrayOf(0x30, 0x81.toByte(), 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)), "long-form length")
        // every fixture signature is strict DER
        for (name in OtaFixtures.root.resolve("catalogs").list()!!.filter { it != "catalog-over-size-limit.json" }) {
            val envelope = runCatching { OtaDocuments.envelope(OtaFixtures.bytes("catalogs/$name")) }.getOrNull() ?: continue
            for (signature in envelope.signatures) {
                assertTrue(P256.isStrictDerSignature(CanonicalBase64.decodeOrNull(signature.sig)!!), "$name/${signature.keyId}")
            }
        }
    }

    @Test
    fun preAuthenticationEncodingFollowsDsse() {
        assertEquals("DSSEv1 13 application/x 5 hello", preAuthenticationEncoding("application/x", "hello".encodeToByteArray()).decodeToString())
        assertEquals("DSSEv1 0  0 ", preAuthenticationEncoding("", ByteArray(0)).decodeToString())
    }

    @Test
    fun streamingSha256MatchesTheJdkForEverySplit() {
        val random = Random(11)
        for (size in listOf(0, 1, 55, 56, 63, 64, 65, 127, 128, 1000, 70_000)) {
            val data = random.nextBytes(size)
            val expected = MessageDigest.getInstance("SHA-256").digest(data)
            assertContentEquals(expected, Sha256().update(data).digest())
            val chunked = Sha256()
            var offset = 0
            while (offset < size) {
                val length = minOf(random.nextInt(1, 200), size - offset)
                chunked.update(data, offset, length)
                offset += length
            }
            assertContentEquals(expected, chunked.digest(), "size $size")
            assertEquals(expected.joinToString("") { "%02x".format(it) }, sha256Hex(data))
        }
    }
}
