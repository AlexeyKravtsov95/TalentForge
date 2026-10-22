package io.github.alexeykravtsov95.talentforge.ui.ota

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val STAND_CATALOG_PATH = "v1/channels/staging/catalog.json"
const val STAND_BLOB_PREFIX = "v1/blobs/sha256/"
private val STAND_BLOB_PATH = Regex("v1/blobs/sha256/[0-9a-f]{64}")

/**
 * A staging stand made by `scripts/ota_staging_stand.py prepare` (M5.OTA.4a): the real
 * publisher's stores, its ephemeral `tf-staging-local-*` keyring, TLS chain and `report.json`.
 * Shared by [OtaStagingE2ETest] and [OtaHistoricalReplayTest].
 */
class StagingStand(val dir: File) {
    val report: JsonObject by lazy { Json.parseToJsonElement(dir.resolve("report.json").readText()).jsonObject }

    fun reportString(vararg path: String): String {
        var node: JsonObject = report
        for (key in path.dropLast(1)) node = node.getValue(key).jsonObject
        return node.getValue(path.last()).jsonPrimitive.content
    }

    val publishedAt: UtcInstant by lazy { UtcInstant.parseOrNull(reportString("now"))!! }

    fun snapshotField(snapshot: String, field: String): String = report.getValue("snapshots").jsonArray
        .map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == snapshot }.getValue(field).jsonPrimitive.content

    val stagingKeys: List<OtaKeySetting> by lazy {
        Json.parseToJsonElement(dir.resolve(reportString("keyring")).readText()).jsonObject.getValue("keys").jsonArray.map {
            val key = it.jsonObject
            OtaKeySetting(key.getValue("keyId").jsonPrimitive.content, key.getValue("algorithm").jsonPrimitive.content, key.getValue("publicKeySpki").jsonPrimitive.content)
        }
    }

    val caPem: String by lazy { dir.resolve(reportString("tls", "ca")).readText() }

    fun publishedManifest(releaseId: String): ByteArray = dir.resolve(reportString("manifests", releaseId, "path")).readBytes()

    fun manifestHex(releaseId: String): String = reportString("manifests", releaseId, "sha256").removePrefix("sha256:")

    /** The staging configuration exactly as an app build resolves it (contract §8, client design §8). */
    fun config(baseUrl: String): OtaConfig =
        assertIs<OtaConfig.Resolution.Enabled>(OtaConfig.resolve(OtaPlatformSettings(enabled = true, channel = OtaChannel.STAGING, baseUrl = baseUrl, keys = stagingKeys))).config

    /** The `HttpURLConnection` fetcher; it trusts the stand's CA only (or the platform's roots). */
    fun fetcher(trustStand: Boolean = true, readTimeoutMillis: Int = 30_000) = PlatformBlobFetcher(
        userAgent = "TalentForge/staging-e2e",
        readTimeoutMillis = readTimeoutMillis,
        sslSocketFactory = if (trustStand) pinnedTrustSocketFactory(caPem) else null,
    )

    companion object {
        /** path → SHA-256 hex of every file of a release manifest. */
        fun releaseFiles(manifest: ByteArray): Map<String, String> =
            Json.parseToJsonElement(manifest.decodeToString()).jsonObject.getValue("files").jsonArray.associate {
                val file = it.jsonObject
                file.getValue("path").jsonPrimitive.content to file.getValue("sha256").jsonPrimitive.content.removePrefix("sha256:")
            }
    }
}

/** What the origin does instead of the normal response to one request. */
sealed interface StandFault {
    data class Status(val code: Int, val location: String? = null) : StandFault

    /** Announces the full length, sends at most [keepBytes], then drops the connection (lost network). */
    data class Truncate(val keepBytes: Int) : StandFault

    /** Waits before the normal response (a read timeout on the client). */
    data class Stall(val millis: Long) : StandFault

    /** Serves other bytes under the same name (corruption in transit or at rest). */
    class Replace(val body: ByteArray) : StandFault
}

/**
 * `stores/<snapshot>` of the stand served like the static origin of contract §1, over HTTPS
 * with the stand's server certificate: the catalog path and blob paths only, 404 otherwise,
 * never a redirect unless a [StandFault] says so. Every request path is logged.
 */
class StandOrigin(private val stand: File) : AutoCloseable {
    @Volatile
    var snapshot: String = "1-a-staged"

    @Volatile
    var fault: ((path: String, count: Int) -> StandFault?)? = null

    val requests = CopyOnWriteArrayList<String>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val pool = Executors.newFixedThreadPool(8)
    private val server: HttpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(serverContext(stand))
        executor = pool
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }
    val base: String = "https://127.0.0.1:${server.address.port}"

    fun blobRequests(): List<String> = requests.filter { it.startsWith(STAND_BLOB_PREFIX) }.map { it.removePrefix(STAND_BLOB_PREFIX) }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath.removePrefix("/")
        requests += path
        val count = counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        val file = if (exchange.requestMethod == "GET" && (path == STAND_CATALOG_PATH || STAND_BLOB_PATH.matches(path))) {
            stand.resolve("stores/$snapshot/$path").takeIf { it.isFile }
        } else {
            null
        }
        when (val fault = fault?.invoke(path, count)) {
            is StandFault.Status -> {
                fault.location?.let { exchange.responseHeaders.add("Location", it) }
                exchange.sendResponseHeaders(fault.code, -1)
            }
            is StandFault.Truncate -> {
                val body = file?.readBytes() ?: return exchange.sendResponseHeaders(404, -1)
                exchange.sendResponseHeaders(200, body.size.toLong())
                try {
                    exchange.responseBody.write(body, 0, fault.keepBytes.coerceAtMost(body.size - 1))
                    exchange.responseBody.flush()
                    exchange.responseBody.close() // fewer bytes than announced: the connection is dropped
                } catch (_: IOException) {
                }
            }
            is StandFault.Stall -> {
                Thread.sleep(fault.millis)
                serve(exchange, file?.readBytes())
            }
            is StandFault.Replace -> serve(exchange, fault.body)
            null -> serve(exchange, file?.readBytes())
        }
    }

    private fun serve(exchange: HttpExchange, body: ByteArray?) {
        if (body == null) return exchange.sendResponseHeaders(404, -1)
        exchange.sendResponseHeaders(200, body.size.toLong())
        try {
            exchange.responseBody.use { it.write(body) }
        } catch (_: IOException) {
            // the client gave up (timeout, cancelled attempt)
        }
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }

    companion object {
        /** The stand's server key and chain (`tls/server.key`, `tls/server.pem`), never committed. */
        fun serverContext(stand: File): SSLContext {
            val keyBase64 = stand.resolve("tls/server.key").readLines().filterNot { it.startsWith("-----") }.joinToString("")
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyBase64)))
            val chain = stand.resolve("tls/server.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it).toTypedArray() }
            val password = CharArray(0)
            val keys = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("server", key, password, chain)
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }.keyManagers
            return SSLContext.getInstance("TLS").apply { init(managers, null, null) }
        }
    }
}
