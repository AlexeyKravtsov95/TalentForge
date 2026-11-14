package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
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
class BuildLibrarySortingUiTest {
    @get:Rule val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun sortingAndGroupingRemainAccessibleAndDoNotWriteInEnRuAtNarrowSizesAndLargeFonts() {
        for (language in AppLanguage.entries) {
            uiLanguage.language = language
            val ru = language == AppLanguage.RUSSIAN
            val copy = ShellCopy.of(language)
            for (width in listOf(320, 360, 393, 412)) for (scale in listOf(1f, 2f)) {
                val storage = FakeBuildLibraryStorage()
                val clock = FixedClock()
                ShellLibraryFixtures.persist(storage, clock, listOf(
                    ShellLibraryFixtures.Build("WARRIOR", name = "Zulu"),
                    ShellLibraryFixtures.Build("MAGE", name = "Alpha"),
                ))
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
                    waitUntil(10_000) { onAllNodesWithText(copy.allBuildsLink(2)).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText(copy.allBuildsLink(2)).performScrollTo().performClick()
                    waitUntil(10_000) { onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty() }
                    val off = if (ru) "выключена" else "off"
                    val on = if (ru) "включена" else "on"
                    val latest = if (ru) "Последние изменения" else "Last edited"
                    val name = if (ru) "По названию" else "Name"
                    val grouping = if (ru) "Группировать по классам" else "Group by class"
                    fun orderDescription(mode: String, status: String): String =
                        if (ru) "Сортировка и группировка: $mode, группировка $status"
                        else "Sort and grouping: $mode, grouping $status"
                    val control = onNodeWithContentDescription(orderDescription(latest, off))
                    control.performScrollTo().assertIsDisplayed()
                    assertTrue(control.fetchSemanticsNode().boundsInRoot.height >= 48f)
                    control.performClick()
                    onNodeWithText(name).performScrollTo().performClick()
                    onNodeWithContentDescription(orderDescription(name, off)).performScrollTo().performClick()
                    onNodeWithText(grouping).performScrollTo().performClick()
                    onNodeWithContentDescription(orderDescription(name, on)).performScrollTo().assertIsDisplayed()
                    onNodeWithText(copy.className("MAGE") + " · 1").performScrollTo().assertIsDisplayed()
                    onNodeWithText(copy.className("WARRIOR") + " · 1").performScrollTo().assertIsDisplayed()
                    assertEquals(before, storage.payload)
                    assertEquals(writes, storage.writeCount, "UI sort/group must not persist library data")
                }
            }
        }
    }
}
