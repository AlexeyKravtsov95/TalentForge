package io.github.alexeykravtsov95.talentforge.ui.ota

import com.sun.net.httpserver.HttpServer
import io.github.alexeykravtsov95.talentforge.data.ota.ActivationPolicy
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogVerifier
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaResult
import io.github.alexeykravtsov95.talentforge.data.ota.SeenCatalog
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * L4 for the Android/JVM adapters (`jvmCommonMain`, the code Android ships): `java.security`
 * signature verification over every fixture catalog, the HttpURLConnection fetcher's in-stream
 * byte limit, redirect refusal and HTTPS-only rule against a real local server, and the
 * java.nio store's atomic operations.
 */
class OtaPlatformAdapterTest {

    @Test
    fun everyFixtureCatalogVerifiesIdenticallyWithThePlatformVerifier() {
        val cases = Json.parseToJsonElement(OtaTestRepo.fixtures.resolve("cases.json").readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.filter { it.getValue("kind").jsonPrimitive.content == "catalog" }
        assertEquals(39, cases.size)
        for (case in cases) {
            val id = case.getValue("id").jsonPrimitive.content
            val context = case.getValue("context").jsonObject
            val expect = case.getValue("expect").jsonObject
            val verifier = CatalogVerifier(OtaTestRepo.keyring, PlatformSignatureVerifier(), "forever", OtaChannel.PRODUCTION)
            val seen = (context["lastSeen"] as? JsonObject)?.let { SeenCatalog(it.getValue("sequence").jsonPrimitive.long, it.getValue("payloadSha256").jsonPrimitive.content) }
            val result = verifier.verify(OtaTestRepo.fixture(case.getValue("file").jsonPrimitive.content), UtcInstant.parseOrNull(context.getValue("now").jsonPrimitive.content)!!, seen)
            when (expect.getValue("result").jsonPrimitive.content) {
                "ACCEPT" -> {
                    val verified = assertIs<OtaResult.Ok<*>>(result, id).value as io.github.alexeykravtsov95.talentforge.data.ota.VerifiedCatalog
                    val (decision, release) = ActivationPolicy.decide(
                        verified,
                        context.getValue("supportedDataLevel").jsonPrimitive.int,
                        context.getValue("bundledProductBuild").jsonPrimitive.content,
                        (context["currentReleaseId"] as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
                    )
                    assertEquals(expect.getValue("decision").jsonPrimitive.content, decision.name, id)
                    assertEquals(expect.getValue("releaseId").jsonPrimitive.content, release.releaseId, id)
                }
                else -> assertEquals(
                    expect.getValue("code").jsonPrimitive.content,
                    ((assertIs<OtaResult.Err>(result, id)).failure as OtaFailure.Contract).code.name,
                    id,
                )
            }
        }
    }

    @Test
    fun theDerivedTestKeysAreTheFixtureKeys() {
        // the test signer is only a fixture tool; it must be the very key the keyring pins
        val message = "check".encodeToByteArray()
        for (name in listOf("test-primary", "test-backup")) {
            val spki = OtaTestRepo.keyring.key(name)!!.spki
            assertTrue(PlatformSignatureVerifier().verifyEcdsaP256Sha256(spki, message, TestKeys.sign(name, message)), name)
        }
        assertFalse(PlatformSignatureVerifier().verifyEcdsaP256Sha256(OtaTestRepo.keyring.key("test-primary")!!.spki, message, TestKeys.sign("test-untrusted", message)))
        assertFalse(PlatformSignatureVerifier().verifyEcdsaP256Sha256(ByteArray(91), message, ByteArray(8)), "a bad key is false, never a crash")
    }

    // ------------------------------------------------------------ HTTP

    private class LocalOrigin : AutoCloseable {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endlessWritten = AtomicLong()
        val targetHits = AtomicInteger()
        val userAgent = AtomicReference<String?>()
        val base: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/endless") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
                try {
                    exchange.responseBody.use { out ->
                        while (endlessWritten.get() < 256L * 1024 * 1024) {
                            out.write(chunk)
                            endlessWritten.addAndGet(chunk.size.toLong())
                        }
                    }
                } catch (_: IOException) {
                    // the client stopped reading
                }
            }
            server.createContext("/exact") { exchange ->
                userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
                val body = ByteArray(1000) { it.toByte() }
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "$base/target")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/target") { exchange ->
                targetHits.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.createContext("/missing") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private val fetcher = PlatformBlobFetcher(userAgent = "TalentForge/test", allowLoopbackHttp = true)

    @Test
    fun theFetcherStopsAtTheLimitPlusOneByteWithoutReadingTheRest() = LocalOrigin().test { origin ->
        val limit = 1024L * 1024
        val body = assertIs<FetchResult.Body>(runBlocking { fetcher.fetch("${origin.base}/endless", limit) })
        assertTrue(body.exceededLimit)
        assertEquals(limit + 1, body.bytes.size.toLong())
        Thread.sleep(300)
        assertTrue(origin.endlessWritten.get() < 64L * 1024 * 1024, "the rest of an endless response is never read (${origin.endlessWritten.get()} bytes sent)")
    }

    @Test
    fun theFetcherReturnsExactBodiesAndMarksOneByteTooMany() = LocalOrigin().test { origin ->
        val exact = assertIs<FetchResult.Body>(runBlocking { fetcher.fetch("${origin.base}/exact", 1000) })
        assertFalse(exact.exceededLimit)
        assertContentEquals(ByteArray(1000) { it.toByte() }, exact.bytes)
        assertEquals("TalentForge/test", origin.userAgent.get())
        val over = assertIs<FetchResult.Body>(runBlocking { fetcher.fetch("${origin.base}/exact", 999) })
        assertTrue(over.exceededLimit)
        assertEquals(1000, over.bytes.size)
    }

    @Test
    fun redirectsAreNeverFollowedAndStatusesAreTyped() = LocalOrigin().test { origin ->
        assertEquals(FetchResult.HttpStatus(302), runBlocking { fetcher.fetch("${origin.base}/redirect", 100) })
        assertEquals(0, origin.targetHits.get(), "the redirect target is never requested")
        assertEquals(FetchResult.HttpStatus(404), runBlocking { fetcher.fetch("${origin.base}/missing", 100) })
    }

    @Test
    fun onlyHttpsIsFetchedAndNetworkFailuresAreTyped() {
        val strict = PlatformBlobFetcher(userAgent = "TalentForge/test")
        assertIs<FetchResult.NetworkError>(runBlocking { strict.fetch("http://127.0.0.1:9/x", 10) })
        val port = java.net.ServerSocket(0).use { it.localPort } // closed again: connection refused
        assertIs<FetchResult.NetworkError>(runBlocking { fetcher.fetch("http://127.0.0.1:$port/x", 10) })
    }

    // ------------------------------------------------------------ files

    @Test
    fun theFileStoreReplacesAtomicallyAndRefusesUnsafePaths() {
        val dir = Files.createTempDirectory("tf-ota-store").toFile()
        try {
            val store = PlatformOtaFileStore(dir)
            store.replaceAtomically("v1/state.json", "one".encodeToByteArray())
            store.replaceAtomically("v1/state.json", "two".encodeToByteArray())
            assertEquals("two", store.readOrNull("v1/state.json")!!.decodeToString())
            assertFalse(dir.resolve("v1/state.json.tmp").exists(), "no temporary file left")
            store.writeSynced("v1/staging/x/a/b.json", byteArrayOf(1))
            store.rename("v1/staging/x", "v1/releases/x")
            assertTrue(store.isDirectory("v1/releases/x"))
            assertNull(store.readOrNull("v1/staging/x/a/b.json"))
            store.writeSynced("v1/staging/y/f", byteArrayOf(2))
            assertFailsWith<OtaIoException>("an existing target is never replaced by a rename") { store.rename("v1/staging/y", "v1/releases/x") }
            assertEquals(listOf("releases", "staging", "state.json"), store.list("v1").map { it.name })
            for (bad in listOf("../escape", "/abs", "v1//x", "v1/./x", "v1\\x", "")) {
                assertFailsWith<OtaIoException>(bad) { store.readOrNull(bad) }
            }
            store.delete("v1/releases")
            assertFalse(store.exists("v1/releases"))
            assertTrue(store.freeSpaceBytes() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
