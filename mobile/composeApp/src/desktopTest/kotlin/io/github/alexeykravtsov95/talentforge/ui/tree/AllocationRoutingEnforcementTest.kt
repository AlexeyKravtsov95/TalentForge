package io.github.alexeykravtsov95.talentforge.ui.tree

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M4.3 allocation-routing guard: production UI code mutates builds ONLY
 * through `TalentAllocationEngine.spend`/`refund` (plus the engine's own
 * `emptyState` for reset), wrapped by the editing session
 * ([TalentBuildEditingState]) — never by hand-editing ranks
 * (`BuildState.withAllocation`), recalculating gates/prerequisites, or any
 * other second allocation path.
 *
 * A source scan (not a runtime guard) is the honest enforcement here: the
 * milestone forbids the calls outright, and Kotlin has no "forbidden method"
 * modifier. Tests and fixtures are out of scope by definition.
 */
class AllocationRoutingEnforcementTest {

    @Test
    fun productionUiNeverMutatesBuildStateDirectly() {
        val offenders = productionSources().filter { source ->
            source.readText().contains(Regex("""\.\s*withAllocation\s*\("""))
        }
        assertTrue(
            offenders.isEmpty(),
            "production sources must not call BuildState.withAllocation " +
                "(rank arithmetic bypasses the engine): " + offenders.joinToString { it.path },
        )
    }

    @Test
    fun onlyTheEditingSessionCallsEngineSpendOrRefund() {
        // The screen/screen layer calls `editing.spend(...)` (the session's own
        // transition API); only a direct ENGINE mutation call is a violation.
        val engineSpend = Regex("""\bengine\s*\.\s*spend\s*\(""")
        val engineRefund = Regex("""\bengine\s*\.\s*refund\s*\(""")
        val offenders = productionSources().filter { source ->
            source.name != "TalentBuildEditingState.kt" &&
                (source.readText().contains(engineSpend) || source.readText().contains(engineRefund))
        }
        assertTrue(
            offenders.isEmpty(),
            "engine.spend/refund belongs to the editing session only " +
                "(TalentBuildEditingState.kt): " + offenders.joinToString { it.path },
        )
    }

    private fun productionSources(): List<File> {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val productionDirs = listOf(
            repoRoot.resolve("mobile/composeApp/src/commonMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/androidMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/iosMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/desktopMain/kotlin"),
        )
        return productionDirs
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filterNot { it.path.contains("/build/") }
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
