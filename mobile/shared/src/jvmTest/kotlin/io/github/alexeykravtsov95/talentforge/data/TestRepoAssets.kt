package io.github.alexeykravtsov95.talentforge.data

import java.io.File

/** Locates the repository checkout and provides canonical dataset files for tests. */
object TestRepoAssets {

    val repoRoot: File by lazy { findRepoRoot(File(System.getProperty("user.dir")).absoluteFile) }

    val forever69977: File get() = repoRoot.resolve("data/normalized/forever/1.60.1.69977")
    val forever70009: File get() = repoRoot.resolve("data/normalized/forever/1.60.1.70009")
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
