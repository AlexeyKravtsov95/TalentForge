package io.github.alexeykravtsov95.talentforge.data.builds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class LocalBuildFavoritesMigrationTest {
    private val storage = InMemoryBuildLibraryStorage(LEGACY_LIBRARY_V1)
    private val repository = LocalBuildRepository(storage, FixedBuildClock(1900000000000), SequentialBuildIds())
    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T = assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    @Test
    fun readingV1AndNoOpDoNotMigrateAndFavoritesAreInitiallyOff() {
        val records = loaded(repository.load())
        assertEquals(3, records.size)
        assertTrue(records.all { !it.isFavorite })
        loaded(repository.setFavorite("legacy-1", false))
        assertEquals(0, storage.writeCount)
        assertEquals(LEGACY_LIBRARY_V1, storage.payload)
    }

    @Test
    fun firstFavoriteMigratesEveryBuildWithoutChangingItsPayloadOrMetadataAndSurvivesRestart() {
        val before = loaded(repository.load())
        val changed = loaded(repository.setFavorite("legacy-2", true))
        assertTrue(changed.isFavorite)
        val fresh = LocalBuildRepository(storage, FixedBuildClock(), SequentialBuildIds())
        val after = loaded(fresh.load())
        assertEquals(before.map { if (it.localId == "legacy-2") it.copy(isFavorite = true) else it }, after)
        assertEquals(1, storage.writeCount)
        val originalBuilds = Json.parseToJsonElement(LEGACY_LIBRARY_V1).jsonObject["builds"]!!.jsonArray
        val migratedBuilds = Json.parseToJsonElement(storage.payload!!).jsonObject["builds"]!!.jsonArray
        originalBuilds.zip(migratedBuilds).forEach { (old, new) ->
            assertEquals(old.jsonObject, JsonObject(new.jsonObject.filterKeys { it != "isFavorite" }))
        }
        assertEquals(JsonPrimitive(2), Json.parseToJsonElement(storage.payload!!).jsonObject["storageVersion"])
        loaded(fresh.setFavorite("legacy-2", true))
        assertEquals(1, storage.writeCount)
        loaded(fresh.setFavorite("legacy-2", false))
        assertEquals(before, loaded(fresh.load()))
        assertEquals(2, storage.writeCount)
    }

    @Test
    fun aFailedFirstMigrationPreservesTheByteExactOriginalAndCanBeRetried() {
        val before = loaded(repository.load())
        storage.failWrites = true
        assertIs<BuildLibraryError.StorageWrite>(assertIs<BuildLibraryOutcome.Failed>(repository.setFavorite("legacy-1", true)).error)
        assertEquals(LEGACY_LIBRARY_V1, storage.payload)
        assertEquals(before, loaded(repository.load()))
        assertEquals(0, storage.writeCount)
        storage.failWrites = false
        loaded(repository.setFavorite("legacy-1", true))
        assertTrue(loaded(repository.load()).single { it.localId == "legacy-1" }.isFavorite)
    }

    @Test
    fun duplicateRenameSaveOpenAndDeleteKeepFavoriteMetadataIndependent() {
        val original = loaded(repository.setFavorite("legacy-1", true))
        val copy = loaded(repository.duplicateBuild(original.localId, " (copy)"))
        assertFalse(copy.isFavorite)
        assertEquals(original.build, copy.build)
        assertEquals(original, loaded(repository.load()).single { it.localId == original.localId })
        val renamed = loaded(repository.renameBuild(original.localId, "New name"))
        assertEquals(original.copy(name = "New name"), renamed)
        val saved = loaded(repository.saveBuild(original.localId, original.build.copy(allocations = emptyList())))
        assertTrue(saved.isFavorite)
        assertEquals(renamed.copy(build = saved.build, updatedAt = saved.updatedAt), saved)
        assertTrue(loaded(repository.open(original.localId)).isFavorite)
        loaded(repository.deleteBuild(copy.localId))
        assertEquals(3, loaded(repository.load()).size)
    }

    @Test
    fun emptyOneTenAndOneHundredLegacyBuildsMigrateWithoutLossOrReordering() {
        val template = Json.parseToJsonElement(LEGACY_LIBRARY_V1).jsonObject["builds"]!!.jsonArray.first().jsonObject
        for (size in listOf(0, 1, 10, 100)) {
            val records = (1..size).map { index -> JsonObject(template + ("localId" to JsonPrimitive("opaque-$index"))) }
            val payload = JsonObject(mapOf("storageVersion" to JsonPrimitive(1), "builds" to JsonArray(records))).toString()
            val store = InMemoryBuildLibraryStorage(payload)
            val repo = LocalBuildRepository(store, FixedBuildClock(), SequentialBuildIds())
            val before = loaded(repo.load())
            assertEquals(size, before.size)
            assertEquals(payload, store.payload)
            if (size == 0) {
                loaded(repo.createDraft(template.let { LocalBuildLibraryCodec.decode(LEGACY_LIBRARY_V1).first().build }))
                assertEquals(1, loaded(repo.load()).size)
            } else {
                loaded(repo.setFavorite("opaque-1", true))
                assertEquals(before.map { if (it.localId == "opaque-1") it.copy(isFavorite = true) else it }, loaded(repo.load()))
            }
            assertEquals(JsonPrimitive(2), Json.parseToJsonElement(store.payload!!).jsonObject["storageVersion"])
        }
    }

    @Test
    fun unknownVersionsCorruptJsonAndReadFailuresNeverOverwriteExistingData() {
        for (payload in listOf("{", "", "null", "[]", "{\"storageVersion\":99,\"future\":true}",
            LEGACY_LIBRARY_V1.replace("\"rank\":1", "\"rank\":\"1\""))) {
            val store = InMemoryBuildLibraryStorage(payload)
            val repo = LocalBuildRepository(store, FixedBuildClock(), SequentialBuildIds())
            val error = assertIs<BuildLibraryOutcome.Failed>(repo.load()).error
            if ("99" in payload) assertIs<BuildLibraryError.UnsupportedStorageVersion>(error)
            else assertIs<BuildLibraryError.Malformed>(error)
            assertIs<BuildLibraryOutcome.Failed>(repo.setFavorite("legacy-1", true))
            assertIs<BuildLibraryOutcome.Failed>(repo.createDraft(LocalBuildLibraryCodec.decode(LEGACY_LIBRARY_V1).first().build))
            assertEquals(payload, store.payload)
            assertEquals(0, store.writeCount)
        }
        storage.failReads = true
        assertIs<BuildLibraryError.StorageRead>(assertIs<BuildLibraryOutcome.Failed>(repository.setFavorite("legacy-1", true)).error)
        assertEquals(LEGACY_LIBRARY_V1, storage.payload)
    }

    @Test
    fun bothVersionsRemainStrictAndV2RequiresAnUnquotedBoolean() {
        val v2 = LocalBuildLibraryCodec.encode(LocalBuildLibraryCodec.decode(LEGACY_LIBRARY_V1))
        for (payload in listOf(
            LEGACY_LIBRARY_V1.replace("\"storageVersion\":1", "\"storageVersion\":\"1\""),
            LEGACY_LIBRARY_V1.replace("\"builds\":", "\"unknown\":0,\"builds\":"),
            LEGACY_LIBRARY_V1.replace("\"name\":\"Raid Arms\"", "\"name\":123"),
            LEGACY_LIBRARY_V1.replace("\"localId\":\"legacy-1\"", "\"isFavorite\":false,\"localId\":\"legacy-1\""),
            v2.replace("\"isFavorite\":false", "\"isFavorite\":\"false\""),
            v2.replace("\"isFavorite\":false", "\"isFavorite\":0"),
            v2.replace("\"isFavorite\":false", "\"isFavorite\":null"),
            v2.replace(",\"isFavorite\":false", ""),
            v2.replace("\"builds\":", "\"unknown\":0,\"builds\":"),
        )) {
            val store = InMemoryBuildLibraryStorage(payload)
            val repo = LocalBuildRepository(store, FixedBuildClock(), SequentialBuildIds())
            assertIs<BuildLibraryError.Malformed>(assertIs<BuildLibraryOutcome.Failed>(repo.setFavorite("legacy-1", true)).error, payload)
            assertEquals(payload, store.payload)
            assertEquals(0, store.writeCount)
        }
    }

    @Test
    fun missingFavoriteTargetIsTypedAndDoesNotMigrate() {
        assertIs<BuildLibraryError.UnknownRecord>(assertIs<BuildLibraryOutcome.Failed>(repository.setFavorite("missing", true)).error)
        assertEquals(LEGACY_LIBRARY_V1, storage.payload)
        assertEquals(0, storage.writeCount)
    }
}
