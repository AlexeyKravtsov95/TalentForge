package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A transfer that did not complete is repeated inside one attempt (client design §4.2, M5.OTA.4b
 * §9); an answer of the origin — a status, an over-limit body, bytes that miss their pin — never is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtaFetchRetryTest {

    private val url = "https://ota.test/v1/blobs/sha256/" + "a".repeat(64)
    private val body = FetchResult.Body(byteArrayOf(1, 2, 3), exceededLimit = false)

    /** Answers [answers] in order, the last one again for every further request. */
    private class ScriptedFetcher(private val answers: List<FetchResult>) : BlobFetcher {
        val calls = AtomicInteger()

        override suspend fun fetch(url: String, maxBytes: Long): FetchResult = answers[minOf(calls.getAndIncrement(), answers.lastIndex)]
    }

    // ------------------------------------------------------------------ fetchRetrying

    @Test
    fun aTransferThatFailsTwiceIsCompletedByTheThirdTry() = runTest {
        val fetcher = ScriptedFetcher(listOf(FetchResult.NetworkError("SocketTimeoutException"), FetchResult.NetworkError("ConnectException"), body))
        val start = currentTime
        assertSame(body, fetcher.fetchRetrying(url, 3, listOf(500L, 2_000L)))
        assertEquals(3, fetcher.calls.get())
        assertEquals(2_500L, currentTime - start, "a pause of 0.5 s, then of 2 s")
    }

    @Test
    fun theRetriesAreBounded() = runTest {
        val fetcher = ScriptedFetcher(listOf(FetchResult.NetworkError("a"), FetchResult.NetworkError("b"), FetchResult.NetworkError("c"), body))
        assertEquals(FetchResult.NetworkError("c"), fetcher.fetchRetrying(url, 3, listOf(500L, 2_000L)))
        assertEquals(3, fetcher.calls.get(), "one try and two repeats, never a fourth")
        val once = ScriptedFetcher(listOf(FetchResult.NetworkError("a"), body))
        assertEquals(FetchResult.NetworkError("a"), once.fetchRetrying(url, 3, emptyList()))
        assertEquals(1, once.calls.get(), "no pauses, no repeat")
    }

    @Test
    fun anAnswerOfTheOriginIsNeverRepeated() = runTest {
        val answers = listOf(
            FetchResult.HttpStatus(503),
            FetchResult.HttpStatus(404),
            FetchResult.HttpStatus(302),
            FetchResult.Body(ByteArray(4), exceededLimit = true),
            FetchResult.Body(ByteArray(2), exceededLimit = false),
        )
        for (answer in answers) {
            val fetcher = ScriptedFetcher(listOf(answer, body))
            assertSame(answer, fetcher.fetchRetrying(url, 3, listOf(500L, 2_000L)), "$answer")
            assertEquals(1, fetcher.calls.get(), "$answer is final")
        }
        // a repeat that reaches the origin ends the repeats with its answer
        val fetcher = ScriptedFetcher(listOf(FetchResult.NetworkError("a"), FetchResult.HttpStatus(503), body))
        assertEquals(FetchResult.HttpStatus(503), fetcher.fetchRetrying(url, 3, listOf(500L, 2_000L)))
        assertEquals(2, fetcher.calls.get())
    }

    @Test
    fun cancellationDuringAPauseEndsTheFetchAtOnce() = runTest {
        val fetcher = ScriptedFetcher(listOf(FetchResult.NetworkError("a"), body))
        val fetch = async { fetcher.fetchRetrying(url, 3, listOf(60_000L, 60_000L)) }
        runCurrent()
        assertEquals(1, fetcher.calls.get(), "the first try failed; the fetch is pausing")
        val cancelledAt = currentTime
        fetch.cancel()
        assertFailsWith<CancellationException> { fetch.await() }
        advanceUntilIdle()
        assertEquals(cancelledAt, currentTime, "the pause was not waited out")
        assertEquals(1, fetcher.calls.get(), "nothing is fetched after the cancellation")
    }

    // ------------------------------------------------------------------ one install attempt

    private fun catalog(name: String) = OtaTestRepo.fixture("catalogs/$name.json")

    private fun failure(outcome: CheckOutcome): OtaFailure = assertIs<CheckOutcome.Failed>(outcome).failure

    /** Short pauses keep the suite fast; the pauses an app uses are asserted by [OtaConfigTest]. */
    private fun device(delays: List<Long> = listOf(1L, 1L)) = OtaDevice(
        config = OtaConfig.forTesting(OtaTestRepo.keyring, baseUrl = "https://ota.test", fetchRetryDelaysMillis = delays),
    ).also { device ->
        device.server.publish(TestRelease.A, TestRelease.B)
        device.server.catalog = catalog("catalog-b-active")
    }

    /** The last file of B whose blob no other path shares (two paths may share one blob, contract §5). */
    private val uniqueFile: ReleaseFile
        get() = TestRelease.B.manifest.files.let { files -> files.last { file -> files.count { it.sha256Hex == file.sha256Hex } == 1 } }

    private val manifestHex = TestRelease.B.entry.manifestSha256.removePrefix("sha256:")

    private fun stagedPaths(device: OtaDevice): Set<String> {
        val root = device.directory.resolve("v1/staging/$B_ID")
        return root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it == "attempt.json" || it == "release.json" || it.endsWith(".part") }.toSet()
    }

    private fun partFiles(device: OtaDevice) = device.directory.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()

    private fun waitUntil(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in time" }
            Thread.sleep(20)
        }
    }

    @Test
    fun aColdInstallCompletesInOneCheckWhenEveryTransferFailsTwice() = device().test { device ->
        // the catalog, the manifest and every file blob: two stalled transfers, then the bytes
        device.server.fault = { _, count -> if (count <= 2) FetchResult.NetworkError("SocketTimeoutException") else null }
        val outcome = assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(B_ID, outcome.releaseId)
        assertTrue(outcome.report.manifestDownloaded)
        assertEquals(TestRelease.B.manifest.files.size, outcome.report.downloaded, "every file arrived in this attempt")
        assertEquals(3, device.server.catalogRequests())
        val perBlob = device.server.blobRequests().groupingBy { it }.eachCount()
        assertEquals(3, perBlob[manifestHex])
        assertEquals(TestRelease.B.manifest.files.map { it.sha256Hex }.toSet() + manifestHex, perBlob.keys)
        assertTrue(perBlob.values.all { it >= 3 }, "each blob arrived on its third transfer at the earliest")
        assertEquals(B_ID, device.runtime.activation.currentState().active)
        assertTrue(partFiles(device).isEmpty())
    }

    @Test
    fun aBlobThatNeverArrivesEndsTheAttemptAfterTwoRepeatsAndTheNextCheckResumes() = device().test { device ->
        val cut = uniqueFile
        device.server.fault = { url, _ -> if (url.endsWith(cut.sha256Hex)) FetchResult.NetworkError("SocketTimeoutException") else null }
        assertEquals(OtaFailure.Network("SocketTimeoutException"), failure(device.check()))
        assertEquals(3, device.server.blobRequests().count { it == cut.sha256Hex }, "one try and two repeats")
        assertNull(device.runtime.activation.currentState().active, "nothing is activated")
        assertTrue(device.directory.resolve("v1/staging/$B_ID/attempt.json").isFile, "staging kept for the resume")
        assertTrue(partFiles(device).isEmpty())
        val staged = stagedPaths(device)
        assertTrue(cut.path !in staged)

        device.server.fault = null
        device.server.requests.clear()
        val outcome = assertIs<CheckOutcome.Activated>(device.check())
        assertEquals(staged.size, outcome.report.reusedStaged)
        val expected = TestRelease.B.manifest.files.filter { it.path !in staged }.map { it.sha256Hex }.toSet()
        assertEquals(expected, device.server.blobRequests().toSet(), "only the missing blobs are requested")
    }

    @Test
    fun aCorruptBlobOrAnHttpStatusEndsTheAttemptWithoutARepeat() = device().test { device ->
        val cut = uniqueFile
        val length = cut.byteLength.toInt()
        val cases = listOf(
            Triple("wrong hash", FetchResult.Body(ByteArray(length), exceededLimit = false), OtaFailure.BlobHashMismatch::class),
            Triple("short body", FetchResult.Body(ByteArray(length - 1), exceededLimit = false), OtaFailure.BlobSizeMismatch::class),
            Triple("over the limit", FetchResult.Body(ByteArray(length + 1), exceededLimit = true), OtaFailure.BlobSizeMismatch::class),
            Triple("503", FetchResult.HttpStatus(503), OtaFailure.Http::class),
            Triple("404", FetchResult.HttpStatus(404), OtaFailure.Http::class),
        )
        for ((name, answer, expected) in cases) {
            device.server.fault = { url, _ -> if (url.endsWith(cut.sha256Hex)) answer else null }
            device.server.requests.clear()
            assertEquals(expected, failure(device.check())::class, name)
            assertEquals(1, device.server.blobRequests().count { it == cut.sha256Hex }, "$name: never repeated")
            assertEquals(1, device.server.catalogRequests(), name)
            assertNull(device.runtime.activation.currentState().active, name)
            assertTrue(partFiles(device).isEmpty(), name)
        }
        // a status for the catalog is final too
        device.server.fault = { url, _ -> if (url == device.config.catalogUrl) FetchResult.HttpStatus(503) else null }
        device.server.requests.clear()
        assertEquals(OtaFailure.Http(503), failure(device.check()))
        assertEquals(1, device.server.requests.size)
        // the faults gone, the next check installs B
        device.server.fault = null
        assertIs<CheckOutcome.Activated>(device.check())
    }

    @Test
    fun leavingTheAppDuringAPauseCancelsTheAttemptAtOnce() = device(delays = listOf(60_000L, 60_000L)).test { device ->
        val cut = uniqueFile
        device.server.fault = { url, _ -> if (url.endsWith(cut.sha256Hex)) FetchResult.NetworkError("SocketTimeoutException") else null }
        device.runtime.coordinator.onAppStarted()
        waitUntil { cut.sha256Hex in device.server.blobRequests() && device.runtime.status.value.checking }
        Thread.sleep(200)
        val left = System.currentTimeMillis()
        device.runtime.coordinator.onBackground()
        waitUntil(timeoutMillis = 5_000) { !device.runtime.status.value.checking }
        assertTrue(System.currentTimeMillis() - left < 5_000, "the 60 s pause is not waited out")
        assertEquals(1, device.server.blobRequests().count { it == cut.sha256Hex }, "no repeat after leaving the app")
        assertNull(device.runtime.activation.currentState().active)
        assertTrue(partFiles(device).isEmpty(), "no .part survives the cancellation")
        assertTrue(device.directory.resolve("v1/staging/$B_ID/attempt.json").isFile, "staging kept for the resume")
    }
}
