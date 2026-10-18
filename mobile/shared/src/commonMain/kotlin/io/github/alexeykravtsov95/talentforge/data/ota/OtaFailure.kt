package io.github.alexeykravtsov95.talentforge.data.ota

/**
 * The closed error codes of `docs/ota/ota-contract-v1.md` §11 — the same names and the same
 * set as the Python reference's `OtaErrorCode` (the conformance replay compares them by name).
 */
enum class OtaErrorCode {
    // catalog (signed envelope + payload)
    CATALOG_TOO_LARGE,
    ENVELOPE_INVALID,
    PAYLOAD_TYPE_MISMATCH,
    UNTRUSTED_SIGNER,
    SIGNATURE_INVALID,
    PAYLOAD_INVALID,
    UNSUPPORTED_SCHEMA_VERSION,
    GAME_MISMATCH,
    CHANNEL_MISMATCH,
    CATALOG_EXPIRED,
    SEQUENCE_REGRESSION,
    CATALOG_INCONSISTENT,

    // release manifest
    MANIFEST_HASH_MISMATCH,
    MANIFEST_INVALID,
    RELEASE_IDENTITY_MISMATCH,
    INCOMPATIBLE_DATA_LEVEL,
    LAYOUT_INVALID,

    // release files
    BLOB_SIZE_MISMATCH,
    BLOB_HASH_MISMATCH,
    CONTENT_INVALID,
    CONTENT_IDENTITY_MISMATCH,
    RELEASE_INCOMPLETE,
    BUILD_IDENTITY_CONFLICT,
}

/**
 * Why an OTA step failed. Program logic branches on the type (and [Contract.code]); the
 * `detail` strings are diagnostics for humans, never inputs for decisions.
 *
 * [Contract] has one member per [OtaErrorCode]; the other members are the client-only
 * conditions of §11 (no fixture): network, HTTP status, storage, local I/O, cancellation.
 * Every failure has the same effect on the device (§11): the attempt is abandoned and the
 * active dataset and the saved-build library are untouched. What differs is whether the
 * release is retried ([installClass], client design §4.7).
 */
sealed class OtaFailure {

    /** A verdict of the contract's verification algorithm (§6). */
    sealed class Contract : OtaFailure() {
        abstract val code: OtaErrorCode
        abstract val detail: String
    }

    data class CatalogTooLarge(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.CATALOG_TOO_LARGE
    }

    data class EnvelopeInvalid(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.ENVELOPE_INVALID
    }

    data class PayloadTypeMismatch(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.PAYLOAD_TYPE_MISMATCH
    }

    data class UntrustedSigner(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.UNTRUSTED_SIGNER
    }

    data class SignatureInvalid(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.SIGNATURE_INVALID
    }

    data class PayloadInvalid(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.PAYLOAD_INVALID
    }

    data class UnsupportedSchemaVersion(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.UNSUPPORTED_SCHEMA_VERSION
    }

    data class GameMismatch(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.GAME_MISMATCH
    }

    data class ChannelMismatch(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.CHANNEL_MISMATCH
    }

    data class CatalogExpired(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.CATALOG_EXPIRED
    }

    data class SequenceRegression(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.SEQUENCE_REGRESSION
    }

    data class CatalogInconsistent(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.CATALOG_INCONSISTENT
    }

    data class ManifestHashMismatch(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.MANIFEST_HASH_MISMATCH
    }

    data class ManifestInvalid(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.MANIFEST_INVALID
    }

    data class ReleaseIdentityMismatch(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.RELEASE_IDENTITY_MISMATCH
    }

    data class IncompatibleDataLevel(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.INCOMPATIBLE_DATA_LEVEL
    }

    data class LayoutInvalid(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.LAYOUT_INVALID
    }

    data class BlobSizeMismatch(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.BLOB_SIZE_MISMATCH
    }

    data class BlobHashMismatch(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.BLOB_HASH_MISMATCH
    }

    data class ContentInvalid(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.CONTENT_INVALID
    }

    data class ContentIdentityMismatch(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.CONTENT_IDENTITY_MISMATCH
    }

    data class ReleaseIncomplete(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.RELEASE_INCOMPLETE
    }

    data class BuildIdentityConflict(override val detail: String) : Contract() {
        override val code: OtaErrorCode get() = OtaErrorCode.BUILD_IDENTITY_CONFLICT
    }

    /** No connection, DNS failure, timeout, TLS failure, interrupted transfer. */
    data class Network(val detail: String) : OtaFailure()

    /** A response other than HTTP 200 (including a redirect, which is never followed). */
    data class Http(val status: Int) : OtaFailure()

    /** Free space below what the attempt needs plus the margin, or `ENOSPC` while writing. */
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : OtaFailure()

    /** A local file operation failed for another reason. */
    data class Io(val detail: String) : OtaFailure()

    /** The attempt was cancelled (app left, superseded, user cancel). */
    data object Cancelled : OtaFailure()
}

/** The typed failure of a contract [OtaErrorCode]. */
fun OtaErrorCode.failure(detail: String): OtaFailure.Contract = when (this) {
    OtaErrorCode.CATALOG_TOO_LARGE -> OtaFailure.CatalogTooLarge(detail)
    OtaErrorCode.ENVELOPE_INVALID -> OtaFailure.EnvelopeInvalid(detail)
    OtaErrorCode.PAYLOAD_TYPE_MISMATCH -> OtaFailure.PayloadTypeMismatch(detail)
    OtaErrorCode.UNTRUSTED_SIGNER -> OtaFailure.UntrustedSigner(detail)
    OtaErrorCode.SIGNATURE_INVALID -> OtaFailure.SignatureInvalid(detail)
    OtaErrorCode.PAYLOAD_INVALID -> OtaFailure.PayloadInvalid(detail)
    OtaErrorCode.UNSUPPORTED_SCHEMA_VERSION -> OtaFailure.UnsupportedSchemaVersion(detail)
    OtaErrorCode.GAME_MISMATCH -> OtaFailure.GameMismatch(detail)
    OtaErrorCode.CHANNEL_MISMATCH -> OtaFailure.ChannelMismatch(detail)
    OtaErrorCode.CATALOG_EXPIRED -> OtaFailure.CatalogExpired(detail)
    OtaErrorCode.SEQUENCE_REGRESSION -> OtaFailure.SequenceRegression(detail)
    OtaErrorCode.CATALOG_INCONSISTENT -> OtaFailure.CatalogInconsistent(detail)
    OtaErrorCode.MANIFEST_HASH_MISMATCH -> OtaFailure.ManifestHashMismatch(detail)
    OtaErrorCode.MANIFEST_INVALID -> OtaFailure.ManifestInvalid(detail)
    OtaErrorCode.RELEASE_IDENTITY_MISMATCH -> OtaFailure.ReleaseIdentityMismatch(detail)
    OtaErrorCode.INCOMPATIBLE_DATA_LEVEL -> OtaFailure.IncompatibleDataLevel(detail)
    OtaErrorCode.LAYOUT_INVALID -> OtaFailure.LayoutInvalid(detail)
    OtaErrorCode.BLOB_SIZE_MISMATCH -> OtaFailure.BlobSizeMismatch(detail)
    OtaErrorCode.BLOB_HASH_MISMATCH -> OtaFailure.BlobHashMismatch(detail)
    OtaErrorCode.CONTENT_INVALID -> OtaFailure.ContentInvalid(detail)
    OtaErrorCode.CONTENT_IDENTITY_MISMATCH -> OtaFailure.ContentIdentityMismatch(detail)
    OtaErrorCode.RELEASE_INCOMPLETE -> OtaFailure.ReleaseIncomplete(detail)
    OtaErrorCode.BUILD_IDENTITY_CONFLICT -> OtaFailure.BuildIdentityConflict(detail)
}

/** How an install attempt treats a failure (client design §4.7). */
enum class InstallFailureClass {
    /**
     * The bytes did not match their pin, or the transfer/storage failed — nothing is known
     * about the release itself: the attempt ends, verified staged files are kept, and the
     * release is retried with backoff.
     */
    TRANSIENT,

    /**
     * A deterministic verdict on bytes that matched their pins: the staging directory is
     * deleted, `{releaseId, manifestSha256, code}` is recorded, and the same manifest bytes
     * are never tried again.
     */
    RELEASE_REJECTED,
}

/** The §4.7 class of a failure that ended an install attempt. */
fun OtaFailure.installClass(): InstallFailureClass = when (this) {
    is OtaFailure.ManifestHashMismatch,
    is OtaFailure.BlobSizeMismatch,
    is OtaFailure.BlobHashMismatch,
    is OtaFailure.Network,
    is OtaFailure.Http,
    is OtaFailure.InsufficientStorage,
    is OtaFailure.Io,
    OtaFailure.Cancelled,
    -> InstallFailureClass.TRANSIENT

    is OtaFailure.Contract -> InstallFailureClass.RELEASE_REJECTED
}

/** Carries a typed failure through linear verification code; caught at each public boundary. */
internal class OtaRejection(val failure: OtaFailure) : Exception(failure.toString())

internal fun reject(code: OtaErrorCode, detail: String): Nothing = throw OtaRejection(code.failure(detail))

/** Result of an OTA step: a value, or a typed failure. */
sealed interface OtaResult<out T> {
    data class Ok<out T>(val value: T) : OtaResult<T>

    data class Err(val failure: OtaFailure) : OtaResult<Nothing>
}

internal inline fun <T> otaResult(block: () -> T): OtaResult<T> = try {
    OtaResult.Ok(block())
} catch (rejection: OtaRejection) {
    OtaResult.Err(rejection.failure)
}
