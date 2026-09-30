package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The M5.1 multi-class loop through the REAL app for the milestone's manual
 * set — Warrior, Mage, Rogue (all nine are covered programmatically by
 * [io.github.alexeykravtsov95.talentforge.ui.library.BuildOpeningTest]):
 * New Build → class tap → correct class name in the editor on the real tree →
 * a real allocation succeeds → back Home shows the draft → reopening restores
 * the exact ranks. Rogue's known UNRESOLVED_CONDITION (Venom, cond 50984)
 * stays fail-closed through the same generic editor path.
 */
@RunWith(Parameterized::class)
class MultiClassDraftLoopUiTest(
    private val classToken: String,
    private val classDisplayName: String,
    private val spendableTalent: String,
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun classes() = listOf(
            arrayOf("WARRIOR", "Warrior", "Improved Rend"),
            arrayOf("MAGE", "Mage", "Arcane Focus"),
            arrayOf("ROGUE", "Rogue", "Improved Gouge"),
        )
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val storage = FakeBuildLibraryStorage()
    private val clock = FixedClock()
    private val ids = SequentialIds()

    private val datasets = io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository(
        io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource(),
    )

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

    @Test
    fun fullDraftLoopThroughTheRealApp() {
        composeApp()
        awaitText("+ New build")
        composeRule.onNodeWithText("+ New build").performClick()
        awaitText(classDisplayName)

        // class tap → editor with the correct real class name
        composeRule.onNodeWithText(classDisplayName).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("$classDisplayName · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        val engine = editorEngineOfCreatedDraft()
        val budget = engine.pointBudget
        composeRule.onNodeWithText("0 / $budget").assertExists()

        // a real allocation succeeds through the explicit control
        composeRule.onNodeWithContentDescription(spendableTalent).performClick()
        composeRule.onNodeWithText("+").performClick()
        awaitText("1 / $budget")

        // back → Home shows the draft (Continue card carries the class meta)
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")
        composeRule.onNodeWithText("$classDisplayName · Forever · Just now").assertExists()

        // reopen → the exact saved ranks are restored
        composeRule.onNodeWithText("Continue editing").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("$classDisplayName · Forever").fetchSemanticsNodes().isNotEmpty()
        }
        awaitText("1 / $budget")
    }

    /** The engine of the draft this test just created, via the generic open path. */
    private fun editorEngineOfCreatedDraft(): TalentAllocationEngine {
        val repository = io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository(storage, clock, ids)
        val records: List<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord> =
            assertIs<io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome.Loaded<List<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord>>>(
                repository.load(),
            ).value
        val record = records.single()
        assertEquals(classToken, record.build.classToken.value)

        val session = runBlocking { loadEditorSession(repository, datasets, record.localId) }
        val ready = assertIs<EditorLoad.Ready>(session)
        if (classToken == "ROGUE") {
            // known M3B rule, unchanged by M5.1: Venom (105712) carries
            // unresolved condition 50984 and stays unplannable
            val venom = ready.session.engine.unsupportedNodes().getValue(105712)
            assertEquals(
                listOf(io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode.UNRESOLVED_CONDITION),
                venom.map { it.code },
            )
        } else {
            assertTrue(ready.session.engine.unsupportedNodes().isEmpty())
        }
        return ready.session.engine
    }
}
