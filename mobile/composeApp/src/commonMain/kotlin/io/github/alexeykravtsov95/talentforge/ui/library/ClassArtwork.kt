package io.github.alexeykravtsov95.talentforge.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource

/**
 * The B+ class-artwork tile slots, exactly as the supplied M5.UI.1 asset
 * integration specification freezes them. The first two are the class-picker
 * slots of the M5.UI.2 Class Selection screen; the last two are the slots
 * Home and All Builds use.
 */
enum class ClassArtworkSlot(val size: Dp, val cornerRadius: Dp) {
    /** Class Selection · standard picker tile: 56 dp, 10 dp corner radius. */
    Picker(56.dp, 10.dp),

    /** Class Selection · compact picker tile (narrow viewports): 46 dp, 10 dp corner radius. */
    PickerCompact(46.dp, 10.dp),

    /** Home · Continue card: 44 dp tile, 9 dp corner radius. */
    Continue(44.dp, 9.dp),

    /** Home · Recent row and All Builds row: 36 dp tile, 8 dp corner radius. */
    Row(36.dp, 8.dp),
}

/**
 * ClassToken → the bundled artwork drawable, in ONE shared lookup (the token
 * string matches the resource filename suffix). The nine supplied masters
 * live in `composeResources/drawable/` at their native 118×118 px, imported
 * byte-exact; there is no network fetch and no Blizzard-hosted URL anywhere.
 * An unknown class id returns `null` — the caller renders the monogram
 * fallback rather than guessing art.
 */
fun classArtworkResourceFor(classToken: ClassToken): DrawableResource? = when (classToken.value) {
    "WARRIOR" -> Res.drawable.class_warrior
    "PALADIN" -> Res.drawable.class_paladin
    "HUNTER" -> Res.drawable.class_hunter
    "ROGUE" -> Res.drawable.class_rogue
    "PRIEST" -> Res.drawable.class_priest
    "SHAMAN" -> Res.drawable.class_shaman
    "MAGE" -> Res.drawable.class_mage
    "WARLOCK" -> Res.drawable.class_warlock
    "DRUID" -> Res.drawable.class_druid
    else -> null
}

/**
 * The shared class artwork of the B+ shell screens (Home, All Builds and the
 * Class Selection picker). Renders the supplied
 * painting unmodified — no tint, no color filter, the art supplies its own
 * colour — cropped to the square slot with high-quality filtering, clipped
 * to the slot's rounded rect with the spec's 1 dp inner hairline on top.
 *
 * The image is decorative by contract: it carries no content description,
 * and the accessible name of the class comes from the text of the component
 * that hosts this mark. A class with no bundled artwork (unknown token)
 * falls back to the existing [ClassMonogramMark] at the same slot size.
 */
@Composable
fun ClassArtworkMark(
    classToken: ClassToken,
    slot: ClassArtworkSlot,
    monogram: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val resource = classArtworkResourceFor(classToken)
    if (resource == null) {
        ClassMonogramMark(monogram = monogram, accent = accent, size = slot.size, modifier = modifier)
        return
    }
    val colors = talentForgeColors()
    val shape = RoundedCornerShape(slot.cornerRadius)
    Box(
        modifier
            .size(slot.size)
            // the spec's reserved ground under the art (the masters are
            // opaque, but the slot is grounded before anything draws)
            .background(colors.classArtworkGround, shape)
            .clip(shape)
            .drawWithContent {
                drawContent()
                // the spec's 1 dp inner hairline, drawn ON TOP of the art
                val stroke = 1.dp.toPx()
                val inset = stroke / 2f
                drawRoundRect(
                    color = colors.classArtworkHairline,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(slot.cornerRadius.toPx() - inset),
                    style = Stroke(width = stroke),
                )
            },
    ) {
        Image(
            bitmap = imageResource(resource),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
