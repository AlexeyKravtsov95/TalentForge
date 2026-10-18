package io.github.alexeykravtsov95.talentforge.data.ota

/**
 * Port: one HTTPS GET of a static object (contract §1). Implemented over the platform API
 * (HttpURLConnection, an ephemeral NSURLSession, java.net.http) in composeApp, and by an
 * in-memory blob server in tests.
 *
 * The byte limit is enforced **while reading**: an implementation stops after
 * `maxBytes + 1` bytes and never buffers the rest, so an oversized or endless response costs
 * at most one byte more than the limit. A redirect is never followed (the host is compiled
 * into the app, §1). Implementations never throw for network conditions; cancellation of the
 * calling coroutine propagates.
 */
interface BlobFetcher {
    suspend fun fetch(url: String, maxBytes: Long): FetchResult
}

sealed interface FetchResult {
    /**
     * HTTP 200 with its decoded body. [exceededLimit] is `true` when the body had more than
     * `maxBytes` bytes; [bytes] then holds `maxBytes + 1` bytes and the rest was not read.
     * Every hash and length of the contract is over these decoded bytes (§1).
     */
    class Body(val bytes: ByteArray, val exceededLimit: Boolean) : FetchResult

    /** Any status other than 200, a redirect included. */
    data class HttpStatus(val status: Int) : FetchResult

    /** The transfer did not complete (no connection, DNS, TLS, timeout, reset). */
    data class NetworkError(val detail: String) : FetchResult
}

/**
 * Port: the files under the OTA root (`<otaRoot>/v1/…`, client design §3), a storage area
 * separate from the saved-build library. Paths are relative, `/`-separated and never contain
 * `..`, a leading `/` or an empty segment ([OtaPaths.isSafe]); implementations refuse any
 * other path.
 *
 * Blocking I/O: callers run it off the main thread. Every failure is an [OtaIoException];
 * "file absent" is a value (`null`, `false`, empty list), never an exception.
 */
interface OtaFileStore {
    /** The file's bytes, or `null` when it does not exist. */
    fun readOrNull(path: String): ByteArray?

    /** Creates or truncates the file (parents created), writes [bytes] and syncs it to disk. */
    fun writeSynced(path: String, bytes: ByteArray)

    /**
     * Atomically replaces [path] with [bytes]: writes a sibling `<path>.tmp`, syncs it, then
     * renames it over [path] (Android `Files.move(ATOMIC_MOVE)`, iOS `NSData.writeToURL
     * atomically`). A crash leaves the old content or the new one, never a mixture.
     */
    fun replaceAtomically(path: String, bytes: ByteArray)

    /** Atomically renames a file or directory on the one volume; [to] must not exist. */
    fun rename(from: String, to: String)

    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    /** The entries directly inside [directory] (empty when it does not exist). */
    fun list(directory: String): List<OtaFileEntry>

    /** Deletes a file or a whole directory tree; absent is fine. */
    fun delete(path: String)

    /** Bytes available to the app on the OTA volume. */
    fun freeSpaceBytes(): Long
}

data class OtaFileEntry(val name: String, val isDirectory: Boolean)

/** A local file operation failed; [noSpace] marks `ENOSPC` (client design §4.7, "Not enough space"). */
class OtaIoException(message: String, val noSpace: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/** Port: the device clock (catalog expiry, attempt timestamps, the check interval). */
fun interface OtaClock {
    fun now(): UtcInstant
}

/** Path rules shared by every [OtaFileStore]. */
object OtaPaths {
    fun isSafe(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && '\\' !in path && '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    fun join(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString("/")
}
