package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.newbuild.NewBuildScreen
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * Produces the M5.UI.2 B+ Class Selection screenshots from the REAL app root
 * and the real bundled datasets over an EMPTY library (no invented builds):
 * the responsive matrix — 320×568, 360×800, 393×852, 130 % and 200 % font
 * scale — plus the unavailable-class, creating, class-loading-failure and
 * creation-failure states.
 *
 * Skipped unless the caller opts in, because it is PR evidence, not a
 * regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5Ui2ScreenshotTest*" \
 *     -Dtalentforge.m5ui2.screenshots=<output-dir>
 * ```
 *
 * [LocalReducedMotion] is pinned so the creating-state capture is a
 * deterministic single frame.
 */
class M5Ui2ScreenshotTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File
        get() = File(System.getProperty("talentforge.m5ui2.screenshots")!!)

    private var storage = FakeBuildLibraryStorage()
    private val clock = FixedClock()
    private val ids = SequentialIds()

    private val realDatasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty("talentforge.m5ui2.screenshots") != null)
        outputDir.mkdirs()
    }

    private fun composeApp(widthDp: Int = 360, heightDp: Int = 800, fontScale: Float = 1f) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalReducedMotion provides true,
                ) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        App(storage = storage, clock = clock, ids = ids)
                    }
                }
            }
        }
    }

    /** The screen composed directly, so a failing dataset repository can be injected. */
    private fun composeScreen(datasets: TalentDatasetRepository) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalReducedMotion provides true,
                ) {
                    Box(Modifier.size(360.dp, 800.dp)) {
                        NewBuildScreen(
                            repository = LocalBuildRepository(storage, clock, ids),
                            datasets = datasets,
                            onBack = {},
                            onDraftCreated = {},
                        )
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

    private fun openClassSelection(widthDp: Int = 360, heightDp: Int = 800, fontScale: Float = 1f) {
        composeApp(widthDp, heightDp, fontScale)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("New build").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("New build").performClick()
        awaitText("Choose a class")
        awaitText("Warrior")
    }

    private fun shoot(name: String) {
        val image = composeRule.onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(outputDir, name))
    }

    @Test
    fun m5ui2BPlusClassSelection() {
        // ---- the responsive matrix (empty library, real nine classes)
        openClassSelection(360, 800)
        shoot("m5ui2-classselection-360x800.png")

        openClassSelection(320, 568)
        shoot("m5ui2-classselection-320x568.png")

        openClassSelection(393, 852)
        shoot("m5ui2-classselection-393x852.png")

        openClassSelection(360, 800, fontScale = 1.3f)
        shoot("m5ui2-classselection-360x800-font130.png")

        openClassSelection(360, 800, fontScale = 2f)
        shoot("m5ui2-classselection-360x800-font200.png")

        // ---- one class document failing: the disabled, dimmed, token-labelled card
        composeScreen(FailingDatasets(realDatasets, failingClassToken = "SHAMAN"))
        awaitText("SHAMAN")
        shoot("m5ui2-unavailable-class.png")

        // ---- the manifest itself failing: the recoverable error state
        composeScreen(FailingDatasets(realDatasets, failManifest = true))
        awaitText("Couldn't load classes")
        shoot("m5ui2-loading-failure.png")

        // ---- draft creation held in flight: progress mark + dimmed others
        storage = FakeBuildLibraryStorage(writeDelayMillis = 2_500)
        openClassSelection(360, 800)
        composeRule.onNodeWithText("Warrior").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription("Creating build").fetchSemanticsNodes().isNotEmpty()
        }
        shoot("m5ui2-creating.png")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }

        // ---- persistence failing: the typed error notice on the kept screen
        storage = FakeBuildLibraryStorage(failWrites = true)
        openClassSelection(360, 800)
        composeRule.onNodeWithText("Mage").performClick()
        awaitText("The draft could not be saved on this device.")
        shoot("m5ui2-creation-failure.png")
    }

    /**
     * Deterministic dataset double over the real bundle (same as the M5.UI.2
     * UI test): the manifest and exactly ONE class document can be made to
     * fail; everything else reads the real bundled data.
     */
    private class FailingDatasets(
        private val delegate: TalentDatasetRepository,
        var failManifest: Boolean = false,
        var failingClassToken: String? = null,
    ) : TalentDatasetRepository {
        override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> =
            if (failManifest) {
                DatasetOutcome.Failed(
                    DatasetLoadError.AssetUnreadable(
                        path = "dataset/${bundle.game.value}/${bundle.clientBuild}/manifest.json",
                        detail = "injected manifest failure",
                    ),
                )
            } else {
                delegate.manifest(bundle)
            }

        override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> =
            if (id.classToken.value == failingClassToken) {
                DatasetOutcome.Failed(
                    DatasetLoadError.AssetUnreadable(
                        path = "dataset/${id.game.value}/${id.clientBuild}/${id.classToken.value.lowercase()}.json",
                        detail = "injected class document failure",
                    ),
                )
            } else {
                delegate.document(id)
            }
    }
}
