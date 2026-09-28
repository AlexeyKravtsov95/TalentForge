package io.github.alexeykravtsov95.talentforge.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * JVM mirror of `scripts/sync_mobile_datasets.py --check`: the bundled Compose
 * resources must stay byte-identical to the canonical `data/normalized` tree,
 * so a canonical update without a bundle sync fails the build in CI.
 */
class DatasetDriftTest {

    private val bundledFiles = listOf(
        "manifest.json",
        "druid.json",
        "hunter.json",
        "mage.json",
        "paladin.json",
        "priest.json",
        "rogue.json",
        "shaman.json",
        "warlock.json",
        "warrior.json",
    )

    @Test
    fun bundledDatasetMatchesCanonicalSourceByteForByte() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val canonicalDir = repoRoot.resolve("data/normalized/forever/1.60.1.70009")
        val bundledDir = repoRoot
            .resolve("mobile/composeApp/src/commonMain/composeResources/files/dataset/forever/1.60.1.70009")

        for (name in bundledFiles) {
            val canonical = canonicalDir.resolve(name)
            val bundled = bundledDir.resolve(name)
            assertTrue(canonical.isFile, "canonical dataset file missing: ${canonical.path}")
            assertTrue(bundled.isFile, "bundled dataset file missing: ${bundled.path} — run scripts/sync_mobile_datasets.py")
            assertContentEquals(
                canonical.readBytes(),
                bundled.readBytes(),
                "bundled $name differs from canonical — run scripts/sync_mobile_datasets.py",
            )
        }
    }

    private fun findRepoRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (dir.resolve("pyproject.toml").isFile && dir.resolve("data/normalized").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        error("repository root not found from $start")
    }
}
