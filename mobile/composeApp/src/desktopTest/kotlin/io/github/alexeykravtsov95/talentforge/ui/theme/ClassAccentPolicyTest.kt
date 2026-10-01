package io.github.alexeykravtsov95.talentforge.ui.theme

import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The M5.1 class-accent safety net. M4.1–M4.3 only ever rendered Warrior, and
 * the old node/action code read the Warrior-only semantic token directly — so
 * the first multi-class milestone must PROVE no non-Warrior class can
 * accidentally inherit the Warrior accent, and that nothing outside the
 * single accent policy may read that token at all.
 */
class ClassAccentPolicyTest {

    @Test
    fun warriorKeepsItsSpecFrozenAccent() {
        assertEquals(TalentForgeColors.accentClassWarrior, classAccentFor(ClassToken("WARRIOR")).color)
        assertEquals(TalentForgeColors.accentClassWarrior, WarriorClassAccent.color)
    }

    @Test
    fun everyNonWarriorClassGetsTheNeutralFallbackNeverTheWarriorAccent() {
        val manifest: DatasetManifest = runBlocking {
            assertIs<DatasetOutcome.Loaded<DatasetManifest>>(
                BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
                    .manifest(DatasetBundleId(GameId("forever"), "1.60.1.70009")),
            ).value
        }
        val nonWarrior = manifest.classes.map { it.token }.filter { it.value != "WARRIOR" }
        assertEquals(8, nonWarrior.size)

        for (token in nonWarrior) {
            val accent = classAccentFor(token)
            assertEquals(UnfrozenClassAccent, accent, "class $token must use the documented neutral fallback")
            assertTrue(
                accent.color != TalentForgeColors.accentClassWarrior,
                "class $token must never receive the Warrior-only accent",
            )
        }
    }

    /**
     * Source-level guard: the Warrior-only token may be referenced ONLY by the
     * theme's colour constants and the one accent policy — if any production
     * file reads it directly again, a non-Warrior class silently becomes
     * Warrior-coloured.
     */
    @Test
    fun productionCodeReadsTheWarriorAccentOnlyThroughThePolicy() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir")).absoluteFile)
        val allowed = setOf("TalentForgeColor.kt", "DesignTokens.kt")
        val offenders = productionFiles(repoRoot)
            .filterNot { it.name in allowed }
            .filter { it.readText().contains("accentClassWarrior") }

        assertEquals(
            emptyList(),
            offenders.map { it.name },
            "accentClassWarrior is the Warrior-only semantic token; use classAccentFor(): " +
                offenders.joinToString { it.path },
        )
    }

    private fun productionFiles(repoRoot: File): List<File> =
        listOf(
            repoRoot.resolve("mobile/composeApp/src/commonMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/androidMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/iosMain/kotlin"),
            repoRoot.resolve("mobile/composeApp/src/desktopMain/kotlin"),
        )
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filterNot { it.path.contains("/build/") }

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
