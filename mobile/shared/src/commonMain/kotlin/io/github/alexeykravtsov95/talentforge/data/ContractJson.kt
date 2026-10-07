package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceSuite
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.visuals.TalentIconRegistry
import io.github.alexeykravtsov95.talentforge.data.visuals.TalentVisualsDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strict reader for the published TalentForge JSON contracts.
 *
 * Parsing policy (deliberate, mirrors the closed-schema contracts):
 *  - unknown keys are rejected (`ignoreUnknownKeys = false`) so schema drift is visible;
 *  - unknown enum values are rejected (`coerceInputValues = false`), never replaced by a default;
 *  - missing required fields are rejected — defaults exist only where the JSON schema
 *    itself declares one;
 *  - an unsupported `schemaVersion` is an explicit [UnsupportedContractVersionException];
 *  - quoted numeric literals (`"maxRank": "3"`) are rejected wherever the model — and
 *    therefore the schema — expects a JSON number. kotlinx.serialization accepts
 *    them by design, so every contract read is followed by a wire-form check that
 *    compares the payload against the canonical re-encoding of the decoded model
 *    (see [verifyNumericWireForm]); the check is centralized here instead of being
 *    scattered across per-property serializers.
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
        decode(ContractKind.TALENT_DATA, bytes, TalentDataDocument.serializer()) { json.decodeFromString(it) }

    fun decodeDatasetManifest(bytes: ByteArray): DatasetManifest =
        decode(ContractKind.DATASET_MANIFEST, bytes, DatasetManifest.serializer()) { json.decodeFromString(it) }

    fun decodeAllocationBuildFile(bytes: ByteArray): BuildFile =
        decode(ContractKind.ALLOCATION_BUILD, bytes, BuildFile.serializer()) { json.decodeFromString(it) }

    fun decodeConformanceSuite(bytes: ByteArray): ConformanceSuite =
        decode(ContractKind.ALLOCATION_CONFORMANCE, bytes, ConformanceSuite.serializer()) { json.decodeFromString(it) }

    fun decodeTalentVisualsDocument(bytes: ByteArray): TalentVisualsDocument =
        decode(ContractKind.TALENT_VISUALS, bytes, TalentVisualsDocument.serializer()) { json.decodeFromString(it) }

    fun decodeTalentIconRegistry(bytes: ByteArray): TalentIconRegistry =
        decode(ContractKind.TALENT_ICONS, bytes, TalentIconRegistry.serializer()) { json.decodeFromString(it) }

    private inline fun <T : Any> decode(
        contract: ContractKind,
        bytes: ByteArray,
        serializer: KSerializer<T>,
        decoder: (String) -> T,
    ): T {
        val payload = bytes.decodeToString()
        val decoded = decoder(payload)
        val version = decoded.schemaVersion()
        if (version != SUPPORTED_VERSION) {
            throw UnsupportedContractVersionException(
                contract = contract,
                actualVersion = version,
                supportedVersion = SUPPORTED_VERSION,
            )
        }
        verifyNumericWireForm(
            payload = json.parseToJsonElement(payload),
            canonical = json.encodeToJsonElement(serializer, decoded),
        )
        return decoded
    }

    private fun Any.schemaVersion(): Int = when (this) {
        is TalentDataDocument -> schemaVersion
        is DatasetManifest -> schemaVersion
        is BuildFile -> schemaVersion
        is ConformanceSuite -> schemaVersion
        is TalentVisualsDocument -> schemaVersion
        is TalentIconRegistry -> schemaVersion
        else -> throw SerializationException("unexpected contract payload ${this::class.simpleName}")
    }

    /**
     * Rejects quoted (or otherwise non-numeric) literals in every position the
     * decoded model re-encodes as a JSON number. The canonical tree is the
     * model's own serialization, so it is exactly the schema's notion of which
     * properties are numeric — no parallel schema description is maintained.
     *
     * Model defaults absent from the payload have no original counterpart and
     * are skipped; unknown payload keys cannot occur (strict decoding already
     * rejected them), so payload keys always have canonical counterparts.
     */
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
                            "quoted numeric literals violate the contract wire form",
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
