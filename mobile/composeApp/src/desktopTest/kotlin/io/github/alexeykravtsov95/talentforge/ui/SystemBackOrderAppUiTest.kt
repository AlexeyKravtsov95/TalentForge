package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.tree.EditorCopy
import io.github.alexeykravtsov95.talentforge.ui.tree.TALENT_INSPECTOR_COMPACT_TAG
import io.github.alexeykravtsov95.talentforge.ui.tree.TALENT_INSPECTOR_SHEET_TAG
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * PR #19: the SYSTEM Back (the desktop dispatcher, same registration rules as
 * Android's) through the real app: expanded → compact → deselected → the
 * app's navigation Back, whose autosave flush and re-entrancy guard still hold
 * — a double Back while the newest save is in flight pops exactly once, and
 * the spent rank is on storage.
 */
class SystemBackOrderAppUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private val clock = FixedClock()
    private val ids = SequentialIds()

    private val datasets = io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository(
        io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource(),
    )

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
    }

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun onEditor(): Boolean =
        composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()

    private fun has(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun systemBack(): Boolean {
        var handled = false
        composeRule.runOnIdle { handled = DesktopBackDispatcher.dispatch() }
        composeRule.waitForIdle()
        return handled
    }

    private fun storedWarriorRank(): Int {
        val records = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(repository().load()).value
        return records.first { it.build.classToken.value == "WARRIOR" }.build.allocations.single().rank
    }

    @Test
    fun systemBackWalksTheInspectorOrderThenNavigatesExactlyOnceWithTheSaveFlushed() {
        assertIs<DraftCreation.Created>(runBlocking { createClassDraft(repository(), datasets, ClassToken("WARRIOR")) })
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(360.dp, 760.dp)) { App(storage = storage, clock = clock, ids = ids) }
                }
            }
        }
        awaitText("Continue editing")
        composeRule.onNodeWithText("Continue editing").performClick()
        composeRule.waitUntil(10_000) { onEditor() }

        // the newest save is held in flight while Back is pressed
        storage.writeDelayMillis = 2_500
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithContentDescription(EditorCopy.English.expand).performClick()
        composeRule.waitForIdle()
        assertTrue(has(TALENT_INSPECTOR_SHEET_TAG))

        assertTrue(systemBack())
        assertFalse(has(TALENT_INSPECTOR_SHEET_TAG), "1: the sheet collapsed")
        assertTrue(has(TALENT_INSPECTOR_COMPACT_TAG) && onEditor(), "… and the editor stayed")

        assertTrue(systemBack())
        assertFalse(has(TALENT_INSPECTOR_COMPACT_TAG), "2: the selection cleared")
        assertTrue(onEditor(), "… and the editor stayed")

        // 3 + 4: two quick system Backs while the flush is still in flight → exactly one pop
        assertTrue(systemBack())
        systemBack()
        awaitText("CONTINUE")
        assertFalse(onEditor(), "the editor is gone after the single pop")
        assertFalse(systemBack(), "Home is the root: nothing handles a further Back, the stack is never emptied")
        assertEquals(1, storedWarriorRank(), "the spend made before Back is on storage")
    }
}
