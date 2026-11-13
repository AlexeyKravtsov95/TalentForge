package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.ShellCopy
import io.github.alexeykravtsov95.talentforge.ui.ShellLibraryFixtures
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

@OptIn(ExperimentalTestApi::class)
class BuildLibraryFiltersUiTest {
    @get:Rule val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun searchClassResetAndNewBuildRemainReachableAcrossTheRequestedSizeAndFontMatrix() {
        for (language in AppLanguage.entries) {
            uiLanguage.language = language
            val ru = language == AppLanguage.RUSSIAN
            val copy = ShellCopy.of(language)
            for (width in listOf(320, 360, 393, 412)) for (scale in listOf(1f, 1.3f, 1.75f, 2f)) {
                val storage = FakeBuildLibraryStorage()
                val clock = FixedClock()
                ShellLibraryFixtures.persist(storage, clock, listOf(ShellLibraryFixtures.Build("WARRIOR", name = "Raid")))
                val before = storage.payload
                val writes = storage.writeCount
                runDesktopComposeUiTest(width = width, height = 852) {
                    setContent {
                        TalentForgeTheme {
                            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                                Box(Modifier.size(width.dp, 852.dp)) {
                                    App(storage = storage, clock = clock, ids = SequentialIds())
                                }
                            }
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(copy.allBuildsLink(1)).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText(copy.allBuildsLink(1)).performScrollTo().performClick()
                    val label = if (ru) "Поиск билдов" else "Search builds"
                    val all = if (ru) "Все классы" else "All classes"
                    val filterPrefix = if (ru) "Фильтр по классу: " else "Filter by class: "
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Raid").fetchSemanticsNodes().isNotEmpty() }
                    val search = onNodeWithContentDescription(label)
                    search.performScrollTo().assertIsDisplayed()
                    assertTrue(search.fetchSemanticsNode().boundsInRoot.height >= 48f)
                    search.performTextReplacement("absent")
                    val clear = onNodeWithContentDescription(if (ru) "Очистить поиск" else "Clear search")
                    clear.assertIsDisplayed()
                    assertTrue(clear.fetchSemanticsNode().boundsInRoot.height >= 48f)
                    clear.performClick()
                    val filter = onNodeWithContentDescription(filterPrefix + all)
                    filter.performScrollTo().assertIsDisplayed().performClick()
                    onNode(hasText(all) and isSelected()).assertIsSelected()
                    // The last of nine options must be reachable even at 200% text.
                    onNodeWithText(copy.className("DRUID")).performScrollTo().assertIsDisplayed().performClick()
                    onNodeWithContentDescription(filterPrefix + copy.className("DRUID")).assertIsDisplayed()
                    val title = if (ru) "Нет билдов этого класса" else "No builds for this class"
                    onNodeWithText(title).performScrollTo().assertIsDisplayed()
                    val layouts = mutableListOf<TextLayoutResult>()
                    onNodeWithText(title).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    assertTrue(layouts.isNotEmpty())
                    layouts.forEach { layout ->
                        // Match the shell's existing glyph/constraint check: Skia can report
                        // hasVisualOverflow for sub-pixel font rounding without clipping.
                        val availableWidth = maxOf(layout.size.width, layout.layoutInput.constraints.maxWidth)
                        val availableHeight = maxOf(layout.size.height, layout.layoutInput.constraints.maxHeight)
                        assertTrue((0 until layout.lineCount).maxOf { layout.getLineRight(it) } <= availableWidth + 1.5f)
                        assertTrue(layout.getLineBottom(layout.lineCount - 1) <= availableHeight + 1.5f)
                        for (line in 0 until layout.lineCount - 1) {
                            val end = layout.getLineEnd(line, visibleEnd = false)
                            assertTrue(layout.layoutInput.text.text[end - 1].isWhitespace(), "$language $width dp $scale: $title wraps mid-word")
                        }
                    }
                    onNodeWithText(if (ru) "Сбросить фильтры" else "Reset filters").performScrollTo().assertIsDisplayed().performClick()
                    onNodeWithText("Raid").performScrollTo().assertIsDisplayed()
                    assertEquals(before, storage.payload)
                    assertEquals(writes, storage.writeCount)
                    val add = if (ru) "Добавить в избранное: Raid" else "Add to Favorites: Raid"
                    val remove = if (ru) "Убрать из избранного: Raid" else "Remove from Favorites: Raid"
                    val favorites = if (ru) "Избранное" else "Favorites"
                    val star = onNodeWithContentDescription(add).performScrollTo().assertIsDisplayed().assertIsOff()
                    assertTrue(star.fetchSemanticsNode().boundsInRoot.width >= 48f)
                    assertTrue(star.fetchSemanticsNode().boundsInRoot.height >= 48f)
                    star.performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription(remove).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithContentDescription(remove).assertIsOn()
                    onNodeWithText(favorites).performScrollTo().performClick().assertIsOn()
                    onNodeWithText("Raid").performScrollTo().assertIsDisplayed()
                    onNodeWithContentDescription(remove).performScrollTo().performClick()
                    val noFavorites = if (ru) "В избранном пока пусто" else "No favorites yet"
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(noFavorites).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText(noFavorites).performScrollTo().assertIsDisplayed()
                    onNodeWithText(if (ru) "Сбросить фильтры" else "Reset filters").performScrollTo().performClick()
                    onNodeWithContentDescription(add).performScrollTo().assertIsOff()
                    assertEquals(before, storage.payload, "favorite/unfavorite restores every byte of v2")
                    assertEquals(writes + 2, storage.writeCount)
                    // The existing + action stays reachable above the scrolling filters.
                    onNodeWithText("+").assertIsDisplayed().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(copy.className("WARRIOR")).fetchSemanticsNodes().isNotEmpty() }
                }
            }
        }
    }
}
