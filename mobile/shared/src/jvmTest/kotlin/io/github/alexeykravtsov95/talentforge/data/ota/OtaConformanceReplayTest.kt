package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.ota.OtaFixtures.stringOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * L1: the Kotlin replay of the OTA conformance suite (`docs/ota/fixtures/v1/cases.json`, 64
 * cases). For every case the client must reach the Python reference's verdict — accept with
 * the same activation decision and release, or reject with the same [OtaErrorCode] — with a
 * JVM `java.security` verifier. A summary is written to `build/reports/ota-conformance.txt`.
 */
class OtaConformanceReplayTest {

    private data class Verdict(val result: String, val code: String?, val decision: String?, val releaseId: String?) {
        override fun toString(): String = listOfNotNull(result, code, decision, releaseId).joinToString(" ")
    }

    private fun expected(case: JsonObject): Verdict {
        val expect = case.getValue("expect").jsonObject
        return Verdict(
            result = expect.getValue("result").jsonPrimitive.content,
            code = expect.stringOrNull("code"),
            decision = expect.stringOrNull("decision"),
            releaseId = expect.stringOrNull("releaseId"),
        )
    }

    private fun replayCatalog(case: JsonObject): Verdict {
        val context = case.getValue("context").jsonObject
        val verifier = CatalogVerifier(
            keyring = OtaFixtures.keyring,
            signatures = JvmSignatureVerifier,
            game = context.getValue("game").jsonPrimitive.content,
            channel = OtaChannel.entries.single { it.wire == context.getValue("channel").jsonPrimitive.content },
        )
        val lastSeen = (context["lastSeen"] as? JsonObject)?.let {
            SeenCatalog(it.getValue("sequence").jsonPrimitive.long, it.getValue("payloadSha256").jsonPrimitive.content)
        }
        val now = UtcInstant.parseOrNull(context.getValue("now").jsonPrimitive.content)!!
        return when (val result = verifier.verify(OtaFixtures.bytes(case.getValue("file").jsonPrimitive.content), now, lastSeen)) {
            is OtaResult.Err -> Verdict("REJECT", (result.failure as OtaFailure.Contract).code.name, null, null)
            is OtaResult.Ok -> {
                val (decision, release) = ActivationPolicy.decide(
                    verified = result.value,
                    supportedDataLevel = context.getValue("supportedDataLevel").jsonPrimitive.int,
                    bundledProductBuild = context.getValue("bundledProductBuild").jsonPrimitive.content,
                    currentReleaseId = context.stringOrNull("currentReleaseId"),
                )
                Verdict("ACCEPT", null, decision.name, release.releaseId)
            }
        }
    }

    private fun replayRelease(case: JsonObject): Verdict = runBlocking {
        val context = case.getValue("context").jsonObject
        val entry = OtaFixtures.catalogEntry(case.getValue("catalogEntry").jsonObject)
        val game = context.getValue("game").jsonPrimitive.content
        val manifest = when (
            val result = ReleaseVerifier.verifyManifest(
                OtaFixtures.bytes(case.getValue("file").jsonPrimitive.content),
                entry,
                game,
                context.getValue("supportedDataLevel").jsonPrimitive.int,
            )
        ) {
            is OtaResult.Err -> return@runBlocking Verdict("REJECT", (result.failure as OtaFailure.Contract).code.name, null, null)
            is OtaResult.Ok -> result.value
        }
        val known = when (context.stringOrNull("knownSameBuildManifest")) {
            null -> null
            "repository" -> OtaFixtures.committedDatasetManifest(manifest.game, manifest.clientBuild)
            else -> error("unknown knownSameBuildManifest")
        }
        when (val result = ReleaseVerifier.verifyFiles(manifest, { OtaFixtures.releaseFileBytes(case, it.path) }, known)) {
            is OtaResult.Err -> Verdict("REJECT", (result.failure as OtaFailure.Contract).code.name, null, null)
            is OtaResult.Ok -> Verdict("ACCEPT", null, null, null)
        }
    }

    private fun replay(case: JsonObject): Verdict = when (case.getValue("kind").jsonPrimitive.content) {
        "catalog" -> replayCatalog(case)
        "release" -> replayRelease(case)
        else -> error("unknown case kind")
    }

    @Test
    fun everyCaseReachesTheReferenceVerdict() {
        val cases = OtaFixtures.cases
        assertEquals(64, cases.size, "the suite has 64 cases")
        val report = StringBuilder("OTA conformance replay (Kotlin/JVM, java.security)\n")
        val mismatches = mutableListOf<String>()
        for (case in cases) {
            val id = case.getValue("id").jsonPrimitive.content
            val expected = expected(case)
            val actual = replay(case)
            val ok = actual == expected
            report.append(if (ok) "PASS" else "FAIL").append("  ").append(id.padEnd(44)).append(actual)
            if (!ok) report.append("   (expected ").append(expected).append(')')
            report.append('\n')
            if (!ok) mismatches += "$id: expected $expected, got $actual"
        }
        report.append("${cases.size - mismatches.size}/${cases.size} cases agree with the reference\n")
        File("build/reports").mkdirs()
        File("build/reports/ota-conformance.txt").writeText(report.toString())
        println(report)
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun theSuiteExercisesEveryCodeAndDecision() {
        val codes = OtaFixtures.cases.mapNotNull { it.getValue("expect").jsonObject.stringOrNull("code") }.toSet()
        assertEquals(OtaErrorCode.entries.map { it.name }.toSet(), codes)
        val decisions = OtaFixtures.cases.mapNotNull { it.getValue("expect").jsonObject.stringOrNull("decision") }.toSet()
        assertEquals(ActivationDecision.entries.map { it.name }.toSet(), decisions)
    }

    private fun case(id: String): JsonObject = OtaFixtures.cases.single { it.getValue("id").jsonPrimitive.content == id }

    @Test
    fun everyDuplicateNameCaseIsRejectedWithItsStageCode() {
        val expected = mapOf(
            "catalog-envelope-duplicate-key" to "ENVELOPE_INVALID",
            "catalog-envelope-nested-duplicate-key" to "ENVELOPE_INVALID",
            "catalog-duplicate-json-key" to "PAYLOAD_INVALID",
            "catalog-duplicate-key-same-value" to "PAYLOAD_INVALID",
            "catalog-duplicate-nested-key" to "PAYLOAD_INVALID",
            "catalog-duplicate-escaped-key" to "PAYLOAD_INVALID",
            "release-duplicate-nested-key" to "MANIFEST_INVALID",
            "release-content-duplicate-key" to "CONTENT_INVALID",
        )
        for ((id, code) in expected) {
            assertEquals(Verdict("REJECT", code, null, null), replay(case(id)), id)
        }
    }

    @Test
    fun anOversizedCatalogIsRejectedBeforeAnythingIsParsed() {
        val bytes = OtaFixtures.bytes("catalogs/catalog-over-size-limit.json")
        assertEquals(OtaContract.MAX_CATALOG_BYTES + 1, bytes.size)
        assertEquals(Verdict("REJECT", "CATALOG_TOO_LARGE", null, null), replay(case("catalog-over-size-limit")))
        // even a signature verifier that would throw is never reached
        val verifier = CatalogVerifier(OtaFixtures.keyring, { _, _, _ -> error("must not verify") }, "forever", OtaChannel.PRODUCTION)
        val result = verifier.verify(bytes, UtcInstant.parseOrNull("2026-10-18T12:00:00Z")!!, null)
        assertIs<OtaFailure.CatalogTooLarge>((result as OtaResult.Err).failure)
    }

    @Test
    fun aCatalogOfExactlyOneMebibyteIsVerifiedNormally() {
        val envelope = OtaFixtures.bytes("catalogs/catalog-b-active.json")
        // JSON allows trailing whitespace: pad the signed envelope to exactly the limit
        val exact = envelope + ByteArray(OtaContract.MAX_CATALOG_BYTES - envelope.size) { ' '.code.toByte() }
        assertEquals(OtaContract.MAX_CATALOG_BYTES, exact.size)
        val verifier = CatalogVerifier(OtaFixtures.keyring, JvmSignatureVerifier, "forever", OtaChannel.PRODUCTION)
        val now = UtcInstant.parseOrNull("2026-10-18T12:00:00Z")!!
        assertIs<OtaResult.Ok<VerifiedCatalog>>(verifier.verify(exact, now, null))
        val over = exact + ' '.code.toByte()
        assertIs<OtaFailure.CatalogTooLarge>((verifier.verify(over, now, null) as OtaResult.Err).failure)
    }

    @Test
    fun fixtureSignaturesAreRejectedByAnUntrustedKeyringAndForgedBytes() {
        val now = UtcInstant.parseOrNull("2026-10-18T12:00:00Z")!!
        val bytes = OtaFixtures.bytes("catalogs/catalog-b-active.json")
        val withUntrusted = CatalogVerifier(OtaFixtures.untrustedKeyring, JvmSignatureVerifier, "forever", OtaChannel.PRODUCTION)
        assertIs<OtaFailure.UntrustedSigner>((withUntrusted.verify(bytes, now, null) as OtaResult.Err).failure)
        // a verifier that says "no" to everything turns every accepted case into SIGNATURE_INVALID
        val alwaysNo = CatalogVerifier(OtaFixtures.keyring, { _, _, _ -> false }, "forever", OtaChannel.PRODUCTION)
        assertIs<OtaFailure.SignatureInvalid>((alwaysNo.verify(bytes, now, null) as OtaResult.Err).failure)
    }
}
