package io.github.alexeykravtsov95.talentforge.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_druid
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_hunter
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_mage
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_paladin
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_priest
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_rogue
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_shaman
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_warlock
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_warrior
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule

/**
 * The M5.UI.1 class-artwork contract: the ClassToken → bundled drawable
 * lookup covers exactly the nine supplied classes (each its own art), never
 * guesses art for an unknown token, and the component falls back to the
 * monogram at the artwork slot size when no art exists.
 */
class ClassArtworkTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nineBundledClassesMapEachToTheirOwnArtwork() {
        val expected = mapOf(
            "WARRIOR" to Res.drawable.class_warrior,
            "PALADIN" to Res.drawable.class_paladin,
            "HUNTER" to Res.drawable.class_hunter,
            "ROGUE" to Res.drawable.class_rogue,
            "PRIEST" to Res.drawable.class_priest,
            "SHAMAN" to Res.drawable.class_shaman,
            "MAGE" to Res.drawable.class_mage,
            "WARLOCK" to Res.drawable.class_warlock,
            "DRUID" to Res.drawable.class_druid,
        )
        for ((token, resource) in expected) {
            assertEquals(resource, classArtworkResourceFor(ClassToken(token)), "art of $token")
        }
        // nine distinct tokens → nine distinct drawables
        assertEquals(expected.values.size, expected.values.toSet().size)
    }

    @Test
    fun unknownClassTokensHaveNoArtworkAndFallBackToTheMonogram() {
        // (an empty token cannot even be constructed — ClassToken is fail-closed)
        assertNull(classArtworkResourceFor(ClassToken("DEATHKNIGHT")))
        assertNull(classArtworkResourceFor(ClassToken("MONK")))

        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier) {
                        ClassArtworkMark(
                            classToken = ClassToken("DEATHKNIGHT"),
                            slot = ClassArtworkSlot.Row,
                            monogram = "DK",
                            accent = io.github.alexeykravtsov95.talentforge.ui.theme.UnfrozenClassAccent.color,
                        )
                    }
                }
            }
        }

        // no bundled art → the existing monogram mark renders in its place
        composeRule.onNodeWithText("DK").assertExists()
    }

    @Test
    fun aKnownClassRendersTheArtworkAndNoMonogramGlyph() {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier) {
                        ClassArtworkMark(
                            classToken = ClassToken("WARRIOR"),
                            slot = ClassArtworkSlot.Continue,
                            monogram = "WA",
                            accent = io.github.alexeykravtsov95.talentforge.ui.theme.WarriorClassAccent.color,
                        )
                    }
                }
            }
        }

        // the artwork is decorative (no text semantics of its own); the
        // monogram must NOT render alongside it
        composeRule.onAllNodesWithText("WA").fetchSemanticsNodes().let {
            kotlin.test.assertTrue(it.isEmpty(), "artwork and monogram must never render together")
        }
    }
}
