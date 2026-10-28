package io.github.alexeykravtsov95.talentforge.ui.ota

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * Historical patch replay, client half (M5.OTA.AUTO.2, opt-in): the app as it was **before**
 * 1.60.1.70450 meets the staging publication of 70450.
 *
 * The released app bundles 70450 as `PRODUCT_BUNDLE`, so it cannot show this update. The device
 * here is the app before the patch: 70374 is its only bundled build and its product (no 70450
 * file can be read locally), OTA is enabled for the staging channel only. The flows are the ones
 * every staged update goes through ([StagedUpdate]), on the pair 70374 → 70450 and the checkout's
 * committed data.
 *
 * `scripts/ota_historical_replay.py run <out>` makes `<out>/stand` (and `<out>/prepare`, the
 * preparation of 70450) and runs this test with `-Dtalentforge.ota.replay=<out>`. Each test
 * writes its evidence to `<out>/client/<test>.json`: what came over the network, from the
 * installed 70374 release (the client's cache) or from the bundle.
 */
class OtaHistoricalReplayTest {
    private val out: File? = System.getProperty(PROPERTY)?.let(::File)

    @Before
    fun requested() = assumeTrue("historical replay not requested (-D$PROPERTY=<replay out>)", out != null)

    /** The replay's pair, and nothing else: a stand of another pair is not this replay. */
    private val update: StagedUpdate by lazy {
        StagedUpdate(StagingStand(out!!.resolve("stand"))).also {
            assertEquals(listOf(A_BUILD, B_BUILD, A_ID, B_ID), listOf(it.base, it.candidate, it.baseId, it.candidateId), "the historical stand")
        }
    }

    private fun evidence(test: String, facts: Map<String, Any?>) = StagedUpdate.writeEvidence(out!!.resolve("client"), test, facts)

    @Test
    fun theClientMovesFrom70374To70450() {
        val prepared = out!!.resolve("prepare/out/ota/$B_ID/release.json").takeIf { it.isFile }
        evidence("theClientMovesFrom70374To70450", update.moveToCandidate(prepared))
    }

    @Test
    fun anInterruptedPatchDownloadResumesAfterARestart() {
        evidence("anInterruptedPatchDownloadResumesAfterARestart", update.interruptedDownloadResumesAfterARestart())
    }

    @Test
    fun noNetworkWhenThePatchAppearsKeeps70374() {
        evidence("noNetworkWhenThePatchAppearsKeeps70374", update.noNetworkKeepsTheBase())
    }

    private companion object {
        const val PROPERTY = "talentforge.ota.replay"
    }
}
