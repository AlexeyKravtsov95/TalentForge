package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * M5.UX.1 × M5.1 autosave/opening regression, on the real app: switching
 * section tabs, panning, zooming and FIT never write the build library (they
 * are not build changes), an explicit spend made under the tabs still
 * autosaves exactly as before, and the reopened saved build shows the same
 * per-section points.
 */
class SectionTabsAutosaveUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private val ids = SequentialIds()
    private val clock = FixedClock()
    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
    }

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    /** M5.UX.2b.1: the phone's window; a rotation swaps it in place. */
    private var window by mutableStateOf(IntSize(360, 760))

    private fun composeApp() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(window.width.dp, window.height.dp)) {
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

    private fun openEditorFromHome() {
        awaitText("Continue editing")
        composeRule.onNodeWithText("Continue editing").performClick()
        awaitText("Warrior · Forever")
        awaitText("Tree 1")
        composeRule.waitForIdle()
    }

    private fun tabState(label: String): String =
        composeRule.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun records(): List<LocalBuildRecord> {
        val reloaded = LocalBuildRepository(FakeBuildLibraryStorage(storage.payload), FixedClock(9L), SequentialIds())
        return assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(reloaded.load()).value
    }

    @Test
    fun tabsPanAndFitNeverWriteTheLibraryWhileASpendStillAutosaves() {
        assertIs<DraftCreation.Created>(runBlocking { createClassDraft(repository(), datasets, ClassToken("WARRIOR")) })
        composeApp()
        openEditorFromHome()
        val writesAfterOpen = storage.writeCount

        // tab switches, a drag, a re-tap FIT: view state only
        composeRule.onNodeWithText("Tree 2").performClick()
        composeRule.onNodeWithText("Tree 3").performClick()
        composeRule.onNodeWithText("Tree 1").performClick()
        composeRule.onRoot().performTouchInput {
            swipe(Offset(300f, 300f), Offset(300f, 380f), durationMillis = 300)
        }
        composeRule.onNodeWithText("Tree 1").performClick() // active tab → FIT
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        assertEquals(writesAfterOpen, storage.writeCount, "no build change, no save")

        // an explicit spend autosaves through the unchanged M5.1 path
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitUntil(10_000) { storage.writeCount > writesAfterOpen }
        composeRule.onNodeWithText("Tree 2").performClick() // switching away keeps the build
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")

        val record = records().single()
        assertEquals(1, record.build.allocations.single().rank)

        // reopening the saved build: tabs again, Tree 1 first, the same points
        openEditorFromHome()
        composeRule.onNodeWithText("Tree 1").assertIsSelected()
        assertEquals("1 point spent", tabState("Tree 1"))
        assertEquals("0 points spent", tabState("Tree 2"))
        composeRule.onNodeWithText("1 / 51").assertExists()
    }

    @Test
    fun rotatingAndLandscapeNavigationNeverWriteTheLibrary() {
        assertIs<DraftCreation.Created>(runBlocking { createClassDraft(repository(), datasets, ClassToken("WARRIOR")) })
        composeApp()
        openEditorFromHome()
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitUntil(10_000) { storage.writeCount > 0 }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        val writesAfterSpend = storage.writeCount

        // portrait → landscape → portrait, with landscape tab switches and a
        // selection in between: view state only, so no save is ever written
        composeRule.runOnIdle { window = IntSize(760, 360) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tree 1").assertIsSelected()
        composeRule.onNodeWithText("Tree 2").performClick()
        composeRule.onNodeWithText("Tree 1").performClick()
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.runOnIdle { window = IntSize(360, 760) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        assertEquals(writesAfterSpend, storage.writeCount, "an orientation change is not a build change")
        assertEquals("1 point spent", tabState("Tree 1"))

        // and the saved build is exactly the one explicit spend
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")
        assertEquals(1, records().single().build.allocations.single().rank)
    }
}
