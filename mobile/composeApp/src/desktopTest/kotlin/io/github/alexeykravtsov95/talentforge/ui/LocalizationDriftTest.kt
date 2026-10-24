package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * JVM mirror of `scripts/sync_mobile_localization.py --check` (M5.REL.3b.2): the bundled
 * `talent-localization.v1` overlays must be a byte-identical mirror of the canonical
 * `data/localization` directory of every bundled build — every locale, nothing missing,
 * nothing extra, nothing different. A canonical build that is not bundled (an OTA-only
 * dataset) is not mirrored, exactly as the script skips it. Reads repository files only;
 * no network.
 */
class LocalizationDriftTest {

    private val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
    private val canonicalRoot = repoRoot.resolve("data/localization/forever")
    private val bundledRoot = repoRoot.resolve("mobile/composeApp/src/commonMain/composeResources/files/localization/forever")

    @Test
    fun bundledOverlaysMirrorTheCanonicalDirectoryByteForByte() {
        for (clientBuild in ALL_BUNDLES.map { it.clientBuild }) {
            val canonicalDir = canonicalRoot.resolve(clientBuild)
            val bundledDir = bundledRoot.resolve(clientBuild)
            val canonical = relativeFiles(canonicalDir)
            assertEquals(canonical, relativeFiles(bundledDir), "bundled $clientBuild overlays differ — run scripts/sync_mobile_localization.py")
            for (name in canonical) {
                assertContentEquals(
                    canonicalDir.resolve(name).readBytes(),
                    bundledDir.resolve(name).readBytes(),
                    "bundled $clientBuild/$name differs from canonical — run scripts/sync_mobile_localization.py",
                )
            }
        }
    }

    @Test
    fun exactlyNineClassOverlaysOfEveryBundledBuildAreBundled() {
        val classes = listOf("druid", "hunter", "mage", "paladin", "priest", "rogue", "shaman", "warlock", "warrior")
        val expected = ALL_BUNDLES.flatMap { bundle -> classes.map { "${bundle.clientBuild}/ruRU/$it.json" } }.toSet()
        assertEquals(45, expected.size)
        assertEquals(expected, relativeFiles(bundledRoot), "bundled overlays are not exactly 5 builds x 9 classes")
        for (bundle in ALL_BUNDLES) {
            assertEquals(
                classes.map { "ruRU/$it.json" }.toSet(),
                relativeFiles(canonicalRoot.resolve(bundle.clientBuild)),
                "canonical overlays of ${bundle.clientBuild} are not exactly 9 classes",
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
