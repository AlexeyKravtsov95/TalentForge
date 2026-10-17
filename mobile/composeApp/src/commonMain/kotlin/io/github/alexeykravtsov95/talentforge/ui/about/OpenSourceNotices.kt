package io.github.alexeykravtsov95.talentforge.ui.about

import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The open-source notices of one platform's distributed binary (M5.RC.2b,
 * LR-4): every third-party module it ships, grouped into components, each
 * with its licence and the full licence text bundled next to it.
 *
 * The file is GENERATED from the resolved release runtime classpath by
 * `scripts/generate_oss_notices.py` (licences read from each module's POM)
 * and drift-checked in CI — never edited by hand.
 */
data class OpenSourceNotices(
    val platform: String,
    val licenses: Map<String, NoticeLicense>,
    val components: List<NoticeComponent>,
) {
    val moduleCount: Int get() = components.sumOf { it.modules.size }
}

/** A licence the notices reference: display name and the bundled full text's resource path. */
data class NoticeLicense(val id: String, val name: String, val textPath: String)

data class NoticeComponent(
    val name: String,
    val url: String,
    val licenseId: String,
    /** `group:artifact:version` coordinates, as Gradle resolved them. */
    val modules: List<String>,
)

/** The notices file or a licence text is missing, unreadable or malformed. */
class OpenSourceNoticesException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val OSS_NOTICES_SCHEMA = "talentforge.oss-notices.v1"

/**
 * Strict parse: the exact v1 shape, no unknown or missing keys, no wrong wire
 * types, no empty lists, every component's licence declared, every licence
 * text path under `files/legal/`. Anything else throws — a half-read notice
 * list must never be shown as complete.
 */
fun parseOpenSourceNotices(text: String): OpenSourceNotices {
    val root = try {
        Json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        throw OpenSourceNoticesException("notices are not valid JSON", e)
    }.asObject("notices", setOf("schema", "platform", "source", "licenses", "components"))
    val schema = root.string("schema")
    if (schema != OSS_NOTICES_SCHEMA) throw OpenSourceNoticesException("unsupported notices schema '$schema'")
    root.string("source")

    val licenses = root.getValue("licenses").asObject("licenses", keys = null).mapValues { (id, value) ->
        val license = value.asObject("license $id", setOf("name", "file"))
        val path = license.string("file")
        if (!path.startsWith("files/legal/")) throw OpenSourceNoticesException("licence $id text outside files/legal/")
        NoticeLicense(id = id, name = license.string("name"), textPath = path)
    }
    if (licenses.isEmpty()) throw OpenSourceNoticesException("no licences declared")

    val components = root.getValue("components").asArray("components").mapIndexed { index, value ->
        val component = value.asObject("component $index", setOf("name", "url", "license", "modules"))
        val licenseId = component.string("license")
        if (licenseId !in licenses) throw OpenSourceNoticesException("component $index: undeclared licence '$licenseId'")
        val modules = component.getValue("modules").asArray("component $index modules").map { module ->
            val coordinate = (module as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw OpenSourceNoticesException("component $index: a module is not a string")
            if (coordinate.split(':').let { parts -> parts.size != 3 || parts.any(String::isEmpty) }) {
                throw OpenSourceNoticesException("component $index: '$coordinate' is not group:artifact:version")
            }
            coordinate
        }
        if (modules.isEmpty()) throw OpenSourceNoticesException("component $index has no modules")
        NoticeComponent(
            name = component.string("name"),
            url = component.string("url"),
            licenseId = licenseId,
            modules = modules,
        )
    }
    if (components.isEmpty()) throw OpenSourceNoticesException("no components listed")
    return OpenSourceNotices(platform = root.string("platform"), licenses = licenses, components = components)
}

private fun JsonElement.asObject(what: String, keys: Set<String>?): JsonObject {
    val value = this as? JsonObject ?: throw OpenSourceNoticesException("$what is not an object")
    if (keys != null && value.keys != keys) {
        throw OpenSourceNoticesException("$what has keys ${value.keys.sorted()}, expected ${keys.sorted()}")
    }
    return value
}

private fun JsonElement.asArray(what: String): JsonArray =
    this as? JsonArray ?: throw OpenSourceNoticesException("$what is not an array")

private fun JsonObject.string(key: String): String =
    (getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        ?: throw OpenSourceNoticesException("'$key' is not a non-empty string")

/** Reads a bundled legal resource (`files/legal/…`) as UTF-8 text, as a typed failure. */
suspend fun readLegalResource(path: String): String = try {
    Res.readBytes(path).decodeToString()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw OpenSourceNoticesException("cannot read $path", e)
}

/** Loads and strictly parses the notices at [path]. */
suspend fun loadOpenSourceNotices(path: String): OpenSourceNotices = parseOpenSourceNotices(readLegalResource(path))
