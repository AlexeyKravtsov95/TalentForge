package io.github.alexeykravtsov95.talentforge.ui.ota

import com.sun.net.httpserver.HttpServer
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.data.ota.OtaKeyring
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadNewBuildChoices
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * Publisher → client interop (opt-in): the client installs exactly what the real M5.OTA.2
 * publisher CLI wrote — not the shared fixtures — over HTTP through the JVM/Android
 * [PlatformBlobFetcher]. The stores come from `scripts/ota_publisher_interop.py`:
 *
 * ```
 * python scripts/ota_publisher_interop.py <out>
 * ./gradlew :composeApp:desktopTest --tests "*OtaPublisherInteropTest*" -Dtalentforge.ota.interop=<out>
 * ```
 */
class OtaPublisherInteropTest {
    private val out: File? = System.getProperty(PROPERTY)?.let(::File)

    @Before
    fun requested() = assumeTrue("publisher interop not requested (-D$PROPERTY=<out>)", out != null)

    /** The publisher's store directory served as the static origin: `<base>/<key>` → `<root>/<key>`. */
    private class StoreOrigin : AutoCloseable {
        @Volatile
        var root: File? = null
        val requests = CopyOnWriteArrayList<String>()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path.removePrefix("/")
                requests += path
                val file = root?.resolve(path)?.takeIf { it.isFile && !path.contains("..") }
                if (file == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    val bytes = file.readBytes()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            start()
        }
        val base: String = "http://127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)
    }

    private fun store(name: String): File = out!!.resolve("stores/$name").also { check(it.isDirectory) { "missing $it" } }

    private fun publishedManifest(releaseId: String): ByteArray = out!!.resolve("artifacts/$releaseId/release.json").readBytes()

    private fun keyring(): OtaKeyring {
        val keys = Json.parseToJsonElement(out!!.resolve("keys/keyring.json").readText()).jsonObject.getValue("keys").jsonArray
        return OtaKeyring.of(keys.map { it.jsonObject }.map {
            OtaKeyring.Entry(it.getValue("keyId").jsonPrimitive.content, it.getValue("algorithm").jsonPrimitive.content, it.getValue("publicKeySpki").jsonPrimitive.content)
        })
    }

    private fun device(origin: StoreOrigin, channel: OtaChannel) = OtaDevice(
        config = OtaConfig.forTesting(keyring(), baseUrl = origin.base, channel = channel),
        fetcher = PlatformBlobFetcher(userAgent = "TalentForge/interop", allowLoopbackHttp = true),
    )

    private fun OtaDevice.installedManifest(releaseId: String): ByteArray = directory.resolve("v1/releases/$releaseId/release.json").readBytes()

    @Test
    fun productionPublicationsInstallActivateAndRollBack() = StoreOrigin().use { origin ->
        device(origin, OtaChannel.PRODUCTION).test { device ->
            // 1. promote A: the bundled product build, installed and activated
            origin.root = store("1-a-active")
            assertEquals(A_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertContentEquals(publishedManifest(A_ID), device.installedManifest(A_ID), "the publisher's exact manifest bytes")

            // 2. promote B (append-only): downloaded, verified, validated, activated
            origin.root = store("2-b-active")
            origin.requests.clear()
            val b = assertIs<CheckOutcome.Activated>(device.check())
            assertEquals(B_ID, b.releaseId)
            assertContentEquals(publishedManifest(B_ID), device.installedManifest(B_ID))
            assertTrue(origin.requests.all { it == "v1/channels/production/catalog.json" || it.startsWith("v1/blobs/sha256/") }, "only contract paths: ${origin.requests.toSet()}")
            assertTrue(origin.requests.any { it.startsWith("v1/blobs/sha256/") })
            val session = SessionDatasets.of(device.runtime.snapshot.value)
            assertEquals(B_BUILD, session.product.clientBuild)
            assertNotNull(runBlocking { loadNewBuildChoices(session.datasets, session.product) })
            val draft = runBlocking { createClassDraft(device.repository, session.datasets, ClassToken("DRUID"), session.product) }
            assertEquals(B_BUILD, assertIs<DraftCreation.Created>(draft).record.build.clientBuild)

            // 3. rollback to A: a new sequence; A is still installed, nothing is downloaded
            origin.root = store("3-rollback-a")
            origin.requests.clear()
            assertEquals(A_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertEquals(listOf("v1/channels/production/catalog.json"), origin.requests.toList())
            assertEquals(A_ID, device.runtime.activation.currentState().active)
            assertEquals(B_ID, device.runtime.activation.currentState().previous)
            assertEquals(3L, device.runtime.activation.currentState().catalog?.sequence)

            // a cold start on the same directory recovers the same state, offline
            origin.root = null
            val restarted = device.restart()
            assertEquals(A_ID, restarted.activation.currentState().active)
            assertEquals(A_BUILD, restarted.snapshot.value.product.clientBuild)
        }
    }

    @Test
    fun theStagedCatalogInstallsOnAStagingClientAndNotOnAProductionOne() = StoreOrigin().use { origin ->
        origin.root = store("3-rollback-a")
        device(origin, OtaChannel.STAGING).test { device ->
            assertEquals(A_ID, assertIs<CheckOutcome.Activated>(device.check()).releaseId)
            assertContentEquals(publishedManifest(A_ID), device.installedManifest(A_ID))
            assertTrue("v1/channels/staging/catalog.json" in origin.requests)
            assertTrue("v1/channels/production/catalog.json" !in origin.requests)
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.ota.interop"
    }
}
