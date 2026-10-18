package io.github.alexeykravtsov95.talentforge.data.ota

import kotlinx.serialization.Serializable

/** A release installed under `releases/<releaseId>/`, pinned by its manifest's SHA-256. */
data class InstalledRelease(
    val releaseId: String,
    val clientBuild: String,
    val manifestSha256: String,
)

/**
 * A release rejected for what it is (client design §4.7): never retried for the same manifest
 * bytes. Transient failures are never recorded here.
 */
data class RejectedRelease(
    val releaseId: String,
    val manifestSha256: String,
    val code: OtaErrorCode,
)

/**
 * The persisted OTA state of one channel (contract §7.2). Written only as a whole, by an
 * atomic replace of `state.json`; this file is the single commit point of every OTA
 * transition (catalog accepted, release installed, release activated, release rejected).
 *
 * [catalog] names the last accepted catalog; its payload lives in a content-addressed file
 * (`catalog/<channel>/<hex>.payload`) written **before** the state that names it, so the state
 * can never refer to a payload that is not on disk (see `DatasetActivationManager`).
 */
data class OtaState(
    val catalog: SeenCatalog?,
    /** The active release; `null` = the bundled product. */
    val active: String?,
    /** The one-step local fallback (the release active before [active]). */
    val previous: String?,
    val installed: List<InstalledRelease>,
    val rejected: List<RejectedRelease>,
) {
    init {
        require(installed.map { it.releaseId }.toSet().size == installed.size) { "duplicate installed release" }
        require(active == null || installed.any { it.releaseId == active }) { "active release is not installed" }
        require(previous == null || installed.any { it.releaseId == previous }) { "previous release is not installed" }
    }

    fun installedRelease(releaseId: String): InstalledRelease? = installed.firstOrNull { it.releaseId == releaseId }

    fun isRejected(releaseId: String, manifestSha256: String): Boolean =
        rejected.any { it.releaseId == releaseId && it.manifestSha256 == manifestSha256 }

    fun withCatalog(seen: SeenCatalog): OtaState = copy(catalog = seen)

    /** Records (or re-records) an installed release. */
    fun withInstalled(release: InstalledRelease): OtaState =
        copy(installed = installed.filterNot { it.releaseId == release.releaseId } + release)

    /** `previous = active; active = releaseId` (client design §4.3); the release must be installed. */
    fun activated(releaseId: String): OtaState =
        if (active == releaseId) this else copy(previous = active, active = releaseId)

    /** Forgets an installed release (deleted by retention, or quarantined). */
    fun withoutInstalled(releaseId: String): OtaState = copy(
        installed = installed.filterNot { it.releaseId == releaseId },
        active = active.takeUnless { it == releaseId },
        previous = previous.takeUnless { it == releaseId },
    )

    /**
     * Drops a broken [active] release from the state (client design §4.1, §4.6): new
     * sessions fall back to [previous], else to the bundled product.
     */
    fun withoutBrokenActive(): OtaState {
        val broken = active ?: return this
        return copy(active = previous, previous = null).withoutInstalledKeepingActive(broken)
    }

    private fun withoutInstalledKeepingActive(releaseId: String): OtaState =
        copy(installed = installed.filterNot { it.releaseId == releaseId })

    fun withRejected(release: RejectedRelease): OtaState = copy(
        rejected = (rejected.filterNot { it.releaseId == release.releaseId && it.manifestSha256 == release.manifestSha256 } + release)
            .takeLast(MAX_REJECTED),
    )

    companion object {
        /** "No OTA state": the bundled product only. */
        val EMPTY: OtaState = OtaState(catalog = null, active = null, previous = null, installed = emptyList(), rejected = emptyList())

        /** Bounded so a hostile channel cannot grow the state file without limit. */
        const val MAX_REJECTED: Int = 64
    }
}

/**
 * Strict codec of `state.json` — a client-private format (contract §7.2 leaves it to the
 * client). Read like a contract: strict JSON, closed shape, no coercion, every invariant
 * checked. An unreadable or invalid file decodes to `null`, which the caller treats as "no
 * OTA state" — never as permission to delete anything (§7.2).
 */
object OtaStateCodec {
    const val FORMAT: String = "talentforge-ota-state"
    const val FORMAT_VERSION: Int = 1

    @Serializable
    private data class Wire(
        val format: String,
        val formatVersion: Int,
        val game: String,
        val channel: OtaChannel,
        val catalog: CatalogWire?,
        val active: String?,
        val previous: String?,
        val installed: List<InstalledWire>,
        val rejected: List<RejectedWire>,
    )

    @Serializable
    private data class CatalogWire(val sequence: Long, val payloadSha256: String)

    @Serializable
    private data class InstalledWire(val releaseId: String, val clientBuild: String, val manifestSha256: String)

    @Serializable
    private data class RejectedWire(val releaseId: String, val manifestSha256: String, val code: OtaErrorCode)

    fun encode(state: OtaState, game: String, channel: OtaChannel): ByteArray {
        val wire = Wire(
            format = FORMAT,
            formatVersion = FORMAT_VERSION,
            game = game,
            channel = channel,
            catalog = state.catalog?.let { CatalogWire(it.sequence, it.payloadSha256) },
            active = state.active,
            previous = state.previous,
            installed = state.installed.map { InstalledWire(it.releaseId, it.clientBuild, it.manifestSha256) },
            rejected = state.rejected.map { RejectedWire(it.releaseId, it.manifestSha256, it.code) },
        )
        return io.github.alexeykravtsov95.talentforge.data.TalentDataContract.json
            .encodeToString(Wire.serializer(), wire).encodeToByteArray()
    }

    /** The state, or `null` when [bytes] is not a valid state file of this game and channel. */
    fun decodeOrNull(bytes: ByteArray, game: String, channel: OtaChannel): OtaState? {
        val wire = try {
            OtaDocuments.decodeStrict(bytes, Wire.serializer(), OtaErrorCode.PAYLOAD_INVALID, schemaVersioned = false)
        } catch (_: OtaRejection) {
            return null
        }
        if (wire.format != FORMAT || wire.formatVersion != FORMAT_VERSION) return null
        if (wire.game != game || wire.channel != channel) return null
        val catalog = wire.catalog?.let {
            if (it.sequence < 1 || !OtaContract.SHA256.matches(it.payloadSha256)) return null
            SeenCatalog(it.sequence, it.payloadSha256)
        }
        val installed = wire.installed.map {
            val id = ReleaseId.parseOrNull(it.releaseId) ?: return null
            if (id.clientBuild != it.clientBuild || !OtaContract.SHA256.matches(it.manifestSha256)) return null
            InstalledRelease(it.releaseId, it.clientBuild, it.manifestSha256)
        }
        val rejected = wire.rejected.map {
            ReleaseId.parseOrNull(it.releaseId) ?: return null
            if (!OtaContract.SHA256.matches(it.manifestSha256)) return null
            RejectedRelease(it.releaseId, it.manifestSha256, it.code)
        }
        if (rejected.size > OtaState.MAX_REJECTED) return null
        return try {
            OtaState(catalog, wire.active, wire.previous, installed, rejected)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * `staging/<releaseId>/attempt.json` (contract §7.3): which manifest bytes an install attempt
 * is for, so a resumed attempt can tell whether its staged files belong to the release the
 * verified catalog pins now.
 */
data class InstallAttempt(
    val releaseId: String,
    val manifestSha256: String,
    val manifestByteLength: Long,
    val startedAt: UtcInstant,
    val updatedAt: UtcInstant,
) {
    /** Does this attempt stage exactly the manifest bytes [entry] pins? */
    fun pins(entry: CatalogRelease): Boolean =
        releaseId == entry.releaseId && manifestSha256 == entry.manifestSha256 &&
            manifestByteLength == entry.manifestByteLength
}

object InstallAttemptCodec {
    const val FORMAT: String = "talentforge-ota-attempt"
    const val FORMAT_VERSION: Int = 1

    @Serializable
    private data class Wire(
        val format: String,
        val formatVersion: Int,
        val releaseId: String,
        val manifestSha256: String,
        val manifestByteLength: Long,
        val startedAt: String,
        val updatedAt: String,
    )

    fun encode(attempt: InstallAttempt): ByteArray =
        io.github.alexeykravtsov95.talentforge.data.TalentDataContract.json.encodeToString(
            Wire.serializer(),
            Wire(
                format = FORMAT,
                formatVersion = FORMAT_VERSION,
                releaseId = attempt.releaseId,
                manifestSha256 = attempt.manifestSha256,
                manifestByteLength = attempt.manifestByteLength,
                startedAt = attempt.startedAt.toIsoSeconds(),
                updatedAt = attempt.updatedAt.toIsoSeconds(),
            ),
        ).encodeToByteArray()

    /** The attempt, or `null` for a missing, unreadable or invalid record. */
    fun decodeOrNull(bytes: ByteArray?): InstallAttempt? {
        if (bytes == null) return null
        val wire = try {
            OtaDocuments.decodeStrict(bytes, Wire.serializer(), OtaErrorCode.PAYLOAD_INVALID, schemaVersioned = false)
        } catch (_: OtaRejection) {
            return null
        }
        if (wire.format != FORMAT || wire.formatVersion != FORMAT_VERSION) return null
        ReleaseId.parseOrNull(wire.releaseId) ?: return null
        if (!OtaContract.SHA256.matches(wire.manifestSha256)) return null
        if (wire.manifestByteLength !in 1..OtaContract.MAX_FILE_BYTES) return null
        val startedAt = UtcInstant.parseOrNull(wire.startedAt) ?: return null
        val updatedAt = UtcInstant.parseOrNull(wire.updatedAt) ?: return null
        return InstallAttempt(wire.releaseId, wire.manifestSha256, wire.manifestByteLength, startedAt, updatedAt)
    }
}
