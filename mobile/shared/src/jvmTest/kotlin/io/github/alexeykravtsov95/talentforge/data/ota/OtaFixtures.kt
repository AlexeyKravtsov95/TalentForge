package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The committed OTA conformance suite (`docs/ota/fixtures/v1`), read from its synced mirror in
 * the jvmTest resources (`scripts/sync_mobile_ota_fixtures.py --check` guards the copy).
 */
object OtaFixtures {

    val root: File by lazy {
        TestRepoAssets.repoRoot.resolve("mobile/shared/src/jvmTest/resources/ota/fixtures/v1").also {
            check(it.resolve("cases.json").isFile) { "OTA fixtures not synced: run scripts/sync_mobile_ota_fixtures.py" }
        }
    }

    fun bytes(relative: String): ByteArray = root.resolve(relative).readBytes()

    val cases: List<JsonObject> by lazy {
        Json.parseToJsonElement(root.resolve("cases.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    }

    private val keyringJson: JsonObject by lazy { Json.parseToJsonElement(root.resolve("keyring.json").readText()).jsonObject }

    private fun entries(member: String): List<OtaKeyring.Entry> = keyringJson.getValue(member).jsonArray.map {
        val key = it.jsonObject
        OtaKeyring.Entry(
            keyId = key.getValue("keyId").jsonPrimitive.content,
            algorithm = key.getValue("algorithm").jsonPrimitive.content,
            publicKeySpki = key.getValue("publicKeySpki").jsonPrimitive.content,
        )
    }

    /** The two pinned TEST keys (`test-primary`, `test-backup`). */
    val keyring: OtaKeyring by lazy { OtaKeyring.of(entries("keys")) }

    /** The key no app pins (`test-untrusted`). */
    val untrustedKeyring: OtaKeyring by lazy { OtaKeyring.of(entries("untrustedKeys")) }

    /** A release path's committed source file (contract §12: `files.source = "repository"`). */
    fun repositoryFile(releasePath: String): File {
        val head = releasePath.substringBefore('/')
        val rest = releasePath.substringAfter('/')
        val directory = when (head) {
            "dataset" -> "data/normalized"
            "visuals" -> "data/visuals"
            "tooltips" -> "data/tooltips"
            "localization" -> "data/localization"
            else -> error("not a release path: $releasePath")
        }
        return TestRepoAssets.repoRoot.resolve("$directory/$rest")
    }

    /** The bytes a release case serves for [path]: an override (fixture blob or other repository path) or the committed file. */
    fun releaseFileBytes(case: JsonObject, path: String): ByteArray {
        val files = case.getValue("files").jsonObject
        check(files.getValue("source").jsonPrimitive.content == "repository")
        val override = files.getValue("overrides").jsonObject[path]?.jsonPrimitive?.content
        return when {
            override == null -> repositoryFile(path).readBytes()
            override.startsWith("repository:") -> repositoryFile(override.removePrefix("repository:")).readBytes()
            else -> bytes(override)
        }
    }

    fun committedDatasetManifest(game: String, clientBuild: String): DatasetManifest =
        TalentDataContract.decodeDatasetManifest(
            TestRepoAssets.repoRoot.resolve("data/normalized/$game/$clientBuild/manifest.json").readBytes(),
        )

    fun catalogEntry(json: JsonObject): CatalogRelease = CatalogRelease(
        releaseId = json.getValue("releaseId").jsonPrimitive.content,
        clientBuild = json.getValue("clientBuild").jsonPrimitive.content,
        minDataLevel = json.getValue("minDataLevel").jsonPrimitive.int,
        manifestSha256 = json.getValue("manifestSha256").jsonPrimitive.content,
        manifestByteLength = json.getValue("manifestByteLength").jsonPrimitive.long,
        totalByteLength = json.getValue("totalByteLength").jsonPrimitive.long,
        publishedAt = json.getValue("publishedAt").jsonPrimitive.content,
    )

    fun JsonObject.stringOrNull(member: String): String? =
        (this[member] as? JsonElement)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
}

/** `java.security` ECDSA P-256 / SHA-256 — the JVM verifier the replay runs with. */
object JvmSignatureVerifier : SignatureVerifier {
    override fun verifyEcdsaP256Sha256(publicKeySpki: ByteArray, message: ByteArray, derSignature: ByteArray): Boolean {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(message)
        return try {
            verifier.verify(derSignature)
        } catch (_: SignatureException) {
            false
        }
    }
}
