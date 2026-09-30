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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/**
 * Small shared controls of the M5.1 shell screens (Home, New Build, Builds).
 * The primary button is neutral light (UX/UI spec G: "The primary button is
 * neutral light"), the secondary sits on the interactive surface; both keep
 * ≥ 48 dp targets.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(colors.focus, RoundedCornerShape(TalentForgeRadius.medium))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = ActionLabelStyle.copy(color = colors.background),
        )
    }
}

@Composable
fun SecondaryActionButton(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(colors.surfaceInteractive, RoundedCornerShape(TalentForgeRadius.medium))
            .border(1.dp, colors.borderStrong, RoundedCornerShape(TalentForgeRadius.medium))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = ActionLabelStyle.copy(color = colors.textPrimary),
        )
    }
}

/**
 * A class "mark": the two-letter monogram derived from the real class display
 * name (first letters of a two-word name, else the first two letters —
 * Warrior → WA, Death Knight → DK), shown in the class accent. The accent is
 * an explicit input (Warrior: spec-frozen; others: the documented neutral
 * fallback), never a Warrior-coloured theme lookup.
 */
@Composable
fun ClassMonogramMark(
    monogram: String,
    accent: Color,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val colors = talentForgeColors()
    val glyphColor = if (dimmed) colors.textMuted else accent
    Box(
        modifier = modifier
            .size(44.dp)
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

/** A compact inline notice with an optional action (used for recoverable errors). */
@Composable
fun InlineNotice(
    text: String,
    actionText: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    Row(
        modifier = modifier
            .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.medium))
            .border(1.dp, colors.warning.copy(alpha = 0.55f), RoundedCornerShape(TalentForgeRadius.medium))
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
                    style = NoticeActionStyle.copy(color = colors.warning),
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
