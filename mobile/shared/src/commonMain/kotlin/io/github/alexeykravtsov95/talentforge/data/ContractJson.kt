package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceSuite
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Strict reader for the published TalentForge JSON contracts.
 *
 * Parsing policy (deliberate, mirrors the closed-schema contracts):
 *  - unknown keys are rejected (`ignoreUnknownKeys = false`) so schema drift is visible;
 *  - unknown enum values are rejected (`coerceInputValues = false`), never replaced by a default;
 *  - missing required fields are rejected — defaults exist only where the JSON schema
 *    itself declares one;
 *  - an unsupported `schemaVersion` is an explicit [UnsupportedContractVersionException].
 */
object TalentDataContract {

    /** Schema version supported for every v1 contract. */
    const val SUPPORTED_VERSION: Int = 1

    val json: Json = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
        explicitNulls = true
        encodeDefaults = true
        isLenient = false
    }

    fun decodeTalentDataDocument(bytes: ByteArray): TalentDataDocument =
        decode(ContractKind.TALENT_DATA, bytes) { json.decodeFromString(it) }

    fun decodeDatasetManifest(bytes: ByteArray): DatasetManifest =
        decode(ContractKind.DATASET_MANIFEST, bytes) { json.decodeFromString(it) }

    fun decodeAllocationBuildFile(bytes: ByteArray): BuildFile =
        decode(ContractKind.ALLOCATION_BUILD, bytes) { json.decodeFromString(it) }

    fun decodeConformanceSuite(bytes: ByteArray): ConformanceSuite =
        decode(ContractKind.ALLOCATION_CONFORMANCE, bytes) { json.decodeFromString(it) }

    private inline fun <T : Any> decode(
        contract: ContractKind,
        bytes: ByteArray,
        decoder: (String) -> T,
    ): T {
        val decoded = decoder(bytes.decodeToString())
        val version = decoded.schemaVersion()
        if (version != SUPPORTED_VERSION) {
            throw UnsupportedContractVersionException(
                contract = contract,
                actualVersion = version,
                supportedVersion = SUPPORTED_VERSION,
            )
        }
        return decoded
    }

    private fun Any.schemaVersion(): Int = when (this) {
        is TalentDataDocument -> schemaVersion
        is DatasetManifest -> schemaVersion
        is BuildFile -> schemaVersion
        is ConformanceSuite -> schemaVersion
        else -> throw SerializationException("unexpected contract payload ${this::class.simpleName}")
    }
}

/**
 * The payload is structurally valid JSON but declares a schema version this client
 * does not support. Must never be silently accepted or approximated.
 */
class UnsupportedContractVersionException(
    val contract: ContractKind,
    val actualVersion: Int,
    val supportedVersion: Int,
) : Exception(
    "unsupported schemaVersion $actualVersion for ${contract.contractName} " +
        "(supported: $supportedVersion)",
)
