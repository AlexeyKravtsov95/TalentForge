import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// ------------------------------------------------- OTA settings (M5.OTA.3, client design §8)
//
// Compile-time OTA configuration per platform (android, ios) and channel (production for
// release/store builds, staging for debug/development builds), generated into
// GeneratedOtaSettings.kt. Android and iOS are enabled independently (OD-17). The values come
// ONLY from Gradle properties outside the repository or the environment:
//
//   talentforge.ota.<platform>.<channel>.enabled   true | false (default false)
//   talentforge.ota.<platform>.<channel>.baseUrl   https://… (the channel's static origin, OD-1)
//   talentforge.ota.<platform>.<channel>.keyring   path to {"keys":[{keyId, algorithm, publicKeySpki}]}
//   talentforge.ota.android.staging.trustAnchor    path to a PEM CA certificate (M5.OTA.4a): the only
//       TLS root of a debug build's staging client, for a staging stand outside the public PKI
//       (docs/ota/staging-setup.md). Certificate and hostname verification stay on.
//   (environment: TALENTFORGE_OTA_<PLATFORM>_<CHANNEL>_<ENABLED|BASEURL|KEYRING|TRUSTANCHOR>)
//
// Nothing is set in the repository, so every build keeps OTA disabled until the owner supplies
// hosting and public keys (OD-1, OD-2). An enabled channel without a valid https base URL and
// keyring, a test- key id (reserved for fixtures, contract §8), a key not made for the channel
// (production pins only tf-prod-* keys, staging only tf-staging-*, publisher-and-security §5.1 —
// checked on each keyring alone, whatever the other channel's settings), a production keyring
// without a backup key, one public key pinned in both channels, or a trust anchor anywhere but the
// Android staging channel (or one that is not a plain certificate) fails the build. A store release
// build never carries staging settings: release variants stop on any Android staging setting
// (:composeApp:checkReleaseOtaSettings). :composeApp:verifyOtaSettingsRules (run before
// desktopTest) proves each rule on fixed inputs.
//
// M5.OTA.PROD.3: the Android production channel is pinned in the repository — the origin
// https://ota.mythweld.com and the public keyring docs/ota/keys/production-keyring.json
// (tf-prod-2026a + backup tf-prod-2026b). talentforge.ota.android.production.enabled=true is the
// only setting the owner supplies (docs/ota/android-production-enablement.md); its baseUrl and
// keyring default to the pinned values, and any other value fails the build. Unset, the channel
// stays off and the generated settings are those of the OTA-off release. The committed keyring's
// content is pinned as well, by the SHA-256 of each key's DER SPKI (the fingerprints the key
// ceremony prints, docs/ota/production-deployment.md §4.4): every build fails while it differs.
//
// M5.IOS.2: the iOS production channel is pinned to the same origin and keyring by the same
// rules; talentforge.ota.ios.production.enabled=true is its only owner setting
// (docs/release/apple-appstore/m5-ios-2-ota-readiness.md). An iOS release framework is linked only
// while no staging setting (Android or iOS) is set (checkReleaseOtaSettings), so a store archive
// never carries staging or test keys.
val otaPlatforms = listOf("android", "ios")
val otaChannels = listOf("production", "staging")
val otaKeyIdPrefixes = mapOf("production" to "tf-prod-", "staging" to "tf-staging-")
val productionOtaOrigin = "https://ota.mythweld.com"
val productionOtaKeyringFile: File = rootProject.file("../docs/ota/keys/production-keyring.json")
val productionOtaSpkiSha256 = mapOf(
    "tf-prod-2026a" to "1fac1b0d15f2a09fc3769099623fe1038e846f0033ec6e233f5111ff71239278",
    "tf-prod-2026b" to "6997b3d47d74ceeb6daec62b1d94f3f3e3bc476534eefe8cd140f67578532b26",
)

/** SHA-256 (hex) of a base64 DER SPKI, or null when it is not base64. */
fun spkiSha256(base64Spki: String): String? = runCatching<String> {
    MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(base64Spki))
        .joinToString("") { byte -> "%02x".format(byte) }
}.getOrNull()

/** Why [keys] are not exactly the keys whose SPKI fingerprints [expected] lists (empty: they are). */
fun keyringFingerprintProblems(keys: List<Map<String, String>>, expected: Map<String, String>): List<String> {
    val ids = keys.map { it.getValue("keyId") }
    if (ids.sorted() != expected.keys.sorted()) return listOf("pins $ids, expected ${expected.keys.toList()}")
    return keys.mapNotNull { key ->
        val id = key.getValue("keyId")
        val actual = spkiSha256(key.getValue("publicKeySpki"))
        if (actual == expected.getValue(id)) null else "$id has SPKI SHA-256 $actual, expected ${expected.getValue(id)}"
    }
}

fun otaSetting(platform: String, channel: String, name: String): String? =
    (providers.environmentVariable("TALENTFORGE_OTA_${platform.uppercase()}_${channel.uppercase()}_${name.uppercase()}").orNull
        ?: providers.gradleProperty("talentforge.ota.$platform.$channel.$name").orNull)
        ?.takeIf { it.isNotBlank() }

fun otaKotlinString(value: String?): String =
    if (value == null) "null" else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        .replace("\r", "").replace("\n", "\\n") + "\""

/** The raw settings of one platform x channel, as read from properties and the keyring file. */
class OtaChannelInput(
    val platform: String,
    val channel: String,
    val enabledRaw: String?,
    val baseUrl: String?,
    val keys: List<Map<String, String>>,
    val keyringProblem: String? = null,
    /** The PEM text of `trustAnchor` (M5.OTA.4a), or null. */
    val trustAnchorPem: String? = null,
    /** M5.OTA.PROD.3: the only base URL this channel may name, or null (not pinned). */
    val pinnedBaseUrl: String? = null,
    /** M5.OTA.PROD.3: the only keys this channel may pin (the committed keyring), or null (not pinned). */
    val pinnedKeys: List<Map<String, String>>? = null,
    /** M5.OTA.PROD.3: the SPKI SHA-256 per key id that [pinnedKeys] must have, or null. */
    val pinnedSpkiSha256: Map<String, String>? = null,
)

/** Every reason these settings must fail the build (empty: valid). Pure: verifyOtaSettingsRules runs it on fixed inputs. */
fun otaSettingsProblems(inputs: List<OtaChannelInput>): List<String> {
    val baseUrlPattern = Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~-]+)*")
    val problems = mutableListOf<String>()
    for (input in inputs) {
        val where = "talentforge.ota.${input.platform}.${input.channel}"
        input.keyringProblem?.let { problems += "$where.keyring $it" }
        if (input.enabledRaw !in listOf(null, "true", "false")) problems += "$where.enabled must be true or false"
        if (input.enabledRaw == "true") {
            if (input.baseUrl == null || !baseUrlPattern.matches(input.baseUrl)) problems += "$where.baseUrl must be an https origin without query or trailing slash"
            if (input.keys.isEmpty()) problems += "$where.keyring must pin at least one key"
            if (input.channel == "production" && input.keys.size < 2) problems += "$where.keyring must pin a primary and a backup key"
        }
        val ids = input.keys.map { it.getValue("keyId") }
        if (ids.any { it.startsWith("test-") }) problems += "$where.keyring contains a test- key (fixtures only)"
        val prefix = otaKeyIdPrefixes.getValue(input.channel)
        ids.filterNot { it.startsWith("test-") || it.startsWith(prefix) }.forEach {
            problems += "$where.keyring pins $it, which is not a ${input.channel} key ($prefix*)"
        }
        if (input.keys.any { it.getValue("algorithm") != "ecdsa-p256-sha256" }) problems += "$where.keyring: every key must be ecdsa-p256-sha256"
        if (input.pinnedBaseUrl != null && input.baseUrl != null && input.baseUrl != input.pinnedBaseUrl) {
            problems += "$where.baseUrl is pinned to ${input.pinnedBaseUrl}; unset it (the origin is not configurable)"
        }
        if (input.pinnedKeys != null && input.keys.isNotEmpty() && input.keys.toSet() != input.pinnedKeys.toSet()) {
            problems += "$where.keyring must pin exactly the committed production keyring (${input.pinnedKeys.joinToString { it.getValue("keyId") }}); unset it"
        }
        // checked on every build, enabled or not: the committed keyring cannot change unnoticed
        if (input.pinnedKeys != null && input.pinnedSpkiSha256 != null) {
            keyringFingerprintProblems(input.pinnedKeys, input.pinnedSpkiSha256).forEach {
                problems += "$where: the committed production keyring changed: $it (update the pinned fingerprints only after the key ceremony record)"
            }
        }
        input.trustAnchorPem?.let { pem ->
            if (input.platform != "android" || input.channel != "staging") problems += "$where.trustAnchor: a trust anchor is for the Android staging channel only"
            if ("PRIVATE KEY" in pem) problems += "$where.trustAnchor contains a private key"
            if (!pem.trimStart().startsWith("-----BEGIN CERTIFICATE-----") || Regex("-----BEGIN (?!CERTIFICATE-----)").containsMatchIn(pem)) {
                problems += "$where.trustAnchor must be PEM certificates only"
            }
        }
    }
    for (platform in inputs.map { it.platform }.distinct()) {
        val production = inputs.filter { it.platform == platform && it.channel == "production" }.flatMap { it.keys }.map { it["publicKeySpki"] }.toSet()
        if (inputs.filter { it.platform == platform && it.channel == "staging" }.flatMap { it.keys }.any { it["publicKeySpki"] in production }) {
            problems += "talentforge.ota.$platform: a staging key is pinned in the production keyring"
        }
    }
    return problems
}

fun readKeyringEntries(file: File): List<Map<String, String>> {
    @Suppress("UNCHECKED_CAST")
    val parsed = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
    return (parsed["keys"] as? List<Map<String, Any?>>).orEmpty().map { key ->
        listOf("keyId", "algorithm", "publicKeySpki").associateWith { (key[it] as? String).orEmpty() }
    }
}

fun readOtaChannelInput(platform: String, channel: String): OtaChannelInput =
    otaChannelInputOf(platform, channel) { name -> otaSetting(platform, channel, name) }

/** The input of one platform x channel from its raw settings [setting] (`enabled`, `baseUrl`, `keyring`, `trustAnchor`). */
fun otaChannelInputOf(platform: String, channel: String, setting: (String) -> String?): OtaChannelInput {
    // M5.OTA.PROD.3 (Android), M5.IOS.2 (iOS): every production channel is pinned
    val pinned = channel == "production"
    val enabledRaw = setting("enabled")
    // the pinned channel, when enabled, defaults to the committed origin and keyring
    val defaults = pinned && enabledRaw == "true"
    val keyringPath = setting("keyring") ?: productionOtaKeyringFile.path.takeIf { defaults }
    var keyringProblem: String? = null
    val entries: List<Map<String, String>> = if (keyringPath == null) emptyList() else {
        val file = File(keyringPath)
        if (!file.isFile) { keyringProblem = "does not name a file"; emptyList() } else readKeyringEntries(file)
    }
    val trustAnchorPath = setting("trustAnchor")
    // a path that names no file reads as "" and is refused as "not PEM certificates"
    val trustAnchorPem = trustAnchorPath?.let { path -> File(path).takeIf { it.isFile }?.readText().orEmpty() }
    return OtaChannelInput(
        platform, channel, enabledRaw,
        setting("baseUrl") ?: productionOtaOrigin.takeIf { defaults },
        entries, keyringProblem, trustAnchorPem,
        pinnedBaseUrl = productionOtaOrigin.takeIf { pinned },
        pinnedKeys = if (pinned) readKeyringEntries(productionOtaKeyringFile) else null,
        pinnedSpkiSha256 = productionOtaSpkiSha256.takeIf { pinned },
    )
}

// M5.OTA.PROD.3: OTA settings are the owner's, per build: one committed to the repository's
// gradle.properties would turn OTA on (or point it elsewhere) for every checkout and CI build.
val committedOtaKeys: List<String> = listOf(rootProject.file("gradle.properties"), file("gradle.properties"))
    .filter { it.isFile }
    .flatMap { properties -> properties.readLines().map { it.substringBefore('=').trim() }.filter { it.startsWith("talentforge.ota.") } }

fun renderOtaSettings(): String {
    val inputs = otaPlatforms.flatMap { platform -> otaChannels.map { channel -> readOtaChannelInput(platform, channel) } }
    val problems = otaSettingsProblems(inputs) + committedOtaKeys.distinct().map {
        "$it must not be committed: set it in the environment, ~/.gradle/gradle.properties or -P (docs/ota/android-production-enablement.md)"
    }
    if (problems.isNotEmpty()) throw GradleException("Invalid OTA settings:\n  " + problems.joinToString("\n  "))
    return renderOtaSettingsSource(inputs)
}

/** GeneratedOtaSettings.kt for [inputs] (one per platform x channel), already validated. Pure: verifyOtaSettingsRules renders fixed inputs. */
fun renderOtaSettingsSource(inputs: List<OtaChannelInput>): String {
    val sections = StringBuilder()
    for (input in inputs) {
        val name = input.platform + input.channel.replaceFirstChar { it.uppercase() }
        sections.append("    val $name: OtaPlatformSettings = OtaPlatformSettings(\n")
        sections.append("        enabled = ${input.enabledRaw == "true"},\n")
        sections.append("        channel = OtaChannel.${input.channel.uppercase()},\n")
        sections.append("        baseUrl = ${otaKotlinString(input.baseUrl)},\n")
        sections.append("        keys = listOf(\n")
        for (key in input.keys) {
            sections.append("            OtaKeySetting(${otaKotlinString(key["keyId"])}, ${otaKotlinString(key["algorithm"])}, ${otaKotlinString(key["publicKeySpki"])}),\n")
        }
        sections.append("        ),\n    )\n\n")
    }
    val anchor = inputs.single { it.platform == "android" && it.channel == "staging" }.trustAnchorPem
    sections.append("    /** The only TLS root of the Android staging client (debug builds), or null: the platform's. */\n")
    sections.append("    val androidStagingTrustAnchorPem: String? = ${otaKotlinString(anchor)}\n")
    return "// GENERATED by :composeApp:generateOtaSettings from Gradle properties - do not edit.\n" +
        "package io.github.alexeykravtsov95.talentforge.ui.ota\n\n" +
        "import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel\n\n" +
        "internal object GeneratedOtaSettings {\n" + sections.toString().trimEnd() + "\n}\n"
}

/**
 * The negative cases of the settings rules (PR #44 review P1-2), on fixed inputs: each must be
 * refused with its own reason, and a correct two-channel configuration must pass. Returns what
 * did not hold (empty: every rule holds).
 */
fun otaSettingsRuleFailures(): List<String> {
    fun key(id: String, spki: String) = mapOf("keyId" to id, "algorithm" to "ecdsa-p256-sha256", "publicKeySpki" to spki)
    fun production(vararg keys: Map<String, String>) = OtaChannelInput("android", "production", "true", "https://ota.example.org", keys.toList())
    fun staging(vararg keys: Map<String, String>) = OtaChannelInput("android", "staging", "true", "https://staging.example.org", keys.toList())
    val certificate = "-----BEGIN CERTIFICATE-----\nQ0E=\n-----END CERTIFICATE-----\n"
    val prodA = key("tf-prod-2026a", "PA")
    val prodB = key("tf-prod-2026b", "PB")
    val stagingKey = key("tf-staging-2026", "S")
    fun pinnedProduction(baseUrl: String, vararg keys: Map<String, String>) =
        OtaChannelInput("android", "production", "true", baseUrl, keys.toList(), pinnedBaseUrl = productionOtaOrigin, pinnedKeys = listOf(prodA, prodB))
    val refused = listOf(
        // a staging key in production with no staging channel configured at all
        "staging key in production, staging absent" to listOf(production(prodA, prodB, stagingKey)) to "pins tf-staging-2026, which is not a production key",
        // ... and with a staging channel configured with other keys
        "staging key in production, staging uses other keys" to listOf(production(prodA, key("tf-staging-2025", "OLD")), staging(stagingKey)) to "pins tf-staging-2025, which is not a production key",
        "only staging keys in production" to listOf(production(key("tf-staging-a", "S1"), key("tf-staging-b", "S2"))) to "which is not a production key (tf-prod-*)",
        "a key of no known purpose in production" to listOf(production(prodA, key("release-b", "R"))) to "pins release-b, which is not a production key",
        "a production key in staging" to listOf(staging(stagingKey, key("tf-prod-2026a", "PA2"))) to "pins tf-prod-2026a, which is not a staging key",
        "a test key" to listOf(production(prodA, key("test-backup", "T"))) to "contains a test- key",
        "a production keyring without a backup" to listOf(production(prodA)) to "must pin a primary and a backup key",
        "one public key in both channels" to listOf(production(prodA, prodB), staging(key("tf-staging-2026", "PA"))) to "a staging key is pinned in the production keyring",
        "a disabled channel with a staging key in production" to listOf(OtaChannelInput("ios", "production", null, null, listOf(prodA, stagingKey))) to "pins tf-staging-2026, which is not a production key",
        "a trust anchor in production" to listOf(OtaChannelInput("android", "production", "true", "https://ota.example.org", listOf(prodA, prodB), trustAnchorPem = certificate)) to "for the Android staging channel only",
        "a trust anchor on iOS" to listOf(OtaChannelInput("ios", "staging", "true", "https://staging.example.org", listOf(stagingKey), trustAnchorPem = certificate)) to "for the Android staging channel only",
        "a private key as trust anchor" to listOf(OtaChannelInput("android", "staging", "true", "https://staging.example.org", listOf(stagingKey), trustAnchorPem = certificate + "-----BEGIN PRIVATE KEY-----\nK\n-----END PRIVATE KEY-----\n")) to "contains a private key",
        "a trust anchor that is not a certificate" to listOf(OtaChannelInput("android", "staging", "true", "https://staging.example.org", listOf(stagingKey), trustAnchorPem = "not a certificate")) to "must be PEM certificates only",
        // M5.OTA.PROD.3: the pinned Android production channel
        "another origin for the pinned channel" to listOf(pinnedProduction("https://ota.example.org", prodA, prodB)) to "baseUrl is pinned to https://ota.mythweld.com",
        "an http origin for the pinned channel" to listOf(pinnedProduction("http://ota.mythweld.com", prodA, prodB)) to "must be an https origin",
        "other production keys for the pinned channel" to listOf(pinnedProduction(productionOtaOrigin, prodA, key("tf-prod-2026c", "PC"))) to "must pin exactly the committed production keyring",
        "a staging key added to the pinned keyring" to listOf(pinnedProduction(productionOtaOrigin, prodA, prodB, stagingKey)) to "pins tf-staging-2026, which is not a production key",
        "a test key added to the pinned keyring" to listOf(pinnedProduction(productionOtaOrigin, prodA, prodB, key("test-a", "T"))) to "contains a test- key",
        "a trust anchor on the pinned channel" to listOf(OtaChannelInput("android", "production", "true", productionOtaOrigin, listOf(prodA, prodB), trustAnchorPem = certificate, pinnedBaseUrl = productionOtaOrigin, pinnedKeys = listOf(prodA, prodB))) to "for the Android staging channel only",
    )
    val failures = mutableListOf<String>()
    for ((case, expected) in refused) {
        val (name, inputs) = case
        val problems = otaSettingsProblems(inputs)
        if (problems.none { expected in it }) failures += "$name: expected a problem containing \"$expected\", got $problems"
    }
    val valid = otaSettingsProblems(listOf(production(prodA, prodB), staging(stagingKey)))
    if (valid.isNotEmpty()) failures += "a correct configuration was refused: $valid"
    val anchored = otaSettingsProblems(listOf(production(prodA, prodB), OtaChannelInput("android", "staging", "true", "https://10.0.2.2:8443", listOf(stagingKey), trustAnchorPem = certificate)))
    if (anchored.isNotEmpty()) failures += "a staging trust anchor was refused: $anchored"
    if (otaSettingsProblems(emptyList()).isNotEmpty()) failures += "no settings must be valid (OTA off)"
    val pinned = otaSettingsProblems(listOf(pinnedProduction(productionOtaOrigin, prodB, prodA), staging(stagingKey)))
    if (pinned.isNotEmpty()) failures += "the pinned production channel was refused: $pinned"
    // the committed keyring itself: exactly the primary and the backup production key, valid for the
    // pinned channel and carrying the pinned SPKI fingerprints
    val committed = readKeyringEntries(productionOtaKeyringFile)
    val committedIds = committed.map { it.getValue("keyId") }
    if (committedIds != listOf("tf-prod-2026a", "tf-prod-2026b")) failures += "the committed production keyring pins $committedIds, expected [tf-prod-2026a, tf-prod-2026b]"
    fun committedInput(keys: List<Map<String, String>>) = OtaChannelInput(
        "android", "production", "true", productionOtaOrigin, keys,
        pinnedBaseUrl = productionOtaOrigin, pinnedKeys = keys, pinnedSpkiSha256 = productionOtaSpkiSha256,
    )
    val committedProblems = otaSettingsProblems(listOf(committedInput(committed)))
    if (committedProblems.isNotEmpty()) failures += "the committed production keyring was refused: $committedProblems"
    // ... and a keyring whose content differs from the fingerprints is refused, enabled or not
    val (a, b) = committed
    val tampered = listOf(
        "the backup key replaced by the primary's SPKI" to listOf(a, b + ("publicKeySpki" to a.getValue("publicKeySpki"))) to "tf-prod-2026b has SPKI SHA-256 ${productionOtaSpkiSha256.getValue("tf-prod-2026a")}",
        "a changed SPKI byte" to listOf(a + ("publicKeySpki" to a.getValue("publicKeySpki").replaceRange(40, 41, if (a.getValue("publicKeySpki")[40] == 'A') "B" else "A")), b) to "tf-prod-2026a has SPKI SHA-256",
        "the backup key missing" to listOf(a) to "pins [tf-prod-2026a], expected [tf-prod-2026a, tf-prod-2026b]",
        "an extra production key" to listOf(a, b, key("tf-prod-2026c", a.getValue("publicKeySpki"))) to "expected [tf-prod-2026a, tf-prod-2026b]",
    )
    for ((case, expected) in tampered) {
        val (name, keys) = case
        for (platform in otaPlatforms) for (enabled in listOf("true", null)) {
            val problems = otaSettingsProblems(listOf(OtaChannelInput(platform, "production", enabled, null, emptyList(), pinnedBaseUrl = productionOtaOrigin, pinnedKeys = keys, pinnedSpkiSha256 = productionOtaSpkiSha256)))
            if (problems.none { "the committed production keyring changed" in it && expected in it }) failures += "tampered keyring ($name, $platform, enabled=$enabled): expected \"$expected\", got $problems"
        }
    }
    failures += iosProductionRuleFailures(committed)
    return failures
}

/**
 * M5.IOS.2: the pinned iOS production channel, through the same path an owner's build takes
 * ([otaChannelInputOf] from raw settings, then [otaSettingsProblems] and [renderOtaSettingsSource]).
 * `enabled=true` alone yields exactly the committed origin, channel and two keys; every attempt to
 * name another origin, keyring, channel key or a staging/test key is refused; unset, nothing of
 * production reaches the generated source. Returns what did not hold.
 */
fun iosProductionRuleFailures(committed: List<Map<String, String>>): List<String> {
    val failures = mutableListOf<String>()
    val scratch = layout.buildDirectory.dir("tmp/verifyOtaSettingsRules").get().asFile.apply { mkdirs() }
    fun keyringFile(name: String, keys: List<Map<String, String>>): String = File(scratch, "$name.json").apply {
        writeText(groovy.json.JsonOutput.toJson(mapOf("keys" to keys)))
    }.path
    fun key(id: String, spki: String) = mapOf("keyId" to id, "algorithm" to "ecdsa-p256-sha256", "publicKeySpki" to spki)
    val (prodA, prodB) = committed
    val stagingKeys = listOf(key("tf-staging-2026", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEstaging"))
    fun ios(channel: String, vararg settings: Pair<String, String>) = otaChannelInputOf("ios", channel) { settings.toMap()[it] }
    fun offExcept(vararg inputs: OtaChannelInput): List<OtaChannelInput> = otaPlatforms.flatMap { platform ->
        otaChannels.map { channel -> inputs.singleOrNull { it.platform == platform && it.channel == channel } ?: otaChannelInputOf(platform, channel) { null } }
    }
    val where = "talentforge.ota.ios.production"
    val refused = listOf(
        "another origin" to ios("production", "enabled" to "true", "baseUrl" to "https://ota.example.org") to "$where.baseUrl is pinned to $productionOtaOrigin",
        "the staging origin" to ios("production", "enabled" to "true", "baseUrl" to "https://talentforge-ota-staging.hb.ru-msk.vkcloud-storage.ru") to "$where.baseUrl is pinned to $productionOtaOrigin",
        "an http origin" to ios("production", "enabled" to "true", "baseUrl" to "http://ota.mythweld.com") to "$where.baseUrl must be an https origin",
        "the origin with a path" to ios("production", "enabled" to "true", "baseUrl" to "$productionOtaOrigin/v2") to "$where.baseUrl is pinned to $productionOtaOrigin",
        "another origin while disabled" to ios("production", "baseUrl" to "https://ota.example.org") to "$where.baseUrl is pinned to $productionOtaOrigin",
        "the staging keyring" to ios("production", "enabled" to "true", "keyring" to keyringFile("staging", stagingKeys)) to "$where.keyring pins tf-staging-2026, which is not a production key",
        "a test key added" to ios("production", "enabled" to "true", "keyring" to keyringFile("test", listOf(prodA, prodB, key("test-primary", "T")))) to "$where.keyring contains a test- key",
        "a staging key added" to ios("production", "enabled" to "true", "keyring" to keyringFile("mixed", listOf(prodA, prodB) + stagingKeys)) to "$where.keyring pins tf-staging-2026, which is not a production key",
        "the primary key only" to ios("production", "enabled" to "true", "keyring" to keyringFile("primary", listOf(prodA))) to "$where.keyring must pin a primary and a backup key",
        "another production key" to ios("production", "enabled" to "true", "keyring" to keyringFile("other", listOf(prodA, key("tf-prod-2026c", "PC")))) to "$where.keyring must pin exactly the committed production keyring",
        "the committed key ids over other SPKIs" to ios("production", "enabled" to "true", "keyring" to keyringFile("swapped", listOf(prodA + ("publicKeySpki" to prodB.getValue("publicKeySpki")), prodB))) to "$where.keyring must pin exactly the committed production keyring",
        "another algorithm" to ios("production", "enabled" to "true", "keyring" to keyringFile("rsa", listOf(prodA + ("algorithm" to "rsa-pss-sha256"), prodB))) to "$where.keyring: every key must be ecdsa-p256-sha256",
        "a keyring that is no file" to ios("production", "enabled" to "true", "keyring" to File(scratch, "missing.json").path) to "$where.keyring does not name a file",
        "a trust anchor" to ios("production", "enabled" to "true", "trustAnchor" to File(scratch, "missing.pem").path) to "$where.trustAnchor: a trust anchor is for the Android staging channel only",
        "enabled neither true nor false" to ios("production", "enabled" to "yes") to "$where.enabled must be true or false",
        "a production key in the iOS staging keyring" to ios("staging", "enabled" to "true", "baseUrl" to "https://localhost:8443", "keyring" to keyringFile("staging-prod", listOf(prodA))) to "talentforge.ota.ios.staging.keyring pins tf-prod-2026a, which is not a staging key",
    )
    for ((case, expected) in refused) {
        val (name, input) = case
        val problems = otaSettingsProblems(offExcept(input))
        if (problems.none { expected in it }) failures += "iOS production, $name: expected a problem containing \"$expected\", got $problems"
    }
    fun section(source: String, name: String): String = source.substringAfter("    val $name: OtaPlatformSettings", "").substringBefore("\n\n")
    // ON: enabled=true alone (and the same with the committed values spelled out) pins exactly the committed origin and keys
    for (on in listOf(ios("production", "enabled" to "true"), ios("production", "enabled" to "true", "baseUrl" to productionOtaOrigin, "keyring" to productionOtaKeyringFile.path))) {
        val inputs = offExcept(on)
        val problems = otaSettingsProblems(inputs)
        if (problems.isNotEmpty()) failures += "iOS production ON was refused: $problems"
        val source = renderOtaSettingsSource(inputs)
        val ios = section(source, "iosProduction")
        val expectedKeys = committed.map { "OtaKeySetting(${otaKotlinString(it["keyId"])}, \"ecdsa-p256-sha256\", ${otaKotlinString(it["publicKeySpki"])})" }
        val keyLines = ios.lines().filter { "OtaKeySetting(" in it }.map { it.trim().removeSuffix(",") }
        if ("enabled = true," !in ios || "channel = OtaChannel.PRODUCTION," !in ios || "baseUrl = \"$productionOtaOrigin\"," !in ios || keyLines != expectedKeys) {
            failures += "iOS production ON did not generate exactly the pinned origin, the production channel and [tf-prod-2026a, tf-prod-2026b]: $ios"
        }
        for (other in listOf("androidProduction", "androidStaging", "iosStaging")) {
            val section = section(source, other)
            if ("enabled = false," !in section || "baseUrl = null," !in section || "OtaKeySetting(" in section) failures += "iOS production ON changed $other: $section"
        }
    }
    // OFF: nothing set (or enabled=false) generates no origin and no key anywhere
    for (off in listOf(ios("production"), ios("production", "enabled" to "false"))) {
        val inputs = offExcept(off)
        val problems = otaSettingsProblems(inputs)
        if (problems.isNotEmpty()) failures += "iOS production OFF was refused: $problems"
        val source = renderOtaSettingsSource(inputs)
        if ("ota.mythweld.com" in source || committed.any { it.getValue("publicKeySpki") in source } || "enabled = true" in source) {
            failures += "iOS production OFF generated production settings: $source"
        }
    }
    return failures
}

val verifyOtaSettingsRules = tasks.register("verifyOtaSettingsRules") {
    group = "verification"
    description = "Proves the OTA settings rules refuse staging, test and unknown keys in production (and the reverse)."
    val failures = provider { otaSettingsRuleFailures() }
    inputs.property("failures", failures)
    doLast {
        val found = failures.get()
        if (found.isNotEmpty()) throw GradleException("OTA settings rules do not hold:\n  " + found.joinToString("\n  "))
    }
}

val generatedOtaSettingsDir = layout.buildDirectory.dir("generated/ota/commonMain/kotlin")
val generateOtaSettings = tasks.register("generateOtaSettings") {
    group = "build"
    description = "Generates the per-platform OTA settings (disabled unless the owner configures hosting and keys)."
    val source = provider { renderOtaSettings() }
    val outputDirectory = generatedOtaSettingsDir
    inputs.property("source", source)
    outputs.dir(outputDirectory)
    doLast {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val file = File(root, "io/github/alexeykravtsov95/talentforge/ui/ota/GeneratedOtaSettings.kt")
        file.parentFile.mkdirs()
        file.writeText(source.get())
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm("desktop")

    // M5.OTA.3: Android and the desktop host share one java.security / java.nio /
    // HttpURLConnection implementation of the OTA platform ports (jvmCommonMain).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateOtaSettings)
        }

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(project(":shared"))
        }

        iosTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.compose.ui.test)
            }
        }
    }
}

// ------------------------------------------------- Release signing (M5.RC.2b, TF-RC1-SIGNING)
//
// One owner-held key signs the one universal APK that goes to RuStore and AppGallery
// (docs/release/appgallery/multi-store-signing.md). The four signing settings come ONLY
// from the environment (`TALENTFORGE_RELEASE_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS`,
// `_KEY_PASSWORD`) or from Gradle properties outside the repository
// (`talentforge.release.store_file`, … in ~/.gradle/gradle.properties); the environment
// wins. The expected certificate fingerprint for the publishable APK comes the same way
// (`TALENTFORGE_RELEASE_CERT_SHA256` / `talentforge.release.cert_sha256`).
//
// - none set: `assembleRelease` builds the UNSIGNED technical release APK, as before;
// - all four set and valid: the release APK is signed with that key;
// - anything else (some set, keystore missing, inside the repository, or the Android
//   debug keystore): every release build fails, naming the missing or wrong settings
//   but never printing a value.
//
// The release build type never falls back to the debug key. The ONLY artifact meant for
// upload is the output of `:composeApp:assemblePublishableRelease`, which additionally
// requires the complete signing settings and the expected fingerprint, and verifies the
// APK with scripts/verify_release_apk.py before writing it to build/outputs/publishable/.
val releaseSigningSettings = listOf("store_file", "store_password", "key_alias", "key_password")

fun releaseSettingNames(name: String) = "TALENTFORGE_RELEASE_${name.uppercase()} / talentforge.release.$name"

fun releaseSetting(name: String): String? =
    (providers.environmentVariable("TALENTFORGE_RELEASE_${name.uppercase()}").orNull
        ?: providers.gradleProperty("talentforge.release.$name").orNull)
        ?.takeIf { it.isNotBlank() }

val repositoryRoot: File = rootProject.projectDir.parentFile.canonicalFile
// where AGP keeps the SDK debug keystore (ANDROID_USER_HOME overrides ~/.android)
val androidDebugKeystore: File = File(
    providers.environmentVariable("ANDROID_USER_HOME").orNull ?: "${System.getProperty("user.home")}/.android",
    "debug.keystore",
)
val releaseSigning: Map<String, String?> = releaseSigningSettings.associateWith(::releaseSetting)

// Signing values never belong in a committed file: a talentforge.release.* or talentforge.play.*
// key in the repository's gradle.properties fails the build whatever its value.
val committedReleaseKeys: List<String> = listOf(rootProject.file("gradle.properties"), file("gradle.properties"))
    .filter { it.isFile }
    .flatMap { properties ->
        properties.readLines()
            .map { it.substringBefore('=').trim() }
            .filter { it.startsWith("talentforge.release.") || it.startsWith("talentforge.play.") }
    }

val releaseSigningProblem: String? = run {
    val missing = releaseSigning.filterValues { it == null }.keys
    val storeFile = releaseSigning["store_file"]?.let { File(it).canonicalFile }
    when {
        committedReleaseKeys.isNotEmpty() ->
            "Release signing settings must not be committed: remove ${committedReleaseKeys.distinct().joinToString()} " +
                "from the repository's gradle.properties and set them in the environment or ~/.gradle/gradle.properties."
        missing.size == releaseSigningSettings.size -> null
        missing.isNotEmpty() ->
            "Incomplete release signing configuration. Missing: ${missing.joinToString { releaseSettingNames(it) }}. " +
                "Set: ${(releaseSigningSettings - missing).joinToString { releaseSettingNames(it) }}. " +
                "Set all four or none (none = unsigned technical release build). Values are not printed."
        storeFile == null || !storeFile.isFile ->
            "${releaseSettingNames("store_file")} does not point to an existing keystore file."
        storeFile.path.startsWith(repositoryRoot.path + File.separator) ->
            "The release keystore must be stored outside the repository checkout."
        storeFile == androidDebugKeystore.canonicalFile || releaseSigning["key_alias"] == "androiddebugkey" ->
            "Release signing must not use the Android debug keystore or its androiddebugkey alias."
        else -> null
    }
}
val releaseSigningComplete = releaseSigningProblem == null && releaseSigning.values.all { it != null }

val expectedReleaseCertSha256: String? = releaseSetting("cert_sha256")
    ?.replace(":", "")
    ?.lowercase()

val publishableProblem: String? = when {
    releaseSigningProblem != null -> releaseSigningProblem
    !releaseSigningComplete ->
        "The publishable release APK needs the release signing configuration: set " +
            releaseSigningSettings.joinToString { releaseSettingNames(it) } +
            " (docs/release/android-release-signing.md). It is never built unsigned or debug-signed."
    expectedReleaseCertSha256 == null ->
        "The publishable release APK needs the expected certificate fingerprint: set ${releaseSettingNames("cert_sha256")}."
    !Regex("[0-9a-f]{64}").matches(expectedReleaseCertSha256) ->
        "${releaseSettingNames("cert_sha256")} must be a SHA-256 fingerprint (64 hexadecimal characters)."
    else -> null
}

// ------------------------------------------------- Google Play upload signing (M5.PLAY.1)
//
// Google Play takes an app bundle signed with a separate UPLOAD key. Play App Signing then signs
// the APKs it delivers with the app signing key: the owner's release key above, which the owner
// gives to Play once, so that installs from all three stores carry one certificate
// (docs/release/google-play/signing.md). The `play` build type is `release` signed with the upload
// key, and exists only to produce that bundle. Its four settings come, like the release ones, ONLY
// from the environment (`TALENTFORGE_PLAY_UPLOAD_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS`,
// `_KEY_PASSWORD`) or from `talentforge.play.upload.*` in ~/.gradle/gradle.properties:
//
// - none set: `bundlePlay` builds an UNSIGNED technical bundle; it is never uploadable;
// - all four set and valid: `bundlePlay` signs the bundle with the upload key;
// - anything else (some set, keystore missing or inside the checkout, the Android debug keystore,
//   or the release keystore's own key): every `play` build fails, naming settings, never values.
//
// The `play` build type never packages an APK (packagePlay fails): an upload-key APK distributed
// through RuStore or AppGallery would leave those users unable to install any later update. The
// ONLY bundle meant for upload is the output of `:composeApp:bundlePublishablePlay`, which also
// needs the expected upload certificate fingerprint (`TALENTFORGE_PLAY_UPLOAD_CERT_SHA256`) and
// the app signing fingerprint (`TALENTFORGE_RELEASE_CERT_SHA256`, which the bundle must NOT
// carry), and verifies the bundle with scripts/verify_play_bundle.py before writing it to
// build/outputs/publishable-google-play/. The release keystore itself is not needed for it.
fun playUploadSettingNames(name: String) = "TALENTFORGE_PLAY_UPLOAD_${name.uppercase()} / talentforge.play.upload.$name"

fun playUploadSetting(name: String): String? =
    (providers.environmentVariable("TALENTFORGE_PLAY_UPLOAD_${name.uppercase()}").orNull
        ?: providers.gradleProperty("talentforge.play.upload.$name").orNull)
        ?.takeIf { it.isNotBlank() }

val playUploadSigning: Map<String, String?> = releaseSigningSettings.associateWith(::playUploadSetting)

val playUploadProblem: String? = run {
    val missing = playUploadSigning.filterValues { it == null }.keys
    val storeFile = playUploadSigning["store_file"]?.let { File(it).canonicalFile }
    val releaseStoreFile = releaseSigning["store_file"]?.let { File(it).canonicalFile }
    when {
        committedReleaseKeys.isNotEmpty() ->
            "Release signing settings must not be committed: remove ${committedReleaseKeys.distinct().joinToString()} " +
                "from the repository's gradle.properties and set them in the environment or ~/.gradle/gradle.properties."
        missing.size == releaseSigningSettings.size -> null
        missing.isNotEmpty() ->
            "Incomplete Google Play upload signing configuration. Missing: ${missing.joinToString { playUploadSettingNames(it) }}. " +
                "Set: ${(releaseSigningSettings - missing).joinToString { playUploadSettingNames(it) }}. " +
                "Set all four or none (none = unsigned technical bundle). Values are not printed."
        storeFile == null || !storeFile.isFile ->
            "${playUploadSettingNames("store_file")} does not point to an existing keystore file."
        storeFile.path.startsWith(repositoryRoot.path + File.separator) ->
            "The upload keystore must be stored outside the repository checkout."
        storeFile == androidDebugKeystore.canonicalFile || playUploadSigning["key_alias"] == "androiddebugkey" ->
            "Upload signing must not use the Android debug keystore or its androiddebugkey alias."
        storeFile == releaseStoreFile && playUploadSigning["key_alias"] == releaseSigning["key_alias"] ->
            "The Google Play upload key must not be the app signing key: ${playUploadSettingNames("store_file")} and " +
                "${playUploadSettingNames("key_alias")} name the release key. Use the separate upload key (docs/release/google-play/signing.md)."
        else -> null
    }
}
val playUploadComplete = playUploadProblem == null && playUploadSigning.values.all { it != null }

val expectedPlayUploadCertSha256: String? = playUploadSetting("cert_sha256")
    ?.replace(":", "")
    ?.lowercase()

val playPublishableProblem: String? = when {
    playUploadProblem != null -> playUploadProblem
    !playUploadComplete ->
        "The publishable Google Play bundle needs the upload signing configuration: set " +
            releaseSigningSettings.joinToString { playUploadSettingNames(it) } +
            " (docs/release/google-play/signing.md). It is never built unsigned, debug-signed or with the app signing key."
    expectedPlayUploadCertSha256 == null ->
        "The publishable Google Play bundle needs the expected upload certificate fingerprint: set ${playUploadSettingNames("cert_sha256")}."
    !Regex("[0-9a-f]{64}").matches(expectedPlayUploadCertSha256) ->
        "${playUploadSettingNames("cert_sha256")} must be a SHA-256 fingerprint (64 hexadecimal characters)."
    expectedReleaseCertSha256 == null ->
        "The publishable Google Play bundle needs the app signing certificate fingerprint, to prove the bundle is not " +
            "signed with it: set ${releaseSettingNames("cert_sha256")}."
    !Regex("[0-9a-f]{64}").matches(expectedReleaseCertSha256) ->
        "${releaseSettingNames("cert_sha256")} must be a SHA-256 fingerprint (64 hexadecimal characters)."
    expectedPlayUploadCertSha256 == expectedReleaseCertSha256 ->
        "${playUploadSettingNames("cert_sha256")} equals ${releaseSettingNames("cert_sha256")}: Google Play needs a " +
            "separate upload key, never the app signing key (docs/release/google-play/signing.md)."
    else -> null
}

// Signing self-test runs only (scripts/*signing_selftest.sh): the validators then require a
// self-test certificate, report SELFTEST-PASS instead of PASS, and the staged files are named
// *-SELFTEST-NOT-FOR-UPLOAD. Without it, a self-test certificate fails validation. Environment only.
val signingSelftest: Boolean = providers.environmentVariable("TALENTFORGE_SIGNING_SELFTEST").orNull == "true"

// The channel a release or play build reads (AndroidOta); it decides the NET-1 manifest overlay.
val androidProductionOtaEnabled: Boolean = otaSetting("android", "production", "enabled") == "true"
val releaseManifestOverlay: String =
    if (androidProductionOtaEnabled) "src/androidReleaseOta/AndroidManifest.xml" else "src/androidRelease/AndroidManifest.xml"

android {
    namespace = "io.github.alexeykravtsov95.talentforge"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.alexeykravtsov95.talentforge"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningComplete) {
            create("release") {
                storeFile = File(releaseSigning.getValue("store_file")!!)
                storePassword = releaseSigning.getValue("store_password")
                keyAlias = releaseSigning.getValue("key_alias")
                keyPassword = releaseSigning.getValue("key_password")
            }
        }
        if (playUploadComplete) {
            create("playUpload") {
                storeFile = File(playUploadSigning.getValue("store_file")!!)
                storePassword = playUploadSigning.getValue("store_password")
                keyAlias = playUploadSigning.getValue("key_alias")
                keyPassword = playUploadSigning.getValue("key_password")
            }
        }
    }

    // NET-1: the release manifest overlay. OTA off (the default, NET-1 = B): src/androidRelease
    // removes INTERNET. talentforge.ota.android.production.enabled=true (M5.OTA.PROD.3):
    // src/androidReleaseOta declares it for the production channel instead. `:composeApp:sourceSets`
    // reports src/androidRelease for `release`, but with this KMP/AGP pair the manifest merger ignored
    // the file until it was set here; checkReleaseInternetPermission fails the release build if the
    // merged manifest disagrees with the setting.
    sourceSets.getByName("release").manifest.srcFile(releaseManifestOverlay)

    // Opt-in isolated debug install for device checks (M5.UX.2b.1):
    // `-Ptalentforge.applicationIdSuffix=.devcheck` installs next to the real
    // app with its own data, so a scripted check never sees or changes the
    // user's saved builds. Unset (the default): the unchanged application id.
    buildTypes {
        getByName("debug") {
            providers.gradleProperty("talentforge.applicationIdSuffix").orNull?.let { applicationIdSuffix = it }
        }
        getByName("release") {
            // the owner's key, or none (unsigned) — never signingConfigs.debug
            signingConfig = signingConfigs.findByName("release")
        }
        // M5.PLAY.1: release, signed with the Google Play upload key (or none: unsigned) — never
        // the release key it would otherwise inherit, never signingConfigs.debug. Bundle only.
        create("play") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("playUpload")
            matchingFallbacks += "release"
        }
    }
    // the same NET-1 overlay as release: the Play bundle requests INTERNET exactly when release does
    sourceSets.getByName("play").manifest.srcFile(releaseManifestOverlay)
}

// Every release-variant build stops here on an incomplete or invalid signing setup.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "release"
    description = "Fails on an incomplete or invalid release signing configuration (never prints values)."
    val problem = releaseSigningProblem
    doLast { if (problem != null) throw GradleException(problem) }
}

val checkPublishableSigning = tasks.register("checkPublishableSigning") {
    group = "release"
    description = "Fails unless the complete release signing configuration and the expected fingerprint are set."
    val problem = publishableProblem
    doLast { if (problem != null) throw GradleException(problem) }
}

// M5.OTA.4a: a store release build never carries the staging configuration of a device test
// (staging base URL, staging keyring, the stand's trust anchor) — not even unused: the generated
// OTA settings are compiled into every variant, so release variants stop on any of them. Since
// M5.OTA.PROD.3 this covers the iOS staging channel too: GeneratedOtaSettings holds every platform,
// so an iOS staging keyring set on the release machine would otherwise land in the Android APK.
val stagingOtaSettings: List<String> = otaPlatforms.flatMap { platform ->
    listOf("enabled", "baseUrl", "keyring", "trustAnchor")
        .filter { name -> otaSetting(platform, "staging", name).let { it != null && !(name == "enabled" && it == "false") } }
        .map { "talentforge.ota.$platform.staging.$it" }
}
val checkReleaseOtaSettings = tasks.register("checkReleaseOtaSettings") {
    group = "release"
    description = "Fails a release-variant build while any staging OTA setting (Android or iOS) is set."
    val present = stagingOtaSettings
    doLast {
        if (present.isNotEmpty()) {
            throw GradleException("Release builds never carry staging OTA settings; unset ${present.joinToString(", ")} (debug/device-test builds only).")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseSigning)
    dependsOn(checkReleaseOtaSettings)
    mustRunAfter(checkPublishableSigning)
}

// M5.IOS.2: the App Store archive links the release framework (embedAndSignAppleFrameworkForXcode
// with CONFIGURATION=Release), so the same staging guard runs before every iOS release link.
tasks.matching { it.name.startsWith("linkRelease") && it.name.contains("Ios") }.configureEach {
    dependsOn(checkReleaseOtaSettings)
}

// M5.IOS.2: IosOtaStagingE2ETest needs the HTTPS origin and simulator of scripts/ios_ota_e2e.py,
// which spawns its phases one process at a time; the plain simulator test run leaves it out.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    filter.excludeTestsMatching("*IosOtaStagingE2ETest*")
}

// M5.PLAY.1: the same gates for the Google Play bundle, with the upload key's settings.
val checkPlayUploadSigning = tasks.register("checkPlayUploadSigning") {
    group = "release"
    description = "Fails on an incomplete or invalid Google Play upload signing configuration (never prints values)."
    val problem = playUploadProblem
    doLast { if (problem != null) throw GradleException(problem) }
}

val checkPlayPublishable = tasks.register("checkPlayPublishable") {
    group = "release"
    description = "Fails unless the upload signing configuration and both distinct fingerprints are set."
    val problem = playPublishableProblem
    doLast { if (problem != null) throw GradleException(problem) }
}

tasks.matching { it.name == "prePlayBuild" }.configureEach {
    dependsOn(checkPlayUploadSigning)
    dependsOn(checkReleaseOtaSettings)
    mustRunAfter(checkPlayPublishable)
}

// The play build type is for the Google Play bundle only. Its APK would carry the upload key's
// certificate; distributed through RuStore or AppGallery it would leave users unable to update.
val refusePlayApk = tasks.register("refusePlayApk") {
    group = "release"
    description = "Fails every attempt to package an APK of the play build type (bundle only)."
    doLast {
        throw GradleException(
            "The play build type builds only the Google Play bundle (:composeApp:bundlePublishablePlay). " +
                "APKs for RuStore and AppGallery come from :composeApp:assemblePublishableRelease (the app signing key).",
        )
    }
}
tasks.matching { it.name == "packagePlay" }.configureEach { dependsOn(refusePlayApk) }

// ------------------------------------------------- INTERNET in release builds (NET-1 = B)
//
// Owner decision NET-1 = B (docs/release/store1-release-audit.md §5.4): while the production OTA
// channel is off, a release build requests no network access; src/androidRelease/AndroidManifest.xml
// removes the INTERNET permission that androidMain declares for the staging channel of debug builds.
// With talentforge.ota.android.production.enabled=true (M5.OTA.PROD.3) the release and play variants
// merge src/androidReleaseOta/AndroidManifest.xml instead, which keeps it for the production channel.
// Each release variant's MERGED manifest must declare INTERNET exactly when
// talentforge.ota.android.production.enabled is true (the channel a release build reads), so the
// variant cannot be packaged when production OTA is enabled without the permission (the client could
// not connect), nor when the permission is back while OTA is off (the store and privacy texts say
// the app does not request internet access). :composeApp:verifyReleaseInternetRules (run before
// desktopTest) proves the rule on fixed manifests.
object ReleaseInternetRule {
    const val INTERNET = "android.permission.INTERNET"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** The permissions a manifest requests (`uses-permission` and `uses-permission-sdk-23`); malformed XML throws. */
    fun requestedPermissions(manifestXml: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val builder = factory.newDocumentBuilder()
        // throw instead of also printing "[Fatal Error]" to stderr
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        })
        val manifest = builder.parse(InputSource(StringReader(manifestXml))).documentElement
        return listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val elements = manifest.getElementsByTagNameNS("*", tag)
            (0 until elements.length).map { (elements.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }
    }

    /** Why this release manifest must not be packaged with this production OTA setting, or null. */
    fun problem(productionOtaEnabled: Boolean, mergedManifestXml: String): String? {
        val requested = INTERNET in requestedPermissions(mergedManifestXml)
        return when {
            productionOtaEnabled && !requested ->
                "talentforge.ota.android.production.enabled is true, but the release manifest does not request $INTERNET, " +
                    "so the production OTA client could not connect. OTA-enabled release builds merge " +
                    "src/androidReleaseOta/AndroidManifest.xml, which must declare it (NET-1, M5.OTA.PROD.3)."
            !productionOtaEnabled && requested ->
                "The release manifest requests $INTERNET while production OTA is off. NET-1 = B: OTA-off release builds request " +
                    "no network access (the OTA-off store and privacy texts rely on it). Keep the tools:node=\"remove\" in " +
                    "src/androidRelease/AndroidManifest.xml, or change the NET-1 decision and its texts deliberately."
            else -> null
        }
    }

    /** The rule on fixed manifests; returns what did not hold (empty: the rule holds). */
    fun failures(): List<String> {
        fun manifest(vararg body: String) =
            "<manifest xmlns:android=\"$ANDROID_NS\" package=\"t\">" + body.joinToString("") + "<application/></manifest>"
        val internet = "<uses-permission android:name=\"$INTERNET\"/>"
        val other = "<uses-permission android:name=\"android.permission.ACCESS_NETWORK_STATE\"/>"
        val cases = listOf(
            Triple("OTA off, no INTERNET", false to manifest(other), null),
            Triple("OTA off, no permissions at all", false to manifest(), null),
            Triple("OTA on, INTERNET", true to manifest(other, internet), null),
            Triple("OTA off, INTERNET", false to manifest(internet), "while production OTA is off"),
            Triple("OTA off, INTERNET as uses-permission-sdk-23", false to manifest("<uses-permission-sdk-23 android:name=\"$INTERNET\"/>"), "while production OTA is off"),
            Triple("OTA on, no INTERNET", true to manifest(other), "could not connect"),
            Triple("OTA on, INTERNET only in a comment", true to manifest("<!-- $internet -->"), "could not connect"),
            Triple("OTA on, INTERNET without the android namespace", true to manifest("<uses-permission name=\"$INTERNET\"/>"), "could not connect"),
        )
        val failures = mutableListOf<String>()
        for ((name, input, expected) in cases) {
            val found = problem(input.first, input.second)
            if (expected == null && found != null) failures += "$name: expected no problem, got \"$found\""
            if (expected != null && (found == null || expected !in found)) failures += "$name: expected a problem containing \"$expected\", got $found"
        }
        if (runCatching { problem(false, "<manifest") }.isSuccess) failures += "a malformed manifest must fail the check, not pass it"
        return failures
    }
}

val verifyReleaseInternetRules = tasks.register("verifyReleaseInternetRules") {
    group = "verification"
    description = "Proves the release INTERNET rule (NET-1 = B) on fixed manifests."
    doLast {
        val found = ReleaseInternetRule.failures()
        if (found.isNotEmpty()) throw GradleException("Release INTERNET rule does not hold:\n  " + found.joinToString("\n  "))
    }
}

abstract class CheckReleaseInternetPermission : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val productionOtaEnabled: Property<Boolean>

    @TaskAction
    fun check() {
        ReleaseInternetRule.problem(productionOtaEnabled.get(), mergedManifest.get().asFile.readText())?.let { throw GradleException(it) }
    }
}

/**
 * Copies the signed release APK to `build/outputs/publishable/TalentForge-<versionName>-<versionCode>.apk`
 * and keeps it only if scripts/verify_release_apk.py passes (report next to it). On any failure
 * — validation or any exception — the publishable directory is deleted: there is no other upload
 * artifact. (A failure BEFORE this task runs is covered by the task-graph hook below.)
 */
abstract class AssemblePublishableApk @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val artifactsLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    abstract val validator: RegularFileProperty

    @get:Input
    abstract val buildToolsDirectory: Property<String>

    @get:Input
    abstract val keytool: Property<String>

    @get:Internal
    abstract val debugKeystore: Property<String>

    @get:Input
    abstract val expectedCertificateSha256: Property<String>

    /** The Google Play upload certificate (M5.PLAY.1), when configured: the APK must not carry it. */
    @get:Input
    abstract val uploadCertificateSha256: Property<String>

    @get:Input
    abstract val allowInternet: Property<Boolean>

    @get:Input
    abstract val selftest: Property<Boolean>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {
        val built = artifactsLoader.get().load(apkDirectory.get())
            ?: throw GradleException("No release APK was produced.")
        val apk = built.elements.singleOrNull()?.outputFile?.let(::File)
            ?: throw GradleException("Expected exactly one universal release APK, found ${built.elements.size}.")
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        try {
            stage(apk, out)
        } catch (failure: Throwable) {
            out.deleteRecursively()
            throw failure
        }
    }

    private fun stage(apk: File, out: File) {
        out.mkdirs()
        val suffix = if (selftest.get()) "-SELFTEST-NOT-FOR-UPLOAD" else ""
        val name = "TalentForge-${versionName.get()}-${versionCode.get()}$suffix.apk"
        val staged = File(out, name)
        apk.copyTo(staged, overwrite = true)
        val log = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(
                listOf(
                    "python3", validator.get().asFile.path,
                    "--apk", staged.path,
                    "--expected-cert-sha256", expectedCertificateSha256.get(),
                    "--expected-version-code", versionCode.get().toString(),
                    "--expected-version-name", versionName.get(),
                    "--build-tools", buildToolsDirectory.get(),
                    "--keytool", keytool.get(),
                    "--debug-keystore", debugKeystore.get(),
                    "--report", File(out, "$name.report.json").path,
                ) + listOfNotNull(
                    uploadCertificateSha256.get().takeIf { it.isNotEmpty() }?.let { "--must-differ-from-cert-sha256=$it" },
                    "--allow-permission=android.permission.INTERNET".takeIf { allowInternet.get() },
                    "--selftest".takeIf { selftest.get() },
                ),
            )
            standardOutput = log
            errorOutput = log
            isIgnoreExitValue = true
        }
        logger.lifecycle(log.toString())
        if (result.exitValue != 0) {
            throw GradleException("The release APK failed validation (exit ${result.exitValue}); nothing is publishable.")
        }
        logger.lifecycle("Publishable APK: ${staged.path}")
    }
}

val assemblePublishableRelease = tasks.register<AssemblePublishableApk>("assemblePublishableRelease") {
    group = "release"
    description = "Builds, verifies and stages the one signed universal APK for RuStore and AppGallery."
    dependsOn(checkPublishableSigning)
    validator.set(rootProject.layout.projectDirectory.file("../scripts/verify_release_apk.py"))
    buildToolsDirectory.set(
        androidComponents.sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}").asFile.path },
    )
    keytool.set(File(System.getProperty("java.home"), "bin/keytool").path)
    debugKeystore.set(androidDebugKeystore.path)
    expectedCertificateSha256.set(expectedReleaseCertSha256.orEmpty())
    uploadCertificateSha256.set(expectedPlayUploadCertSha256?.takeIf { Regex("[0-9a-f]{64}").matches(it) }.orEmpty())
    allowInternet.set(androidProductionOtaEnabled)
    selftest.set(signingSelftest)
    outputDirectory.set(layout.buildDirectory.dir("outputs/publishable"))
    outputs.upToDateWhen { false }
}

/**
 * M5.PLAY.1: copies the upload-key-signed `play` bundle to
 * `build/outputs/publishable-google-play/TalentForge-<versionName>-<versionCode>.aab` and keeps it
 * only if scripts/verify_play_bundle.py passes (report next to it). On any failure the directory is
 * deleted; a failure before this task runs is covered by the task-graph hook below.
 */
abstract class BundlePublishablePlay @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputFile
    abstract val bundle: RegularFileProperty

    @get:InputFile
    abstract val validator: RegularFileProperty

    @get:Input
    abstract val keytool: Property<String>

    @get:Input
    abstract val jarsigner: Property<String>

    @get:Internal
    abstract val debugKeystore: Property<String>

    @get:Input
    abstract val expectedUploadCertificateSha256: Property<String>

    @get:Input
    abstract val appSigningCertificateSha256: Property<String>

    @get:Input
    abstract val allowInternet: Property<Boolean>

    @get:Input
    abstract val selftest: Property<Boolean>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        try {
            stage(bundle.get().asFile, out)
        } catch (failure: Throwable) {
            out.deleteRecursively()
            throw failure
        }
    }

    private fun stage(aab: File, out: File) {
        out.mkdirs()
        val suffix = if (selftest.get()) "-SELFTEST-NOT-FOR-UPLOAD" else ""
        val name = "TalentForge-${versionName.get()}-${versionCode.get()}$suffix.aab"
        val staged = File(out, name)
        aab.copyTo(staged, overwrite = true)
        val log = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(
                listOf(
                    "python3", validator.get().asFile.path,
                    "--aab", staged.path,
                    "--expected-upload-cert-sha256", expectedUploadCertificateSha256.get(),
                    "--app-signing-cert-sha256", appSigningCertificateSha256.get(),
                    "--expected-version-code", versionCode.get().toString(),
                    "--expected-version-name", versionName.get(),
                    "--keytool", keytool.get(),
                    "--jarsigner", jarsigner.get(),
                    "--debug-keystore", debugKeystore.get(),
                    "--report", File(out, "$name.report.json").path,
                ) + listOfNotNull(
                    "--allow-permission=android.permission.INTERNET".takeIf { allowInternet.get() },
                    "--selftest".takeIf { selftest.get() },
                ),
            )
            standardOutput = log
            errorOutput = log
            isIgnoreExitValue = true
        }
        logger.lifecycle(log.toString())
        if (result.exitValue != 0) {
            throw GradleException("The Google Play bundle failed validation (exit ${result.exitValue}); nothing is publishable.")
        }
        logger.lifecycle("Publishable Google Play bundle: ${staged.path}")
    }
}

val bundlePublishablePlay = tasks.register<BundlePublishablePlay>("bundlePublishablePlay") {
    group = "release"
    description = "Builds, verifies and stages the upload-key-signed app bundle for Google Play."
    dependsOn(checkPlayPublishable)
    validator.set(rootProject.layout.projectDirectory.file("../scripts/verify_play_bundle.py"))
    keytool.set(File(System.getProperty("java.home"), "bin/keytool").path)
    jarsigner.set(File(System.getProperty("java.home"), "bin/jarsigner").path)
    debugKeystore.set(androidDebugKeystore.path)
    expectedUploadCertificateSha256.set(expectedPlayUploadCertSha256.orEmpty())
    appSigningCertificateSha256.set(expectedReleaseCertSha256.orEmpty())
    allowInternet.set(androidProductionOtaEnabled)
    selftest.set(signingSelftest)
    outputDirectory.set(layout.buildDirectory.dir("outputs/publishable-google-play"))
    outputs.upToDateWhen { false }
}

// Fail-closed staging (M5.RC.2b review): a publishable build that fails ANYWHERE — the signing
// checks, compilation, packaging, validation — must not leave the previous attempt's APK in
// build/outputs/publishable/. Task order cannot guarantee that (checkReleaseSigning, compile or
// :shared tasks may fail before any publishable task runs), so the directory is cleared as soon
// as the task graph containing assemblePublishableRelease is ready, before any task executes.
// It is refilled only by a fully successful assemblePublishableRelease.
val publishableOutput = layout.buildDirectory.dir("outputs/publishable")
val publishablePlayOutput = layout.buildDirectory.dir("outputs/publishable-google-play")
gradle.taskGraph.whenReady {
    if (hasTask("${project.path}:assemblePublishableRelease")) {
        publishableOutput.get().asFile.deleteRecursively()
    }
    // M5.PLAY.1: the same for the Google Play bundle
    if (hasTask("${project.path}:bundlePublishablePlay")) {
        publishablePlayOutput.get().asFile.deleteRecursively()
    }
}

androidComponents {
    // release (RuStore/AppGallery APK) and play (Google Play bundle, M5.PLAY.1)
    onVariants(selector().all()) onVariant@{ variant ->
        if (variant.buildType !in setOf("release", "play")) return@onVariant
        val output = variant.outputs.single()
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val checkInternet = tasks.register<CheckReleaseInternetPermission>("check${variantName}InternetPermission") {
            group = "release"
            description = "Fails unless the merged ${variant.name} manifest requests INTERNET exactly when production OTA is enabled (NET-1)."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            productionOtaEnabled.set(androidProductionOtaEnabled)
        }
        // every APK or bundle of the variant, including the publishable APK, is packaged only after the check
        tasks.matching { it.name == "package$variantName" || it.name == "package${variantName}Bundle" }.configureEach {
            dependsOn(checkInternet)
        }
        if (variant.buildType == "play") {
            bundlePublishablePlay.configure {
                bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
                versionCode.set(output.versionCode.map { it ?: 0 })
                versionName.set(output.versionName.map { it.orEmpty() })
            }
            return@onVariant
        }
        assemblePublishableRelease.configure {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            versionCode.set(output.versionCode.map { it ?: 0 })
            versionName.set(output.versionName.map { it.orEmpty() })
        }
    }
}

// Opt-in screenshot evidence: `./gradlew :composeApp:desktopTest \
//     -Dtalentforge.m41.screenshots=<dir>` (M4.1), `-Dtalentforge.m42.screenshots`
// (M4.2), `-Dtalentforge.m43.screenshots` (M4.3) or
// `-Dtalentforge.m51.screenshots` (M5.1) or `-Dtalentforge.m5v1.screenshots`
// (M5.V1, desktop-rendered) or `-Dtalentforge.m5ux.screenshots` (M5.UX.1,
// desktop-rendered section tabs) or `-Dtalentforge.m5ux2.screenshots`
// (M5.UX.2a, adaptive inspector and header) or `-Dtalentforge.m5ux2b1.screenshots`
// (M5.UX.2b.1, landscape phones) or `-Dtalentforge.m5ui1.screenshots`
// (M5.UI.1, B+ Home / All Builds) or `-Dtalentforge.m5ui2.screenshots`
// (M5.UI.2, B+ Class Selection) or `-Dtalentforge.m5rel1.screenshots`
// (M5.REL.1, localized specialization names) or
// `-Dtalentforge.m5rel2a.screenshots` (M5.REL.2a, localized shell screens) or
// `-Dtalentforge.m5rel2b.screenshots` (M5.REL.2b, localized talent editor) or
// `-Dtalentforge.m5rel3b2.screenshots` (M5.REL.3b.2, Russian talent text) or
// `-Dtalentforge.m5share2.screenshots` (M5.SHARE.2, build export/import) or
// `-Dtalentforge.m5uiabout1.screenshots` (M5.UI.ABOUT.1, About and licenses) or
// `-Dtalentforge.m5ota3.screenshots` (M5.OTA.3, talent data updates) or
// `-Dtalentforge.m5tablet1.screenshots` (M5.UI.TABLET.1, tablet specialization headers) or
// `-Dtalentforge.m5releasefinal1.screenshots` (M5.RELEASE.FINAL.1, OTA-enabled About) or
// `-Dtalentforge.ota.remote=<dir>` (M5.OTA.4b, remote staging check) or
// `-Dtalentforge.ota.replay=<dir>` (M5.OTA.AUTO.2, historical patch replay) or
// `-Dtalentforge.pr19.recording`
// (PR #19, gesture frames) — skipped by default.
tasks.named<Test>("desktopTest") {
    dependsOn(verifyOtaSettingsRules)
    dependsOn(verifyReleaseInternetRules)
    // The drift and baseline tests read the canonical repository data directly; without this
    // input a data-only change replays a cached result and the drift is never checked.
    inputs.dir(repositoryRoot.resolve("data")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("canonicalData")
    System.getProperty("talentforge.m41.screenshots")?.let {
        systemProperty("talentforge.m41.screenshots", it)
    }
    System.getProperty("talentforge.m42.screenshots")?.let {
        systemProperty("talentforge.m42.screenshots", it)
    }
    System.getProperty("talentforge.m43.screenshots")?.let {
        systemProperty("talentforge.m43.screenshots", it)
    }
    System.getProperty("talentforge.m51.screenshots")?.let {
        systemProperty("talentforge.m51.screenshots", it)
    }
    System.getProperty("talentforge.m52a.screenshots")?.let {
        systemProperty("talentforge.m52a.screenshots", it)
    }
    System.getProperty("talentforge.m5v1.screenshots")?.let {
        systemProperty("talentforge.m5v1.screenshots", it)
    }
    System.getProperty("talentforge.m5ux.screenshots")?.let {
        systemProperty("talentforge.m5ux.screenshots", it)
    }
    System.getProperty("talentforge.m5ux2.screenshots")?.let {
        systemProperty("talentforge.m5ux2.screenshots", it)
    }
    System.getProperty("talentforge.m5ux2b1.screenshots")?.let {
        systemProperty("talentforge.m5ux2b1.screenshots", it)
    }
    System.getProperty("talentforge.m5ui1.screenshots")?.let {
        systemProperty("talentforge.m5ui1.screenshots", it)
    }
    System.getProperty("talentforge.m5ui2.screenshots")?.let {
        systemProperty("talentforge.m5ui2.screenshots", it)
    }
    System.getProperty("talentforge.m5rel1.screenshots")?.let {
        systemProperty("talentforge.m5rel1.screenshots", it)
    }
    System.getProperty("talentforge.m5rel2a.screenshots")?.let {
        systemProperty("talentforge.m5rel2a.screenshots", it)
    }
    System.getProperty("talentforge.m5rel2b.screenshots")?.let {
        systemProperty("talentforge.m5rel2b.screenshots", it)
    }
    System.getProperty("talentforge.m5rel3b2.screenshots")?.let {
        systemProperty("talentforge.m5rel3b2.screenshots", it)
    }
    System.getProperty("talentforge.m5share2.screenshots")?.let {
        systemProperty("talentforge.m5share2.screenshots", it)
    }
    System.getProperty("talentforge.m5uiabout1.screenshots")?.let {
        systemProperty("talentforge.m5uiabout1.screenshots", it)
    }
    System.getProperty("talentforge.ota.benchmark")?.let {
        systemProperty("talentforge.ota.benchmark", it)
    }
    System.getProperty("talentforge.ota.interop")?.let {
        systemProperty("talentforge.ota.interop", it)
    }
    System.getProperty("talentforge.ota.staging")?.let {
        systemProperty("talentforge.ota.staging", it)
    }
    // M5.OTA.AUTO.2: OtaHistoricalReplayTest (opt-in; the replay's output directory)
    System.getProperty("talentforge.ota.replay")?.let {
        systemProperty("talentforge.ota.replay", it)
    }
    // M5.OTA.4b: OtaRemoteStagingCheck against the remote staging origin (opt-in; out dir,
    // phase, base URL, keyring, runs, parallelism)
    System.getProperties().stringPropertyNames().filter { it.startsWith("talentforge.ota.remote") }.forEach {
        systemProperty(it, System.getProperty(it))
    }
    System.getProperty("talentforge.m5ota3.screenshots")?.let {
        systemProperty("talentforge.m5ota3.screenshots", it)
    }
    System.getProperty("talentforge.m5tablet1.screenshots")?.let {
        systemProperty("talentforge.m5tablet1.screenshots", it)
    }
    System.getProperty("talentforge.m5releasefinal1.screenshots")?.let {
        systemProperty("talentforge.m5releasefinal1.screenshots", it)
    }
    System.getProperty("talentforge.pr19.recording")?.let {
        systemProperty("talentforge.pr19.recording", it)
    }
    // M5.OTA.4c: OtaRetryRemoteMeasurement against the remote staging origin (opt-in; out dir,
    // base URL, keyring, runs, parallelism)
    System.getProperties().stringPropertyNames().filter { it.startsWith("talentforge.ota.retrymeasure") }.forEach {
        systemProperty(it, System.getProperty(it))
    }
}
