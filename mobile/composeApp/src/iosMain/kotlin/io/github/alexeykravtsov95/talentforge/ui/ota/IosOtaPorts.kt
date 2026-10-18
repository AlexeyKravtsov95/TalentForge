@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileEntry
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaPaths
import io.github.alexeykravtsov95.talentforge.data.ota.P256
import io.github.alexeykravtsov95.talentforge.data.ota.SignatureVerifier
import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.appendBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.setValue
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.darwin.NSObject
import platform.posix.ENOSPC
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.open
import platform.posix.write

/**
 * Security.framework ECDSA P-256 / SHA-256 (contract §3, §8): the key is the 65-byte X9.63
 * point — the last 65 bytes of the pinned 91-byte SPKI — given to `SecKeyCreateWithData`; the
 * ASN.1 DER signature is checked by `SecKeyVerifySignature` with
 * `kSecKeyAlgorithmECDSASignatureMessageX962SHA256`. A failure of any kind is `false`.
 */
class PlatformSignatureVerifier : SignatureVerifier {
    override fun verifyEcdsaP256Sha256(publicKeySpki: ByteArray, message: ByteArray, derSignature: ByteArray): Boolean {
        if (!P256.isSubjectPublicKeyInfo(publicKeySpki)) return false
        val point = publicKeySpki.copyOfRange(publicKeySpki.size - 65, publicKeySpki.size)
        val keyData = point.toCFData() ?: return false
        val messageData = message.toCFData() ?: return false.also { CFRelease(keyData) }
        val signatureData = derSignature.toCFData() ?: return false.also { CFRelease(keyData); CFRelease(messageData) }
        return memScoped {
            val bits = alloc<IntVar>().apply { value = 256 }
            val size = CFNumberCreate(null, kCFNumberIntType, bits.ptr)
            val attributes = CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            CFDictionaryAddValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            CFDictionaryAddValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPublic)
            CFDictionaryAddValue(attributes, kSecAttrKeySizeInBits, size)
            val error = alloc<CFErrorRefVar>()
            val key = SecKeyCreateWithData(keyData, attributes, error.ptr)
            val verified = key != null &&
                SecKeyVerifySignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, messageData, signatureData, error.ptr)
            error.value?.let { CFRelease(it) }
            key?.let { CFRelease(it) }
            CFRelease(attributes)
            CFRelease(size)
            CFRelease(keyData)
            CFRelease(messageData)
            CFRelease(signatureData)
            verified
        }
    }

    private fun ByteArray.toCFData(): CFDataRef? =
        if (isEmpty()) CFDataCreate(null, null, 0) else usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), size.convert()) }
}

/**
 * One GET over an ephemeral `NSURLSession` (no cookies, no URL cache — the verifier decides
 * freshness; App Transport Security defaults stay, so HTTPS only). The body is read through
 * the data delegate and the task is cancelled once more than `maxBytes` bytes arrived, so at
 * most `maxBytes + 1` bytes are kept and the rest is never read. Redirects are refused (the
 * 3xx response is the result). NSURLSession decodes `gzip`/`br` transparently, so lengths and
 * hashes are over the decoded body (contract §1).
 */
class PlatformBlobFetcher(private val userAgent: String) : BlobFetcher {
    override suspend fun fetch(url: String, maxBytes: Long): FetchResult {
        if (!url.startsWith("https://")) return FetchResult.NetworkError("refusing a non-HTTPS URL")
        val nsUrl = NSURL.URLWithString(url) ?: return FetchResult.NetworkError("bad URL")
        return suspendCancellableCoroutine { continuation ->
            val delegate = BoundedDataDelegate(maxBytes) { result -> if (continuation.isActive) continuation.resume(result) }
            val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
                setURLCache(null)
                setHTTPCookieStorage(null)
                setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            }
            val session = NSURLSession.sessionWithConfiguration(configuration, delegate, NSOperationQueue())
            val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
                setValue(userAgent, forHTTPHeaderField = "User-Agent")
                setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            }
            val task = session.dataTaskWithRequest(request)
            continuation.invokeOnCancellation {
                task.cancel()
                session.invalidateAndCancel()
            }
            task.resume()
        }
    }
}

private class BoundedDataDelegate(
    private val maxBytes: Long,
    private val onResult: (FetchResult) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val data = NSMutableData()
    private var finished = false

    private fun finish(session: NSURLSession, result: FetchResult) {
        if (finished) return
        finished = true
        session.invalidateAndCancel()
        onResult(result)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val status = (didReceiveResponse as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        if (status != 200) {
            completionHandler(NSURLSessionResponseCancel)
            finish(session, FetchResult.HttpStatus(status))
        } else {
            completionHandler(NSURLSessionResponseAllow)
        }
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (finished) return
        val limit = maxBytes + 1
        val room = limit - data.length.toLong()
        val take = minOf(room, didReceiveData.length.toLong())
        if (take > 0) data.appendBytes(didReceiveData.bytes, take.convert())
        if (data.length.toLong() > maxBytes) {
            dataTask.cancel()
            finish(session, FetchResult.Body(data.toByteArray(), exceededLimit = true))
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        completionHandler(null) // never follow a redirect: the 3xx is the response
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (didCompleteWithError != null) {
            finish(session, FetchResult.NetworkError(didCompleteWithError.domain ?: "NSURLError"))
        } else {
            finish(session, FetchResult.Body(data.toByteArray(), exceededLimit = false))
        }
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return bytes!!.readBytes(size)
}

/**
 * The OTA store in `<Application Support>/TalentForge/ota` (client design §3): inside the app
 * container, not `Caches` (the OS may purge it) and not `Documents` (user-visible), excluded
 * from iCloud/computer backups. Writes are `open` + `write` + `fsync`; replace and install are
 * `rename(2)` on the one volume (atomic, replacing for the state file). The container's default
 * file protection applies, so a check before the first unlock fails cleanly.
 */
class PlatformOtaFileStore(private val root: String) : OtaFileStore {
    private val files = NSFileManager.defaultManager

    init {
        files.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)
        NSURL.fileURLWithPath(root).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    private fun resolve(path: String): String {
        if (!OtaPaths.isSafe(path)) throw OtaIoException("unsafe OTA path: $path")
        return "$root/$path"
    }

    private fun parentOf(full: String) = full.substringBeforeLast('/')

    private fun mkdirs(directory: String) {
        if (!files.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)) {
            throw OtaIoException("cannot create $directory")
        }
    }

    private fun isDirectoryAt(full: String): Boolean? = memScoped {
        val isDirectory = alloc<BooleanVar>()
        if (files.fileExistsAtPath(full, isDirectory.ptr)) isDirectory.value else null
    }

    override fun readOrNull(path: String): ByteArray? {
        val full = resolve(path)
        if (isDirectoryAt(full) != false) return null
        val data = NSData.dataWithContentsOfFile(full) ?: throw OtaIoException("cannot read $path")
        return data.toByteArray()
    }

    override fun writeSynced(path: String, bytes: ByteArray) {
        val full = resolve(path)
        mkdirs(parentOf(full))
        writeAndSync(full, bytes)
    }

    override fun replaceAtomically(path: String, bytes: ByteArray) {
        val full = resolve(path)
        mkdirs(parentOf(full))
        val temp = "$full.tmp"
        writeAndSync(temp, bytes)
        if (platform.posix.rename(temp, full) != 0) throw OtaIoException("replace $path failed (errno $errno)")
    }

    override fun rename(from: String, to: String) {
        val source = resolve(from)
        val target = resolve(to)
        if (isDirectoryAt(target) != null) throw OtaIoException("rename target exists: $to")
        mkdirs(parentOf(target))
        if (platform.posix.rename(source, target) != 0) throw OtaIoException("rename $from failed (errno $errno)")
    }

    override fun exists(path: String): Boolean = isDirectoryAt(resolve(path)) != null

    override fun isDirectory(path: String): Boolean = isDirectoryAt(resolve(path)) == true

    override fun list(directory: String): List<OtaFileEntry> {
        val full = resolve(directory)
        if (isDirectoryAt(full) != true) return emptyList()
        val names = files.contentsOfDirectoryAtPath(full, error = null) ?: throw OtaIoException("cannot list $directory")
        return names.map { it as String }.sorted().map { OtaFileEntry(it, isDirectoryAt("$full/$it") == true) }
    }

    override fun delete(path: String) {
        val full = resolve(path)
        if (isDirectoryAt(full) == null) return
        if (!files.removeItemAtPath(full, error = null) && isDirectoryAt(full) != null) throw OtaIoException("cannot delete $path")
    }

    override fun freeSpaceBytes(): Long {
        val values = NSURL.fileURLWithPath(root).resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), error = null)
        return (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue ?: 0L
    }

    private fun writeAndSync(full: String, bytes: ByteArray) {
        val fd = open(full, O_WRONLY or O_CREAT or O_TRUNC, 384) // 0600
        if (fd < 0) throw OtaIoException("cannot open $full (errno $errno)", noSpace = errno == ENOSPC)
        try {
            var offset = 0
            bytes.usePinned { pinned ->
                while (offset < bytes.size) {
                    val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                    if (written < 0) throw OtaIoException("write $full failed (errno $errno)", noSpace = errno == ENOSPC)
                    offset += written.toInt()
                }
            }
            if (fsync(fd) != 0) throw OtaIoException("fsync $full failed (errno $errno)", noSpace = errno == ENOSPC)
        } finally {
            close(fd)
        }
    }
}
