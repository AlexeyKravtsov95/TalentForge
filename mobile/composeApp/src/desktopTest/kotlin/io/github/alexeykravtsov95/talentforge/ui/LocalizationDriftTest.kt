package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * JVM mirror of `scripts/sync_mobile_localization.py --check` (M5.REL.3b.2): the bundled
 * `talent-localization.v1` overlays must be a byte-identical mirror of the canonical
 * `data/localization` tree — every locale of every bundled build, nothing missing, nothing
 * extra, nothing different. Reads repository files only; no network.
 */
class LocalizationDriftTest {

    private val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
    private val canonicalRoot = repoRoot.resolve("data/localization/forever")
    private val bundledRoot = repoRoot.resolve("mobile/composeApp/src/commonMain/composeResources/files/localization/forever")

    @Test
    fun bundledOverlaysMirrorTheCanonicalDirectoryByteForByte() {
        val canonical = relativeFiles(canonicalRoot)
        val bundled = relativeFiles(bundledRoot)
        assertEquals(canonical, bundled, "bundled overlays differ — run scripts/sync_mobile_localization.py")
        for (name in canonical) {
            assertContentEquals(
                canonicalRoot.resolve(name).readBytes(),
                bundledRoot.resolve(name).readBytes(),
                "bundled $name differs from canonical — run scripts/sync_mobile_localization.py",
            )
        }
    }

    @Test
    fun exactlyNineClassOverlaysOfEveryBundledBuildAreBundled() {
        val classes = listOf("druid", "hunter", "mage", "paladin", "priest", "rogue", "shaman", "warlock", "warrior")
        val expected = ALL_BUNDLES.flatMap { bundle -> classes.map { "${bundle.clientBuild}/ruRU/$it.json" } }.toSet()
        assertEquals(36, expected.size)
        assertEquals(expected, relativeFiles(bundledRoot), "bundled overlays are not exactly 4 builds x 9 classes")
        assertEquals(expected, relativeFiles(canonicalRoot), "canonical overlays are not exactly 4 builds x 9 classes")
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
