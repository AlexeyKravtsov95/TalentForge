package io.github.alexeykravtsov95.talentforge.data.builds

import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One locally stored build: the portable [BuildFile] payload plus the
 * app-private metadata that surrounds it. The metadata (identity, name,
 * timestamps) is deliberately **not** part of `allocation-build.v1` — the
 * public contract stays a pure allocation plan, and the local library envelope
 * around it stays app-private and versioned independently.
 *
 * `name == null` means the build is a **Draft** (unnamed). A Draft is not an
 * unsaved build: it is persisted the moment it is created. An empty string is
 * never a second representation of "unnamed" — persisted blank names are
 * normalized to `null` while decoding.
 */
data class LocalBuildRecord(
    /** Opaque, stable identifier inside this device's library. UI derives no meaning from it. */
    val localId: String,
    /** `null` = Draft. Never blank. */
    val name: String?,
    /** Epoch milliseconds (injected clock). */
    val createdAt: Long,
    /** Epoch milliseconds of the last successful build mutation (autosave). */
    val updatedAt: Long,
    /** Epoch milliseconds of the last open, `null` while never opened. */
    val lastOpenedAt: Long?,
    /** The canonical allocation payload — the only portable part of the record. */
    val build: BuildFile,
)

/** Typed, fail-closed decoding failures of the private library envelope. */
sealed class LocalBuildLibraryFormatException(message: String) : Exception(message) {

    /** The envelope declares a `storageVersion` this client cannot read. */
    class UnsupportedVersion(val actualVersion: Int) : LocalBuildLibraryFormatException(
        "unsupported local build library storageVersion $actualVersion " +
            "(supported: ${LocalBuildLibraryCodec.STORAGE_VERSION})",
    )

    /** Structurally invalid payload (malformed JSON, wrong wire form, bad record). */
    class Malformed(detail: String) :
        LocalBuildLibraryFormatException("malformed local build library: $detail")
}

/**
 * Codec of the app-private local-library envelope.
 *
 * This is **not** a public TalentForge interchange contract: the format is
 * written and read by this app only, is versioned from day one via
 * `storageVersion`, and never leaves the device. It deliberately keeps the
 * same fail-closed reading standard as the public contracts:
 *
 * - unknown fields are rejected (`ignoreUnknownKeys = false`): drift *within*
 *   a storage version is a bug and must surface, schema evolution requires a
 *   `storageVersion` bump plus a migration (none exists in M5.1);
 * - quoted numeric literals are rejected in every numeric position (the same
 *   canonical wire-form check `TalentDataContract` applies to public
 *   contracts — kotlinx.serialization would otherwise accept them);
 * - a `storageVersion` other than [STORAGE_VERSION] is an explicit
 *   [LocalBuildLibraryFormatException.UnsupportedVersion];
 * - an embedded [BuildFile] with an unexpected `schemaVersion` is malformed;
 * - duplicate or blank `localId`s are malformed (ids are stable and unique);
 * - a blank `name` is normalized to `null` (Draft) rather than rejected —
 *   an older writer may have persisted one.
 *
 * Encoding is deterministic: records are serialized in ascending `localId`
 * order with a stable field order, so the same library always produces the
 * same bytes regardless of how the in-memory list was built.
 */
object LocalBuildLibraryCodec {

    /** The only storage format version M5.1 reads and writes. */
    const val STORAGE_VERSION: Int = 1

    private val json: Json = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
        explicitNulls = true
        encodeDefaults = true
        isLenient = false
    }

    /** Serializes [records] deterministically (canonical `localId` order). */
    fun encode(records: List<LocalBuildRecord>): String {
        val envelope = LocalBuildLibraryFile(
            storageVersion = STORAGE_VERSION,
            builds = records
                .sortedBy { it.localId }
                .map { it.toWire() },
        )
        return json.encodeToString(LocalBuildLibraryFile.serializer(), envelope)
    }

    /**
     * Parses and validates a stored payload. Throws
     * [LocalBuildLibraryFormatException] on any invalid input — never returns a
     * guessed or partially repaired library.
     */
    fun decode(payload: String): List<LocalBuildRecord> {
        val envelope = try {
            json.decodeFromString(LocalBuildLibraryFile.serializer(), payload)
        } catch (e: SerializationException) {
            throw LocalBuildLibraryFormatException.Malformed(e.message ?: "invalid JSON")
        } catch (e: IllegalArgumentException) {
            throw LocalBuildLibraryFormatException.Malformed(e.message ?: "invalid JSON")
        }
        if (envelope.storageVersion != STORAGE_VERSION) {
            throw LocalBuildLibraryFormatException.UnsupportedVersion(envelope.storageVersion)
        }
        // The same numeric wire-form standard as the public contracts: at every
        // position the model re-encodes as a number, the payload must carry an
        // unquoted numeric literal.
        try {
            verifyNumericWireForm(
                payload = json.parseToJsonElement(payload),
                canonical = json.encodeToJsonElement(LocalBuildLibraryFile.serializer(), envelope),
            )
        } catch (e: SerializationException) {
            throw LocalBuildLibraryFormatException.Malformed(e.message ?: "invalid wire form")
        }
        return decodeRecords(envelope)
    }

    private fun decodeRecords(envelope: LocalBuildLibraryFile): List<LocalBuildRecord> {
        val seenIds = HashSet<String>(envelope.builds.size)
        val records = ArrayList<LocalBuildRecord>(envelope.builds.size)
        for (wire in envelope.builds) {
            if (wire.localId.isBlank()) {
                throw LocalBuildLibraryFormatException.Malformed("record with a blank localId")
            }
            if (!seenIds.add(wire.localId)) {
                throw LocalBuildLibraryFormatException.Malformed("duplicate localId ${wire.localId}")
            }
            if (wire.build.schemaVersion != BuildFileVersion.V1) {
                throw LocalBuildLibraryFormatException.Malformed(
                    "record ${wire.localId} embeds build schemaVersion " +
                        "${wire.build.schemaVersion} (expected ${BuildFileVersion.V1})",
                )
            }
            records += LocalBuildRecord(
                localId = wire.localId,
                name = wire.name?.takeIf { it.isNotBlank() },
                createdAt = wire.createdAt,
                updatedAt = wire.updatedAt,
                lastOpenedAt = wire.lastOpenedAt,
                build = wire.build,
            )
        }
        return records
    }

    /** Recomputes the payload against the model's canonical re-encoding (numeric positions). */
    private fun verifyNumericWireForm(payload: JsonElement, canonical: JsonElement) {
        verifyNumericWireForm(payload, canonical, "$")
    }

    private fun verifyNumericWireForm(payload: JsonElement, canonical: JsonElement, path: String) {
        when (canonical) {
            is JsonPrimitive -> if (canonical.isNumericLiteral()) {
                val original = payload as? JsonPrimitive
                    ?: throw SerializationException(
                        "expected a JSON number at $path, found structured value $payload",
                    )
                if (!original.isNumericLiteral()) {
                    throw SerializationException(
                        "expected an unquoted JSON number at $path, found ${original.describe()} — " +
                            "quoted numeric literals violate the storage format",
                    )
                }
            }

            is JsonObject -> {
                val original = payload as? JsonObject
                    ?: throw SerializationException("expected a JSON object at $path, found $payload")
                for ((key, canonicalValue) in canonical) {
                    val originalValue = original[key] ?: continue // model default, absent in payload
                    verifyNumericWireForm(originalValue, canonicalValue, "$path.$key")
                }
            }

            is JsonArray -> {
                val original = payload as? JsonArray
                    ?: throw SerializationException("expected a JSON array at $path, found $payload")
                if (original.size != canonical.size) {
                    throw SerializationException(
                        "array length drift at $path: payload ${original.size}, canonical ${canonical.size}",
                    )
                }
                for (index in canonical.indices) {
                    verifyNumericWireForm(original[index], canonical[index], "$path[$index]")
                }
            }
        }
    }

    private fun JsonPrimitive.isNumericLiteral(): Boolean =
        this !== JsonNull && !isString && content != "true" && content != "false"

    private fun JsonPrimitive.describe(): String = when {
        this === JsonNull -> "null"
        isString -> "quoted string \"$content\""
        else -> "literal $content"
    }

    private fun LocalBuildRecord.toWire(): LocalBuildRecordFile = LocalBuildRecordFile(
        localId = localId,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastOpenedAt = lastOpenedAt,
        build = build,
    )
}

/** `schemaVersion` of `allocation-build.v1` — the only version the library embeds. */
private object BuildFileVersion {
    const val V1: Int = 1
}

/** Wire form of the whole library. Internal to the private envelope. */
@Serializable
internal data class LocalBuildLibraryFile(
    val storageVersion: Int,
    val builds: List<LocalBuildRecordFile> = emptyList(),
)

/** Wire form of one record: private metadata + the portable [BuildFile]. */
@Serializable
internal data class LocalBuildRecordFile(
    val localId: String,
    val name: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long? = null,
    val build: BuildFile,
)
