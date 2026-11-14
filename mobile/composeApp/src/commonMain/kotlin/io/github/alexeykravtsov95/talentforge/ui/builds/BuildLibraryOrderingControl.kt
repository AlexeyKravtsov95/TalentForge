package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_group_by_class
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_group_off
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_group_on
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_order_description
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_order_label
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_sort_class
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_sort_last_edited
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_sort_name
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import org.jetbrains.compose.resources.stringResource

/** One compact control rather than another permanently visible filter row/menu. */
@Composable
internal fun BuildLibraryOrderingControl(
    sortMode: BuildLibrarySortMode,
    onSortModeChange: (BuildLibrarySortMode) -> Unit,
    groupByClass: Boolean,
    onGroupByClassChange: (Boolean) -> Unit,
) {
    val colors = talentForgeColors()
    val shape = RoundedCornerShape(TalentForgeRadius.medium)
    val sortName = when (sortMode) {
        BuildLibrarySortMode.LAST_EDITED -> stringResource(Res.string.builds_sort_last_edited)
        BuildLibrarySortMode.NAME -> stringResource(Res.string.builds_sort_name)
        BuildLibrarySortMode.CLASS -> stringResource(Res.string.builds_sort_class)
    }
    val groupStatus = stringResource(if (groupByClass) Res.string.builds_group_on else Res.string.builds_group_off)
    val description = stringResource(Res.string.builds_order_description, sortName, groupStatus)
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(colors.surfaceInteractive, shape)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { contentDescription = description }
                .clickable(role = Role.DropdownList, onClickLabel = description) { expanded = !expanded }
                .padding(horizontal = TalentForgeSpacing.rowGap, vertical = TalentForgeSpacing.inline),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        ) {
            BasicText(
                stringResource(Res.string.builds_order_label, sortName),
                style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText("▾", style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary))
        }
        if (expanded) Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                Modifier.width(menuWidth)
                    .heightIn(max = 360.dp)
                    .background(colors.surfaceElevated, shape)
                    .border(1.dp, colors.borderStrong, shape)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(Modifier.selectableGroup()) {
                    for (mode in BuildLibrarySortMode.entries) {
                        val label = when (mode) {
                            BuildLibrarySortMode.LAST_EDITED -> stringResource(Res.string.builds_sort_last_edited)
                            BuildLibrarySortMode.NAME -> stringResource(Res.string.builds_sort_name)
                            BuildLibrarySortMode.CLASS -> stringResource(Res.string.builds_sort_class)
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = mode == sortMode, role = Role.RadioButton) {
                                    onSortModeChange(mode)
                                    expanded = false
                                }
                                .padding(TalentForgeSpacing.rowGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                        ) {
                            BasicText(
                                label,
                                style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary),
                                modifier = Modifier.weight(1f),
                            )
                            if (mode == sortMode) BasicText("✓", style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = groupByClass, role = Role.Checkbox) {
                            onGroupByClassChange(it)
                            expanded = false
                        }
                        .padding(TalentForgeSpacing.rowGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                ) {
                    BasicText(
                        stringResource(Res.string.builds_group_by_class),
                        style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary),
                        modifier = Modifier.weight(1f),
                    )
                    BasicText(if (groupByClass) "✓" else "□", style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary))
                }
            }
        }
    }
}
