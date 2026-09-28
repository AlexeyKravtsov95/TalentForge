package io.github.alexeykravtsov95.talentforge.data.manifest

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.GameId
import kotlinx.serialization.Serializable

/**
 * Root of a `dataset-manifest.v1` document (docs/schema/dataset-manifest.v1.schema.json):
 * the inventory of one dataset bundle. The manifest is the dataset list —
 * consumers never rely on hardcoded file names.
 */
@Serializable
data class DatasetManifest(
    val schemaVersion: Int = 1,
    val game: GameId,
    val clientBuild: String,
    val generatedAt: String,
    val source: String,
    val classes: List<ManifestClassEntry>,
)

/** One class document inside the bundle, ordered by class id. */
@Serializable
data class ManifestClassEntry(
    val token: ClassToken,
    val file: String,
    val treeIds: List<Int>,
    val contentHash: ContentHash,
    val validation: ManifestValidation,
)

/** Validation summary recorded by the publishing pipeline. */
@Serializable
data class ManifestValidation(
    val errors: Int,
    val warnings: Int,
    val warningsByCode: Map<String, Int> = emptyMap(),
)
