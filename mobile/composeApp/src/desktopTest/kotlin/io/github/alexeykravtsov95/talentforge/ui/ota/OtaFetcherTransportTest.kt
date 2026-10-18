package io.github.alexeykravtsov95.talentforge.ui.ota

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Android/JVM fetcher over real sockets: which transfer failures may be repeated
 * ([FetchResult.NetworkError.retryable]) and how fast a fetch blocked in the platform's socket
 * I/O ends when its coroutine is cancelled (leaving the app, client design §4.2).
 */
class OtaFetcherTransportTest {

    // ------------------------------------------------------------------ origins

    /** Accepts connections and never answers; with [readRequest] it first reads the request head, with [head] it then sends it and stalls. */
    private class SilentOrigin(private val readRequest: Boolean = false, private val head: String? = null) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = CopyOnWriteArrayList<Socket>()
        val stalled = CountDownLatch(1)
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: java.io.IOException) {
                        break
                    }
                    sockets += socket
                    if (readRequest) {
                        val input = socket.getInputStream()
                        var tail = 0
                        while (tail != 0x0D0A0D0A) {
                            val b = input.read()
                            if (b < 0) break
                            tail = (tail shl 8) or b
                        }
                        head?.let { socket.getOutputStream().apply { write(it.encodeToByteArray()); flush() } }
                    }
                    stalled.countDown()
                }
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { it.close() }
        }
    }

    /** Accepts connections and closes each at once: a TLS handshake reset by the peer. */
    private class ResettingOrigin : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        server.accept().close()
                    } catch (_: java.io.IOException) {
                        break
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    /** A self-signed EC certificate made by the JDK's `keytool` (a PKCS12 keystore and its PEM). */
    private class Certificate(dir: File, alias: String, san: String) {
        val keystore: File = dir.resolve("$alias.p12")
        val pem: String

        init {
            keytool("-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=$alias", "-ext", "SAN=$san", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", keystore.path, "-storepass", PASSWORD, "-keypass", PASSWORD)
            pem = keytool("-exportcert", "-rfc", "-alias", alias, "-keystore", keystore.path, "-storepass", PASSWORD)
        }

        private fun keytool(vararg args: String): String {
            val process = ProcessBuilder(listOf(File(System.getProperty("java.home"), "bin/keytool").path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            check(process.waitFor() == 0) { output }
            return output
        }

        companion object {
            const val PASSWORD = "test-only"
        }
    }

    private class TlsOrigin(certificate: Certificate) : AutoCloseable {
        private val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            val keys = KeyStore.getInstance("PKCS12").apply { certificate.keystore.inputStream().use { load(it, Certificate.PASSWORD.toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, Certificate.PASSWORD.toCharArray()) }.keyManagers
            httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(managers, null, null) })
            createContext("/x") { exchange ->
                exchange.sendResponseHeaders(200, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            }
            start()
        }
        val base: String get() = "https://127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)
    }

    private class Counting(private val delegate: BlobFetcher) : BlobFetcher {
        val calls = AtomicInteger()

        override suspend fun fetch(url: String, maxBytes: Long): FetchResult {
            calls.incrementAndGet()
            return delegate.fetch(url, maxBytes)
        }
    }

    private fun <T> withTempDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("tf-ota-tls").toFile()
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private val strict = PlatformBlobFetcher(userAgent = "TalentForge/test")
    private val loopbackHttp = PlatformBlobFetcher(userAgent = "TalentForge/test", allowLoopbackHttp = true)

    // ------------------------------------------------------------------ what may be repeated

    @Test
    fun aRejectedCertificateOrHostIsNotRetryable() = withTempDir { dir ->
        val local = Certificate(dir, "local", "ip:127.0.0.1")
        TlsOrigin(local).test { origin ->
            // the TLS setup itself works: the same origin, its certificate pinned
            val pinned = PlatformBlobFetcher(userAgent = "TalentForge/test", sslSocketFactory = pinnedTrustSocketFactory(local.pem))
            assertIs<FetchResult.Body>(runBlocking { pinned.fetch("${origin.base}/x", 10) })
            // the platform's roots do not trust it
            val untrusted = assertIs<FetchResult.NetworkError>(runBlocking { strict.fetch("${origin.base}/x", 10) })
            assertFalse(untrusted.retryable, "an untrusted certificate: $untrusted")
        }
        val elsewhere = Certificate(dir, "elsewhere", "dns:other.invalid")
        TlsOrigin(elsewhere).test { origin ->
            val pinned = PlatformBlobFetcher(userAgent = "TalentForge/test", sslSocketFactory = pinnedTrustSocketFactory(elsewhere.pem))
            val wrongHost = assertIs<FetchResult.NetworkError>(runBlocking { pinned.fetch("${origin.base}/x", 10) })
            assertFalse(wrongHost.retryable, "a trusted certificate for another host: $wrongHost")
        }
    }

    @Test
    fun aRefusedUrlIsNotRetryableButAConnectionFailureIs() {
        assertEquals(FetchResult.NetworkError("refusing a non-HTTPS URL", retryable = false), runBlocking { strict.fetch("http://127.0.0.1:9/x", 10) })
        val malformed = assertIs<FetchResult.NetworkError>(runBlocking { strict.fetch("https://[not-a-host/x", 10) })
        assertFalse(malformed.retryable, "$malformed")
        val port = ServerSocket(0).use { it.localPort } // closed again: connection refused
        assertTrue(assertIs<FetchResult.NetworkError>(runBlocking { loopbackHttp.fetch("http://127.0.0.1:$port/x", 10) }).retryable)
    }

    @Test
    fun aResetOrStalledHandshakeStaysRetryable() {
        ResettingOrigin().test { origin ->
            val reset = assertIs<FetchResult.NetworkError>(runBlocking { strict.fetch("https://127.0.0.1:${origin.port}/x", 10) })
            assertTrue(reset.retryable, "a handshake reset by the peer: $reset")
        }
        SilentOrigin().test { origin ->
            val quick = PlatformBlobFetcher(userAgent = "TalentForge/test", readTimeoutMillis = 300)
            val stalled = assertIs<FetchResult.NetworkError>(runBlocking { quick.fetch("https://127.0.0.1:${origin.port}/x", 10) })
            assertTrue(stalled.retryable, "a handshake that never completes: $stalled")
        }
    }

    @Test
    fun theRetryNeverRepeatsARejectedCertificate() = withTempDir { dir ->
        TlsOrigin(Certificate(dir, "local", "ip:127.0.0.1")).test { origin ->
            val counting = Counting(strict)
            val started = System.nanoTime()
            val result = runBlocking { counting.fetchRetrying("${origin.base}/x", 10, listOf(60_000L, 60_000L)) }
            assertFalse(assertIs<FetchResult.NetworkError>(result).retryable)
            assertEquals(1, counting.calls.get(), "tried once")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "no pause waited")
        }
    }

    // ------------------------------------------------------------------ an unexpected failure in the platform stack

    /** A platform TLS stack that fails with an unchecked exception — not an `IOException` — when a connection is layered. */
    private class BrokenTls : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = emptyArray()
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket = throw IllegalStateException("broken TLS stack (test)")
        override fun createSocket(host: String, port: Int): Socket = throw IllegalStateException("broken TLS stack (test)")
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw IllegalStateException("broken TLS stack (test)")
        override fun createSocket(host: InetAddress, port: Int): Socket = throw IllegalStateException("broken TLS stack (test)")
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            throw IllegalStateException("broken TLS stack (test)")
    }

    @Test
    fun anUnexpectedExceptionInTheTransferEndsTheFetchInsteadOfHangingIt() = SilentOrigin().test { origin ->
        val broken = PlatformBlobFetcher(userAgent = "TalentForge/test", sslSocketFactory = BrokenTls())
        // a timeout here (a CancellationException) would mean the fetch never ended
        val failure = runBlocking { withTimeout(5_000) { runCatching { broken.fetch("https://127.0.0.1:${origin.port}/x", 10) }.exceptionOrNull() } }
        assertTrue(failure != null && failure !is CancellationException, "the fetch ends with the transfer's failure: $failure")
        assertTrue(
            generateSequence(failure) { it.cause }.any { it is IllegalStateException && it.message == "broken TLS stack (test)" },
            "the platform stack's own exception reaches the caller (the JDK may wrap it): $failure",
        )
    }

    // ------------------------------------------------------------------ cancellation inside socket I/O

    /** Starts a fetch, waits until the origin holds it stalled, cancels it and returns how long the fetch took to end. */
    private fun cancelWhileStalled(origin: SilentOrigin, fetcher: PlatformBlobFetcher, url: String): Long = runBlocking {
        val fetch = async(Dispatchers.Default) { fetcher.fetch(url, 1_000) }
        assertTrue(origin.stalled.await(10, TimeUnit.SECONDS), "the origin holds the request")
        Thread.sleep(300) // the fetcher is now blocked in the socket read
        val cancelled = System.nanoTime()
        fetch.cancel()
        fetch.join()
        assertTrue(fetch.isCancelled)
        (System.nanoTime() - cancelled) / 1_000_000
    }

    @Test
    fun cancellingAFetchBlockedOnTheResponseEndsItAtOnce() = SilentOrigin(readRequest = true).test { origin ->
        val millis = cancelWhileStalled(origin, loopbackHttp, "http://127.0.0.1:${origin.port}/x")
        assertTrue(millis < 2_000, "the 30 s read timeout is not waited out: $millis ms")
    }

    @Test
    fun cancellingAFetchBlockedInTheBodyEndsItAtOnce() =
        SilentOrigin(readRequest = true, head = "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\npartial").test { origin ->
            val millis = cancelWhileStalled(origin, loopbackHttp, "http://127.0.0.1:${origin.port}/x")
            assertTrue(millis < 2_000, "the 30 s read timeout is not waited out: $millis ms")
        }

    @Test
    fun cancellingAFetchBlockedInTheTlsHandshakeEndsItAtOnce() = SilentOrigin().test { origin ->
        val millis = cancelWhileStalled(origin, strict, "https://127.0.0.1:${origin.port}/x")
        assertTrue(millis < 2_000, "the 30 s read timeout is not waited out: $millis ms")
    }
}
