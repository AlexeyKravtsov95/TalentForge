package io.github.alexeykravtsov95.talentforge.data.visuals

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.GameId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root of a `talent-visuals.v1` document (docs/schema/talent-visuals.v1.schema.json):
 * the icon FileDataID of every talent entry of one class at one client build.
 *
 * A presentation sidecar of `talent-data.v1`, never a part of it. Every
 * property is required on the wire — there are deliberately no Kotlin
 * defaults, so a missing field (including `schemaVersion`) fails decoding.
 */
@Serializable
data class TalentVisualsDocument(
    val schemaVersion: Int,
    val game: GameId,
    val clientBuild: String,
    val classToken: ClassToken,
    /** `contentHash` of the `talent-data.v1` document these visuals were resolved for. */
    val talentDataContentHash: ContentHash,
    val entries: List<TalentVisualEntry>,
    val contentHash: ContentHash,
    val provenance: TalentVisualsProvenance,
)

/** One talent entry's icon: `iconFileDataId` keys into the build's `talent-icons.v1`. */
@Serializable
data class TalentVisualEntry(
    val entryId: Int,
    val definitionId: Int,
    val spellId: Int,
    val displaySpellId: Int,
    val iconFileDataId: Int,
)

@Serializable
data class TalentVisualsProvenance(
    val source: String,
    val tables: List<String>,
    val iconColumn: String,
)

/**
 * Root of a `talent-icons.v1` document (docs/schema/talent-icons.v1.schema.json):
 * the registry mapping every icon FileDataID of one client build to its
 * bundled image file. All properties are required on the wire.
 */
@Serializable
data class TalentIconRegistry(
    val schemaVersion: Int,
    val game: GameId,
    val clientBuild: String,
    val imageFormat: TalentIconImageFormat,
    val icons: List<TalentIconAsset>,
    val contentHash: ContentHash,
    val provenance: TalentIconsProvenance,
)

/** One bundled image; [file] is relative to the registry (`icons/<fileDataId>.png`). */
@Serializable
data class TalentIconAsset(
    val fileDataId: Int,
    val file: String,
    val width: Int,
    val height: Int,
    val byteLength: Int,
    val sha256: ContentHash,
    val sourceFormat: TalentIconSourceFormat,
    val sourceByteLength: Int,
    val sourceSha256: ContentHash,
)

/** Closed: the only bundled image format of `talent-icons.v1`. */
@Serializable
enum class TalentIconImageFormat {
    @SerialName("png")
    PNG,
}

/** Closed: the only client container `talent-icons.v1` records. */
@Serializable
enum class TalentIconSourceFormat {
    @SerialName("BLP2")
    BLP2,
}

@Serializable
data class TalentIconsProvenance(
    val source: String,
    val retrieval: String,
    val converter: String,
)
