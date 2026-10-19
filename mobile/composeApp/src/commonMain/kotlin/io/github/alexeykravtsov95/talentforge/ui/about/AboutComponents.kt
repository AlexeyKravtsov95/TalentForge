package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.library.SectionLabel
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.BackChevronButton

/**
 * The frame the About and Licenses screens share: the visible Back chevron and
 * the screen title as a heading, above one scrolling column with the screen
 * gutter. No persistent navigation (UX/UI spec 02 §1).
 */
@Composable
internal fun AboutScaffold(
    title: String,
    onBack: () -> Unit,
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
                .verticalScroll(rememberScrollState())
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
