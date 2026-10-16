package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.MAGE_EMPTY
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.WARRIOR_13_OF_51
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * M5.SHARE.2 PR evidence, desktop-rendered from the REAL app (bundled data,
 * real engine, in-memory library): Home's import entry, the import screen,
 * the confirmation of a current and of an older client build, an error, the
 * editor's ⋯ sheet (Rename / Share / Reset talents) and share sheet, All
 * Builds' row sheet, Home's Recent-row actions, the rename dialog opened from
 * the editor and the editor title after the rename — in English and Russian,
 * portrait and landscape, 100 % and 200 % text.
 *
 * Skipped unless the caller opts in (evidence, not a regression assertion —
 * the assertions live in `BuildShareUiTest`, `BuildImportUiTest` and
 * `ShareLayoutUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5Share2ScreenshotTest*" \
 *     -Dtalentforge.m5share2.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class M5Share2ScreenshotTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private class Copy(
        val lang: String,
        val importBuild: String,
        val continueEditing: String,
        val continueImport: String,
        val cancel: String,
        val damaged: String,
        val buildActions: String,
        val shareBuild: String,
        val copyCode: String,
        val share: String,
        val field: String,
        val allBuilds: String,
        val rowActions: String,
        val recentActions: String,
        val rename: String,
        val renameField: String,
        val save: String,
        val newName: String,
        val delete: String,
    )

    private val english = Copy(
        lang = "en", importBuild = "Import build", continueEditing = "Continue editing", continueImport = "Continue",
        cancel = "Cancel", damaged = "This code is damaged or incomplete. Copy it again from the original message.",
        buildActions = "open build actions", shareBuild = "Share", copyCode = "Copy code", share = "Share",
        field = "build code", allBuilds = "All builds 2", rowActions = "build actions for Arms",
        recentActions = "build actions for Mage build", rename = "Rename", renameField = "build name", save = "Save",
        newName = "Arms · raid", delete = "Delete",
    )

    private val russian = Copy(
        lang = "ru", importBuild = "Импортировать билд", continueEditing = "Продолжить", continueImport = "Продолжить",
        cancel = "Отмена", damaged = "Код повреждён или неполон. Скопируйте его ещё раз из исходного сообщения.",
        buildActions = "открыть действия с билдом", shareBuild = "Поделиться", copyCode = "Копировать код",
        share = "Поделиться", field = "код билда", allBuilds = "Все билды 2", rowActions = "действия: Arms",
        recentActions = "действия: Билд мага", rename = "Переименовать", renameField = "название билда",
        save = "Сохранить", newName = "Оружие · рейд", delete = "Удалить",
    )

    private fun copyOf(language: AppLanguage) = if (language == AppLanguage.RUSSIAN) russian else english

    /** An empty library, or two builds: "Arms" (Warrior 13/51, the newest) and an unnamed Mage. */
    private fun library(empty: Boolean): FakeBuildLibraryStorage {
        val storage = FakeBuildLibraryStorage()
        if (empty) return storage
        val clock = FixedClock(startMillis = 1_700_000_000_000L)
        val repository = LocalBuildRepository(storage, clock, SequentialIds())
        assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.createDraft(decoded(MAGE_EMPTY)))
        clock.advanceBy(60_000)
        val arms = assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(
            repository.createDraft(decoded(WARRIOR_13_OF_51)),
        ).value
        repository.renameBuild(arms.localId, "Arms")
        return storage
    }

    private fun decoded(code: String) = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build

    private fun onApp(
        language: AppLanguage,
        widthDp: Int = 393,
        heightDp: Int = 852,
        fontScale: Float = 1f,
        empty: Boolean = false,
        block: ComposeUiTest.(Copy) -> Unit,
    ) {
        uiLanguage.language = language
        val storage = library(empty)
        runDesktopComposeUiTest(width = (widthDp * DENSITY).toInt(), height = (heightDp * DENSITY).toInt()) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(DENSITY, fontScale),
                        LocalReducedMotion provides true,
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                            App(
                                storage = storage,
                                clock = FixedClock(startMillis = 1_700_000_100_000L),
                                ids = SequentialIds(),
                                sharing = RecordingBuildCodeSharing(),
                            )
                        }
                    }
                }
            }
            block(copyOf(language))
        }
    }

    private fun ComposeUiTest.await(text: String) =
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.tapText(text: String) {
        await(text)
        onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.tapDescription(description: String) {
        waitUntil(timeoutMillis = 20_000) {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithContentDescription(description).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, "m5share2-$name.png"))
    }

    private fun ComposeUiTest.enter(copy: Copy, code: String) {
        onNodeWithContentDescription(copy.field).performTextInput(code)
        waitForIdle()
    }

    private fun ComposeUiTest.openBuildActions(copy: Copy) {
        tapText(copy.continueEditing)
        tapDescription(copy.buildActions)
        await(copy.shareBuild)
    }

    @Test
    fun importFlow() {
        for (language in AppLanguage.entries) {
            onApp(language, empty = true) { copy ->
                await(copy.importBuild)
                shoot("${copy.lang}-393x852-home-empty")
                tapText(copy.importBuild)
                enter(copy, WARRIOR_13_OF_51)
                shoot("${copy.lang}-393x852-import-input")
                tapText(copy.continueImport)
                await(copy.cancel)
                shoot("${copy.lang}-393x852-import-preview")
            }
            onApp(language, empty = true) { copy ->
                tapText(copy.importBuild)
                enter(copy, DRUID_FULL_70009)
                tapText(copy.continueImport)
                await(copy.cancel)
                shoot("${copy.lang}-393x852-import-preview-older-build")
            }
            onApp(language, empty = true) { copy ->
                tapText(copy.importBuild)
                enter(copy, MAGE_EMPTY.substring(0, 20) + "Z" + MAGE_EMPTY.substring(21))
                tapText(copy.continueImport)
                await(copy.damaged)
                shoot("${copy.lang}-393x852-import-damaged")
            }
        }
        onApp(AppLanguage.RUSSIAN, widthDp = 320, heightDp = 568, fontScale = 2f, empty = true) { copy ->
            tapText(copy.importBuild)
            enter(copy, WARRIOR_13_OF_51)
            tapText(copy.continueImport)
            await(copy.cancel)
            shoot("ru-320x568-font200-import-preview")
        }
        onApp(AppLanguage.ENGLISH, widthDp = 915, heightDp = 412, empty = true) { copy ->
            tapText(copy.importBuild)
            enter(copy, WARRIOR_13_OF_51)
            shoot("en-915x412-import-input")
        }
    }

    @Test
    fun exportFlow() {
        for (language in AppLanguage.entries) {
            onApp(language) { copy ->
                openBuildActions(copy)
                shoot("${copy.lang}-393x852-editor-build-actions")
                tapText(copy.shareBuild)
                await(copy.copyCode)
                tapText(copy.copyCode)
                shoot("${copy.lang}-393x852-editor-share-sheet-copied")
            }
            onApp(language) { copy ->
                tapText(copy.allBuilds)
                tapDescription(copy.rowActions)
                await(copy.share)
                shoot("${copy.lang}-393x852-all-builds-row-actions")
                tapText(copy.share)
                await(copy.copyCode)
                shoot("${copy.lang}-393x852-all-builds-share-sheet")
            }
            onApp(language, widthDp = 915, heightDp = 412) { copy ->
                openBuildActions(copy)
                tapText(copy.shareBuild)
                await(copy.copyCode)
                shoot("${copy.lang}-915x412-editor-share-sheet")
            }
        }
        onApp(AppLanguage.RUSSIAN, widthDp = 320, heightDp = 568, fontScale = 2f) { copy ->
            openBuildActions(copy)
            tapText(copy.shareBuild)
            await(copy.copyCode)
            shoot("ru-320x568-font200-editor-share-sheet")
        }
    }

    @Test
    fun renameAndShareActionsFlow() {
        for (language in AppLanguage.entries) {
            onApp(language) { copy ->
                tapDescription(copy.recentActions)
                await(copy.rename)
                shoot("${copy.lang}-393x852-home-recent-actions")
                tapText(copy.delete)
                await(copy.cancel)
                shoot("${copy.lang}-393x852-home-delete-confirm")
            }
            onApp(language) { copy ->
                openBuildActions(copy)
                tapText(copy.rename)
                await(copy.save)
                onNodeWithContentDescription(copy.renameField).performTextReplacement(copy.newName)
                waitForIdle()
                shoot("${copy.lang}-393x852-editor-rename-dialog")
                tapText(copy.save)
                await(copy.newName)
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(copy.save).fetchSemanticsNodes().isEmpty() }
                shoot("${copy.lang}-393x852-editor-renamed-title")
            }
            onApp(language, widthDp = 915, heightDp = 412) { copy ->
                openBuildActions(copy)
                shoot("${copy.lang}-915x412-editor-build-actions")
            }
        }
        onApp(AppLanguage.RUSSIAN, widthDp = 320, heightDp = 568, fontScale = 2f) { copy ->
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithContentDescription(copy.recentActions).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithContentDescription(copy.recentActions).performScrollTo()
            shoot("ru-320x568-font200-home-recent-row")
            tapDescription(copy.recentActions)
            await(copy.rename)
            shoot("ru-320x568-font200-home-recent-actions")
        }
        onApp(AppLanguage.RUSSIAN, widthDp = 320, heightDp = 568, fontScale = 2f) { copy ->
            openBuildActions(copy)
            shoot("ru-320x568-font200-editor-build-actions")
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5share2.screenshots"
        const val DENSITY = 2f
    }
}
