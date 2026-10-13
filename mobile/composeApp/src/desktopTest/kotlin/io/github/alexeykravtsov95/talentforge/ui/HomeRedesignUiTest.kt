package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
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

    private fun composeApp() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(360.dp, 760.dp)) {
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
        composeRule.onNodeWithText(
            "Pick a version and class, then spend points on the talent tree. " +
                "Builds are stored on this device; no account is needed.",
        ).assertExists()
        composeRule.onNodeWithText("Forever talent data 1.60.1").assertExists()
        // the single primary action of the empty state, pinned at the bottom
        composeRule.onNodeWithText("New build").assertExists()
        // nothing fake, nothing decorative
        composeRule.onNodeWithText("CONTINUE").assertDoesNotExist()
        composeRule.onNodeWithText("RECENT").assertDoesNotExist()
        composeRule.onNodeWithText("All builds 0").assertDoesNotExist()
    }

    @Test
    fun emptyHomeNewBuildOpensTheClassSelection() {
        composeApp()
        awaitText("New build")

        composeRule.onNodeWithText("New build").performClick()

        awaitText("GAME VERSION")
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
