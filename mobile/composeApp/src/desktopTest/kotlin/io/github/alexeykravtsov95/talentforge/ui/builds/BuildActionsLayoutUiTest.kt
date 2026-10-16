package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.share.BuildShareCode
import io.github.alexeykravtsov95.talentforge.share.ShareCodeDecoding
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.DRUID_FULL_70009
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.WARRIOR_13_OF_51
import io.github.alexeykravtsov95.talentforge.ui.share.RecordingBuildCodeSharing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M5.SHARE.2 layout guarantees of the Rename / Share actions on the real app
 * at the supported extremes — 320 dp portrait, landscape phones and a tablet,
 * at 100 % and 200 % text, in English and Russian: the Recent row's ⋯ is a
 * full 48 dp target inside the window next to a still-visible build name, and
 * every action of the Recent sheet (Rename / Share / Delete), the delete
 * confirmation, the rename dialog and the editor's ⋯
 * sheet (Rename / Share / Reset talents) is fully inside the window.
 */
@OptIn(ExperimentalTestApi::class)
class BuildActionsLayoutUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private class Copy(
        val rowActions: String,
        val rename: String,
        val share: String,
        val reset: String,
        val delete: String,
        val save: String,
        val cancel: String,
        val field: String,
        val buildActions: String,
    )

    private val english = Copy(
        rowActions = "build actions for Arms", rename = "Rename", share = "Share", reset = "Reset talents",
        delete = "Delete", save = "Save", cancel = "Cancel", field = "build name", buildActions = "open build actions",
    )

    private val russian = Copy(
        rowActions = "действия: Arms", rename = "Переименовать", share = "Поделиться", reset = "Сбросить таланты",
        delete = "Удалить", save = "Сохранить", cancel = "Отмена", field = "название билда", buildActions = "открыть действия с билдом",
    )

    private fun ComposeUiTest.await(text: String) =
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitDescription(description: String) =
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.tap(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun assertInsideWindow(node: SemanticsNodeInteraction, what: String, widthDp: Int, heightDp: Int) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val inside = bounds.width > 0f && bounds.height > 0f &&
            bounds.left >= -0.5f && bounds.top >= -0.5f &&
            bounds.right <= widthDp + 0.5f && bounds.bottom <= heightDp + 0.5f
        assertTrue(inside, "$what must be fully visible in $widthDp×$heightDp, was $bounds")
    }

    /** "Arms" (a Recent row, the older build) and "Feral" (the Continue card). */
    private fun seed(storage: FakeBuildLibraryStorage) {
        val clock = FixedClock()
        val repository = LocalBuildRepository(storage, clock, SequentialIds())
        for ((code, name) in listOf(WARRIOR_13_OF_51 to "Arms", DRUID_FULL_70009 to "Feral")) {
            val build = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build
            val record = assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.createDraft(build)).value
            repository.renameBuild(record.localId, name)
            clock.advanceBy(60_000)
        }
    }

    private fun checkActions(language: AppLanguage, widthDp: Int, heightDp: Int, fontScale: Float) {
        uiLanguage.language = language
        val copy = if (language == AppLanguage.RUSSIAN) russian else english
        val storage = FakeBuildLibraryStorage().also(::seed)
        val where = "$language ${widthDp}x$heightDp font ${(fontScale * 100).toInt()}%"
        runDesktopComposeUiTest(width = widthDp, height = heightDp) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, fontScale),
                        LocalReducedMotion provides true,
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                            App(
                                storage = storage,
                                clock = FixedClock(),
                                ids = SequentialIds(),
                                sharing = RecordingBuildCodeSharing(),
                            )
                        }
                    }
                }
            }
            // Home: the Recent row's ⋯ — a full 48 dp target, inside the window, beside its name
            awaitDescription(copy.rowActions)
            val more = onNodeWithContentDescription(copy.rowActions)
            more.performScrollTo()
            waitForIdle()
            assertInsideWindow(more, "$where Recent ⋯", widthDp, heightDp)
            val moreBounds = more.fetchSemanticsNode().boundsInRoot
            assertTrue(moreBounds.width >= 47.5f && moreBounds.height >= 47.5f, "$where ⋯ target was $moreBounds")
            // the title TEXT itself (the merged row node spans the whole row, ⋯ included)
            val title = onNodeWithText("Arms", useUnmergedTree = true)
            assertInsideWindow(title, "$where Recent build name", widthDp, heightDp)
            assertTrue(title.fetchSemanticsNode().boundsInRoot.right <= moreBounds.left + 0.5f, "$where name overlaps ⋯")

            // the Recent sheet
            tap(more)
            await(copy.rename)
            assertInsideWindow(onNodeWithText(copy.rename), "$where sheet Rename", widthDp, heightDp)
            assertInsideWindow(onNodeWithText(copy.share), "$where sheet Share", widthDp, heightDp)
            assertInsideWindow(onNodeWithText(copy.delete), "$where sheet Delete", widthDp, heightDp)

            // the shared delete confirmation
            tap(onNodeWithText(copy.delete))
            await(copy.cancel)
            assertInsideWindow(onNodeWithText(copy.delete), "$where confirm Delete", widthDp, heightDp)
            assertInsideWindow(onNodeWithText(copy.cancel), "$where confirm Cancel", widthDp, heightDp)
            tap(onNodeWithText(copy.cancel))
            awaitDescription(copy.rowActions)
            tap(onNodeWithContentDescription(copy.rowActions))
            await(copy.rename)

            // the shared rename dialog
            tap(onNodeWithText(copy.rename))
            await(copy.save)
            assertInsideWindow(onNodeWithContentDescription(copy.field), "$where rename field", widthDp, heightDp)
            assertInsideWindow(onNodeWithText(copy.save), "$where Save", widthDp, heightDp)
            assertInsideWindow(onNodeWithText(copy.cancel), "$where Cancel", widthDp, heightDp)
            tap(onNodeWithText(copy.cancel))

            // the editor's ⋯ sheet: Rename / Share / Reset talents
            awaitDescription(copy.rowActions)
            tap(onNodeWithText("Arms"))
            awaitDescription(copy.buildActions)
            tap(onNodeWithContentDescription(copy.buildActions))
            await(copy.reset)
            for ((label, what) in listOf(copy.rename to "Rename", copy.share to "Share", copy.reset to "Reset talents")) {
                assertInsideWindow(onNodeWithText(label), "$where editor $what", widthDp, heightDp)
            }
        }
    }

    @Test
    fun smallPortraitPhone() {
        for (language in AppLanguage.entries) {
            for (scale in listOf(1f, 2f)) checkActions(language, 320, 568, scale)
        }
    }

    @Test
    fun regularPortraitPhone() {
        for (language in AppLanguage.entries) checkActions(language, 393, 852, 2f)
    }

    @Test
    fun landscapePhones() {
        for (language in AppLanguage.entries) {
            for (scale in listOf(1f, 2f)) checkActions(language, 640, 360, scale)
            checkActions(language, 915, 412, 2f)
        }
    }

    @Test
    fun tablet() {
        for (language in AppLanguage.entries) checkActions(language, 1280, 800, 1f)
    }
}
