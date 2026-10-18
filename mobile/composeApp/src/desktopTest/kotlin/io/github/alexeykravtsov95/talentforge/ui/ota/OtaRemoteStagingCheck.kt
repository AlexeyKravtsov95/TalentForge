package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.OtaErrorCode
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * The real client against the **real remote staging origin** (M5.OTA.4b: VK Cloud Object
 * Storage over its public HTTPS host) — opt-in, one phase per run, because each phase needs
 * the live staging catalog in a given state (the operator publishes between phases, see
 * `docs/ota/M5.OTA.4b-remote-staging-report.md`).
 *
 * The stack is the production one: the configuration through [OtaConfig.resolve] (staging
 * channel, `tf-staging-*` keyring, `https://` base URL), [PlatformBlobFetcher] with the
 * platform's TLS roots (no trust anchor: the origin has a public-PKI certificate) wrapped only
 * to *count* requests, bytes, TCP connections and TLS handshakes, `java.security` signatures,
 * the `java.nio` store, and the system clock. The bundle is build 70009 alone, so releases of
 * 70374/70450 are fetched over the network rather than copied from bundled files.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*OtaRemoteStagingCheck*" \
 *     -Dtalentforge.ota.remote=<out dir> -Dtalentforge.ota.remote.phase=<phase> \
 *     -Dtalentforge.ota.remote.baseUrl=https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru \
 *     -Dtalentforge.ota.remote.keyring=<staging-keyring.json>
 * ```
 *
 * Phases: `a-active`, `b-active`, `rollback-a`, `b-again`, `bad-signature`, `restored`,
 * `expired`, `recovered`; `a-perf` / `b-perf` repeat only the measurements. A persistent "device" directory (`<out>/device`) carries the client
 * state from phase to phase (accepted sequences, installed releases), like one phone; the
 * measurements use fresh directories per run. Results are appended to `<out>/remote-results.txt`
 * (scenarios) and `<out>/remote-perf.tsv` (measurements).
 */
class OtaRemoteStagingCheck {
    private val out: File? = System.getProperty(PROPERTY)?.let(::File)

    @Before
    fun requested() = assumeTrue("remote staging check not requested (-D$PROPERTY=<dir>)", out != null)

    private val dir: File get() = out!!.apply { mkdirs() }
    private val phase: String get() = requireNotNull(System.getProperty("$PROPERTY.phase")) { "-D$PROPERTY.phase" }
    private val baseUrl: String get() = requireNotNull(System.getProperty("$PROPERTY.baseUrl")) { "-D$PROPERTY.baseUrl" }
    private val runs: Int get() = System.getProperty("$PROPERTY.runs")?.toInt() ?: 3
    private val parallelisms: List<Int> get() = (System.getProperty("$PROPERTY.parallelism") ?: "4,8").split(',').map { it.trim().toInt() }

    private val keys: List<OtaKeySetting> by lazy {
        val path = requireNotNull(System.getProperty("$PROPERTY.keyring")) { "-D$PROPERTY.keyring" }
        Json.parseToJsonElement(File(path).readText()).jsonObject.getValue("keys").jsonArray.map {
            val key = it.jsonObject
            OtaKeySetting(key.getValue("keyId").jsonPrimitive.content, key.getValue("algorithm").jsonPrimitive.content, key.getValue("publicKeySpki").jsonPrimitive.content)
        }
    }

    /** The app's resolution path, then the same keyring/URL at the parallelism under test. */
    private fun config(parallelism: Int): OtaConfig {
        val resolved = assertIs<OtaConfig.Resolution.Enabled>(
            OtaConfig.resolve(OtaPlatformSettings(enabled = true, channel = OtaChannel.STAGING, baseUrl = baseUrl, keys = keys)),
        ).config
        return OtaConfig.forTesting(resolved.keyring, baseUrl = resolved.baseUrl, channel = OtaChannel.STAGING, parallelism = parallelism)
    }

    // ------------------------------------------------------------------ instrumentation

    /**
     * Counts what goes over the wire; delegates every byte decision to the platform fetcher.
     * Requests slower than [SLOW_MILLIS], and every failed one, are reported to [slow].
     */
    private class Counting(private val delegate: BlobFetcher, private val slow: (String) -> Unit) : BlobFetcher {
        val requests = AtomicInteger()
        val blobRequests = AtomicInteger()
        val bodyBytes = AtomicLong()
        val failures = AtomicInteger()

        @Volatile var onBlob: ((Int) -> Unit)? = null

        override suspend fun fetch(url: String, maxBytes: Long): FetchResult {
            requests.incrementAndGet()
            val blobIndex = if ("/blobs/sha256/" in url) blobRequests.incrementAndGet() else 0
            if (blobIndex > 0) onBlob?.invoke(blobIndex)
            val start = System.nanoTime()
            val result = delegate.fetch(url, maxBytes)
            val millis = (System.nanoTime() - start) / 1_000_000
            when (result) {
                is FetchResult.Body -> bodyBytes.addAndGet(result.bytes.size.toLong())
                else -> failures.incrementAndGet()
            }
            if (millis > SLOW_MILLIS || result !is FetchResult.Body) {
                val outcome = if (result is FetchResult.Body) "${result.bytes.size} bytes" else result.toString()
                slow("${url.substringAfterLast("/v1/")}\t$millis\t$outcome")
            }
            return result
        }
    }

    /** The platform's default TLS socket factory (its roots, its verification), counting connections and handshakes. */
    private class CountingTls(private val delegate: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()) : SSLSocketFactory() {
        val connections = AtomicInteger()
        val handshakes = AtomicInteger()

        private fun track(socket: Socket): Socket {
            connections.incrementAndGet()
            (socket as? SSLSocket)?.addHandshakeCompletedListener { handshakes.incrementAndGet() }
            return socket
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket = track(delegate.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int): Socket = track(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = track(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = track(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            track(delegate.createSocket(address, port, localAddress, localPort))
    }

    private class Device(val runtime: OtaRuntime, val counting: Counting, val tls: CountingTls, val scope: CoroutineScope) : AutoCloseable {
        override fun close() = scope.cancel()
    }

    private fun device(store: File, parallelism: Int = OtaConfig.DEFAULT_PARALLELISM, offline: Boolean = false): Device {
        val tls = CountingTls()
        val platform: BlobFetcher = if (offline) {
            object : BlobFetcher {
                override suspend fun fetch(url: String, maxBytes: Long) = FetchResult.NetworkError("offline (airplane mode)")
            }
        } else {
            PlatformBlobFetcher(userAgent = "TalentForge/remote-staging-check", sslSocketFactory = tls)
        }
        val counting = Counting(platform) { line ->
            dir.resolve("remote-slow.tsv").appendText("${UtcInstant.ofEpochMillis(System.currentTimeMillis())}\t$phase\t$line\n")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = OtaRuntime.open(
            config = config(parallelism),
            store = PlatformOtaFileStore(store),
            fetcher = counting,
            signatures = PlatformSignatureVerifier(),
            clock = { UtcInstant.ofEpochMillis(System.currentTimeMillis()) },
            bundle = BundleInventory(DatasetBundleId(GameId("forever"), BUNDLED), setOf(BUNDLED), RepoBundleSource(setOf(BUNDLED))),
            libraryPayload = { null },
            scope = scope,
        )
        return Device(runtime, counting, tls, scope)
    }

    private fun record(line: String) {
        println(line)
        dir.resolve("remote-results.txt").appendText("${UtcInstant.ofEpochMillis(System.currentTimeMillis())} [$phase] $line\n")
    }

    private fun measure(case: String, parallelism: Int, run: Int, device: Device, millis: Long, outcome: CheckOutcome) {
        val report = (outcome as? CheckOutcome.Activated)?.report
        val tsv = dir.resolve("remote-perf.tsv")
        if (!tsv.exists()) {
            tsv.writeText("phase\tcase\tparallelism\trun\tmillis\trequests\tblobRequests\tbodyBytes\tconnections\thandshakes\tdownloaded\tcopiedFromInstalled\treusedStaged\tcopiedFromBundle\toutcome\n")
        }
        val row = listOf(
            phase, case, parallelism, run, millis, device.counting.requests.get(), device.counting.blobRequests.get(), device.counting.bodyBytes.get(),
            device.tls.connections.get(), device.tls.handshakes.get(), report?.downloaded ?: 0, report?.copiedFromInstalled ?: 0,
            report?.reusedStaged ?: 0, report?.copiedFromBundle ?: 0, outcome::class.simpleName,
        )
        tsv.appendText(row.joinToString("\t") + "\n")
        println("$case p$parallelism #$run: $millis ms, ${device.counting.requests.get()} requests, ${device.counting.bodyBytes.get()} bytes, ${device.tls.connections.get()} connections, ${device.tls.handshakes.get()} handshakes")
    }

    private fun timedCheck(device: Device): Pair<Long, CheckOutcome> {
        val start = System.nanoTime()
        val outcome = runBlocking { device.runtime.coordinator.checkNow() }
        return (System.nanoTime() - start) / 1_000_000 to outcome
    }

    private fun fresh(name: String): File = dir.resolve("runs/$phase/$name").apply { deleteRecursively(); mkdirs() }

    private val mainDevice: File get() = dir.resolve("device")

    private fun template(name: String): File = dir.resolve("templates/$name")

    private fun releaseFiles(): Set<String> =
        mainDevice.walkTopDown().filter { it.isFile && it.name != "state.json" && !it.name.endsWith(".tmp") }.map { it.relativeTo(mainDevice).path }.toSortedSet()

    // ------------------------------------------------------------------ scenarios

    /** Install the active release on the persistent device; a second check downloads nothing. */
    private fun installOnDevice(expected: String) {
        device(mainDevice).use { device ->
            val (millis, outcome) = timedCheck(device)
            val activated = assertIs<CheckOutcome.Activated>(outcome, "first check: $outcome")
            assertEquals(expected, activated.releaseId)
            assertEquals(expected, device.runtime.snapshot.value.productReleaseId)
            record(
                "PASSED install $expected over HTTPS: $millis ms, ${device.counting.requests.get()} requests (${device.counting.blobRequests.get()} blobs), " +
                    "${device.counting.bodyBytes.get()} bytes, ${device.tls.handshakes.get()} TLS handshakes; downloaded ${activated.report.downloaded}, " +
                    "from installed ${activated.report.copiedFromInstalled}, from bundle ${activated.report.copiedFromBundle}",
            )
        }
        recheckDownloadsNothing(expected)
    }

    private fun recheckDownloadsNothing(expected: String) {
        device(mainDevice).use { device ->
            val (millis, outcome) = timedCheck(device)
            val current = assertIs<CheckOutcome.Current>(outcome, "re-check: $outcome")
            assertEquals(expected, current.releaseId)
            assertEquals(1, device.counting.requests.get(), "a re-check is the catalog GET only")
            assertEquals(0, device.counting.blobRequests.get())
            record("PASSED re-check: Current($expected), 1 request (catalog), 0 blobs, ${device.counting.bodyBytes.get()} bytes, $millis ms")
        }
    }

    /** Process restart without network: the installed release is served at once, nothing is deleted. */
    private fun offlineRestart(expected: String) {
        val before = releaseFiles()
        device(mainDevice, offline = true).use { device ->
            assertEquals(expected, device.runtime.snapshot.value.productReleaseId, "offline cold start serves the installed release")
            val outcome = runBlocking { device.runtime.coordinator.checkNow() }
            assertIs<OtaFailure.Network>(assertIs<CheckOutcome.Failed>(outcome).failure)
            assertEquals(expected, device.runtime.snapshot.value.productReleaseId)
        }
        assertEquals(before, releaseFiles(), "an offline restart and a failed check change no release file")
        record("PASSED offline restart: $expected served from local files at once; check -> Network failure; no release file changed")
    }

    private fun expectReactivation(expected: String) {
        device(mainDevice).use { device ->
            val (millis, outcome) = timedCheck(device)
            val activated = assertIs<CheckOutcome.Activated>(outcome, "reactivation: $outcome")
            assertEquals(expected, activated.releaseId)
            assertTrue(activated.report.alreadyInstalled, "an installed release is re-activated, not re-downloaded")
            assertEquals(0, device.counting.blobRequests.get(), "no blob is fetched for an installed release")
            record("PASSED reactivate $expected: already installed, ${device.counting.requests.get()} request(s), 0 blobs, $millis ms")
        }
        recheckDownloadsNothing(expected)
    }

    private fun expectRejected(code: OtaErrorCode, active: String) {
        val before = releaseFiles()
        device(mainDevice).use { device ->
            val outcome = runBlocking { device.runtime.coordinator.checkNow() }
            val failure = assertIs<OtaFailure.Contract>(assertIs<CheckOutcome.Failed>(outcome, "rejection: $outcome").failure)
            assertEquals(code, failure.code, failure.detail)
            assertEquals(active, device.runtime.snapshot.value.productReleaseId, "the active dataset is untouched")
            assertEquals(1, device.counting.requests.get(), "only the catalog was fetched")
            record("PASSED rejected live catalog: $code (${failure.detail}); 1 request; $active stays active")
        }
        assertEquals(before, releaseFiles(), "a rejected catalog changes no release file")
    }

    private fun expectCurrent(active: String) {
        device(mainDevice).use { device ->
            val (millis, outcome) = timedCheck(device)
            assertEquals(active, assertIs<CheckOutcome.Current>(outcome, "after recovery: $outcome").releaseId)
            record("PASSED recovered catalog accepted: Current($active), ${device.counting.requests.get()} request(s), $millis ms")
        }
    }

    /**
     * Fresh installs at each parallelism. A run whose attempt fails on the network (a stalled
     * response hits the read timeout) is recorded as such, and the next check on the same store
     * must then complete it (case `<case>-retry`): the recovery the app performs on its next trigger.
     */
    private fun installRuns(case: String, expected: String, seed: File? = null) {
        for (parallelism in parallelisms) for (run in 1..runs) {
            val store = fresh("$case-p$parallelism-$run")
            seed?.copyRecursively(store, overwrite = true)
            val first = device(store, parallelism).use { device ->
                val (millis, outcome) = timedCheck(device)
                measure(case, parallelism, run, device, millis, outcome)
                outcome
            }
            if (first is CheckOutcome.Failed && first.failure is OtaFailure.Network) {
                device(store, parallelism).use { device ->
                    val (millis, outcome) = timedCheck(device)
                    measure("$case-retry", parallelism, run, device, millis, outcome)
                    val activated = assertIs<CheckOutcome.Activated>(outcome, "$case p$parallelism retry: $outcome")
                    assertEquals(expected, activated.releaseId)
                    record("PASSED $case p$parallelism #$run: attempt failed (${first.failure}); the next check completed it, ${device.counting.blobRequests.get()} blob GETs, ${activated.report.reusedStaged} staged files reused")
                }
            } else {
                assertEquals(expected, assertIs<CheckOutcome.Activated>(first, "$case p$parallelism: $first").releaseId)
            }
            if (case == "cold" && run == 1 && parallelism == parallelisms.first()) {
                template("installed-$expected").apply { deleteRecursively(); store.copyRecursively(this) }
            }
            store.deleteRecursively()
        }
    }

    /** The first attempt is cancelled (app to the background) after half the files; the next check resumes. */
    private fun resumeRuns(expected: String) {
        for (parallelism in parallelisms) for (run in 1..runs) {
            val store = fresh("resume-p$parallelism-$run")
            device(store, parallelism).use { first ->
                val half = 397 / 2
                first.counting.onBlob = { index -> if (index == half) first.runtime.coordinator.onBackground() }
                val outcome = runCatching { runBlocking { first.runtime.coordinator.checkNow() } }.getOrNull()
                check(outcome !is CheckOutcome.Activated) { "the interruption did not interrupt: $outcome" }
            }
            val leftovers = store.walkTopDown().count { it.name.endsWith(".part") }
            device(store, parallelism).use { resumed ->
                val (millis, outcome) = timedCheck(resumed)
                measure("resume", parallelism, run, resumed, millis, outcome)
                val activated = assertIs<CheckOutcome.Activated>(outcome, "resume p$parallelism: $outcome")
                assertEquals(expected, activated.releaseId)
                if (run == 1) {
                    record(
                        "PASSED interrupted at 50 % then resumed (p$parallelism): $leftovers .part before restart, recovery deleted " +
                            "${resumed.runtime.recovery.deletedPartFiles}; resumed attempt ${resumed.counting.blobRequests.get()} blob GETs, reused ${activated.report.reusedStaged} staged",
                    )
                }
            }
            store.deleteRecursively()
        }
    }

    @Test
    fun remoteStaging() {
        record("phase $phase against $baseUrl, keys ${keys.map { it.keyId }}")
        when (phase) {
            "a-active" -> {
                installOnDevice(A)
                offlineRestart(A)
                installRuns("cold", A)
            }
            "b-active" -> {
                installOnDevice(B) // the device has A installed: B reuses A's identical files
                installRuns("cold", B)
                installRuns("warm", B, seed = template("installed-$A"))
                resumeRuns(B)
                offlineRestart(B)
            }
            "a-perf" -> installRuns("cold", A) // measurements only (repeatable)
            "b-perf" -> {
                installRuns("cold", B)
                installRuns("warm", B, seed = template("installed-$A"))
                resumeRuns(B)
            }
            "rollback-a" -> expectReactivation(A)
            "b-again" -> expectReactivation(B)
            "bad-signature" -> expectRejected(OtaErrorCode.SIGNATURE_INVALID, B)
            "restored" -> expectCurrent(B)
            "expired" -> expectRejected(OtaErrorCode.CATALOG_EXPIRED, B)
            "recovered" -> expectCurrent(B)
            else -> error("unknown phase $phase")
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.ota.remote"
        const val SLOW_MILLIS = 2_000L
        const val BUNDLED = "1.60.1.70009"
        const val A = "1.60.1.70374-r1"
        const val B = "1.60.1.70450-r1"
    }
}
