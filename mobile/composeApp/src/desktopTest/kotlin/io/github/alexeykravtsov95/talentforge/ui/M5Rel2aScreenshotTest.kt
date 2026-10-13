package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.ShellLibraryFixtures.Build
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
 * M5.REL.2a PR evidence: the REAL localized shell screens, desktop-rendered
 * in English and Russian — Home (empty, populated, error), All Builds (list,
 * row sheet, rename and delete dialogs, duplicate notice, empty), Class
 * Selection (grid, load error, creation error) — at 393 × 852, the narrow
 * 320 × 568 and 200 % font scale.
 *
 * Skipped unless the caller opts in (evidence, not a regression assertion —
 * the assertions live in `ShellLocalizationUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5Rel2aScreenshotTest*" \
 *     -Dtalentforge.m5rel2a.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class M5Rel2aScreenshotTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    private var storage = FakeBuildLibraryStorage()
    private val clock = FixedClock(startMillis = 1_700_000_000_000L)
    private val realDatasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    /** One window of [widthDp] × [heightDp] dp at [fontScale] in [language], showing [content]. */
    private fun onScreen(
        language: AppLanguage,
        widthDp: Int = 393,
        heightDp: Int = 852,
        fontScale: Float = 1f,
        content: @Composable () -> Unit = { App(storage = storage, clock = clock, ids = SequentialIds()) },
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = (widthDp * DENSITY).toInt(), height = (heightDp * DENSITY).toInt()) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(DENSITY, fontScale),
                        LocalReducedMotion provides true,
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) { content() }
                    }
                }
            }
            block()
        }
    }

    private fun classSelection(datasets: TalentDatasetRepository): @Composable () -> Unit = {
        NewBuildScreen(
            repository = LocalBuildRepository(storage, clock, SequentialIds()),
            datasets = datasets,
            onBack = {},
            onDraftCreated = {},
        )
    }

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.click(text: String) {
        awaitText(text)
        onAllNodesWithText(text).onFirst().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openRowActions(title: String, copy: ShellCopy) {
        waitUntil(timeoutMillis = 20_000) {
            onAllNodesWithContentDescription(copy.rowActions(title)).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithContentDescription(copy.rowActions(title)).onFirst().performClick()
        awaitText(copy.rename)
    }

    private fun ComposeUiTest.shoot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    /** Four builds: an unnamed Warlock draft, a spent Rogue, a user-named Mage and the spent Warrior (Continue). */
    private fun populate() {
        storage = FakeBuildLibraryStorage()
        ShellLibraryFixtures.persist(
            storage,
            clock,
            listOf(
                Build("WARLOCK"),
                Build("ROGUE", talent = "Improved Sinister Strike", ranks = 2),
                Build("MAGE", name = "Arcane levelling"),
                Build("WARRIOR", talent = "Improved Rend", ranks = 3),
            ),
        )
    }

    @Test
    fun m5rel2aLocalizedShellScreens() {
        for (language in AppLanguage.entries) {
            val tag = if (language == AppLanguage.RUSSIAN) "ru" else "en"
            val copy = ShellCopy.of(language)
            val warrior = copy.unnamedTitle("WARRIOR")

            // ---- Home: the empty state, then the populated library
            storage = FakeBuildLibraryStorage()
            onScreen(language) {
                awaitText(copy.homeEmptyTitle)
                shoot("m5rel2a-$tag-393x852-home-empty.png")
            }
            populate()
            onScreen(language) {
                awaitText(copy.continueSection)
                shoot("m5rel2a-$tag-393x852-home.png")
            }

            // ---- All Builds: the list, the row sheet, rename, delete, duplicate
            onScreen(language) {
                click(copy.allBuildsLink(4))
                awaitText(copy.buildsTitle(4))
                shoot("m5rel2a-$tag-393x852-all-builds.png")
                openRowActions(warrior, copy)
                shoot("m5rel2a-$tag-393x852-row-actions.png")
                click(copy.rename)
                awaitText(copy.renameTitle)
                shoot("m5rel2a-$tag-393x852-rename.png")
                click(copy.cancel)
                openRowActions(warrior, copy)
                click(copy.delete)
                awaitText(copy.deleteTitle(warrior))
                shoot("m5rel2a-$tag-393x852-delete.png")
                click(copy.cancel)
                openRowActions(copy.unnamedTitle("ROGUE"), copy)
                click(copy.duplicate)
                awaitText(copy.duplicated)
                awaitText(copy.buildsTitle(5))
                shoot("m5rel2a-$tag-393x852-duplicated.png")
            }

            // ---- Class Selection (the real nine classes)
            populate()
            onScreen(language) {
                click(copy.newBuild)
                awaitText(copy.className("WARLOCK"))
                shoot("m5rel2a-$tag-393x852-class-selection.png")
            }

            // ---- the narrow 320 dp phone
            onScreen(language, 320, 568) {
                awaitText(copy.continueSection)
                shoot("m5rel2a-$tag-320x568-home.png")
                click(copy.allBuildsLink(4))
                awaitText(copy.buildsTitle(4))
                shoot("m5rel2a-$tag-320x568-all-builds.png")
            }
            onScreen(language, 320, 568) {
                click(copy.newBuild)
                awaitText(copy.className("WARLOCK"))
                shoot("m5rel2a-$tag-320x568-class-selection.png")
            }

            // ---- 200 % font scale (393 dp and the narrow 320 dp)
            onScreen(language, fontScale = 2f) {
                awaitText(copy.continueSection)
                shoot("m5rel2a-$tag-393x852-font200-home.png")
                click(copy.allBuildsLink(4))
                awaitText(copy.buildsTitle(4))
                shoot("m5rel2a-$tag-393x852-font200-all-builds.png")
                openRowActions(warrior, copy)
                click(copy.delete)
                awaitText(copy.deleteTitle(warrior))
                shoot("m5rel2a-$tag-393x852-font200-delete.png")
            }
            onScreen(language, fontScale = 2f) {
                click(copy.newBuild)
                awaitText(copy.className("WARLOCK"))
                shoot("m5rel2a-$tag-393x852-font200-class-selection.png")
            }
            onScreen(language, 320, 568, fontScale = 2f) {
                awaitText(copy.continueSection)
                shoot("m5rel2a-$tag-320x568-font200-home.png")
                click(copy.newBuild)
                awaitText(copy.className("WARLOCK"))
                shoot("m5rel2a-$tag-320x568-font200-class-selection.png")
            }

            // ---- error states: the library unreadable, the classes
            // unreadable, the draft creation failing
            storage = FakeBuildLibraryStorage(failReads = true)
            onScreen(language) {
                awaitText(copy.libraryFailedTitle)
                shoot("m5rel2a-$tag-393x852-home-error.png")
            }
            onScreen(language, content = classSelection(ShellLibraryFixtures.FailingDatasets(realDatasets, true))) {
                awaitText(copy.classesFailedTitle)
                shoot("m5rel2a-$tag-393x852-class-selection-error.png")
            }
            storage = FakeBuildLibraryStorage(failWrites = true)
            onScreen(language, content = classSelection(realDatasets)) {
                click(copy.className("MAGE"))
                awaitText(copy.draftNotSaved)
                shoot("m5rel2a-$tag-393x852-class-selection-creation-error.png")
            }

            // ---- the empty All Builds list: delete the only build
            storage = FakeBuildLibraryStorage()
            ShellLibraryFixtures.persist(storage, clock, listOf(Build("PALADIN")))
            onScreen(language) {
                click(copy.allBuildsLink(1))
                awaitText(copy.buildsTitle(1))
                openRowActions(copy.unnamedTitle("PALADIN"), copy)
                click(copy.delete)
                awaitText(copy.deleteTitle(copy.unnamedTitle("PALADIN")))
                onAllNodesWithText(copy.delete).onFirst().performClick()
                awaitText(copy.buildsEmptyTitle)
                shoot("m5rel2a-$tag-393x852-all-builds-empty.png")
            }
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5rel2a.screenshots"
        const val DENSITY = 2f
    }
}
