package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule

@OptIn(ExperimentalTestApi::class)
class AdaptiveSpecializationTabsUiTest {
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun localizedHunterSwipeRevealsTheActiveTabAndKeepsTheIndicatorOnIt() =
        runDesktopComposeUiTest(width = 568, height = 568) {
            val state = productState("HUNTER")
            uiLanguage.language = AppLanguage.RUSSIAN
            var window by mutableStateOf(IntSize(320, 568))
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, 1.3f),
                        LocalAppLanguage provides AppLanguage.RUSSIAN,
                    ) {
                        Box(Modifier.size(window.width.dp, window.height.dp)) {
                            TalentTreeScreen(state)
                        }
                    }
                }
            }

            val first = "Повелитель зверей"
            val second = "Стрельба"
            val third = "Выживание"
            shoot("hunter-ru-320-font130")
            assertFullText(first, first)
            assertActiveTab(first)
            val lastBefore = onNodeWithText(third).fetchSemanticsNode()
            assertTrue(lastBefore.boundsInRoot.width < lastBefore.size.width, "The last tab starts clipped by the viewport")

            for (label in listOf(second, third)) {
                onRoot().performTouchInput {
                    swipe(Offset(268f, 300f), Offset(56f, 300f), durationMillis = 280)
                }
                assertActiveTab(label)
                assertFullText(label, label)
            }
            shoot("hunter-ru-320-font130-after-swipe")

            runOnIdle { window = IntSize(568, 320) }
            assertActiveTab(third)
            assertFullText(first, first)

            runOnIdle { window = IntSize(320, 568) }
            assertActiveTab(third)
            onNodeWithText(first).performClick()
            assertActiveTab(first)
            // Browsing the strip itself reveals tabs without switching the tree.
            onRoot().performTouchInput {
                swipe(Offset(290f, 68f), Offset(52f, 68f), durationMillis = 280)
            }
            onNodeWithText(first).assertIsSelected()
            val browsedTab = onNodeWithText(third).fetchSemanticsNode()
            assertEquals(browsedTab.size.width.toFloat(), browsedTab.boundsInRoot.width)
            onNodeWithText(third).performTouchInput { click() }
            assertActiveTab(third)
        }

    @Test
    fun everyClassKeepsItsRenderedLabelsAndPointCountsAtPhoneWidths() =
        runDesktopComposeUiTest(width = 412, height = 48) {
            val classes = listOf("DRUID", "HUNTER", "MAGE", "PALADIN", "PRIEST", "ROGUE", "SHAMAN", "WARLOCK", "WARRIOR")
            val states = classes.map(::productState)
            var state by mutableStateOf(states.first())
            var width by mutableStateOf(320)
            var fontScale by mutableStateOf(1f)
            var tabs by mutableStateOf(emptyList<TalentSectionTab>())
            var selected by mutableStateOf(0)

            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        Box(Modifier.size(width.dp, 48.dp)) {
                            TalentTreeSectionTabs(
                                tabs = tabs,
                                selectedIndex = selected,
                                accent = state.classAccent,
                                icons = state.icons,
                                highlightedSections = emptySet(),
                                highlightToken = 0,
                                onSelectSection = { selected = it },
                            )
                        }
                    }
                }
            }

            for (classState in states) {
                val sections = buildTalentTreeSections(classState.engine.tree, classState.model) as TalentTreeSectionsResult.Sectioned
                val counts = sections.sections.mapIndexed { ordinal, section ->
                    section.index to listOf(0, 10, 41)[ordinal]
                }.toMap()
                for (locale in listOf(AppLanguage.ENGLISH, AppLanguage.RUSSIAN)) {
                    val localizedTabs = talentSectionTabs(sections, counts, classState.icons, classState.classToken, locale)
                    for (available in listOf(320, 360, 393, 412)) {
                        for (scale in listOf(1f, 1.3f, 1.75f, 2f)) {
                            runOnIdle {
                                state = classState
                                uiLanguage.language = locale
                                width = available
                                fontScale = scale
                                tabs = localizedTabs
                                selected = tabs.first().index
                            }
                            for ((ordinal, tab) in localizedTabs.withIndex()) {
                                val context = "${classState.classToken} $locale ${available}dp ${scale}x, ${tab.label}"
                                val numeric = scale >= 1.75f
                                assertFullText(if (numeric) "${ordinal + 1}" else tab.label, context)
                                assertFullText(tab.points.toString(), context)
                                val target = if (numeric) onNodeWithContentDescription(tab.label) else onNodeWithText(tab.label)
                                val config = target.fetchSemanticsNode().config
                                assertEquals(Role.Tab, config[SemanticsProperties.Role], context)
                                val pointsSpent = config[SemanticsProperties.StateDescription]
                                val expectedPoints = when {
                                    locale == AppLanguage.ENGLISH -> "${tab.points} points spent"
                                    tab.points == 41 -> "потрачено 41 очко"
                                    else -> "потрачено ${tab.points} очков"
                                }
                                assertEquals(expectedPoints, pointsSpent, context)
                            }
                            if (scale >= 1.75f) {
                                for (tab in localizedTabs) onNodeWithText(tab.label).assertDoesNotExist()
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun fittingTabsFillTheRowAndTheIndicatorFollowsUnequalWidthsDuringPaging() =
        runDesktopComposeUiTest(width = 360, height = 48) {
            var firstLabel by mutableStateOf("One")
            var position by mutableStateOf(0f)
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        TalentTreeSectionTabs(
                            tabs = listOf(firstLabel, "Two", "Three").mapIndexed { index, label ->
                                TalentSectionTab(index, label, 0, null)
                            },
                            selectedIndex = 0,
                            accent = Color.White,
                            icons = TalentNodeIcons.None,
                            highlightedSections = emptySet(),
                            highlightToken = 0,
                            onSelectSection = {},
                            indicatorPosition = { position },
                        )
                    }
                }
            }

            for (label in listOf("One", "Two", "Three")) {
                assertEquals(120f, onNodeWithText(label).fetchSemanticsNode().boundsInRoot.width)
            }

            val longLabel = "The first specialization"
            runOnIdle { firstLabel = longLabel }
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                .assertCountEquals(0)
            assertFullText(longLabel, longLabel)
            val first = onNodeWithText(longLabel).fetchSemanticsNode().boundsInRoot
            val second = onNodeWithText("Two").fetchSemanticsNode().boundsInRoot
            val last = onNodeWithText("Three").fetchSemanticsNode().boundsInRoot
            assertTrue(first.width > second.width)
            assertEquals(0f, first.left)
            assertEquals(360f, last.right)
            assertActiveTab(longLabel)

            runOnIdle { position = 0.5f }
            val indicator = onNodeWithTag(TALENT_SECTION_INDICATOR_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals((first.center.x + second.center.x) / 2, indicator.center.x, 1f)
            assertEquals((first.width + second.width) / 2 * 0.64f, indicator.width, 1f)
            // During a drag, logical selection stays on the first tab until the swipe commits.
            onNodeWithText(longLabel).assertIsSelected()
        }

    private fun ComposeUiTest.assertFullText(text: String, context: String) {
        val node = onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true, context)
        val layout = layouts.single()
        assertEquals(1, layout.lineCount, context)
        assertFalse(layout.isLineEllipsized(0), "Ellipsized text '$text' ($context)")
        // The paragraph reports fractional bounds while the layout size is integral.
        assertTrue(
            layout.getLineRight(0) <= layout.size.width + 1.5f &&
                layout.getLineBottom(0) <= layout.size.height + 1.5f,
            "Clipped text '$text' ($context): ${layout.getLineRight(0)} x ${layout.getLineBottom(0)} in ${layout.size}",
        )
    }

    private fun ComposeUiTest.assertActiveTab(label: String) {
        val node = onNodeWithText(label).assertIsSelected().fetchSemanticsNode()
        val bounds = node.boundsInRoot
        assertEquals(48f, bounds.height, label)
        assertEquals(node.size.width.toFloat(), bounds.width, "The entire active tab is visible: $label")
        val indicator = onNodeWithTag(TALENT_SECTION_INDICATOR_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.center.x, indicator.center.x, 1f, label)
        assertEquals(bounds.width * 0.64f, indicator.width, 1f, label)
    }

    private fun ComposeUiTest.shoot(name: String) {
        val directory = System.getenv("TALENTFORGE_ADAPTIVE_TAB_SCREENSHOTS") ?: return
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
