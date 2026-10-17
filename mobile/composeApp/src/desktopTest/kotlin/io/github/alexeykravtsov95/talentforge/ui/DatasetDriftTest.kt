package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM mirror of `scripts/sync_mobile_datasets.py --check`: the bundled Compose
 * resources must stay byte-identical to the canonical `data/normalized` tree
 * for every bundled build — the reference baseline, the retained product
 * datasets 1.60.1.70245, 1.60.1.70338 and 1.60.1.70374 and the product dataset —
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
        for (clientBuild in ALL_BUNDLES.map { it.clientBuild }) {
            assertBuildMatches(clientBuild)
        }
    }

    @Test
    fun exactlyTheFiveBundledBuildsAreBundled() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val bundledRoot = repoRoot
            .resolve("mobile/composeApp/src/commonMain/composeResources/files/dataset/forever")
        val bundledBuilds = bundledRoot.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .map { it.name }
            .toSet()
        assertEquals(ALL_BUNDLES.map { it.clientBuild }.toSet(), bundledBuilds)
    }

    private fun assertBuildMatches(clientBuild: String) {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val canonicalDir = repoRoot.resolve("data/normalized/forever/$clientBuild")
        val bundledDir = repoRoot
            .resolve("mobile/composeApp/src/commonMain/composeResources/files/dataset/forever/$clientBuild")

        assertEquals(
            bundledFiles.toSet(),
            canonicalDir.listFiles().orEmpty().filter { !it.name.startsWith(".") }.map { it.name }.toSet(),
            "canonical $clientBuild holds exactly the manifest and nine class documents",
        )
        assertEquals(
            bundledFiles.toSet(),
            bundledDir.listFiles().orEmpty().filter { !it.name.startsWith(".") }.map { it.name }.toSet(),
            "bundled $clientBuild holds extra or missing files — run scripts/sync_mobile_datasets.py",
        )
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
