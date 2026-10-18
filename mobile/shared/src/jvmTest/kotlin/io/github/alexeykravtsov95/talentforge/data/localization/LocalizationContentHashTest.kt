package io.github.alexeykravtsov95.talentforge.data.localization

import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The runtime `contentHash` procedure against the Python producer: the cross-language vectors
 * in `tests/fixtures/localization/content-hash-vectors.json` are generated — and re-checked in
 * CI — by `tests/unit/localization/test_content_hash_vectors.py` with
 * `talent_data.models.visuals.content_hash`; Kotlin must reproduce every canonical string
 * and hash, and recompute the published hash of all 45 bundled overlays.
 */
class LocalizationContentHashTest {

    private val vectors = Json.parseToJsonElement(
        TestRepoAssets.repoRoot.resolve("tests/fixtures/localization/content-hash-vectors.json").readText(),
    ).jsonObject

    @Test
    fun sha256MatchesTheFipsVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        // block-boundary lengths (55/56/64 bytes pad differently)
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256Hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
        for (length in listOf(55, 56, 63, 64, 65)) {
            val data = ByteArray(length) { (it * 7).toByte() }
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, sha256Hex(data), "length $length")
        }
    }

    @Test
    fun canonicalJsonAndHashEqualPythonsForEveryVector() {
        val list = vectors.getValue("vectors").jsonArray
        assertEquals(8, list.size)
        for (vector in list.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            val canonical = canonicalJson(vector.getValue("payload"))
            assertEquals(vector.getValue("canonical").jsonPrimitive.content, canonical, name)
            assertEquals(
                vector.getValue("contentHash").jsonPrimitive.content,
                "sha256:" + sha256Hex(canonical.encodeToByteArray()),
                name,
            )
        }
    }

    @Test
    fun everyBundledOverlaysHashIsRecomputedExactlyAsPythonPublishedIt() {
        val overlays = vectors.getValue("overlays").jsonArray.map { it.jsonObject }
        assertEquals(45, overlays.size)
        for (row in overlays) {
            val path = row.getValue("path").jsonPrimitive.content
            val document = TalentDataContract.decodeTalentLocalizationDocument(
                TestRepoAssets.repoRoot.resolve(path).readBytes(),
            )
            assertEquals(row.getValue("contentHash").jsonPrimitive.content, localizationContentHash(document).value, path)
            assertEquals(document.contentHash, localizationContentHash(document), path)
        }
    }

    @Test
    fun theHashCoversEverythingButContentHashAndProvenance() {
        val path = "data/localization/forever/$PRODUCT_BUILD/ruRU/warrior.json"
        val document = TalentDataContract.decodeTalentLocalizationDocument(TestRepoAssets.repoRoot.resolve(path).readBytes())
        val original = localizationContentHash(document)
        assertEquals(original, localizationContentHash(document.copy(provenance = document.provenance.copy(source = "x"))))
        assertEquals(original, localizationContentHash(document.copy(contentHash = io.github.alexeykravtsov95.talentforge.data.ContentHash("sha256:0"))))
        val first = document.entries.first()
        val renamed = document.copy(entries = listOf(first.copy(maxRank = first.maxRank + 1)) + document.entries.drop(1))
        assertNotEquals(original, localizationContentHash(renamed))
        assertNotEquals(original, localizationContentHash(document.copy(clientBuild = "1.60.1.70338")))
    }

    @Test
    fun aNonIntegralNumberHasNoCanonicalForm() {
        assertFailsWith<IllegalArgumentException> { canonicalJson(JsonPrimitive(1.5)) }
    }
}
