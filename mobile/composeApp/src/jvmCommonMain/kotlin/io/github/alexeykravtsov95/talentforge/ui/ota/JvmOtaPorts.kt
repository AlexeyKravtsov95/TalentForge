package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileEntry
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaPaths
import io.github.alexeykravtsov95.talentforge.data.ota.SignatureVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * `java.security` ECDSA P-256 / SHA-256 (Android and the JVM, contract §3): the pinned key as an
 * `X509EncodedKeySpec` (the 91-byte SubjectPublicKeyInfo), the signature as ASN.1 DER.
 */
class PlatformSignatureVerifier : SignatureVerifier {
    override fun verifyEcdsaP256Sha256(publicKeySpki: ByteArray, message: ByteArray, derSignature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(message)
        verifier.verify(derSignature)
    } catch (_: GeneralSecurityException) {
        false
    }
}

/**
 * One GET over `HttpURLConnection` (OD-11: the platform API, no new library). The byte limit is
 * enforced while reading — at most `maxBytes + 1` bytes are read, the rest of the response is
 * never buffered. Redirects are not followed (the host is compiled into the app): a 3xx is an
 * HTTP status like any other non-200. No cookies, no cache, one fixed `User-Agent` (OD-4).
 * Android's implementation decodes `gzip` transparently, so every length and hash is over the
 * decoded body (contract §1).
 *
 * The connect timeout is 10 s: three SYN retransmissions on a slow link, while a connection the
 * origin never accepts fails early enough for the caller's retry ([fetchRetrying]) to open a new
 * one (M5.OTA.4b §3). The read timeout (TLS handshake and every read) stays 30 s.
 */
class PlatformBlobFetcher(
    private val userAgent: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
    /** Tests and the benchmark only: plain HTTP to 127.0.0.1 (app configurations are HTTPS-only). */
    private val allowLoopbackHttp: Boolean = false,
    /**
     * Which TLS roots the HTTPS connections trust: `null` (every app configuration but a staging
     * stand's) is the platform's; [pinnedTrustSocketFactory] trusts exactly a staging stand's own
     * CA (M5.OTA.4a). Certificate and hostname verification are never turned off.
     */
    private val sslSocketFactory: SSLSocketFactory? = null,
) : BlobFetcher {
    override suspend fun fetch(url: String, maxBytes: Long): FetchResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://") && !(allowLoopbackHttp && url.startsWith("http://127.0.0.1:"))) {
            return@withContext FetchResult.NetworkError("refusing a non-HTTPS URL")
        }
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return@withContext FetchResult.NetworkError(e.javaClass.simpleName)
        }
        try {
            if (sslSocketFactory != null && connection is HttpsURLConnection) connection.sslSocketFactory = sslSocketFactory
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("User-Agent", userAgent)
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) return@withContext FetchResult.HttpStatus(status)
            connection.inputStream.use { readBounded(it, maxBytes) }
        } catch (e: IOException) {
            FetchResult.NetworkError(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun readBounded(input: InputStream, maxBytes: Long): FetchResult.Body {
        val limit = maxBytes + 1
        val out = ByteArrayOutputStream(minOf(limit, 64L * 1024).toInt())
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (total < limit) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return FetchResult.Body(out.toByteArray(), exceededLimit = total > maxBytes)
    }
}

/**
 * TLS trust for an origin outside the public PKI — the local staging stand of M5.OTA.4a
 * (`docs/ota/staging-setup.md`): a socket factory whose **only** trust anchors are the PEM
 * certificates given. Certificate path validation, validity and hostname verification are the
 * platform's, unchanged; only *which* roots are trusted differs. A PEM carrying anything but
 * certificates is refused. Used by desktop tests and by debuggable Android builds configured with
 * `talentforge.ota.android.staging.trustAnchor`; never by a release build (Gradle refuses one).
 */
fun pinnedTrustSocketFactory(pem: String): SSLSocketFactory {
    require("PRIVATE KEY" !in pem) { "a trust anchor never contains a private key" }
    val certificates = try {
        CertificateFactory.getInstance("X.509").generateCertificates(pem.byteInputStream())
    } catch (e: CertificateException) {
        throw IllegalArgumentException("not a PEM certificate", e)
    }
    require(certificates.isNotEmpty()) { "no certificate in the trust anchor" }
    val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        certificates.forEachIndexed { index, certificate -> setCertificateEntry("anchor-$index", certificate) }
    }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
    return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
}

/**
 * The OTA store over `java.io` / `java.nio` (client design §3): Android `noBackupFilesDir/ota`
 * (app-private, never cleared by the system, excluded from backups), or a directory injected by
 * desktop tests. Atomic replace = write `<path>.tmp`, `fsync`, `Files.move(ATOMIC_MOVE,
 * REPLACE_EXISTING)`; installs are `Files.move(ATOMIC_MOVE)` on the one volume; the parent
 * directory is synced after a rename where the platform allows it.
 */
class PlatformOtaFileStore(private val root: File) : OtaFileStore {

    private fun resolve(path: String): Path {
        if (!OtaPaths.isSafe(path)) throw OtaIoException("unsafe OTA path: $path")
        return root.toPath().resolve(path)
    }

    private inline fun <T> io(what: String, path: String, block: () -> T): T = try {
        block()
    } catch (e: OtaIoException) {
        throw e
    } catch (e: IOException) {
        throw OtaIoException("$what $path failed: ${e.javaClass.simpleName}", noSpace = root.usableSpace < LOW_SPACE_BYTES, cause = e)
    } catch (e: SecurityException) {
        throw OtaIoException("$what $path denied", cause = e)
    }

    override fun readOrNull(path: String): ByteArray? = io("read", path) {
        val file = resolve(path)
        if (!Files.isRegularFile(file)) null else Files.readAllBytes(file)
    }

    override fun writeSynced(path: String, bytes: ByteArray) = io("write", path) {
        val file = resolve(path)
        Files.createDirectories(file.parent)
        writeAndSync(file.toFile(), bytes)
    }

    override fun replaceAtomically(path: String, bytes: ByteArray) = io("replace", path) {
        val target = resolve(path)
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        writeAndSync(temp.toFile(), bytes)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(target.parent)
    }

    override fun rename(from: String, to: String) = io("rename", "$from -> $to") {
        val source = resolve(from)
        val target = resolve(to)
        if (Files.exists(target)) throw OtaIoException("rename target exists: $to")
        Files.createDirectories(target.parent)
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(target.parent)
    }

    override fun exists(path: String): Boolean = io("stat", path) { Files.exists(resolve(path)) }

    override fun isDirectory(path: String): Boolean = io("stat", path) { Files.isDirectory(resolve(path)) }

    override fun list(directory: String): List<OtaFileEntry> = io("list", directory) {
        val dir = resolve(directory)
        if (!Files.isDirectory(dir)) return@io emptyList()
        Files.list(dir).use { stream ->
            stream.map { OtaFileEntry(it.fileName.toString(), Files.isDirectory(it)) }.toList()
        }.sortedBy { it.name }
    }

    override fun delete(path: String) = io("delete", path) {
        val target = resolve(path)
        if (!Files.exists(target)) return@io
        if (Files.isDirectory(target)) {
            Files.walk(target).use { stream -> stream.sorted(Comparator.reverseOrder()).toList() }.forEach {
                try {
                    Files.delete(it)
                } catch (_: NoSuchFileException) {
                }
            }
        } else {
            Files.delete(target)
        }
    }

    override fun freeSpaceBytes(): Long {
        root.mkdirs()
        return root.usableSpace
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    private fun syncDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // not every platform can open a directory for syncing; the rename itself is atomic
        }
    }

    private companion object {
        /** Below this, a failed write is reported as `ENOSPC` (the OS reason is not exposed as a type). */
        const val LOW_SPACE_BYTES: Long = 1024 * 1024
    }
}
