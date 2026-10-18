package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import kotlinx.coroutines.delay

/**
 * One GET of [url], repeated after each pause of [retryDelaysMillis] while the transfer did not
 * complete ([FetchResult.NetworkError] with `retryable`: no connection, a reset or stalled TLS
 * handshake, a timeout) — a stalled TCP connection to the origin is replaced by a new one inside
 * the same attempt instead of ending it (M5.OTA.4b report §3, §9). A failure repeating cannot
 * change (`retryable = false`: a refused URL, a rejected certificate or host) and any answer the
 * origin gave — a body, an over-limit body, any HTTP status — are returned as they are and
 * never repeated here: what they mean is decided by the caller, fail-closed, exactly as for a
 * first answer. The URL, the byte limit and so every check of contract v1 are the same on each
 * try. The pauses are cancellable, so leaving the app stops a retry as promptly as a download.
 */
internal suspend fun BlobFetcher.fetchRetrying(url: String, maxBytes: Long, retryDelaysMillis: List<Long>): FetchResult {
    var result = fetch(url, maxBytes)
    for (pause in retryDelaysMillis) {
        if (result !is FetchResult.NetworkError || !result.retryable) return result
        delay(pause)
        result = fetch(url, maxBytes)
    }
    return result
}
