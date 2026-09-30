package io.github.alexeykravtsov95.talentforge.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Spacing scale frozen by TalentForge_UX_UI_Spec_v1.pdf, section G
 * (4 dp base).
 */
object TalentForgeSpacing {
    val hairline: Dp = 4.dp
    val inline: Dp = 8.dp
    val rowGap: Dp = 12.dp
    val screenX: Dp = 16.dp
    val sheetX: Dp = 20.dp
    val group: Dp = 24.dp
    val section: Dp = 32.dp
    val tabletX: Dp = 48.dp
}

/**
 * Radius scale frozen by TalentForge_UX_UI_Spec_v1.pdf, section G.
 */
object TalentForgeRadius {
    val small: Dp = 6.dp
    val medium: Dp = 12.dp
    val large: Dp = 16.dp
    val sheet: Dp = 20.dp
}

/**
 * Motion durations frozen by TalentForge_UX_UI_Spec_v1.pdf, section G.
 * Durations are stored as milliseconds; ranges are kept as pairs exactly as
 * the spec states them.
 */
object TalentForgeMotion {
    const val STATE_RESPONSE_MIN_MS: Int = 100
    const val STATE_RESPONSE_MAX_MS: Int = 160

    const val TALENT_PURCHASE_GLOW_MS: Int = 260
    const val TALENT_PURCHASE_RANK_POP_MS: Int = 180

    const val SMALL_LAYOUT_MIN_MS: Int = 180
    const val SMALL_LAYOUT_MAX_MS: Int = 240

    const val SHEET_IN_MS: Int = 280
    const val SHEET_OUT_MS: Int = 220

    const val SHOW_PAN_MS: Int = 300
    const val SHOW_PULSE_MS: Int = 900

    const val REDUCED_MOTION_MAX_MS: Int = 80
}

/**
 * A per-class accent colour. The type and the Warrior instance are fixed by the
 * UX spec (accentClass token); the remaining class accents will be frozen when
 * the production UI milestone lands, not invented now.
 */
data class ClassAccent(val color: androidx.compose.ui.graphics.Color)

val WarriorClassAccent: ClassAccent = ClassAccent(TalentForgeColors.accentClassWarrior)

/**
 * The documented **neutral fallback** for classes whose accent is not frozen by
 * any committed design source or dataset field: the `textSecondary` neutral
 * grey (oklch(0.74 0.01 260) — chroma 0.01 is effectively hueless, so it claims
 * no class hue and collides with no semantic token: danger/warning stay
 * reserved, focus stays the neutral white selection ring).
 *
 * M5.1 renders every non-Warrior class with this accent. It is a presentation
 * fact, deliberately replaceable when the spec freezes real per-class accents;
 * no class colour is ever invented from memory or external sources, and the
 * dataset schema stays colour-free.
 */
val UnfrozenClassAccent: ClassAccent = ClassAccent(TalentForgeColors.textSecondary)

/**
 * The one class-accent policy: Warrior uses its spec-frozen accent; every
 * other class uses [UnfrozenClassAccent] until a committed source freezes its
 * real accent. Callers must never read `accentClassWarrior` directly — that
 * token is semantically *the Warrior accent*, not a generic accent default.
 */
fun classAccentFor(classToken: io.github.alexeykravtsov95.talentforge.data.ClassToken): ClassAccent =
    if (classToken.value == WARRIOR_TOKEN) WarriorClassAccent else UnfrozenClassAccent

private const val WARRIOR_TOKEN = "WARRIOR"

/**
 * OKLCH → sRGB conversion (Björn Ottosson's OKLab matrices), shared by the
 * token-verification test. Kept next to the tokens so drift between spec and
 * constants is caught mechanically.
 */
object OklchConversions {

    /** Converts OKLCH to linear-light-normalized sRGB in [0, 1] (unclamped). */
    fun oklchToLinearSrgb(lightness: Double, chroma: Double, hueDegrees: Double): Triple<Double, Double, Double> {
        val hue = hueDegrees * PI / 180.0
        val a = chroma * cos(hue)
        val b = chroma * sin(hue)

        val lPrime = lightness + 0.3963377774 * a + 0.2158037573 * b
        val mPrime = lightness - 0.1055613458 * a - 0.0638541728 * b
        val sPrime = lightness - 0.0894841775 * a - 1.2914855480 * b

        val l = lPrime * lPrime * lPrime
        val m = mPrime * mPrime * mPrime
        val s = sPrime * sPrime * sPrime

        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return Triple(r, g, bl)
    }

    /** Converts OKLCH to an sRGB hex constant like `0xFF0B0D11`, clamped to gamut. */
    fun oklchToArgbHex(lightness: Double, chroma: Double, hueDegrees: Double): Long {
        val (r, g, b) = oklchToLinearSrgb(lightness, chroma, hueDegrees)
        fun encode(channel: Double): Int {
            val linear = channel.coerceIn(0.0, 1.0)
            val srgb = if (linear <= 0.0031308) {
                12.92 * linear
            } else {
                1.055 * linear.pow(1.0 / 2.4) - 0.055
            }
            return kotlin.math.round(srgb.coerceIn(0.0, 1.0) * 255.0).toInt()
        }
        return 0xFF000000L or (encode(r).toLong() shl 16) or
            (encode(g).toLong() shl 8) or encode(b).toLong()
    }
}
