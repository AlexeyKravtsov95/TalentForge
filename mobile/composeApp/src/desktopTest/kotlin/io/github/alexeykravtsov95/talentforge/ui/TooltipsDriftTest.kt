package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM mirror of `scripts/sync_mobile_tooltips.py --check`: the bundled talent
 * descriptions must be a byte-identical mirror of the canonical
 * `data/tooltips` directory of every bundled build — nothing missing,
 * nothing extra, nothing different. Reads repository files only; no network.
 */
class TooltipsDriftTest {

    @Test
    fun bundledTooltipsMirrorTheCanonicalDirectoryByteForByte() {
        for (clientBuild in ALL_BUNDLES.map { it.clientBuild }) {
            assertBuildMirrors(clientBuild)
        }
    }

    @Test
    fun exactlyTheFiveBundledBuildsAreBundled() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val bundledRoot = repoRoot
            .resolve("mobile/composeApp/src/commonMain/composeResources/files/tooltips/forever")
        val bundledBuilds = bundledRoot.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .map { it.name }
            .toSet()
        assertEquals(ALL_BUNDLES.map { it.clientBuild }.toSet(), bundledBuilds)
    }

    private fun assertBuildMirrors(clientBuild: String) {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val canonicalDir = repoRoot.resolve("data/tooltips/forever/$clientBuild")
        val bundledDir = repoRoot
            .resolve("mobile/composeApp/src/commonMain/composeResources/files/tooltips/forever/$clientBuild")

        val canonical = relativeFiles(canonicalDir)
        val bundled = relativeFiles(bundledDir)
        assertEquals(
            setOf(
                "druid.json", "hunter.json", "mage.json", "paladin.json", "priest.json",
                "rogue.json", "shaman.json", "warlock.json", "warrior.json",
            ),
            canonical,
        )
        assertEquals(canonical, bundled, "bundled tooltips differ — run scripts/sync_mobile_tooltips.py")
        for (name in canonical) {
            assertContentEquals(
                canonicalDir.resolve(name).readBytes(),
                bundledDir.resolve(name).readBytes(),
                "bundled $name differs from canonical — run scripts/sync_mobile_tooltips.py",
            )
        }
    }

    private fun relativeFiles(directory: File): Set<String> =
        directory.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.relativeTo(directory).invariantSeparatorsPath }
            .toSortedSet()

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
