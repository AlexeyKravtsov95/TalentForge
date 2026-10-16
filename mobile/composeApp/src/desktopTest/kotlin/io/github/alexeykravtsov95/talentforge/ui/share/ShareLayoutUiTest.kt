package io.github.alexeykravtsov95.talentforge.ui.share

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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.WARRIOR_13_OF_51
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M5.SHARE.2 layout guarantees on the real app at the supported extremes —
 * small and regular portrait phones, landscape phones and tablets, at 100 %
 * and 200 % text, in English and Russian: the import entry, Continue, the
 * confirmation's Cancel / Import and the share sheet's Share / Copy code are
 * always fully inside the window (never pushed off by a long paste, a long
 * translation or large text), and the code is shown in full.
 */
@OptIn(ExperimentalTestApi::class)
class ShareLayoutUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private class Copy(
        val importBuild: String,
        val continueEditing: String,
        val continueImport: String,
        val importConfirm: String,
        val cancel: String,
        val buildActions: String,
        val shareBuild: String,
        val share: String,
        val copyCode: String,
        val field: String,
        val goBack: String,
    )

    private val english = Copy(
        importBuild = "Import build", continueEditing = "Continue editing", continueImport = "Continue",
        importConfirm = "Import", cancel = "Cancel", buildActions = "open build actions",
        shareBuild = "Share", share = "Share", copyCode = "Copy code", field = "build code", goBack = "go back",
    )

    private val russian = Copy(
        importBuild = "Импортировать билд", continueEditing = "Продолжить", continueImport = "Продолжить",
        importConfirm = "Импортировать", cancel = "Отмена", buildActions = "открыть действия с билдом",
        shareBuild = "Поделиться", share = "Поделиться", copyCode = "Копировать код", field = "код билда",
        goBack = "назад",
    )

    private fun ComposeUiTest.await(text: String) =
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

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

    private fun seedArms(storage: FakeBuildLibraryStorage) {
        val repository = LocalBuildRepository(storage, FixedClock(), SequentialIds())
        val build = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(WARRIOR_13_OF_51)).build
        val record = assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.createDraft(build)).value
        repository.renameBuild(record.localId, "Arms")
    }

    private fun checkFlow(language: AppLanguage, widthDp: Int, heightDp: Int, fontScale: Float) {
        uiLanguage.language = language
        val copy = if (language == AppLanguage.RUSSIAN) russian else english
        val storage = FakeBuildLibraryStorage().also(::seedArms)
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
            // Home: the import entry sits in the pinned zone
            await(copy.importBuild)
            assertInsideWindow(onNodeWithText(copy.importBuild), "$where Home import", widthDp, heightDp)

            // Import: a long paste never pushes Continue off screen
            tap(onNodeWithText(copy.importBuild))
            await(copy.continueImport)
            val field = onNodeWithContentDescription(copy.field)
            field.performTextInput(WARRIOR_13_OF_51.repeat(12))
            waitForIdle()
            assertInsideWindow(onNodeWithText(copy.continueImport), "$where Continue after a long paste", widthDp, heightDp)
            field.performTextClearance()
            field.performTextInput(WARRIOR_13_OF_51)
            tap(onNodeWithText(copy.continueImport))

            // the confirmation keeps both actions on screen
            await(copy.importConfirm)
            assertInsideWindow(onNodeWithText(copy.importConfirm), "$where Import", widthDp, heightDp)
            assertInsideWindow(onNodeWithText(copy.cancel), "$where Cancel", widthDp, heightDp)
            tap(onNodeWithText(copy.cancel))
            tap(onNodeWithContentDescription(copy.goBack))

            // the editor's share sheet
            await(copy.continueEditing)
            tap(onAllNodesWithText(copy.continueEditing).onFirst())
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithContentDescription(copy.buildActions).fetchSemanticsNodes().isNotEmpty()
            }
            tap(onNodeWithContentDescription(copy.buildActions))
            await(copy.shareBuild)
            tap(onNodeWithText(copy.shareBuild))
            await(copy.copyCode)
            assertInsideWindow(onNodeWithText(copy.share), "$where Share", widthDp, heightDp)
            assertInsideWindow(onNodeWithText(copy.copyCode), "$where Copy code", widthDp, heightDp)
            // the full code is on the sheet (wrapped or scrolled, never cut)
            onNodeWithText(WARRIOR_13_OF_51).assertExists()
        }
    }

    @Test
    fun smallPortraitPhone() {
        for (language in AppLanguage.entries) {
            for (scale in listOf(1f, 2f)) checkFlow(language, 320, 568, scale)
        }
    }

    @Test
    fun regularPortraitPhone() {
        for (language in AppLanguage.entries) checkFlow(language, 393, 852, 2f)
    }

    @Test
    fun landscapePhones() {
        for (language in AppLanguage.entries) {
            for (scale in listOf(1f, 2f)) checkFlow(language, 640, 360, scale)
            checkFlow(language, 915, 412, 2f)
        }
    }

    @Test
    fun tablets() {
        for (language in AppLanguage.entries) {
            checkFlow(language, 1280, 800, 1f)
            checkFlow(language, 800, 1280, 2f)
        }
    }
}
