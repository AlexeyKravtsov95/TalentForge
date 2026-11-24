package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

@OptIn(ExperimentalTestApi::class)
class FirstBuildJourneyUiTest {
    @get:Rule val language = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun aFirstBuildCanBeNamedFromItsTitleAndReopenedAfterRestart() {
        for (locale in AppLanguage.entries) {
            language.language = locale
            fun copy(en: String, ru: String) = if (locale == AppLanguage.RUSSIAN) ru else en
            val storage = FakeBuildLibraryStorage()
            val clock = FixedClock()
            val ids = SequentialIds()
            val buildName = "W1.02 Warrior"
            val points = "1 / 51"
            val back = copy("go back", "назад")
            val continueEditing = copy("Continue editing", "Продолжить")
            val draftHint = copy("Draft · tap to name", "Черновик · назвать")
            val renameDialog = copy("Rename build", "Новое название")
            val rend = copy("Improved Rend", "Улучшенное кровопускание")
            val wounds = copy("Deep Wounds", "Глубокие раны")

            runDesktopComposeUiTest(width = 320, height = 760) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        App(storage, clock, ids)
                    }
                }
                awaitText(copy("No builds yet", "Пока нет билдов"))
                onNodeWithText(copy("New build", "Новый билд")).performClick()
                awaitText(copy("Warrior", "Воин"))
                onNodeWithText(copy("Warrior", "Воин")).performClick()
                awaitText("0 / 51")

                onNodeWithContentDescription(wounds).performClick()
                onNodeWithText(copy("Requires Improved Rend 3/3", "Требуется «Улучшенное кровопускание» 3/3")).assertExists()
                onNodeWithText(copy("Show", "Показать")).performClick()
                onNodeWithText("0 / 51").assertExists()
                onNodeWithContentDescription(copy("Add rank to $rend", "Добавить ранг: $rend")).performClick()
                awaitText(points)

                val nameAction = onNodeWithText(draftHint).assertHasClickAction()
                assertTrue(nameAction.fetchSemanticsNode().boundsInRoot.height >= 48f)
                nameAction.performClick()
                awaitText(renameDialog)
                onNodeWithContentDescription(copy("build name", "название билда")).performTextReplacement(buildName)
                onNodeWithText(copy("Save", "Сохранить")).performClick()
                awaitText(buildName)
                onNodeWithText(draftHint).assertDoesNotExist()
                onNodeWithText(points).assertExists()

                onNodeWithContentDescription(back).performClick()
                awaitText(continueEditing)
                onNodeWithText(continueEditing).performClick()
                awaitText(buildName)
                onNodeWithText(points).assertExists()
            }

            val relaunchedStorage = FakeBuildLibraryStorage(storage.payload)
            val relaunchedIds = SequentialIds()
            runDesktopComposeUiTest(width = 360, height = 760) {
                setContent { App(relaunchedStorage, clock, relaunchedIds) }
                awaitText(continueEditing)
                onNodeWithText(continueEditing).performClick()
                awaitText(buildName)
                onNodeWithText(points).assertExists()
                onNodeWithContentDescription(rend).performClick()
                onNode(hasText(rend) and hasText("1/3")).assertExists()
            }
        }
    }

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
