package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildLibraryCodec
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
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.DRUID_FULL_70009
import io.github.alexeykravtsov95.talentforge.ui.share.BuildImportFlowTest.Companion.WARRIOR_13_OF_51
import io.github.alexeykravtsov95.talentforge.ui.share.RecordingBuildCodeSharing
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule

@OptIn(ExperimentalTestApi::class)
class BuildMigrationUiTest {
    @get:Rule
    val languageRule = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun libraryFlowEnglish() = libraryFlow(AppLanguage.ENGLISH)

    @Test
    fun libraryFlowRussian() = libraryFlow(AppLanguage.RUSSIAN)

    private fun libraryFlow(language: AppLanguage) = withApp(language) { storage, copy ->
        val before = storage.payload
        tapActionLabel(copy.allBuilds)
        await(copy.available)
        shoot(copy, "01-original-library")
        tapDescription(copy.rowActions("Warrior PvP"))
        await(copy.action)
        shoot(copy, "02-build-actions")
        tapText(copy.action)
        await(copy.full)
        shoot(copy, "03-full-preview")
        assertEquals(before, storage.payload)
        tapText(copy.cancel)
        assertEquals(before, storage.payload)

        tapDescription(copy.rowActions("Druid Raid"))
        tapText(copy.action)
        await(copy.partial)
        await(copy.losses, substring = true)
        assertEquals(before, storage.payload)
        shoot(copy, "04-partial-preview")
        tapText(copy.cancel)

        tapDescription(copy.rowActions("Unavailable Data"))
        tapText(copy.action)
        await(copy.missing)
        assertTrue(onAllNodesWithText(copy.confirm).fetchSemanticsNodes().isEmpty())
        shoot(copy, "05-blocked-preview")
        tapText(copy.cancel)
        assertEquals(before, storage.payload)

        tapDescription(copy.rowActions("Warrior PvP"))
        tapText(copy.action)
        await(copy.full)
        onNodeWithContentDescription(copy.name).performTextReplacement(copy.savedName)
        val confirm = onAllNodesWithText(copy.confirm).onFirst().fetchSemanticsNode().config[SemanticsActions.OnClick]
        runOnIdle {
            checkNotNull(confirm.action).invoke()
            confirm.action?.invoke()
        }
        await(copy.savedName)
        val originalRecords = LocalBuildLibraryCodec.decode(checkNotNull(before))
        val records = LocalBuildLibraryCodec.decode(checkNotNull(storage.payload))
        assertEquals(originalRecords.size + 1, records.size)
        assertEquals(originalRecords, records.filter { it.localId != "local-1" })
        val migrated = records.single { it.name == copy.savedName }
        assertEquals(PRODUCT_BUNDLE.clientBuild, migrated.build.clientBuild)
        assertEquals(decode(WARRIOR_13_OF_51).allocations, migrated.build.allocations)
        assertFalse(migrated.isFavorite)
        assertEquals(1, storage.writeCount)
        shoot(copy, "06-original-and-copy")

        tapDescription(copy.rowActions(copy.savedName))
        assertTrue(onAllNodesWithText(copy.action).fetchSemanticsNodes().isEmpty())
        tapActionLabel(copy.cancelSheet)
        tapDescription(copy.rowActions("Future Build"))
        assertTrue(onAllNodesWithText(copy.action).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun editorUsesSameMenuAndOpensNewCopy() = withApp(AppLanguage.ENGLISH, buildCount = 1) { storage, copy ->
        tapText("Continue editing")
        await(copy.available)
        val original = LocalBuildLibraryCodec.decode(checkNotNull(storage.payload)).single()
        tapDescription("open build actions")
        tapText(copy.action)
        await(copy.full)
        tapText(copy.confirm)
        await("Warrior PvP — 70450")
        val records = LocalBuildLibraryCodec.decode(checkNotNull(storage.payload))
        assertEquals(original, records.single { it.localId == original.localId })
        assertEquals(PRODUCT_BUNDLE.clientBuild, records.single { it.localId != original.localId }.build.clientBuild)
        tapDescription("open build actions")
        assertTrue(onAllNodesWithText(copy.action).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun partialConfirmationRemainsReachableAtLargeTextOnPhoneAndTablet() {
        for ((width, height) in listOf(320 to 568, 740 to 360, 820 to 1180)) {
            withApp(AppLanguage.RUSSIAN, width, height, textScale = 2f) { storage, copy ->
                tapActionLabel(copy.allBuilds)
                tapDescription(copy.rowActions("Druid Raid"))
                tapText(copy.action)
                await(copy.partial)
                onAllNodesWithText(copy.confirm).onFirst().assertIsDisplayed()
                onAllNodesWithText(copy.cancel).onFirst().assertIsDisplayed()
                onNodeWithContentDescription(copy.name, useUnmergedTree = true).performScrollTo()
                waitForIdle()
                if (width == 320) shoot(copy, "07-320dp-large-type")
                if (width == 740) shoot(copy, "08-landscape-large-type")
                onNodeWithContentDescription(copy.name, useUnmergedTree = true).assertIsDisplayed()
                tapText(copy.confirm)
                await("Druid Raid — 70450")
                val migrated = LocalBuildLibraryCodec.decode(checkNotNull(storage.payload)).single { it.localId == "local-1" }
                assertTrue(migrated.build.allocations.sumOf { it.rank } in 1..50)
            }
        }
    }

    private fun withApp(
        language: AppLanguage,
        width: Int = 390,
        height: Int = 844,
        textScale: Float = 1f,
        buildCount: Int = 4,
        block: ComposeUiTest.(FakeBuildLibraryStorage, Copy) -> Unit,
    ) {
        languageRule.language = language
        val copy = if (language == AppLanguage.RUSSIAN) Russian else English
        val storage = seededLibrary(buildCount)
        val density = if (System.getProperty(SCREENSHOTS) == null) 1f else 2f
        runDesktopComposeUiTest(width = (width * density).toInt(), height = (height * density).toInt()) {
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density, textScale),
                    LocalReducedMotion provides true,
                ) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        App(storage, FixedClock(1_701_000_000_000L), SequentialIds(), RecordingBuildCodeSharing())
                    }
                }
            }
            block(storage, copy)
        }
    }

    private fun seededLibrary(buildCount: Int): FakeBuildLibraryStorage {
        val warrior = decode(WARRIOR_13_OF_51)
        fun record(id: Int, name: String, build: BuildFile, favorite: Boolean = false) =
            LocalBuildRecord(
                "fixture-$id", name, 1_700_000_000_000L, 1_700_000_000_000L,
                lastOpenedAt = null, build = build, isFavorite = favorite,
            )
        val records = listOf(
            record(1, "Warrior PvP", warrior, favorite = true),
            record(2, "Druid Raid", decode(DRUID_FULL_70009)),
            record(3, "Unavailable Data", warrior.copy(clientBuild = "1.60.1.70010")),
            record(4, "Future Build", warrior.copy(clientBuild = "1.60.2.1")),
        )
        return FakeBuildLibraryStorage(payload = LocalBuildLibraryCodec.encode(records.take(buildCount)))
    }

    private fun decode(code: String) = assertIs<ShareCodeDecoding.Decoded>(BuildShareCode.decode(code)).build

    private fun ComposeUiTest.await(text: String, substring: Boolean = false) =
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.tapText(text: String) {
        await(text)
        onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.tapDescription(description: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.tapActionLabel(label: String) {
        val matcher = SemanticsMatcher("click label: $label") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == label
        }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        onNode(matcher).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(copy: Copy, state: String) {
        val output = System.getProperty(SCREENSHOTS) ?: return
        waitForIdle()
        val dir = File(output).also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "${copy.language}-$state.png"))
    }

    private data class Copy(
        val language: String,
        val available: String,
        val allBuilds: String,
        val action: String,
        val full: String,
        val partial: String,
        val losses: String,
        val missing: String,
        val cancel: String,
        val cancelSheet: String,
        val confirm: String,
        val name: String,
        val savedName: String,
    ) {
        fun rowActions(build: String) = if (language == "en") "build actions for $build" else "действия: $build"
    }

    private companion object {
        const val SCREENSHOTS = "talentforge.w104.screenshots"
        val English = Copy(
            "en", "Newer talent data available", "open all builds", "Create copy on latest patch",
            "All allocated points can be transferred.",
            "Some talent ranks will change in the copy. Review the changes before creating it.", "Points lost:",
            "Talent data for the original patch is unavailable.", "Cancel", "close build actions", "Create copy",
            "Copy name", "Warrior PvP — 70450",
        )
        val Russian = Copy(
            "ru", "Доступна новая версия талантов", "открыть все билды", "Создать копию на новом патче",
            "Все распределённые очки можно перенести.",
            "Некоторые ранги талантов в копии изменятся. Проверьте изменения перед созданием копии.", "Не переносится очков:",
            "Данные талантов исходного патча недоступны.", "Отмена", "закрыть действия с билдом", "Создать копию",
            "Название копии", "Воин PvP — 70450",
        )
    }
}
