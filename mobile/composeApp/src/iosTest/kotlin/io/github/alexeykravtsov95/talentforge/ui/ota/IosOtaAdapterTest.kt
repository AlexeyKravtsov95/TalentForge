package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.ActivationPolicy
import io.github.alexeykravtsov95.talentforge.data.ota.CanonicalBase64
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogVerifier
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaKeyring
import io.github.alexeykravtsov95.talentforge.data.ota.OtaResult
import io.github.alexeykravtsov95.talentforge.data.ota.SeenCatalog
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSUUID

/**
 * L4 on the iOS simulator: every fixture catalog (all but the 1 MiB + 1 byte one, rejected by
 * size before any signature is read) reaches the Python reference's verdict through
 * `CatalogVerifier` with the Security.framework verifier — the same verdicts the JVM replay
 * reaches with `java.security` — and the Application Support store's atomic operations.
 */
class IosOtaAdapterTest {

    private val keyring = OtaKeyring.of(OtaCatalogFixtureVectors.keys.map { OtaKeyring.Entry(it.keyId, it.algorithm, it.publicKeySpki) })

    @Test
    fun everyFixtureCatalogReachesTheReferenceVerdictWithSecurityFramework() {
        assertEquals(38, OtaCatalogFixtureVectors.cases.size)
        val mismatches = mutableListOf<String>()
        for (case in OtaCatalogFixtureVectors.cases) {
            val verifier = CatalogVerifier(keyring, PlatformSignatureVerifier(), case.game, OtaChannel.entries.single { it.wire == case.channel })
            val seen = case.lastSeenSequence?.let { SeenCatalog(it, case.lastSeenPayloadSha256!!) }
            val result = verifier.verify(CanonicalBase64.decodeOrNull(case.envelopeBase64)!!, UtcInstant.parseOrNull(case.now)!!, seen)
            val actual = when (result) {
                is OtaResult.Err -> "REJECT ${(result.failure as OtaFailure.Contract).code.name}"
                is OtaResult.Ok -> {
                    val (decision, release) = ActivationPolicy.decide(result.value, case.supportedDataLevel, case.bundledProductBuild, case.currentReleaseId)
                    "ACCEPT ${decision.name} ${release.releaseId}"
                }
            }
            val expected = if (case.expectResult == "REJECT") "REJECT ${case.expectCode}" else "ACCEPT ${case.expectDecision} ${case.expectReleaseId}"
            if (actual != expected) mismatches += "${case.id}: expected $expected, got $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun aMalformedKeyOrSignatureIsFalseNeverACrash() {
        val spki = keyring.key("test-primary")!!.spki
        assertFalse(PlatformSignatureVerifier().verifyEcdsaP256Sha256(spki, "m".encodeToByteArray(), ByteArray(8)))
        assertFalse(PlatformSignatureVerifier().verifyEcdsaP256Sha256(ByteArray(91), "m".encodeToByteArray(), ByteArray(70)))
    }

    @Test
    fun theStoreReplacesAtomicallyAndRefusesUnsafePaths() {
        val root = NSTemporaryDirectory() + "tf-ota-" + NSUUID().UUIDString
        val store = PlatformOtaFileStore(root)
        store.replaceAtomically("v1/state.json", "one".encodeToByteArray())
        store.replaceAtomically("v1/state.json", "two".encodeToByteArray())
        assertEquals("two", store.readOrNull("v1/state.json")!!.decodeToString())
        assertFalse(store.exists("v1/state.json.tmp"))
        store.writeSynced("v1/staging/x/a/b.json", byteArrayOf(1))
        store.rename("v1/staging/x", "v1/releases/x")
        assertTrue(store.isDirectory("v1/releases/x"))
        assertNull(store.readOrNull("v1/staging/x/a/b.json"))
        store.writeSynced("v1/staging/y/f", byteArrayOf(2))
        assertFailsWith<OtaIoException> { store.rename("v1/staging/y", "v1/releases/x") }
        assertEquals(listOf("releases", "staging", "state.json"), store.list("v1").map { it.name })
        for (bad in listOf("../escape", "/abs", "v1//x", "v1/./x")) assertFailsWith<OtaIoException>(bad) { store.readOrNull(bad) }
        store.delete("v1")
        assertFalse(store.exists("v1"))
        assertTrue(store.freeSpaceBytes() > 0)
    }

    @Test
    fun theFetcherRefusesAnythingButHttps() = kotlinx.coroutines.test.runTest {
        val refused = assertIs<io.github.alexeykravtsov95.talentforge.data.ota.FetchResult.NetworkError>(
            PlatformBlobFetcher("TalentForge/test").fetch("http://127.0.0.1:9/x", 10),
        )
        assertFalse(refused.retryable, "a refused URL is never repeated")
    }

    @Test
    fun aRejectedCertificateIsNotRetryableButALostConnectionIs() {
        for (code in listOf(-1201L, -1202L, -1203L, -1204L, -1205L, -1206L, -1022L, -1000L, -1002L)) {
            assertFalse(isRetryableUrlError(NSURLErrorDomain, code), "NSURLErrorDomain $code")
        }
        // timed out, cannot connect, connection lost, not connected, DNS, secure connection failed (resets included)
        for (code in listOf(-1001L, -1004L, -1005L, -1009L, -1003L, -1200L)) {
            assertTrue(isRetryableUrlError(NSURLErrorDomain, code), "NSURLErrorDomain $code")
        }
        assertTrue(isRetryableUrlError("kCFErrorDomainCFNetwork", -1202L), "only NSURLErrorDomain codes are classified")
        assertTrue(isRetryableUrlError(null, -1202L))
    }
}
