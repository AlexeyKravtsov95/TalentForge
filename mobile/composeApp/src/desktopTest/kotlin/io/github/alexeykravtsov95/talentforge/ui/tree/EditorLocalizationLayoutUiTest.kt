package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M5.REL.2b visual validation as assertions: the localized editor at the
 * review matrix — 393 × 852 and 320 × 568 portrait, 915 × 412 landscape, at
 * 100 % and 200 % text — with the compact (portrait) or side (landscape)
 * inspector of an available, a prerequisite-locked, a tier-locked and a
 * partially ranked talent, the expanded sheet (portrait), the build-actions
 * sheet and the reset notice, in English and Russian. In every state: no
 * text clipped or overflowing the space it was given, no line broken
 * mid-word, nothing past the window edge, and the rank actions and Show keep
 * their ≥ 44 dp touch targets.
 */
@OptIn(ExperimentalTestApi::class)
class EditorLocalizationLayoutUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val warrior by lazy { productState("WARRIOR") }
    private val scenarios by lazy { compactScenarios(warrior) }

    @Test
    fun theLocalizedEditorFitsEveryReviewSizeAndTextScale() {
        val sizes = listOf(393 to 852, 320 to 568, 915 to 412)
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            for ((width, height) in sizes) {
                val landscape = width > height
                for (fontScale in listOf(1f, 2f)) {
                    val at = "$language ${width}x$height ${(fontScale * 100).toInt()}%"
                    for (label in listOf("available", "prerequisite", "tier", "partial")) {
                        val scenario = scenarios.getValue(label)
                        onEditor(language, width, height, fontScale, warrior.copy(buildState = scenario.build)) {
                            onNodeWithContentDescription(scenario.nodeName).performClick()
                            waitForIdle()
                            assertLayout("$at $label", copy, scenario.nodeName)
                            if (!landscape && label == "partial") {
                                onNodeWithContentDescription(copy.expand).performClick()
                                waitForIdle()
                                assertLayout("$at $label sheet", copy, scenario.nodeName)
                            }
                        }
                    }
                    // the build-actions sheet and the reset notice over the canvas
                    val partial = scenarios.getValue("partial")
                    onEditor(language, width, height, fontScale, warrior.copy(buildState = partial.build)) {
                        onNodeWithContentDescription(copy.buildActionsOpen).performClick()
                        waitForIdle()
                        assertLayout("$at actions sheet", copy, null)
                        onNodeWithText(copy.resetTalents).performClick()
                        waitForIdle()
                        assertLayout("$at reset notice", copy, null)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ harness

    private fun onEditor(
        language: AppLanguage,
        width: Int,
        height: Int,
        fontScale: Float,
        state: TalentTreeUiState.Ready,
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, fontScale),
                        LocalReducedMotion provides true,
                        LocalAppLanguage provides language,
                    ) {
                        Box(Modifier.size(width.dp, height.dp)) { TalentTreeScreen(state = state, onBack = {}) }
                    }
                }
            }
            waitForIdle()
            block()
        }
    }

    private fun ComposeUiTest.allNodes(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    private fun ComposeUiTest.assertLayout(where: String, copy: EditorCopy, talent: String?) {
        assertTextsFitAndWrapAtWords(where)
        if (talent != null) assertTouchTargets(where, copy, talent)
    }

    /**
     * Every text with words: a wrapped text breaks only between words, a
     * text that is not deliberately ellipsized never overflows the space its
     * parent gave it, and no text extends past the window's width. Texts
     * without letters (the canvas rank chips, glyphs) belong to the canvas
     * and may legitimately sit outside the viewport when the camera moves.
     */
    private fun ComposeUiTest.assertTextsFitAndWrapAtWords(where: String) {
        val rootWidth = onRoot().fetchSemanticsNode().size.width
        for (node in allNodes()) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val results = mutableListOf<TextLayoutResult>()
            action.action?.invoke(results)
            val layout = results.firstOrNull() ?: continue
            val text = layout.layoutInput.text.text
            if (text.none { it.isLetter() }) continue
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = false)
                val previous = text.getOrNull(end - 1)
                assertTrue(
                    previous == null || previous.isWhitespace() || previous in WORD_BREAK_PUNCTUATION,
                    "$where: '$text' breaks mid-word after '${text.substring(0, end)}'",
                )
            }
            if (layout.layoutInput.overflow != TextOverflow.Ellipsis && layout.lineCount > 0) {
                val constraints = layout.layoutInput.constraints
                val availableWidth = maxOf(layout.size.width, constraints.maxWidth)
                val availableHeight = maxOf(layout.size.height, constraints.maxHeight)
                val contentRight = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
                val contentBottom = layout.getLineBottom(layout.lineCount - 1)
                assertTrue(
                    contentRight <= availableWidth + OVERFLOW_TOLERANCE_PX &&
                        contentBottom <= availableHeight + OVERFLOW_TOLERANCE_PX,
                    "$where: '$text' overflows the space it was given " +
                        "($contentRight x $contentBottom in ${layout.size}, $constraints)",
                )
            }
            val bounds = node.boundsInRoot
            assertTrue(bounds.left >= -0.5f && bounds.right <= rootWidth + 0.5f, "$where: '$text' leaves the window")
        }
    }

    /** The selected talent's rank actions and Show keep ≥ 44 dp targets (B3 08) in every language. */
    private fun ComposeUiTest.assertTouchTargets(where: String, copy: EditorCopy, talent: String) {
        val labels = setOf(copy.addRank(talent), copy.removeRank(talent), copy.maxOut(talent))
        val showPrefix = copy.showAction("")
        for (node in allNodes()) {
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val clickLabel = node.config.getOrNull(SemanticsActions.OnClick)?.label
            val isAction = descriptions.any { it in labels } || clickLabel?.startsWith(showPrefix) == true
            if (!isAction) continue
            val bounds = node.boundsInRoot
            assertTrue(
                bounds.height >= MIN_TARGET_DP - 0.5f && bounds.width >= MIN_TARGET_DP - 0.5f,
                "$where: '${descriptions.ifEmpty { listOfNotNull(clickLabel) }}' target is $bounds",
            )
        }
    }

    private companion object {
        /** Sub-pixel rounding between the paragraph metrics and the text's integer box. */
        const val OVERFLOW_TOLERANCE_PX = 1.5f

        /** Where a line may legitimately end besides whitespace. */
        const val WORD_BREAK_PUNCTUATION = "-–—/·:«»"

        /** B3 08: "All buttons at least 44 × 44 dp" (density 1: px = dp). */
        const val MIN_TARGET_DP = 44f
    }
}
