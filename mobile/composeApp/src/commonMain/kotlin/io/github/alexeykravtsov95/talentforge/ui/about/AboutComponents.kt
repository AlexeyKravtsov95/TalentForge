package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.library.SectionLabel
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.BackChevronButton

/**
 * The frame the About and Licenses screens share: the visible Back chevron and
 * the screen title as a heading, above one scrolling column with the screen
 * gutter. No persistent navigation (UX/UI spec 02 §1). A page that is left and
 * returned to passes its own [scrollState] to come back where it was.
 */
@Composable
internal fun AboutScaffold(
    title: String,
    onBack: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = talentForgeColors()
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevronButton(onClick = onBack)
            BasicText(
                text = title,
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = TalentForgeSpacing.screenX)
                    .semantics { heading() },
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = TalentForgeSpacing.screenX),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
        ) {
            Spacer(Modifier.height(TalentForgeSpacing.hairline))
            content()
            Spacer(Modifier.height(TalentForgeSpacing.group))
        }
    }
}

/** A 48 dp text action that shows or hides a block of detail. */
@Composable
internal fun AboutToggle(expanded: Boolean, show: String, hide: String, onToggle: () -> Unit) {
    val label = if (expanded) hide else show
    BasicText(
        text = label,
        style = ToggleStyle.copy(color = talentForgeColors().textPrimary),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
            .padding(vertical = 14.dp),
    )
}

/**
 * A full-width 48 dp row that opens another page: [title], an optional
 * [detail] below it and a forward chevron, on an elevated card.
 */
@Composable
internal fun AboutNavigationRow(title: String, detail: String?, onClickLabel: String, onClick: () -> Unit) {
    val colors = talentForgeColors()
    val shape = RoundedCornerShape(TalentForgeRadius.medium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.borderDefault, shape)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = TalentForgeSpacing.rowGap, vertical = TalentForgeSpacing.inline),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline),
        ) {
            BasicText(title, style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary))
            if (detail != null) AboutMeta(detail)
        }
        ForwardChevron(colors.textSecondary)
    }
}

/** A › chevron that says "opens another page": two strokes meeting in a right point (the mirror of the Back chevron). */
@Composable
internal fun ForwardChevron(color: Color) {
    Box(
        Modifier
            .size(width = 10.dp, height = 16.dp)
            .drawBehind { drawForwardChevron(color) },
    )
}

private fun DrawScope.drawForwardChevron(color: Color) {
    val stroke = 2.dp.toPx()
    val point = Offset(size.width * 0.8f, size.height * 0.5f)
    drawLine(color, start = Offset(size.width * 0.2f, size.height * 0.1f), end = point, strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, start = Offset(size.width * 0.2f, size.height * 0.9f), end = point, strokeWidth = stroke, cap = StrokeCap.Round)
}

@Composable
internal fun AboutSection(text: String) {
    Spacer(Modifier.height(TalentForgeSpacing.hairline))
    SectionLabel(text, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun AboutBody(text: String) {
    BasicText(text, style = TalentForgeTypography.body.copy(color = talentForgeColors().textSecondary))
}

@Composable
internal fun AboutMeta(text: String) {
    BasicText(text, style = TalentForgeTypography.meta.copy(color = talentForgeColors().textMuted))
}

private val ToggleStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)
