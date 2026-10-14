package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import io.github.alexeykravtsov95.talentforge.ui.library.BUNDLE_70245
import io.github.alexeykravtsov95.talentforge.ui.library.PREVIOUS_PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.REFERENCE_BUNDLE
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM mirror of `scripts/sync_mobile_visuals.py --check`: the bundled talent
 * visuals (sidecars, icon registry, PNG icons) must be a byte-identical mirror
 * of the canonical `data/visuals` tree of every bundled build — nothing
 * missing, nothing extra, nothing different, and no build borrowing another
 * build's icons. Reads repository files only; no network.
 */
class VisualsDriftTest {

    @Test
    fun bundledVisualsMirrorTheCanonicalTreeByteForByte() {
        // nine sidecars + icons.json + one PNG per distinct FileDataID of that build
        assertBuildMirrors(REFERENCE_BUNDLE.clientBuild, files = 368, icons = 358)
        assertBuildMirrors(BUNDLE_70245.clientBuild, files = 367, icons = 357)
        assertBuildMirrors(PREVIOUS_PRODUCT_BUNDLE.clientBuild, files = 368, icons = 358)
        assertBuildMirrors(PRODUCT_BUNDLE.clientBuild, files = 368, icons = 358)
    }

    @Test
    fun exactlyTheFourBundledBuildsAreBundled() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val bundledRoot = repoRoot
            .resolve("mobile/composeApp/src/commonMain/composeResources/files/visuals/forever")
        val bundledBuilds = bundledRoot.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .map { it.name }
            .toSet()
        assertEquals(ALL_BUNDLES.map { it.clientBuild }.toSet(), bundledBuilds)
    }

    private fun assertBuildMirrors(clientBuild: String, files: Int, icons: Int) {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val canonicalDir = repoRoot.resolve("data/visuals/forever/$clientBuild")
        val bundledDir = repoRoot
            .resolve("mobile/composeApp/src/commonMain/composeResources/files/visuals/forever/$clientBuild")

        val canonical = relativeFiles(canonicalDir)
        val bundled = relativeFiles(bundledDir)
        assertTrue(canonical.isNotEmpty(), "canonical visuals missing: ${canonicalDir.path}")
        assertEquals(
            canonical,
            bundled,
            "bundled visuals differ in file inventory — run scripts/sync_mobile_visuals.py",
        )
        assertEquals(files, canonical.size)
        assertEquals(icons, canonical.count { it.startsWith("icons/") && it.endsWith(".png") })
        for (name in canonical) {
            val bytes = canonicalDir.resolve(name).readBytes()
            assertTrue(bytes.isNotEmpty(), "canonical $name is empty")
            assertContentEquals(
                bytes,
                bundledDir.resolve(name).readBytes(),
                "bundled $name differs from canonical — run scripts/sync_mobile_visuals.py",
            )
        }
    }

    private fun relativeFiles(directory: File): Set<String> =
        directory.walkTopDown()
            // dotfiles (Finder's .DS_Store) are OS noise, as in the Python check
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
