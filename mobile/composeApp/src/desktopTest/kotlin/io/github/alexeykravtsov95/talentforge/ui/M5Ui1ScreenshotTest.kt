package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.assertIs
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * Produces the M5.UI.1 B+ Home / All Builds screenshots from the REAL app
 * root and a real persisted library (fake injected storage): the B+ states
 * across the milestone's responsive matrix — 320×568, 360×800, 393×852, and
 * 130 % / 200 % font scale — plus the one-build and repository-error states.
 *
 * Skipped unless the caller opts in, because it is PR evidence, not a
 * regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5Ui1ScreenshotTest*" \
 *     -Dtalentforge.m5ui1.screenshots=<output-dir>
 * ```
 */
class M5Ui1ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File
        get() = File(System.getProperty("talentforge.m5ui1.screenshots")!!)

    private val storage = FakeBuildLibraryStorage()
    private val clock = FixedClock(startMillis = 1_700_000_000_000L)
    private val ids = SequentialIds()

    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty("talentforge.m5ui1.screenshots") != null)
        outputDir.mkdirs()
    }

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun composeApp(widthDp: Int = 360, heightDp: Int = 800, fontScale: Float = 1f) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        App(storage = storage, clock = clock, ids = SequentialIds())
                    }
                }
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    private fun documentOf(token: String): TalentDataDocument {
        val outcome = runBlocking {
            datasets.document(DatasetId(GameId("forever"), "1.60.1.70338", ClassToken(token)))
        }
        return assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(outcome).value
    }

    /**
     * Persists a real engine-spent build of one class on the product bundle
     * (empty ranks = a fresh draft); optional [name] renames it afterwards.
     */
    private fun persistBuild(token: String, ranks: Map<String, Int>, name: String? = null) {
        val document = documentOf(token)
        val engine = TalentAllocationEngine(document)
        var state = engine.emptyState()
        for ((talent, count) in ranks) {
            val nodeId = engine.tree.nodes.first { it.name == talent }.id
            repeat(count) { state = engine.spend(state, nodeId).state }
        }
        val repository = repository()
        val buildFile = buildFileOf(
            state = state,
            game = document.game,
            clientBuild = document.clientBuild,
            classToken = document.classInfo.token,
        )
        val created = loaded(repository.createDraft(buildFile))
        if (name != null) loaded(repository.renameBuild(created.localId, name))
        clock.advanceBy(7_200_000L) // "2 h ago" in the card metadata
    }

    private fun shoot(name: String) {
        val image = composeRule.onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }

    @Test
    fun m5ui1BPlusHomeAndAllBuilds() {
        // ---- the typography-only B+ empty state (360 × 800)
        composeApp()
        awaitText("No builds yet")
        shoot("m5ui1-360x800-empty.png")

        // ---- one saved build: Continue + the standalone All-builds row
        persistBuild("WARRIOR", mapOf("Improved Rend" to 3, "Deflection" to 2))
        composeApp()
        awaitText("CONTINUE")
        shoot("m5ui1-360x800-one-build.png")
        composeRule.onNodeWithText("All builds 1").performClick()
        awaitText("Builds 1")
        shoot("m5ui1-360x800-all-builds-one.png")

        // ---- several saved builds: Continue + Recent + the header link
        persistBuild("MAGE", emptyMap())
        persistBuild("ROGUE", mapOf("Improved Sinister Strike" to 2))
        clock.advanceBy(60_000L)
        persistBuild("WARLOCK", emptyMap(), name = "Affliction levelling")
        composeApp()
        awaitText("RECENT")
        shoot("m5ui1-360x800-several.png")

        composeRule.onNodeWithText("All builds 4").performClick()
        awaitText("Builds 4")
        shoot("m5ui1-360x800-all-builds.png")

        // ---- the responsive matrix on the populated Home
        composeApp(320, 568)
        awaitText("RECENT")
        shoot("m5ui1-320x568-several.png")

        composeApp(393, 852)
        awaitText("RECENT")
        shoot("m5ui1-393x852-several.png")

        composeApp(360, 800, fontScale = 1.3f)
        awaitText("RECENT")
        shoot("m5ui1-360x800-font130.png")

        composeApp(360, 800, fontScale = 2f)
        awaitText("RECENT")
        shoot("m5ui1-360x800-font200.png")

        // ---- the recoverable repository error (never a faked empty Home)
        storage.failReads = true
        composeApp()
        awaitText("Couldn't load your builds")
        shoot("m5ui1-360x800-error.png")
    }
}
