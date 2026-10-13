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
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.EditorLoad
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadEditorSession
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The M5.1 autosave-exit guarantee (review fix): pressing Back immediately
 * after a mutation — WITHOUT waiting for autosave — must still leave the
 * newest BuildState persisted. Navigation only completes after the app-level
 * save coordinator confirms the flush, so "editor left composition before the
 * save consumer persisted" can no longer lose a build.
 */
class AutosaveExitGuaranteeUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
    }

    private val ids = SequentialIds()
    private val clock = FixedClock()

    private fun repository() = LocalBuildRepository(storage, clock, ids)

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

    private fun openWarriorEditorFromHome() {
        val created = assertIs<io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation.Created>(
            runBlocking { createClassDraft(repository(), datasets, ClassToken("WARRIOR")) },
        )
        composeApp()
        awaitText("Continue editing")
        composeRule.onNodeWithText("Continue editing").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Warrior · Forever").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A fresh repository over the SAME serialized storage — a relaunched app. */
    private fun freshRepositoryRecords(): List<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord> {
        val reloaded = LocalBuildRepository(FakeBuildLibraryStorage(storage.payload), FixedClock(9L), SequentialIds())
        return assertIs<BuildLibraryOutcome.Loaded<List<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord>>>(
            reloaded.load(),
        ).value
    }

    @Test
    fun immediateBackAfterOneMutationStillPersistsRankOne() {
        openWarriorEditorFromHome()

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.onNodeWithText("+").performClick()
        // NO wait for autosave here — Back must flush the pipeline itself
        composeRule.onNodeWithContentDescription("go back").performClick()

        // navigation completed ⇒ the flush guarantee held
        awaitText("CONTINUE")

        val record = freshRepositoryRecords().single()
        val allocation = record.build.allocations.single()
        assertEquals(1, allocation.rank, "the just-spent rank must already be persisted")
        assertEquals(
            "Warrior",
            assertIs<EditorLoad.Ready>(
                runBlocking {
                    loadEditorSession(
                        LocalBuildRepository(FakeBuildLibraryStorage(storage.payload), FixedClock(9L), SequentialIds()),
                        datasets,
                        record.localId,
                    )
                },
            ).session.classDisplayName,
        )
    }

    @Test
    fun immediateBackAfterABurstPersistsExactlyTheNewestState() {
        openWarriorEditorFromHome()

        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText("+").performClick()
        // immediate Back: the burst may coalesce, the NEWEST state must land
        composeRule.onNodeWithContentDescription("go back").performClick()

        awaitText("CONTINUE")

        val record = freshRepositoryRecords().single()
        val allocation = record.build.allocations.single()
        assertEquals(3, allocation.rank, "exactly the latest 3/3 state must be persisted")
        assertEquals(1, record.build.allocations.size)
    }

    @Test
    fun aFailedSaveVisiblyBlocksBackUntilRetrySucceeds() {
        openWarriorEditorFromHome()

        storage.failWrites = true
        composeRule.onNodeWithContentDescription("Improved Rend").performClick()
        composeRule.onNodeWithText("+").performClick()
        awaitText("Couldn't save your changes on this device")

        // Back must NOT complete while the newest state is unpersisted
        composeRule.onNodeWithContentDescription("go back").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Couldn't save your changes on this device")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("CONTINUE").fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "Home must not appear while the newest build is unpersisted")
        }

        // retry from the visible notice clears the error, then Back completes
        storage.failWrites = false
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Couldn't save your changes on this device")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("go back").performClick()
        awaitText("CONTINUE")

        val record = freshRepositoryRecords().single()
        assertEquals(1, record.build.allocations.single().rank)
    }
}
