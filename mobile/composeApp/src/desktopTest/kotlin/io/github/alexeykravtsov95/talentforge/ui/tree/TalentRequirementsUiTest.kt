package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import org.junit.Rule
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class TalentRequirementsUiTest {
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun compactInspectorKeepsBothUnlockReasons() = runDesktopComposeUiTest(width = 360, height = 760) {
        val state = productState("WARRIOR")
        val changes = mutableListOf<BuildState>()
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        TalentTreeScreen(state, onBuildStateChanged = { changes += it })
                    }
                }
            }
        }
        onNodeWithContentDescription("Deep Wounds").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithText("Requires Improved Rend 3/3", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Spend 10 more points in this tree", substring = true, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(emptyList(), changes)
    }

    @Test
    fun hunterMultipleRequirementsNameBothOptions() = runDesktopComposeUiTest(width = 360, height = 760) {
        val state = productState("HUNTER")
        val changes = mutableListOf<BuildState>()
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppLanguage provides AppLanguage.ENGLISH) {
                    Box(Modifier.size(360.dp, 760.dp)) { TalentTreeScreen(state, onBuildStateChanged = { changes += it }) }
                }
            }
        }
        onNodeWithContentDescription("Intimidation").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithText("Requirements").performClick()
        waitForIdle()
        onNodeWithText("Bestial Swiftness", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Bestial Wrath", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("○ Not met · Any one of these talents", useUnmergedTree = true).assertExists()
        evidence("hunter-choices")
        onNode(SemanticsMatcher("Show Bestial Swiftness") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "show prerequisite Bestial Swiftness"
        }).performScrollTo().performClick()
        waitForIdle()
        onNodeWithContentDescription("Bestial Swiftness").assertIsSelected()
        assertEquals(emptyList(), changes, "Show must never spend or autosave")
    }
    @Test
    fun combinedRequirementsWrapAtEveryPhoneSizeAndFontScaleInBothLanguages() {
        val state = localizedProductState("WARRIOR")
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.RUSSIAN)) {
            uiLanguage.language = language
            val copy = if (language == AppLanguage.ENGLISH) EditorCopy.English else EditorCopy.Russian
            for (width in listOf(320, 360, 393, 412)) for (fontScale in listOf(1f, 1.3f, 2f)) {
                runDesktopComposeUiTest(width = width, height = 760) {
                    val changes = mutableListOf<BuildState>()
                    val cameras = TalentTreeSectionCameras("requirements", state.model.treeId)
                    setContent {
                        TalentForgeTheme {
                            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalAppLanguage provides language) {
                                Box(Modifier.size(width.dp, 760.dp)) {
                                    TalentTreeScreen(state, sectionCameras = cameras, onBuildStateChanged = { changes += it })
                                }
                            }
                        }
                    }
                    waitForIdle()
                    val camera = cameras.stateOf(state.engine.tree.sections.first().index)
                    runOnIdle { camera.applyUserTransform(camera.viewport!!.zoomAt(androidx.compose.ui.geometry.Offset(160f, 180f), 1.4f)) }
                    val zoom = camera.viewport!!.scale
                    val name = if (language == AppLanguage.ENGLISH) WarriorText.DEEP_WOUNDS_EN else WarriorText.DEEP_WOUNDS_RU
                    onNodeWithContentDescription(name).performSemanticsAction(SemanticsActions.OnClick)
                    waitForIdle()
                    mainClock.advanceTimeBy(1000)
                    waitForIdle()
                    val reason = onNodeWithTag(TALENT_INSPECTOR_REASON_TAG, useUnmergedTree = true)
                    reason.assertIsDisplayed()
                    val tierText = copy.textOf(InspectorText.SpendMorePoints(10))
                    val tier = onNodeWithText(tierText, useUnmergedTree = true)
                    tier.assertIsDisplayed()
                    for (text in listOf(reason, tier)) {
                        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertFalse(layouts.single().hasVisualOverflow, "$language $width $fontScale: ${layouts.single().layoutInput.text.text}; size=${layouts.single().size}; width=${layouts.single().didOverflowWidth}; height=${layouts.single().didOverflowHeight}")
                        val bounds = text.fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 760)
                    }
                    assertEquals(zoom, camera.viewport!!.scale, "inspecting never resets zoom")
                    assertEquals(emptyList(), changes, "ordinary viewing never changes saved builds")
                    if (fontScale == 2f && width == 320) evidence("compact-${language.name}")
                }
            }
        }
    }

    @Test
    fun requirementDetailsAndShowRemainReachableOnSmallPortraitAndLandscapeAtLargeText() {
        val state = localizedProductState("WARRIOR")
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.RUSSIAN)) {
            uiLanguage.language = language
            val copy = if (language == AppLanguage.ENGLISH) EditorCopy.English else EditorCopy.Russian
            val source = if (language == AppLanguage.ENGLISH) WarriorText.REND_EN else WarriorText.REND_RU
            val target = if (language == AppLanguage.ENGLISH) WarriorText.DEEP_WOUNDS_EN else WarriorText.DEEP_WOUNDS_RU
            for ((width, height) in listOf(320 to 568, 760 to 360, 1024 to 768)) {
                runDesktopComposeUiTest(width = width, height = height) {
                    val changes = mutableListOf<BuildState>()
                    setContent {
                        TalentForgeTheme {
                            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalAppLanguage provides language) {
                                Box(Modifier.size(width.dp, height.dp)) {
                                    TalentTreeScreen(state, onBuildStateChanged = { changes += it })
                                }
                            }
                        }
                    }
                    onNodeWithContentDescription(target).performSemanticsAction(SemanticsActions.OnClick)
                    waitForIdle()
                    if (width < height) {
                        onNodeWithContentDescription(copy.expand).performClick()
                        waitForIdle()
                    }
                    val label = if (language == AppLanguage.ENGLISH) "Counted points: 0/10" else "Засчитано очков: 0/10"
                    onNodeWithText(label, substring = true, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    evidence("detail-${language.name}-${width}x$height")
                    onNode(SemanticsMatcher("one-hop Show") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == copy.showAction(source)
                    }).assertIsDisplayed().performClick()
                    waitForIdle()
                    onNodeWithContentDescription(source).assertIsSelected()
                    assertEquals(emptyList(), changes)
                }
            }
        }
    }

    private fun ComposeUiTest.evidence(name: String) {
        System.getenv("TALENTFORGE_REQUIREMENTS_SCREENSHOTS")?.let { dir ->
            val output = File(dir).apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "$name.png"))
        }
    }

}
