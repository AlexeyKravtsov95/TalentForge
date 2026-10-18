package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.BlobFetcher
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogVerifier
import io.github.alexeykravtsov95.talentforge.data.ota.FetchResult
import io.github.alexeykravtsov95.talentforge.data.ota.OtaClock
import io.github.alexeykravtsov95.talentforge.data.ota.OtaContract
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaResult
import io.github.alexeykravtsov95.talentforge.data.ota.VerifiedCatalog

/**
 * The channel's catalog (client design §4.2): one GET that reads at most 1 MiB + 1 byte
 * (repeated only when the transfer did not complete, [fetchRetrying]),
 * verification per contract §6.1 against the last accepted `{sequence, payloadSha256}`, and —
 * only after acceptance — the persisted acceptance (payload, then state) before anything acts
 * on it. A rejected or unreachable catalog changes nothing.
 */
class RemoteDatasetCatalog(
    private val config: OtaConfig,
    private val fetcher: BlobFetcher,
    private val verifier: CatalogVerifier,
    private val activation: DatasetActivationManager,
    private val clock: OtaClock,
) {
    suspend fun fetchAndAccept(): OtaResult<VerifiedCatalog> {
        val bytes = when (val response = fetcher.fetchRetrying(config.catalogUrl, OtaContract.MAX_CATALOG_BYTES.toLong(), config.fetchRetryDelaysMillis)) {
            is FetchResult.Body -> response.bytes
            is FetchResult.HttpStatus -> return OtaResult.Err(OtaFailure.Http(response.status))
            is FetchResult.NetworkError -> return OtaResult.Err(OtaFailure.Network(response.detail))
        }
        // an over-limit body holds MAX + 1 bytes: the verifier's first check reports CATALOG_TOO_LARGE
        val verified = when (val result = verifier.verify(bytes, clock.now(), activation.currentState().catalog)) {
            is OtaResult.Err -> return result
            is OtaResult.Ok -> result.value
        }
        return try {
            activation.acceptCatalog(verified)
            OtaResult.Ok(verified)
        } catch (e: OtaIoException) {
            OtaResult.Err(if (e.noSpace) OtaFailure.InsufficientStorage(verified.payload.size.toLong(), -1) else OtaFailure.Io(e.message ?: "write failed"))
        }
    }
}
