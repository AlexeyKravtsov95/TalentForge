package io.github.alexeykravtsov95.talentforge.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/**
 * Small shared controls of the shell screens (Home, New Build, Builds).
 * Since M5.UI.1 (B+) the primary button is the amber accent
 * ([TalentForgeColors.primaryAction], frozen by the supplied class-artwork
 * integration spec — the M5.1 "neutral light" primary is retired with the
 * B+ Home it belonged to); the secondary stays neutral on the interactive
 * surface; both keep ≥ 48 dp targets. `enabled = false` (M5.SHARE.2 busy
 * states) keeps the target and the label but greys both and ignores taps. Amber marks PRIMARY actions only —
 * the editor keeps its neutral token set untouched.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = talentForgeColors()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(
                if (enabled) colors.primaryAction else colors.surfaceInteractive,
                RoundedCornerShape(TalentForgeRadius.medium),
            )
            .clickable(role = Role.Button, onClickLabel = onClickLabel, enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = ActionLabelStyle.copy(color = if (enabled) colors.background else colors.disabled),
        )
    }
}

@Composable
fun SecondaryActionButton(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = talentForgeColors()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(colors.surfaceInteractive, RoundedCornerShape(TalentForgeRadius.medium))
            .border(1.dp, colors.borderStrong, RoundedCornerShape(TalentForgeRadius.medium))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = ActionLabelStyle.copy(color = if (enabled) colors.textPrimary else colors.disabled),
        )
    }
}

/**
 * A class "mark": the two-letter monogram derived from the real class display
 * name (first letters of a two-word name, else the first two letters —
 * Warrior → WA, Death Knight → DK), shown in the class accent. The accent is
 * an explicit input (Warrior: spec-frozen; others: the documented neutral
 * fallback), never a Warrior-coloured theme lookup.
 *
 * Since M5.UI.1 this mark is the FALLBACK of [ClassArtworkMark] (a class
 * without bundled artwork); [size] lets it match the artwork slot it stands
 * in for. The B+ Class Selection picker (M5.UI.2) uses it the same way —
 * only for class tokens without bundled artwork.
 */
@Composable
fun ClassMonogramMark(
    monogram: String,
    accent: Color,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    size: Dp = 44.dp,
) {
    val colors = talentForgeColors()
    val glyphColor = if (dimmed) colors.textMuted else accent
    Box(
        modifier = modifier
            .size(size)
            .background(
                if (dimmed) colors.surface else accent.copy(alpha = 0.16f),
                CircleShape,
            )
            .border(1.dp, if (dimmed) colors.borderDefault else accent.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = monogram,
            style = MonogramStyle.copy(color = glyphColor),
        )
    }
}

/** Section label of the shell screens ("CONTINUE", "RECENT", "CLASS"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        style = SectionLabelStyle.copy(color = talentForgeColors().textSecondary),
        modifier = modifier,
    )
}

/** Visual tone of an [InlineNotice]: recoverable-problem (warning) or success (positive). */
enum class InlineNoticeTone { Warning, Positive }

/** A compact inline notice with an optional action (used for recoverable errors and confirmations). */
@Composable
fun InlineNotice(
    text: String,
    actionText: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tone: InlineNoticeTone = InlineNoticeTone.Warning,
) {
    val colors = talentForgeColors()
    val toneColor = when (tone) {
        InlineNoticeTone.Warning -> colors.warning
        InlineNoticeTone.Positive -> colors.positive
    }
    Row(
        modifier = modifier
            .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.medium))
            .border(1.dp, toneColor.copy(alpha = 0.55f), RoundedCornerShape(TalentForgeRadius.medium))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = text,
            style = NoticeStyle.copy(color = colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        if (actionText != null && onAction != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClickLabel = actionText, onClick = onAction)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = actionText,
                    style = NoticeActionStyle.copy(color = toneColor),
                )
            }
        }
    }
}

/** Monogram of a real class display name (see [ClassMonogramMark]). */
fun classMonogramOf(displayName: String): String {
    val words = displayName.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val letters = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> "?"
    }
    return letters.uppercase()
}

private val ActionLabelStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

private val MonogramStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

/** UX spec G SectionTitle: 12 / 16 · 500 · caps. */
private val SectionLabelStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
)

private val NoticeStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 13.5.sp,
    lineHeight = 20.sp,
)

private val NoticeActionStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)
