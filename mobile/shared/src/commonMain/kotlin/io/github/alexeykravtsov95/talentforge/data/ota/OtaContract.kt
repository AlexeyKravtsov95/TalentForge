package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Constants of the OTA contract v1 (`docs/ota/ota-contract-v1.md`); the same values as the
 * Python models (`talent_data.ota.models`).
 */
object OtaContract {
    const val SCHEMA_VERSION: Int = 1
    const val CATALOG_PAYLOAD_TYPE: String = "application/vnd.talentforge.ota-catalog.v1+json"

    /** `catalog.json` as received (§2 rule 10), enforced while reading. */
    const val MAX_CATALOG_BYTES: Int = 1024 * 1024
    const val MAX_SIGNATURES: Int = 4
    const val MAX_CATALOG_RELEASES: Int = 512
    const val MAX_RELEASE_FILES: Int = 4096
    const val MAX_FILE_BYTES: Long = 16L * 1024 * 1024
    const val MAX_RELEASE_BYTES: Long = 256L * 1024 * 1024

    /** `issuedAt < expiresAt ≤ issuedAt + 180 days` (§4). */
    const val MAX_CATALOG_VALIDITY_SECONDS: Long = 180L * 86_400

    /**
     * The highest data level this app implements (§9): level 1 = `dataset-manifest.v1`,
     * `talent-data.v1`, `talent-visuals.v1`, `talent-icons.v1`, `talent-tooltips.v1`,
     * `talent-localization.v1` (`ruRU`) as read by this app, with the allocation semantics of
     * `docs/talent-allocation-semantics.md`.
     */
    const val SUPPORTED_DATA_LEVEL: Int = 1

    internal val GAME = Regex("[a-z][a-z0-9-]{0,31}")
    internal val KEY_ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
    internal val SHA256 = Regex("sha256:[0-9a-f]{64}")
    internal val BUILD = Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
    internal val RELEASE_ID = Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+-r[1-9][0-9]{0,5}")
    internal val COMMIT = Regex("[0-9a-f]{40}")
}

// ------------------------------------------------------------------ ota-envelope.v1

@Serializable
data class EnvelopeSignature(
    val keyId: String,
    /** base64 of the ASN.1 DER ECDSA P-256 / SHA-256 signature over PAE(payloadType, payload). */
    val sig: String,
)

@Serializable
data class SignedEnvelope(
    val payloadType: String,
    /** base64 of the exact payload bytes. */
    val payload: String,
    val signatures: List<EnvelopeSignature>,
)

// ------------------------------------------------------------------ ota-catalog.v1

/** Closed: the two channels of v1 (§8). */
@Serializable
enum class OtaChannel(val wire: String) {
    @SerialName("production")
    PRODUCTION("production"),

    @SerialName("staging")
    STAGING("staging"),
}

@Serializable
data class CatalogRelease(
    val releaseId: String,
    val clientBuild: String,
    val minDataLevel: Int,
    val manifestSha256: String,
    val manifestByteLength: Long,
    val totalByteLength: Long,
    val publishedAt: String,
)

@Serializable
data class OtaCatalog(
    val schemaVersion: Int,
    val game: String,
    val channel: OtaChannel,
    val sequence: Long,
    val issuedAt: String,
    val expiresAt: String,
    val activeReleaseId: String,
    val releases: List<CatalogRelease>,
) {
    fun release(releaseId: String): CatalogRelease? = releases.firstOrNull { it.releaseId == releaseId }
}

// ------------------------------------------------------------------ ota-release.v1

/** Closed (§5.1): there is no role for code, scripts, configuration, URLs or UI. */
@Serializable
enum class FileRole {
    DATASET_MANIFEST,
    TALENT_DATA,
    VISUALS,
    ICON_REGISTRY,
    ICON_IMAGE,
    TOOLTIPS,
    LOCALIZATION,
}

@Serializable
data class ReleaseFile(
    val path: String,
    val role: FileRole,
    /** The file's SHA-256 **and** its blob address (`v1/blobs/sha256/<hex>`). */
    val sha256: String,
    val byteLength: Long,
) {
    /** The 64 hex digits of [sha256]. */
    val sha256Hex: String get() = sha256.removePrefix("sha256:")
}

@Serializable
data class ReleaseProvenance(
    val repositoryCommit: String,
    val validation: String,
)

@Serializable
data class OtaReleaseManifest(
    val schemaVersion: Int,
    val game: String,
    val clientBuild: String,
    val releaseId: String,
    val minDataLevel: Int,
    val createdAt: String,
    val provenance: ReleaseProvenance,
    val files: List<ReleaseFile>,
    val totalByteLength: Long,
)

/**
 * Strict readers of the three OTA documents, in the reference's order: strict JSON
 * ([JsonDuplicateNames]: UTF-8, no BOM, RFC 8259, unique names) → an integer `schemaVersion`
 * other than 1 → the closed shape → field constraints. Each stage of the contract has its own
 * code; nothing is coerced.
 *
 * The typed decode is followed by a wire-form check: the payload tree must **equal** the
 * canonical re-encoding of the decoded model. That rejects every coercion the Kotlin library
 * might make — a quoted number (`"2"`), an exponent or fraction for an integer (`1e3`), a
 * number where a string belongs — because the re-encoding is spelled differently.
 */
internal object OtaDocuments {

    fun envelope(bytes: ByteArray): SignedEnvelope {
        val envelope = decodeStrict(bytes, SignedEnvelope.serializer(), OtaErrorCode.ENVELOPE_INVALID, schemaVersioned = false)
        val code = OtaErrorCode.ENVELOPE_INVALID
        if (envelope.payloadType.codePointLength() !in 1..128) reject(code, "payloadType length")
        if (envelope.payload.length < 4) reject(code, "payload shorter than 4 characters")
        if (envelope.signatures.size !in 1..OtaContract.MAX_SIGNATURES) {
            reject(code, "${envelope.signatures.size} signatures (1..${OtaContract.MAX_SIGNATURES})")
        }
        for (signature in envelope.signatures) {
            if (!OtaContract.KEY_ID.matches(signature.keyId)) reject(code, "keyId ${signature.keyId}")
            if (signature.sig.length !in 4..256) reject(code, "sig length ${signature.sig.length}")
        }
        // the schema's base64 pattern, then canonical spelling (one encoding per byte string)
        CanonicalBase64.decodeOrNull(envelope.payload) ?: reject(code, "payload is not canonical base64")
        for (signature in envelope.signatures) {
            CanonicalBase64.decodeOrNull(signature.sig) ?: reject(code, "sig of ${signature.keyId} is not canonical base64")
        }
        val keyIds = envelope.signatures.map { it.keyId }
        if (keyIds.size != keyIds.toSet().size) reject(code, "duplicate keyId in $keyIds")
        return envelope
    }

    fun catalog(bytes: ByteArray): OtaCatalog {
        val code = OtaErrorCode.PAYLOAD_INVALID
        val catalog = decodeStrict(bytes, OtaCatalog.serializer(), code, schemaVersioned = true)
        if (catalog.schemaVersion != OtaContract.SCHEMA_VERSION) reject(code, "schemaVersion ${catalog.schemaVersion}")
        if (!OtaContract.GAME.matches(catalog.game)) reject(code, "game ${catalog.game}")
        if (catalog.sequence < 1) reject(code, "sequence ${catalog.sequence}")
        utc(catalog.issuedAt, "issuedAt", code)
        utc(catalog.expiresAt, "expiresAt", code)
        if (!OtaContract.RELEASE_ID.matches(catalog.activeReleaseId)) reject(code, "activeReleaseId")
        if (catalog.releases.size !in 1..OtaContract.MAX_CATALOG_RELEASES) reject(code, "${catalog.releases.size} releases")
        for (release in catalog.releases) {
            if (!OtaContract.RELEASE_ID.matches(release.releaseId)) reject(code, "releaseId ${release.releaseId}")
            if (!OtaContract.BUILD.matches(release.clientBuild)) reject(code, "clientBuild ${release.clientBuild}")
            if (release.minDataLevel < 1) reject(code, "${release.releaseId}.minDataLevel")
            if (!OtaContract.SHA256.matches(release.manifestSha256)) reject(code, "${release.releaseId}.manifestSha256")
            if (release.manifestByteLength !in 1..OtaContract.MAX_FILE_BYTES) {
                reject(code, "${release.releaseId}.manifestByteLength")
            }
            if (release.totalByteLength !in 1..OtaContract.MAX_RELEASE_BYTES) {
                reject(code, "${release.releaseId}.totalByteLength")
            }
            utc(release.publishedAt, "${release.releaseId}.publishedAt", code)
        }
        return catalog
    }

    fun releaseManifest(bytes: ByteArray): OtaReleaseManifest {
        val code = OtaErrorCode.MANIFEST_INVALID
        val manifest = decodeStrict(bytes, OtaReleaseManifest.serializer(), code, schemaVersioned = true)
        if (manifest.schemaVersion != OtaContract.SCHEMA_VERSION) reject(code, "schemaVersion ${manifest.schemaVersion}")
        if (!OtaContract.GAME.matches(manifest.game)) reject(code, "game ${manifest.game}")
        if (!OtaContract.BUILD.matches(manifest.clientBuild)) reject(code, "clientBuild ${manifest.clientBuild}")
        if (!OtaContract.RELEASE_ID.matches(manifest.releaseId)) reject(code, "releaseId ${manifest.releaseId}")
        if (manifest.minDataLevel < 1) reject(code, "minDataLevel ${manifest.minDataLevel}")
        utc(manifest.createdAt, "createdAt", code)
        if (!OtaContract.COMMIT.matches(manifest.provenance.repositoryCommit)) reject(code, "provenance.repositoryCommit")
        if (manifest.provenance.validation.codePointLength() !in 1..200) reject(code, "provenance.validation length")
        if (manifest.files.size !in 1..OtaContract.MAX_RELEASE_FILES) reject(code, "${manifest.files.size} files")
        for (file in manifest.files) {
            if (file.path.codePointLength() !in 1..160) reject(code, "path length of ${file.path}")
            if (!OtaContract.SHA256.matches(file.sha256)) reject(code, "${file.path}.sha256")
            if (file.byteLength !in 1..OtaContract.MAX_FILE_BYTES) reject(code, "${file.path}.byteLength")
        }
        if (manifest.totalByteLength !in 1..OtaContract.MAX_RELEASE_BYTES) reject(code, "totalByteLength")
        return manifest
    }

    private fun utc(text: String, name: String, code: OtaErrorCode): UtcInstant =
        UtcInstant.parseOrNull(text) ?: reject(code, "$name must be a UTC timestamp (...Z), got $text")

    internal fun <T> decodeStrict(bytes: ByteArray, serializer: KSerializer<T>, code: OtaErrorCode, schemaVersioned: Boolean): T {
        val scan = JsonDuplicateNames.scan(bytes)
        if (scan is JsonScan.Rejected) reject(code, "not strict JSON: ${scan.violation} at byte ${scan.offset}")
        val json = TalentDataContract.json
        val tree = try {
            json.parseToJsonElement(bytes.decodeToString())
        } catch (e: SerializationException) {
            reject(code, "unparseable: ${e.message}")
        }
        if (schemaVersioned) {
            val version = (tree as? JsonObject)?.get("schemaVersion")
            if (version is JsonPrimitive && !version.isString && INTEGER.matches(version.content) &&
                version.content != OtaContract.SCHEMA_VERSION.toString()
            ) {
                throw OtaRejection(OtaFailure.UnsupportedSchemaVersion("schemaVersion ${version.content}"))
            }
        }
        val decoded = try {
            json.decodeFromJsonElement(serializer, tree)
        } catch (e: SerializationException) {
            reject(code, "shape: ${e.message}")
        } catch (e: IllegalArgumentException) {
            reject(code, "shape: ${e.message}")
        }
        if (json.encodeToJsonElement(serializer, decoded) != tree) {
            reject(code, "wire form differs from the contract (coerced or quoted value)")
        }
        return decoded
    }

    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
}
