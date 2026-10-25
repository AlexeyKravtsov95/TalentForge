package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The M5.UI.1 B+ Home redesign against the REAL app root, real bundled
 * datasets and a fake injected storage: the typography-only empty state, the
 * always-reachable All Builds navigation (including exactly one build),
 * class ARTWORK instead of monograms, the display-only name rules on screen,
 * and the recoverable repository-error state.
 */
class HomeRedesignUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private lateinit var clock: FixedClock
    private val ids = SequentialIds()

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
        clock = FixedClock()
    }

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun composeApp(widthDp: Int = 360, heightDp: Int = 760, fontScale: Float = 1f) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        App(storage = storage, clock = clock, ids = ids)
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

    private fun persistDraft(token: String): String {
        val created = runBlocking {
            assertIs<DraftCreation.Created>(
                createClassDraft(repository(), realDatasets(), ClassToken(token)),
            )
        }
        clock.advanceBy(1_000)
        return created.record.localId
    }

    private fun realDatasets() = io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository(
        io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource(),
    )

    // ------------------------------------------------------------ the B+ empty state

    @Test
    fun emptyHomeIsTheTypographyOnlyBPlusState() {
        composeApp()

        awaitText("No builds yet")
        composeRule.onNodeWithText("TalentForge").assertExists()
        // PR #22 review: the copy names the class (the actual New Build
        // choice) — Forever is the only version and stays on its own line
        composeRule.onNodeWithText(
            "Pick a class and spend points on its talent tree. " +
                "Builds are stored on this device; no account is needed.",
        ).assertExists()
        composeRule.onNodeWithText("Pick a version and class").assertDoesNotExist()
        composeRule.onNodeWithText("Forever talent data 1.60.1").assertExists()
        // the single primary action of the empty state, pinned at the bottom
        composeRule.onNodeWithText("New build").assertExists()
        // nothing fake, nothing decorative
        composeRule.onNodeWithText("CONTINUE").assertDoesNotExist()
        composeRule.onNodeWithText("RECENT").assertDoesNotExist()
        composeRule.onNodeWithText("All builds 0").assertDoesNotExist()
    }

    @Test
    fun emptyHomeCopySitsBelowTheHeaderInTheUpperMiddle() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(360.dp, 800.dp)) {
                        App(storage = storage, clock = clock, ids = ids)
                    }
                }
            }
        }
        awaitText("No builds yet")

        // the heading must start clearly below the brand header (the B+
        // upper-middle alignment), with the whole copy well above mid-screen
        val header = composeRule.onNodeWithText("TalentForge")
        val heading = composeRule.onNodeWithText("No builds yet")
        val headerTop = header.fetchSemanticsNode().positionInRoot.y
        val headingTop = heading.fetchSemanticsNode().positionInRoot.y
        kotlin.test.assertTrue(
            headingTop - headerTop > 88f,
            "the empty-state copy must sit well below the brand header (was ${headingTop - headerTop}px)",
        )
        kotlin.test.assertTrue(
            headingTop < 400f,
            "the empty-state copy must stay in the upper-middle of the 800 dp screen (was $headingTop)",
        )
    }

    @Test
    fun emptyHomeNewBuildOpensTheClassSelection() {
        composeApp()
        awaitText("New build")

        composeRule.onNodeWithText("New build").performClick()

        awaitText("Choose a class")
    }

    // -------------------------------------------------------- TF-UX-01 purpose subtitle

    /**
     * The purpose line sits directly under the wordmark, starts at its first
     * letter, never reaches into the About target, and stays above the
     * pinned New build / Import build pair, which keep their order.
     */
    private fun assertPurposeSubtitleLayout(subtitle: String, about: String, newBuild: String, importBuild: String) {
        val wordmark = composeRule.onNodeWithText("TalentForge").fetchSemanticsNode().boundsInRoot
        val line = composeRule.onNodeWithText(subtitle).fetchSemanticsNode().boundsInRoot
        val aboutTarget = composeRule.onAllNodesWithContentDescription(about).fetchSemanticsNodes().single().boundsInRoot
        val newBuildButton = composeRule.onNodeWithText(newBuild).fetchSemanticsNode().boundsInRoot
        val importAction = composeRule.onNodeWithText(importBuild).fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(line.top >= wordmark.bottom - 0.5f, "subtitle $line must sit under the wordmark $wordmark")
        kotlin.test.assertTrue(
            kotlin.math.abs(line.left - wordmark.left) <= 1f,
            "subtitle $line must start at the wordmark's first letter $wordmark",
        )
        kotlin.test.assertTrue(line.right <= aboutTarget.left + 0.5f, "subtitle $line overlaps the About target $aboutTarget")
        kotlin.test.assertTrue(line.bottom <= newBuildButton.top, "subtitle $line overlaps New build $newBuildButton")
        kotlin.test.assertTrue(newBuildButton.bottom <= importAction.top, "Import build $importAction must stay under New build $newBuildButton")
    }

    @Test
    fun emptyHomeExplainsThePurposeUnderTheWordmark() {
        composeApp()
        awaitText("No builds yet")

        assertPurposeSubtitleLayout("Talent build planner for the game WoW\u00A0Forever", "About", "New build", "Import build")
    }

    @Test
    fun populatedHomeKeepsThePurposeSubtitle() {
        persistDraft("WARRIOR")
        persistDraft("MAGE")
        composeApp()
        awaitText("RECENT")

        assertPurposeSubtitleLayout("Talent build planner for the game WoW\u00A0Forever", "About", "New build", "Import build")
    }

    @Test
    fun thePurposeSubtitleWrapsWholeOnACompactPhoneAtLargeTextInRussian() {
        uiLanguage.language = AppLanguage.RUSSIAN
        composeApp(widthDp = 320, heightDp = 568, fontScale = 2f)
        awaitText("Пока нет билдов")

        val subtitle = "Планировщик талантов для игры WoW\u00A0Forever"
        // wraps instead of clipping: more than one line, nothing ellipsized
        val node = composeRule.onNodeWithText(subtitle).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        kotlin.test.assertTrue(layout.lineCount > 1, "the subtitle wraps at 320 dp @200 % (lines: ${layout.lineCount})")
        kotlin.test.assertTrue(!layout.hasVisualOverflow, "the subtitle is never clipped")
        assertPurposeSubtitleLayout(subtitle, "О приложении", "Новый билд", "Импортировать билд")
    }

    // ------------------------------------------------------- All Builds reachability

    @Test
    fun allBuildsStaysReachableWithExactlyOneBuild() {
        persistDraft("WARRIOR")
        composeApp()
        awaitText("CONTINUE")
        // one build = no RECENT section, but the link renders as its own row
        composeRule.onNodeWithText("RECENT").assertDoesNotExist()

        composeRule.onNodeWithText("All builds 1").performClick()

        awaitText("Builds 1")
        composeRule.onNodeWithText("Warrior build").assertExists()
    }

    @Test
    fun aRecentRowOpensItsBuild() {
        persistDraft("WARRIOR")
        persistDraft("MAGE")
        composeApp()
        awaitText("RECENT")

        // Mage is the newest (the Continue card); the RECENT row is Warrior
        composeRule.onNodeWithText("Warrior build").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------- class artwork

    @Test
    fun populatedHomeShowsClassArtworkInsteadOfMonograms() {
        persistDraft("WARRIOR")
        persistDraft("MAGE")
        composeApp()
        awaitText("RECENT")

        // the nine bundled classes render the artwork; the monogram fallback
        // ("WA"/"MA" glyphs) must NOT appear anywhere on the B+ Home
        composeRule.onAllNodesWithText("WA").fetchSemanticsNodes().let {
            kotlin.test.assertTrue(it.isEmpty(), "Warrior must render artwork, not the WA monogram")
        }
        composeRule.onAllNodesWithText("MA").fetchSemanticsNodes().let {
            kotlin.test.assertTrue(it.isEmpty(), "Mage must render artwork, not the MA monogram")
        }
    }

    // ------------------------------------------------------------ display-only names

    @Test
    fun anExplicitlyNamedDraftIsDisplayedAsSuchOnHome() {
        val id = persistDraft("WARRIOR")
        runBlocking { repository().renameBuild(id, "Draft") }
        composeApp()

        awaitText("CONTINUE")
        composeRule.onNodeWithText("Draft").assertExists()
        composeRule.onNodeWithText("Warrior build").assertDoesNotExist()
    }

    // ---------------------------------------------------------- repository failures

    @Test
    fun homeRepositoryFailureIsRecoverableAndNeverFakesEmpty() {
        persistDraft("WARRIOR")
        storage.failReads = true
        composeApp()

        awaitText("Couldn't load your builds")
        // a failed load is never presented as the empty state
        composeRule.onNodeWithText("No builds yet").assertDoesNotExist()

        storage.failReads = false
        composeRule.onNodeWithText("Retry").performClick()

        awaitText("CONTINUE")
        composeRule.onNodeWithText("Warrior build").assertExists()
    }
}
