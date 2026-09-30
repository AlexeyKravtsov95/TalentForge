package io.github.alexeykravtsov95.talentforge.ui.tree

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M4.2 read-only guard: no *production* code path of the mobile UI may call
 * the M3B mutation surface (`TalentAllocationEngine.spend`/`refund`) or mutate
 * a `BuildState` (`withAllocation`). The projection layer may only query the
 * engine (`canSpend`/`validate`/`spentPoints`/`remainingPoints`).
 *
 * A source scan (not a runtime guard) is the honest enforcement here: the
 * milestone forbids the calls outright, and Kotlin has no "forbidden method"
 * modifier. Tests and fixtures are out of scope by definition.
 */
class ReadOnlyAllocationEnforcementTest {

    @Test
    fun productionUiSourcesNeverCallSpendOrRefund() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val productionDirs = listOf(
            repoRoot.resolve("mobile/composeApp/src/commonMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/androidMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/iosMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/desktopMain/kotlin"),
        )
        val offenders = productionDirs
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filterNot { it.path.contains("/build/") }
            .filter { source ->
                val text = source.readText()
                // `engine.spend(`, `.spend(state`, `spend(` — any direct call site
                text.contains(Regex("""\.\s*spend\s*\(""")) ||
                    text.contains(Regex("""\.\s*refund\s*\(""")) ||
                    text.contains(Regex("""\.\s*withAllocation\s*\("""))
            }

        assertTrue(
            offenders.isEmpty(),
            "production sources must not call spend/refund/withAllocation (M4.2 is read-only): " +
                offenders.joinToString { it.path },
        )
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
