package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * M5.OTA.4c: the per-request retry ([fetchRetrying]) measured against the **real remote staging
 * origin** — opt-in and read-only (the client only sends GETs; nothing on the origin changes).
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*OtaRetryRemoteMeasurement*" \
 *     -Dtalentforge.ota.retrymeasure=<out dir> \
 *     -Dtalentforge.ota.retrymeasure.baseUrl=https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru \
 *     -Dtalentforge.ota.retrymeasure.keyring=<staging-keyring.json> \
 *     [-Dtalentforge.ota.retrymeasure.runs=20] [-Dtalentforge.ota.retrymeasure.parallelism=8] \
 *     [-Dtalentforge.ota.retrymeasure.injectTryLoss=0.002]
 * ```
 *
 * Two arms, interleaved run by run so that both meet the same network:
 * * `retry` — this client: two repeats of a failed transfer (0.5 s, 2 s), 10 s connect timeout;
 * * `control` — the M5.OTA.4b client: no repeat, 15 s connect timeout.
 *
 * Every run is a cold install of the catalog's active release into a fresh directory, with only
 * build 70009 bundled so every file comes over the network, on the production stack
 * ([OtaConfig.resolve] of the staging settings, [PlatformBlobFetcher] with the platform's TLS
 * roots, `java.security`, `java.nio`). A first check that fails is followed by a second one on
 * the same directory (the app's next trigger). Each HTTP try is recorded below the retry, so
 * single failed tries and failed installs are counted apart: `<out>/installs.tsv` (one row per
 * check), `<out>/errors.tsv` (one row per failed try) and `<out>/summary.md`.
 *
 * `injectTryLoss=q` (default 0: none) reproduces M5.OTA.4b's finding on demand when the origin
 * does not: each HTTP try is, with probability `q`, a connection the origin never accepts — it
 * waits its arm's connect timeout and fails with `NetworkError(SocketTimeoutException)` without
 * reaching the network; every other try is real. (Failing the TLS connection itself does not
 * work: `HttpURLConnection` re-opens a connection that fails while being layered, so no try fails.)
 * The draws are seeded by the run, so both arms of a run meet the same sequence; injected
 * failures are counted apart from real ones.
 */
class OtaRetryRemoteMeasurement {
    private val out: File? = System.getProperty(PROPERTY)?.let(::File)

    @Before
    fun requested() = assumeTrue("retry measurement not requested (-D$PROPERTY=<dir>)", out != null)

    private val dir: File get() = out!!.apply { mkdirs() }
    private val baseUrl: String get() = requireNotNull(System.getProperty("$PROPERTY.baseUrl")) { "-D$PROPERTY.baseUrl" }
    private val runs: Int get() = System.getProperty("$PROPERTY.runs")?.toInt() ?: 20
    private val parallelism: Int get() = System.getProperty("$PROPERTY.parallelism")?.toInt() ?: 8
    private val injectedLoss: Double get() = System.getProperty("$PROPERTY.injectTryLoss")?.toDouble() ?: 0.0

    private val keys: List<OtaKeySetting> by lazy {
        val path = requireNotNull(System.getProperty("$PROPERTY.keyring")) { "-D$PROPERTY.keyring" }
        Json.parseToJsonElement(File(path).readText()).jsonObject.getValue("keys").jsonArray.map {
            val key = it.jsonObject
            OtaKeySetting(key.getValue("keyId").jsonPrimitive.content, key.getValue("algorithm").jsonPrimitive.content, key.getValue("publicKeySpki").jsonPrimitive.content)
        }
    }

    /** `null` connect timeout: the fetcher's own default, as shipped. */
    private enum class Arm(val label: String, val retryDelaysMillis: List<Long>, val connectTimeoutMillis: Int?) {
        RETRY("retry", OtaConfig.DEFAULT_FETCH_RETRY_DELAYS_MILLIS, null),
        CONTROL("control", emptyList(), 15_000),
    }

    // ------------------------------------------------------------------ instrumentation

    /** One HTTP try as the platform fetcher returned it (below the retry). */
    private class Try(val url: String, val startNanos: Long, val endNanos: Long, val result: FetchResult, val injected: Boolean) {
        val millis: Long get() = (endNanos - startNanos) / 1_000_000
    }

    /** Records every try; with [loss] > 0 a try is, with that probability, a lost connection ([stallMillis], then a timeout). */
    private class Recording(
        private val delegate: BlobFetcher,
        private val loss: Double,
        private val random: Random,
        private val stallMillis: Long,
    ) : BlobFetcher {
        val tries = CopyOnWriteArrayList<Try>()

        override suspend fun fetch(url: String, maxBytes: Long): FetchResult {
            val start = System.nanoTime()
            val lost = loss > 0 && synchronized(random) { random.nextDouble() } < loss
            val result = if (lost) {
                delay(stallMillis)
                FetchResult.NetworkError("SocketTimeoutException")
            } else {
                delegate.fetch(url, maxBytes)
            }
            tries += Try(url, start, System.nanoTime(), result, lost)
            return result
        }

        /** A failed try followed, after it ended, by another try of the same URL. */
        fun retried(failed: Try): Boolean = tries.any { it !== failed && it.url == failed.url && it.startNanos >= failed.endNanos }
    }

    /** The platform's default TLS socket factory (its roots, its verification), counting connections. */
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

    // ------------------------------------------------------------------ one check

    private class Check(
        val arm: Arm,
        val run: Int,
        val index: Int,
        val millis: Long,
        val outcome: CheckOutcome,
        val tries: List<Try>,
        val retriedFailures: Int,
        val connections: Int,
        val handshakes: Int,
    ) {
        val failures: List<Try> get() = tries.filter { it.result is FetchResult.NetworkError }
        val injected: Int get() = tries.count { it.injected }
        val installed: Boolean get() = outcome is CheckOutcome.Activated
    }

    private fun check(arm: Arm, run: Int, index: Int, store: File): Check {
        val tls = CountingTls()
        val platform = if (arm.connectTimeoutMillis == null) {
            PlatformBlobFetcher(userAgent = "TalentForge/retry-measurement", sslSocketFactory = tls)
        } else {
            PlatformBlobFetcher(userAgent = "TalentForge/retry-measurement", connectTimeoutMillis = arm.connectTimeoutMillis, sslSocketFactory = tls)
        }
        val recording = Recording(platform, injectedLoss, Random(run * 1_000L + index), (arm.connectTimeoutMillis ?: DEFAULT_CONNECT_TIMEOUT_MILLIS).toLong())
        val resolved = assertIs<OtaConfig.Resolution.Enabled>(
            OtaConfig.resolve(OtaPlatformSettings(enabled = true, channel = OtaChannel.STAGING, baseUrl = baseUrl, keys = keys)),
        ).config
        val config = OtaConfig.forTesting(
            resolved.keyring,
            baseUrl = resolved.baseUrl,
            channel = OtaChannel.STAGING,
            parallelism = parallelism,
            fetchRetryDelaysMillis = arm.retryDelaysMillis,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = OtaRuntime.open(
                config = config,
                store = PlatformOtaFileStore(store),
                fetcher = recording,
                signatures = PlatformSignatureVerifier(),
                clock = { UtcInstant.ofEpochMillis(System.currentTimeMillis()) },
                bundle = BundleInventory(DatasetBundleId(GameId("forever"), BUNDLED), setOf(BUNDLED), RepoBundleSource(setOf(BUNDLED))),
                libraryPayload = { null },
                scope = scope,
            )
            val start = System.nanoTime()
            val outcome = runBlocking { runtime.coordinator.checkNow() }
            val millis = (System.nanoTime() - start) / 1_000_000
            val tries = recording.tries.toList()
            val retried = tries.count { it.result is FetchResult.NetworkError && recording.retried(it) }
            return Check(arm, run, index, millis, outcome, tries, retried, tls.connections.get(), tls.handshakes.get()).also { record(it, recording) }
        } finally {
            scope.cancel()
        }
    }

    /** A timeout by its duration: the read timeout (30 s, TLS handshake included), else the arm's connect timeout. */
    private fun kind(arm: Arm, failed: Try): String {
        if (failed.injected) return "injected"
        val detail = (failed.result as FetchResult.NetworkError).detail
        if (detail != "SocketTimeoutException") return detail
        val connect = (arm.connectTimeoutMillis ?: DEFAULT_CONNECT_TIMEOUT_MILLIS).toLong()
        return when {
            failed.millis >= READ_TIMEOUT_MILLIS - 500 -> "read-timeout"
            failed.millis >= connect - 500 -> "connect-timeout"
            else -> "timeout"
        }
    }

    private fun outcomeText(outcome: CheckOutcome): String = when (outcome) {
        is CheckOutcome.Activated -> "Activated(${outcome.releaseId})"
        is CheckOutcome.Failed -> "Failed(${outcome.failure})"
        else -> outcome.toString()
    }

    private fun record(check: Check, recording: Recording) {
        val installs = dir.resolve("installs.tsv")
        if (!installs.exists()) {
            installs.writeText("utc\tarm\trun\tcheck\tmillis\toutcome\ttries\tblobTries\tfailedTries\tretriedTries\tconnectTimeouts\treadTimeouts\totherErrors\tconnections\thandshakes\tinjected\tdownloaded\n")
        }
        val kinds = check.failures.map { kind(check.arm, it) }
        val downloaded = (check.outcome as? CheckOutcome.Activated)?.report?.downloaded ?: 0
        installs.appendText(
            listOf(
                UtcInstant.ofEpochMillis(System.currentTimeMillis()), check.arm.label, check.run, check.index, check.millis, outcomeText(check.outcome),
                check.tries.size, check.tries.count { "/blobs/sha256/" in it.url }, check.failures.size, check.retriedFailures,
                kinds.count { it == "connect-timeout" }, kinds.count { it == "read-timeout" }, kinds.count { it !in setOf("connect-timeout", "read-timeout", "injected") },
                check.connections, check.handshakes, check.injected, downloaded,
            ).joinToString("\t") + "\n",
        )
        val errors = dir.resolve("errors.tsv")
        if (!errors.exists()) errors.writeText("arm\trun\tcheck\tobject\tstartOffsetMillis\tmillis\tkind\tretried\n")
        val origin = check.tries.minOfOrNull { it.startNanos } ?: 0L
        for (failed in check.failures.sortedBy { it.startNanos }) {
            errors.appendText(
                listOf(
                    check.arm.label, check.run, check.index, failed.url.substringAfterLast("/v1/"), (failed.startNanos - origin) / 1_000_000,
                    failed.millis, kind(check.arm, failed), if (recording.retried(failed)) "yes" else "no",
                ).joinToString("\t") + "\n",
            )
        }
        println("${check.arm.label} #${check.run}.${check.index}: ${outcomeText(check.outcome)} in ${check.millis} ms, ${check.tries.size} tries, ${check.failures.size} failed, ${check.retriedFailures} retried, ${check.connections} connections")
    }

    // ------------------------------------------------------------------ the measurement

    @Test
    fun coldInstallsWithAndWithoutTheRetry() {
        val checks = ArrayList<Check>()
        for (run in 1..runs) {
            val order = if (run % 2 == 1) listOf(Arm.RETRY, Arm.CONTROL) else listOf(Arm.CONTROL, Arm.RETRY)
            for (arm in order) {
                val store = dir.resolve("runs/${arm.label}-$run").apply { deleteRecursively(); mkdirs() }
                val first = check(arm, run, 1, store)
                checks += first
                if (!first.installed) checks += check(arm, run, 2, store)
                store.deleteRecursively()
            }
        }
        dir.resolve("summary.md").writeText(summary(checks))
        println(dir.resolve("summary.md").readText())
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    private fun seconds(millis: Long): String = "%.1f s".format(millis / 1000.0)

    private fun summary(checks: List<Check>): String = buildString {
        appendLine("# Retry measurement — ${UtcInstant.ofEpochMillis(System.currentTimeMillis())}")
        appendLine()
        appendLine("Origin `$baseUrl`, parallelism $parallelism, $runs cold installs per arm (interleaved), injected loss per try $injectedLoss.")
        appendLine()
        appendLine("| Metric | " + Arm.entries.joinToString(" | ") { it.label } + " |")
        appendLine("| --- | " + Arm.entries.joinToString(" | ") { "---" } + " |")
        fun row(name: String, value: (List<Check>, List<Check>) -> String) {
            appendLine("| $name | " + Arm.entries.joinToString(" | ") { arm ->
                val all = checks.filter { it.arm == arm }
                value(all, all.filter { it.index == 1 })
            } + " |")
        }
        row("installs") { _, first -> "${first.size}" }
        row("installed by the first check") { _, first -> "${first.count { it.installed }}/${first.size}" }
        row("installed by the second check") { all, first -> "${all.count { it.index == 2 && it.installed }}/${first.count { !it.installed }}" }
        row("not installed after two checks") { all, first -> "${first.count { f -> !f.installed && all.none { it.run == f.run && it.index == 2 && it.installed } }}" }
        row("installs with ≥ 1 retried try (first check)") { _, first -> "${first.count { it.retriedFailures > 0 }}" }
        row("retried tries, total (first check)") { _, first -> "${first.sumOf { it.retriedFailures }}" }
        row("retried tries per install, max") { _, first -> "${first.maxOfOrNull { it.retriedFailures } ?: 0}" }
        row("failed tries, total (all checks)") { all, _ -> "${all.sumOf { it.failures.size }}" }
        row("— connect timeouts") { all, _ -> "${all.sumOf { c -> c.failures.count { kind(c.arm, it) == "connect-timeout" } }}" }
        row("— read timeouts") { all, _ -> "${all.sumOf { c -> c.failures.count { kind(c.arm, it) == "read-timeout" } }}" }
        row("— other network errors") { all, _ -> "${all.sumOf { c -> c.failures.count { kind(c.arm, it) !in setOf("connect-timeout", "read-timeout", "injected") } }}" }
        row("— injected lost connections") { all, _ -> "${all.sumOf { it.injected }}" }
        row("first check, median / worst") { _, first -> "${seconds(median(first.map { it.millis }))} / ${seconds(first.maxOf { it.millis })}" }
        row("time to installed (both checks), median / worst") { all, first ->
            val totals = first.map { f -> f.millis + all.filter { it.run == f.run && it.index == 2 }.sumOf { it.millis } }
            "${seconds(median(totals))} / ${seconds(totals.max())}"
        }
        row("HTTP tries per install, median") { _, first -> "${median(first.map { it.tries.size.toLong() })}" }
        row("TLS connections per install, median / max") { _, first -> "${median(first.map { it.connections.toLong() })} / ${first.maxOf { it.connections }}" }
    }

    private companion object {
        const val PROPERTY = "talentforge.ota.retrymeasure"
        const val BUNDLED = "1.60.1.70009"
        const val READ_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
    }
}
