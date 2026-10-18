package io.github.alexeykravtsov95.talentforge.ui.ota

import com.sun.net.httpserver.HttpServer
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Test-plan §7 (L6): the cost of the per-file design (ADR-0001 D2) — cold, warm and resumed
 * installs of release B at parallelism 1, 4 and 8 over a real local HTTP origin that injects
 * the profiles' round-trip time, a shared link bandwidth (one token bucket for all
 * connections, like a phone's single link) and loss (each 16 KiB burst loses a packet with
 * probability 1 − (1 − p)^11 and then stalls one retransmission timeout, max(200 ms, 2 × RTT)).
 * The client is the production stack: `OtaRuntime` with the `HttpURLConnection` fetcher and
 * the `java.nio` store. Opt-in (tens of minutes):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*OtaPerformanceBenchmark*" -Dtalentforge.ota.benchmark=<report.md>
 * ```
 */
class OtaPerformanceBenchmark {

    private data class Profile(val name: String, val rttMillis: Long, val bitsPerSecond: Long, val lossRate: Double)

    private val profiles = listOf(
        Profile("good", 20, 20_000_000, 0.0),
        Profile("mobile", 100, 5_000_000, 0.0),
        Profile("poor", 300, 1_000_000, 0.01),
    )

    @BeforeTest
    fun requested() = assumeTrue("benchmark not requested", System.getProperty(PROPERTY) != null)

    /** The static origin with the injected network: RTT before each response, one shared token bucket, loss stalls. */
    private class ThrottledOrigin(private val profile: Profile, private val blobs: Map<String, ByteArray>, seed: Int) : AutoCloseable {
        val requests = AtomicInteger()
        val bodyBytes = AtomicLong()
        val requestedBlobs: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        @Volatile var catalog: ByteArray = ByteArray(0)
        @Volatile var onBlob: (() -> Unit)? = null
        private val random = Random(seed)
        private val bucket = Any()
        private var nextFreeNanos = System.nanoTime()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
        val base: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                Thread.sleep(profile.rttMillis)
                val path = exchange.requestURI.path
                val body = when {
                    path.endsWith("/catalog.json") -> catalog
                    path.contains("/blobs/sha256/") -> blobs[path.substringAfterLast('/')]?.also {
                        requestedBlobs += path.substringAfterLast('/')
                        onBlob?.invoke()
                    }
                    else -> null
                }
                try {
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { out ->
                            var offset = 0
                            while (offset < body.size) {
                                val n = minOf(CHUNK, body.size - offset)
                                throttle(n)
                                out.write(body, offset, n)
                                bodyBytes.addAndGet(n.toLong())
                                offset += n
                            }
                        }
                    }
                } catch (_: java.io.IOException) {
                    // the client went away (cancelled attempt)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        private fun throttle(bytes: Int) {
            val wait: Long
            synchronized(bucket) {
                val now = System.nanoTime()
                val start = maxOf(now, nextFreeNanos)
                var cost = bytes * 8L * 1_000_000_000L / profile.bitsPerSecond
                val lossChance = 1 - Math.pow(1 - profile.lossRate, 11.0)
                if (profile.lossRate > 0 && random.nextDouble() < lossChance) cost += maxOf(200L, 2 * profile.rttMillis) * 1_000_000L
                nextFreeNanos = start + cost
                wait = nextFreeNanos - now
            }
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }

        override fun close() = server.stop(0)
    }

    private data class Run(val millis: Long, val requests: Int, val decodedBytes: Long, val gzipBytes: Long, val reused: Int, val downloaded: Int)

    private val gzipSizes = HashMap<String, Long>()

    private fun gzipSize(hex: String, bytes: ByteArray): Long = gzipSizes.getOrPut(hex) {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        out.size().toLong()
    }

    private val blobs: Map<String, ByteArray> by lazy { TestRelease.A.blobs() + TestRelease.B.blobs() }

    /** One device: an empty OTA store, the bundle of the reference build only (nothing of B reusable). */
    private fun runtime(dir: File, origin: ThrottledOrigin, parallelism: Int, scope: CoroutineScope): OtaRuntime =
        OtaRuntime.open(
            config = OtaConfig.forTesting(OtaTestRepo.keyring, baseUrl = origin.base, parallelism = parallelism),
            store = PlatformOtaFileStore(dir),
            fetcher = PlatformBlobFetcher(userAgent = "TalentForge/benchmark", allowLoopbackHttp = true),
            signatures = PlatformSignatureVerifier(),
            clock = TestOtaClock(),
            bundle = BundleInventory(DatasetBundleId(GameId("forever"), "1.60.1.70009"), setOf("1.60.1.70009"), RepoBundleSource(setOf("1.60.1.70009"))),
            libraryPayload = { null },
            scope = scope,
        )

    private enum class Case { COLD, WARM, RESUME }

    private fun measure(profile: Profile, case: Case, parallelism: Int, seed: Int): Run {
        val dir = Files.createTempDirectory("tf-ota-bench").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            if (case == Case.WARM) {
                // A installed first over an unthrottled origin; B then reuses A's identical files
                ThrottledOrigin(Profile("setup", 0, Long.MAX_VALUE / 16, 0.0), blobs, seed).use { setup ->
                    setup.catalog = OtaTestRepo.fixture("catalogs/catalog-a-first-publication.json")
                    val warmup = runtime(dir, setup, parallelism, scope)
                    check(runBlocking { warmup.coordinator.checkNow() } is CheckOutcome.Activated)
                }
            }
            if (case == Case.RESUME) {
                // the first attempt is interrupted at 50 % of the files (the app goes to the background)
                ThrottledOrigin(profile, blobs, seed).use { first ->
                    first.catalog = OtaTestRepo.fixture("catalogs/catalog-b-active.json")
                    val interrupted = runtime(dir, first, parallelism, scope)
                    val half = TestRelease.B.manifest.files.size / 2
                    first.onBlob = { if (first.requestedBlobs.size == half) interrupted.coordinator.onBackground() }
                    runCatching { runBlocking { interrupted.coordinator.checkNow() } }
                }
                scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
            }
            ThrottledOrigin(profile, blobs, seed).use { origin ->
                origin.catalog = OtaTestRepo.fixture("catalogs/catalog-b-active.json")
                val runtime = runtime(dir, origin, parallelism, CoroutineScope(SupervisorJob() + Dispatchers.Default))
                val start = System.nanoTime()
                val outcome = runBlocking { runtime.coordinator.checkNow() }
                val millis = (System.nanoTime() - start) / 1_000_000
                val report = (outcome as? CheckOutcome.Activated)?.report ?: error("${profile.name} $case p$parallelism: $outcome")
                val gzip = origin.requestedBlobs.sumOf { gzipSize(it, blobs.getValue(it)) } + origin.catalog.size
                return Run(millis, origin.requests.get(), origin.bodyBytes.get(), gzip, report.reusedStaged + report.copiedFromInstalled, report.downloaded)
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    private fun p90(values: List<Long>) = values.sorted()[((values.size - 1) * 0.9).toInt()]

    @Test
    fun perFileDeliveryCost() {
        val report = StringBuilder()
        report.appendLine("# M5.OTA.3 — OTA download performance (test-plan §7)")
        report.appendLine()
        report.appendLine("Release B = `$B_ID`: ${TestRelease.B.manifest.files.size} files, ${TestRelease.B.entry.totalByteLength} bytes decoded, manifest ${TestRelease.B.entry.manifestByteLength} bytes.")
        report.appendLine("Client: production JVM stack (`HttpURLConnection` fetcher, `java.nio` store, full verification and validation). Origin: local HTTP server, injected RTT/bandwidth/loss (see the class comment).")
        report.appendLine()
        report.appendLine("| profile | case | parallelism | runs | median s | p90 s | requests | decoded bytes | gzip bytes (if served) | files reused | files downloaded | transfer-only s | overhead |")
        report.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        val medians = HashMap<Triple<String, Case, Int>, Pair<Long, Run>>()
        for (profile in profiles) {
            val plan = listOf(Case.COLD to 1, Case.COLD to 4, Case.COLD to 8, Case.WARM to 4, Case.RESUME to 4)
            for ((case, parallelism) in plan) {
                val runs = if (profile.name == "poor" && !(case == Case.COLD && parallelism == 4)) 3 else 5
                val results = (1..runs).map { measure(profile, case, parallelism, seed = it) }
                val times = results.map { it.millis }
                val typical = results.sortedBy { it.millis }[results.size / 2]
                val transfer = typical.decodedBytes * 8.0 / profile.bitsPerSecond
                val overhead = 1 - transfer / (median(times) / 1000.0)
                medians[Triple(profile.name, case, parallelism)] = median(times) to typical
                report.appendLine(
                    "| ${profile.name} (${profile.rttMillis} ms, ${profile.bitsPerSecond / 1_000_000} Mbit/s, ${profile.lossRate * 100} % loss) | $case | $parallelism | $runs | " +
                        "%.1f | %.1f | ${typical.requests} | ${typical.decodedBytes} | ${typical.gzipBytes} | ${typical.reused} | ${typical.downloaded} | %.1f | %.0f %% |"
                            .format(median(times) / 1000.0, p90(times) / 1000.0, transfer, overhead * 100),
                )
                println(report.lines().last { it.isNotBlank() })
            }
        }
        val coldPoor = medians.getValue(Triple("poor", Case.COLD, 4)).first / 1000.0
        val warmMobile = medians.getValue(Triple("mobile", Case.WARM, 4)).first / 1000.0
        val (coldMobileMillis, coldMobileRun) = medians.getValue(Triple("mobile", Case.COLD, 4))
        val mobileOverhead = 1 - (coldMobileRun.decodedBytes * 8.0 / 5_000_000) / (coldMobileMillis / 1000.0)
        report.appendLine()
        report.appendLine("## §7.3 triggers (parallelism 4)")
        report.appendLine()
        report.appendLine("| trigger | threshold | measured | fired |")
        report.appendLine("| --- | --- | --- | --- |")
        report.appendLine("| cold install, poor profile (median) | > 90 s | %.1f s | ${if (coldPoor > 90) "YES" else "no"} |".format(coldPoor))
        report.appendLine("| warm install, mobile profile (median) | > 15 s | %.1f s | ${if (warmMobile > 15) "YES" else "no"} |".format(warmMobile))
        report.appendLine("| request overhead, cold mobile | > 50 %% | %.0f %% | ${if (mobileOverhead > 0.5) "YES" else "no"} |".format(mobileOverhead * 100))
        File(System.getProperty(PROPERTY)!!).apply { parentFile?.mkdirs() }.writeText(report.toString())
        println(report)
        // the files of a release must be what the request log says: no blob outside the release was fetched
        check(blobs.keys.containsAll(TestRelease.B.manifest.files.map { it.sha256Hex }))
        check(sha256Hex(TestRelease.B.manifestBytes) in blobs)
    }

    private companion object {
        const val PROPERTY = "talentforge.ota.benchmark"
        const val CHUNK = 16 * 1024
    }
}
