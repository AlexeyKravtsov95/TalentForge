package io.github.alexeykravtsov95.talentforge.data.builds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalBuildMigrationGoldenTest {
    private fun golden(): String = checkNotNull(javaClass.getResource("/builds/local-library-v1.json")).readText()

    @Test
    fun firstMutationMigratesButReadingDoesNotWrite() {
        val payload = golden()
        assertEquals(LEGACY_LIBRARY_V1, payload)
        val storage = InMemoryBuildLibraryStorage(payload)
        val repository = LocalBuildRepository(storage, FixedBuildClock(), SequentialBuildIds())
        val before = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository.load()).value
        assertEquals(3, before.size)
        assertEquals(payload, storage.payload)
        assertEquals(0, storage.writeCount)
        assertIs<BuildLibraryOutcome.Loaded<*>>(repository.renameBuild("legacy-1", "Renamed"))
        assertTrue(storage.payload!!.startsWith("{\"storageVersion\":2,"))
        val after = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository.load()).value
        assertEquals(before.map { if (it.localId == "legacy-1") it.copy(name = "Renamed") else it }, after)
    }
}
