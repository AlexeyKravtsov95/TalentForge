package io.github.alexeykravtsov95.talentforge.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Machine-readable JSON contract handled by the mobile client.
 *
 * Every contract is closed: an unknown field, enum value or schema version is an
 * explicit failure, never a silent fallback (see [TalentDataContract]).
 */
enum class ContractKind(val contractName: String) {
    TALENT_DATA("talent-data.v1"),
    DATASET_MANIFEST("dataset-manifest.v1"),
    ALLOCATION_BUILD("allocation-build.v1"),
    ALLOCATION_CONFORMANCE("allocation-conformance.v1"),
    TALENT_VISUALS("talent-visuals.v1"),
    TALENT_ICONS("talent-icons.v1"),
    TALENT_TOOLTIPS("talent-tooltips.v1"),
    TALENT_LOCALIZATION("talent-localization.v1"),
}

/**
 * Game identity, e.g. `forever`.
 *
 * Deliberately not a `value class`: kotlinx.serialization requires `@JvmInline`
 * for serializable value classes, and that annotation is unavailable in common
 * (non-JVM) sources. The wire form stays a plain JSON string.
 */
@Serializable(with = GameIdSerializer::class)
data class GameId(val value: String) {
    init {
        require(value.isNotEmpty()) { "game id must not be empty" }
    }

    override fun toString(): String = value
}

/** Locale-independent class token, e.g. `WARRIOR`. */
@Serializable(with = ClassTokenSerializer::class)
data class ClassToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "class token must not be empty" }
    }

    override fun toString(): String = value
}

/** Published content hash of a dataset document, e.g. `sha256:…`. */
@Serializable(with = ContentHashSerializer::class)
data class ContentHash(val value: String) {
    init {
        require(value.isNotEmpty()) { "content hash must not be empty" }
    }

    override fun toString(): String = value
}

/** Identity of one dataset bundle: a game at a specific client build. */
data class DatasetBundleId(
    val game: GameId,
    val clientBuild: String,
) {
    fun datasetPath(): String = "dataset/${game.value}/$clientBuild"
}

/** Identity of a single class dataset document inside a bundle. */
data class DatasetId(
    val game: GameId,
    val clientBuild: String,
    val classToken: ClassToken,
) {
    fun bundle(): DatasetBundleId = DatasetBundleId(game = game, clientBuild = clientBuild)
}

internal object GameIdSerializer : KSerializer<GameId> {
    override val descriptor: SerialDescriptor =
        String.serializer().descriptor

    override fun serialize(encoder: Encoder, value: GameId) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): GameId = GameId(decoder.decodeString())
}

internal object ClassTokenSerializer : KSerializer<ClassToken> {
    override val descriptor: SerialDescriptor =
        String.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ClassToken) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): ClassToken = ClassToken(decoder.decodeString())
}

internal object ContentHashSerializer : KSerializer<ContentHash> {
    override val descriptor: SerialDescriptor =
        String.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ContentHash) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): ContentHash = ContentHash(decoder.decodeString())
}
