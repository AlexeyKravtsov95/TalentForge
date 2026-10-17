package io.github.alexeykravtsov95.talentforge.data

import java.io.File

/** Locates the repository checkout and provides canonical dataset files for tests. */
object TestRepoAssets {

    val repoRoot: File by lazy { findRepoRoot(File(System.getProperty("user.dir")).absoluteFile) }

    val forever69977: File get() = repoRoot.resolve("data/normalized/forever/1.60.1.69977")
    val forever70009: File get() = repoRoot.resolve("data/normalized/forever/1.60.1.70009")
    val visuals70009: File get() = repoRoot.resolve("data/visuals/forever/1.60.1.70009")
    val tooltips70009: File get() = repoRoot.resolve("data/tooltips/forever/1.60.1.70009")

    /** The product dataset of M5.DATA, still bundled for the builds saved on it. */
    val forever70245: File get() = repoRoot.resolve("data/normalized/forever/1.60.1.70245")
    val visuals70245: File get() = repoRoot.resolve("data/visuals/forever/1.60.1.70245")
    val tooltips70245: File get() = repoRoot.resolve("data/tooltips/forever/1.60.1.70245")

    /** The product dataset of M5.DATA.2, still bundled for the builds saved on it. */
    val forever70338: File get() = repoRoot.resolve("data/normalized/forever/1.60.1.70338")
    val visuals70338: File get() = repoRoot.resolve("data/visuals/forever/1.60.1.70338")
    val tooltips70338: File get() = repoRoot.resolve("data/tooltips/forever/1.60.1.70338")

    /** The product dataset of M5.DATA.3, still bundled for the builds saved on it. */
    val forever70374: File get() = repoRoot.resolve("data/normalized/forever/1.60.1.70374")
    val visuals70374: File get() = repoRoot.resolve("data/visuals/forever/1.60.1.70374")
    val tooltips70374: File get() = repoRoot.resolve("data/tooltips/forever/1.60.1.70374")

    /** The product dataset of M5.DATA.4 (70009 stays the reference baseline). */
    val forever70450: File get() = repoRoot.resolve("data/normalized/forever/1.60.1.70450")
    val conformance70009: File get() =
        repoRoot.resolve("data/conformance/forever/1.60.1.70009/allocation.v1.json")

    val classTokens: List<String> = listOf(
        "druid", "hunter", "mage", "paladin", "priest", "rogue", "shaman", "warlock", "warrior",
    )

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
