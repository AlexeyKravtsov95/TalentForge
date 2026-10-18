package io.github.alexeykravtsov95.talentforge.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The primary and secondary action labels wrap only between words, whatever
 * the platform font's real width. A full-width button on a 320 dp phone
 * leaves its label 240 dp; at 200 % "Импортировать" is wider than that in
 * Linux CI's font (it broke as "Импортирова|ть билд" there, M5.REL.2a matrix)
 * but not in macOS's. At 280 dp even macOS's font needs the label shrunk to
 * about 12 sp — more than a wider font needs at 320 dp — so the fit is proven
 * by measurement here, not by one font being narrow enough. Where every word
 * fits, the label renders at its full 15 sp.
 *
 * The rendered size is read from the glyphs: a label's layout reports its
 * declared style even when auto-size laid it out smaller.
 */
@OptIn(ExperimentalTestApi::class)
class ActionButtonLabelTest {

    private enum class Kind { Primary, Secondary }

    /** The label's layout, and its first glyph's box when laid out at the full 15 sp with the same font. */
    private class Rendered(val layout: TextLayoutResult, val fullSizeGlyph: Rect) {
        val atFullSize: Boolean
            get() = layout.getBoundingBox(0).let { abs(it.width - fullSizeGlyph.width) < 0.01f && abs(it.height - fullSizeGlyph.height) < 0.01f }
    }

    private fun render(kind: Kind, label: String, widthDp: Int, fontScale: Float): Rendered {
        var rendered: Rendered? = null
        var fullSizeGlyph = Rect.Zero
        runDesktopComposeUiTest(width = widthDp, height = 400) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        fullSizeGlyph = rememberTextMeasurer().measure(label, FullSizeLabel, softWrap = false).getBoundingBox(0)
                        Box(Modifier.size(widthDp.dp, 400.dp)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = TalentForgeSpacing.screenX)) {
                                when (kind) {
                                    Kind.Primary -> PrimaryActionButton(label, label, {}, Modifier.fillMaxWidth())
                                    Kind.Secondary -> SecondaryActionButton(label, label, {}, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
            val layouts = mutableListOf<TextLayoutResult>()
            onAllNodesWithText(label, useUnmergedTree = true).onFirst().fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            rendered = Rendered(layouts.single(), fullSizeGlyph)
        }
        return rendered!!
    }

    @Test
    fun aLabelNeverBreaksInsideAWord() {
        for (kind in Kind.entries) {
            for (label in listOf("Импортировать билд", "Import build", "Скопировать код")) {
                for (widthDp in listOf(320, 300, 280)) {
                    val layout = render(kind, label, widthDp, fontScale = 2f).layout
                    val where = "$kind '$label' at $widthDp dp @200 %"
                    assertEquals(label, layout.layoutInput.text.text, "$where: the label is shown whole")
                    for (line in 0 until layout.lineCount - 1) {
                        val end = layout.getLineEnd(line, visibleEnd = false)
                        assertTrue(label[end - 1].isWhitespace(), "$where breaks mid-word after '${label.substring(0, end)}'")
                    }
                    assertTrue(!layout.didOverflowWidth, "$where: a word overflows the label")
                }
            }
        }
    }

    @Test
    fun theLabelShrinksOnlyWhenAWordDoesNotFit() {
        for (kind in Kind.entries) {
            val roomy = render(kind, "Импортировать билд", widthDp = 393, fontScale = 1f)
            assertEquals(1, roomy.layout.lineCount)
            assertTrue(roomy.atFullSize, "$kind: the label shrank although it fits on one line")

            // each word fits at 200 % on 320 dp: the label may wrap between words but keeps its size
            val wrapped = render(kind, "Import build", widthDp = 320, fontScale = 2f)
            assertTrue(wrapped.atFullSize, "$kind: 'Import build' shrank although each word fits")

            // "Импортировать" does not fit 200 dp at 200 % with the full size, so it is laid out smaller
            val squeezed = render(kind, "Импортировать билд", widthDp = 280, fontScale = 2f)
            assertTrue(!squeezed.atFullSize, "$kind: the label kept its full size although a word does not fit")
        }
    }

    private companion object {
        /** The action label style (LibraryComponents' ActionLabelStyle): 15 / 20 · 600. */
        val FullSizeLabel = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
