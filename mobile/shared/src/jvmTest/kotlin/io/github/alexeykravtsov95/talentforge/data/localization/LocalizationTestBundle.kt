package io.github.alexeykravtsov95.talentforge.data.localization

import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import io.github.alexeykravtsov95.talentforge.data.AssetNotFoundException
import io.github.alexeykravtsov95.talentforge.data.AssetReadException
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.tooltips.BundledTalentTooltipsRepository
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltips
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipsOutcome
import io.github.alexeykravtsov95.talentforge.data.visuals.BundledTalentVisualsRepository
import io.github.alexeykravtsov95.talentforge.data.visuals.TalentVisuals
import io.github.alexeykravtsov95.talentforge.data.visuals.VisualsOutcome
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The five bundled builds, oldest first (nine overlays each: M5.REL.3b.1, 70450 by M5.DATA.4). */
val LOCALIZED_BUILDS = listOf("1.60.1.70009", "1.60.1.70245", "1.60.1.70338", "1.60.1.70374", "1.60.1.70450")
const val PRODUCT_BUILD = "1.60.1.70450"

/**
 * The committed canonical files as a bundled asset root: `data/` has exactly the bundled
 * layout (`tooltips/…`, `visuals/…`, `localization/…`), so the real repositories read the
 * real data. [overrides] replaces (bytes) or removes (`null`) single asset paths.
 */
class RepoDataAssets(
    private val overrides: Map<String, ByteArray?> = emptyMap(),
    private val unreadable: Set<String> = emptySet(),
) : DatasetAssetSource {
    val reads = mutableListOf<String>()

    override suspend fun read(path: String): ByteArray {
        reads += path
        if (path in unreadable) throw AssetReadException(path)
        if (path in overrides) return overrides[path] ?: throw AssetNotFoundException(path)
        val file = TestRepoAssets.repoRoot.resolve("data").resolve(path)
        if (!file.isFile) throw AssetNotFoundException(path)
        return file.readBytes()
    }
}

/** One class of one build with its verified English sidecars — the inputs of every overlay check. */
class LocalizationCase(val build: String, val token: String) {
    val document: TalentDataDocument = TalentDataContract.decodeTalentDataDocument(
        TestRepoAssets.repoRoot.resolve("data/normalized/forever/$build/$token.json").readBytes(),
    )
    val path = "localization/forever/$build/ruRU/$token.json"
    val canonicalBytes: ByteArray get() = TestRepoAssets.repoRoot.resolve("data/$path").readBytes()

    suspend fun tooltips(): TalentTooltips =
        assertIs<TooltipsOutcome.Loaded<TalentTooltips>>(
            BundledTalentTooltipsRepository(RepoDataAssets()).tooltips(document),
        ).value

    suspend fun visuals(): TalentVisuals =
        assertIs<VisualsOutcome.Loaded<TalentVisuals>>(
            BundledTalentVisualsRepository(RepoDataAssets()).visuals(document),
        ).value

    /** The overlay outcome with this class's asset replaced by [bytes] (`null` = removed). */
    suspend fun load(
        assets: DatasetAssetSource = RepoDataAssets(),
        localeCode: String = "ruRU",
    ): LocalizationOutcome<TalentLocalization> =
        BundledTalentLocalizationRepository(assets).localization(document, tooltips(), visuals(), localeCode)

    suspend fun loadBytes(bytes: ByteArray?): LocalizationOutcome<TalentLocalization> =
        load(RepoDataAssets(overrides = mapOf(path to bytes)))

    suspend fun loadJson(json: JsonObject): LocalizationOutcome<TalentLocalization> =
        loadBytes(json.toString().encodeToByteArray())

    suspend fun loaded(): TalentLocalization = assertIs<LocalizationOutcome.Loaded<TalentLocalization>>(load()).value

    suspend fun errorOf(json: JsonObject): LocalizationLoadError =
        assertIs<LocalizationOutcome.Failed>(loadJson(json)).error

    /** The committed overlay as a JSON tree to tamper with. */
    fun json(): JsonObject = Json.parseToJsonElement(canonicalBytes.decodeToString()).jsonObject
}

// ------------------------------------------------------------------ JSON tampering helpers

fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

fun JsonObject.with(key: String, value: String): JsonObject = with(key, JsonPrimitive(value))

fun JsonObject.with(key: String, value: Int): JsonObject = with(key, JsonPrimitive(value))

fun JsonObject.without(key: String): JsonObject = JsonObject(this - key)

fun JsonObject.entries(): List<JsonObject> = getValue("entries").jsonArray.map { it.jsonObject }

fun JsonObject.withEntries(change: (List<JsonObject>) -> List<JsonObject>): JsonObject =
    with("entries", JsonArray(change(entries())))

/** Edits the entry with [entryId] (which must exist). */
fun JsonObject.withEntry(entryId: Int, change: (JsonObject) -> JsonObject): JsonObject = withEntries { list ->
    require(list.any { it.id() == entryId }) { "no entry $entryId" }
    list.map { if (it.id() == entryId) change(it) else it }
}

fun JsonObject.id(): Int = getValue("entryId").toString().toInt()

fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

/** The document with a correct `contentHash` again — tampering that a rule (not the hash) must catch. */
fun JsonObject.rehashed(): JsonObject {
    val payload = JsonObject(filterKeys { it != "contentHash" && it != "provenance" })
    return with("contentHash", "sha256:" + sha256Hex(canonicalJson(payload).encodeToByteArray()))
}

/** One rank object `{rank, segments:[{kind,text}...]}`. */
fun rankJson(rank: Int, vararg segments: Pair<String, String>): JsonObject = JsonObject(
    mapOf(
        "rank" to JsonPrimitive(rank),
        "segments" to JsonArray(
            segments.map { (kind, text) -> JsonObject(mapOf("kind" to JsonPrimitive(kind), "text" to JsonPrimitive(text))) },
        ),
    ),
)

/** Real Warrior entries (stable across the four builds). */
object Warrior {
    /** Translated name, rendered description (3 ranks). */
    const val IMPROVED_HEROIC_STRIKE = 130688

    /** Translated name; English sidecar partial → `sourceNotResolved`. */
    const val BLOODTHIRST = 130660

    /** Translated name; three-form Russian plural → `pluralRuleUnverified`. */
    const val FLURRY = 130658

    /** Translated name; English `5.5` min → `decimalFormatUnverified`. */
    const val IMPROVED_SHIELD_WALL = 130694

    /** Forever-only talent (70338+): the ruRU export serves the English name → `untranslated`. */
    const val GORE_DRINKER = 141191
}
