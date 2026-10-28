package io.github.alexeykravtsov95.talentforge.ui.ota

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * Staging verification of an OTA candidate before production (M5.OTA.AUTO.4, opt-in), client half:
 * the app as it is **before** the update - the base bundled and the product, the candidate neither
 * bundled nor installed - meets the staging publication of the candidate ([StagedUpdate]).
 *
 * `scripts/ota_staging_verify.py run <out> --base B --candidate C [--revision R]` fixes the commit,
 * builds both releases from it with the real publisher into the stand `<out>/stand`, exports the
 * base's committed data of that commit to `<out>/subject` (the bundle read here) and runs this
 * test with
 *
 * ```
 * -Dtalentforge.ota.candidate=<out>
 * -Dtalentforge.ota.candidate.base=<B> -Dtalentforge.ota.candidate.build=<C>
 * -Dtalentforge.ota.candidate.revision=<40-hex commit>
 * ```
 *
 * The requested pair and commit are checked against the stand before anything runs: a stand of
 * another pair (the historical 70374 → 70450 replay, say) never counts as a check of this one.
 * Each test writes its evidence, the requested pair and commit included, to `<out>/client/<test>.json`.
 */
class OtaCandidateStagingTest {
    private val out: File? = System.getProperty(PROPERTY)?.let(::File)

    @Before
    fun requested() = assumeTrue("candidate staging verification not requested (-D$PROPERTY=<out>)", out != null)

    private fun required(name: String): String = requireNotNull(System.getProperty("$PROPERTY.$name")) { "-D$PROPERTY.$name is required" }

    private val requestedBase: String by lazy { required("base") }
    private val requestedCandidate: String by lazy { required("build") }
    private val requestedRevision: String by lazy { required("revision") }

    private val update: StagedUpdate by lazy {
        val subject = out!!.resolve("subject")
        StagedUpdate(StagingStand(out!!.resolve("stand")), dataRoot = subject).also {
            assertEquals(requestedBase to requestedCandidate, it.base to it.candidate, "the stand publishes the requested pair")
            assertEquals("$requestedBase-r1" to "$requestedCandidate-r1", it.baseId to it.candidateId)
            assertEquals(requestedRevision, it.stand.reportString("commit"), "the stand's releases are built from the requested commit")
            assertTrue(subject.resolve("data/normalized/forever/$requestedBase/manifest.json").isFile, "the base's data of the commit is exported")
            assertFalse(subject.resolve("data/normalized/forever/$requestedCandidate").exists(), "the candidate's data is not in the bundle")
        }
    }

    private fun evidence(test: String, facts: Map<String, Any?>) =
        StagedUpdate.writeEvidence(out!!.resolve("client"), test, facts + mapOf("requested" to mapOf("base" to requestedBase, "candidate" to requestedCandidate, "revision" to requestedRevision)))

    @Test
    fun theCandidateReplacesTheBaseOverHttps() {
        evidence("theCandidateReplacesTheBaseOverHttps", update.moveToCandidate())
    }

    @Test
    fun aCorruptCatalogSignatureChangesNothing() {
        evidence("aCorruptCatalogSignatureChangesNothing", update.corruptPublicationChangesNothing(StagedUpdate.Corruption.SIGNATURE))
    }

    @Test
    fun aCorruptReleaseManifestChangesNothing() {
        evidence("aCorruptReleaseManifestChangesNothing", update.corruptPublicationChangesNothing(StagedUpdate.Corruption.MANIFEST))
    }

    @Test
    fun aCorruptBlobChangesNothing() {
        evidence("aCorruptBlobChangesNothing", update.corruptPublicationChangesNothing(StagedUpdate.Corruption.BLOB))
    }

    @Test
    fun anInterruptedDownloadResumesAfterARestart() {
        evidence("anInterruptedDownloadResumesAfterARestart", update.interruptedDownloadResumesAfterARestart())
    }

    @Test
    fun noNetworkWhenTheCandidateAppearsKeepsTheBase() {
        evidence("noNetworkWhenTheCandidateAppearsKeepsTheBase", update.noNetworkKeepsTheBase())
    }

    private companion object {
        const val PROPERTY = "talentforge.ota.candidate"
    }
}
