package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.sha256Ref

/** What the client persisted about the newest catalog it accepted (contract §6.1 #11, §7.2). */
data class SeenCatalog(val sequence: Long, val payloadSha256: String)

/** A catalog that passed every check of §6.1, with the exact verified payload bytes. */
class VerifiedCatalog(
    val catalog: OtaCatalog,
    payload: ByteArray,
    val payloadSha256: String,
) {
    private val bytes = payload.copyOf()

    /** The verified payload bytes — what the client persists (§7.2). */
    val payload: ByteArray get() = bytes.copyOf()

    val expiresAt: UtcInstant get() = UtcInstant.parseOrNull(catalog.expiresAt)!!

    val seen: SeenCatalog get() = SeenCatalog(catalog.sequence, payloadSha256)

    val activeRelease: CatalogRelease get() = catalog.release(catalog.activeReleaseId)!!
}

/**
 * Contract §6.1 (`verify_catalog` of the reference), in the contract's order, stopping at the
 * first failure: size → envelope → payload type → pinned signer → signature → payload JSON →
 * schema version → payload shape → game → channel → expiry → sequence → consistency.
 *
 * A rejected catalog changes nothing on the device. Signature math is delegated to the
 * platform [SignatureVerifier]; everything else is decided here, identically on every target.
 */
class CatalogVerifier(
    private val keyring: OtaKeyring,
    private val signatures: SignatureVerifier,
    private val game: String,
    private val channel: OtaChannel,
) {

    fun verify(envelopeBytes: ByteArray, now: UtcInstant, lastSeen: SeenCatalog?): OtaResult<VerifiedCatalog> = otaResult {
        // 0. size first: a reader that stopped at the limit + 1 byte reports the same code
        if (envelopeBytes.size > OtaContract.MAX_CATALOG_BYTES) {
            reject(
                OtaErrorCode.CATALOG_TOO_LARGE,
                "${envelopeBytes.size} bytes exceed the ${OtaContract.MAX_CATALOG_BYTES}-byte catalog limit",
            )
        }
        // 1. envelope: strict JSON, shape, canonical base64, unique key ids
        val envelope = OtaDocuments.envelope(envelopeBytes)
        val payload = CanonicalBase64.decodeOrNull(envelope.payload)!!
        // 2. domain separation
        if (envelope.payloadType != OtaContract.CATALOG_PAYLOAD_TYPE) {
            reject(OtaErrorCode.PAYLOAD_TYPE_MISMATCH, "payloadType ${envelope.payloadType}")
        }
        // 3-4. at least one signature of a pinned key verifies over PAE(type, payload)
        val trusted = envelope.signatures.filter { keyring.key(it.keyId) != null }
        if (trusted.isEmpty()) {
            reject(OtaErrorCode.UNTRUSTED_SIGNER, "no pinned key among ${envelope.signatures.map { it.keyId }}")
        }
        val message = preAuthenticationEncoding(envelope.payloadType, payload)
        val verified = trusted.any { signature ->
            val der = CanonicalBase64.decodeOrNull(signature.sig)!!
            P256.isStrictDerSignature(der) &&
                signatures.verifyEcdsaP256Sha256(keyring.key(signature.keyId)!!.spki, message, der)
        }
        if (!verified) reject(OtaErrorCode.SIGNATURE_INVALID, "no valid signature by ${trusted.map { it.keyId }}")
        // 5-7. only now are the verified bytes parsed
        val catalog = OtaDocuments.catalog(payload)
        // 8-9. identity
        if (catalog.game != game) reject(OtaErrorCode.GAME_MISMATCH, "catalog game ${catalog.game} != $game")
        if (catalog.channel != channel) {
            reject(OtaErrorCode.CHANNEL_MISMATCH, "catalog channel ${catalog.channel.wire} != ${channel.wire}")
        }
        // 10. freshness (device clock)
        val issuedAt = UtcInstant.parseOrNull(catalog.issuedAt)!!
        val expiresAt = UtcInstant.parseOrNull(catalog.expiresAt)!!
        if (now >= expiresAt) reject(OtaErrorCode.CATALOG_EXPIRED, "expired at ${catalog.expiresAt} (now $now)")
        // 11. anti-rollback
        val payloadSha256 = sha256Ref(payload)
        if (lastSeen != null) {
            if (catalog.sequence < lastSeen.sequence) {
                reject(OtaErrorCode.SEQUENCE_REGRESSION, "sequence ${catalog.sequence} < accepted ${lastSeen.sequence}")
            }
            if (catalog.sequence == lastSeen.sequence && payloadSha256 != lastSeen.payloadSha256) {
                reject(OtaErrorCode.SEQUENCE_REGRESSION, "sequence ${catalog.sequence} re-used with different content")
            }
        }
        // 12. internal consistency
        checkConsistency(catalog, issuedAt, expiresAt)
        VerifiedCatalog(catalog, payload, payloadSha256)
    }

    private fun checkConsistency(catalog: OtaCatalog, issuedAt: UtcInstant, expiresAt: UtcInstant) {
        val code = OtaErrorCode.CATALOG_INCONSISTENT
        if (expiresAt <= issuedAt) reject(code, "expiresAt is not after issuedAt")
        if (expiresAt.microsSince(issuedAt) > OtaContract.MAX_CATALOG_VALIDITY_SECONDS * 1_000_000L) {
            reject(code, "validity exceeds 180 days")
        }
        val ids = catalog.releases.map { it.releaseId }
        if (ids.size != ids.toSet().size) reject(code, "duplicate releaseId")
        for (release in catalog.releases) {
            if (!release.releaseId.startsWith(release.clientBuild + "-r")) {
                reject(code, "${release.releaseId} does not name clientBuild ${release.clientBuild}")
            }
        }
        val keys = ids.map { ReleaseId.parseOrNull(it)!! }
        if (keys != keys.sorted()) reject(code, "releases are not ordered by (clientBuild, revision)")
        if (catalog.activeReleaseId !in ids) reject(code, "activeReleaseId ${catalog.activeReleaseId} is not listed")
    }

    /**
     * Re-reads a payload this client verified and persisted earlier (§7.2: it never needs the
     * network to look up a release it has already seen). The bytes are trusted only because
     * their SHA-256 equals the recorded one; they are still parsed strictly.
     */
    fun reloadAccepted(payload: ByteArray, seen: SeenCatalog): VerifiedCatalog? {
        val payloadSha256 = sha256Ref(payload)
        if (payloadSha256 != seen.payloadSha256) return null
        val catalog = try {
            OtaDocuments.catalog(payload)
        } catch (_: OtaRejection) {
            return null
        }
        if (catalog.game != game || catalog.channel != channel || catalog.sequence != seen.sequence) return null
        return VerifiedCatalog(catalog, payload, payloadSha256)
    }
}
