package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_favorites_filter
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_filter_all
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_filter_description
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_search_clear
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_search_hint
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_search_label
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.ui.library.classNameResourceFor
import io.github.alexeykravtsov95.talentforge.ui.library.libraryClassTokens
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import org.jetbrains.compose.resources.stringResource

/** View state only. Controls share the shell's surfaces, borders, type and 48 dp targets. */
@Composable
internal fun BuildLibraryFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedClass: ClassToken?,
    onClassChange: (ClassToken?) -> Unit,
    favoritesOnly: Boolean,
    onFavoritesChange: (Boolean) -> Unit,
) {
    val colors = talentForgeColors()
    val shape = RoundedCornerShape(TalentForgeRadius.medium)
    val searchLabel = stringResource(Res.string.builds_search_label)
    val clearLabel = stringResource(Res.string.builds_search_clear)
    val focus = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
        BasicText(searchLabel, style = TalentForgeTypography.meta.copy(color = colors.textSecondary))
        Row(
            Modifier.fillMaxWidth()
                .background(colors.surfaceInteractive, shape)
                .border(1.dp, if (searchFocused) colors.focus else colors.borderStrong, shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.weight(1f)
                    .semantics { contentDescription = searchLabel }
                    .onFocusChanged { searchFocused = it.isFocused }
                    .heightIn(min = 48.dp),
                decorationBox = { field ->
                    Box(Modifier.padding(TalentForgeSpacing.rowGap), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) BasicText(
                            stringResource(Res.string.builds_search_hint),
                            style = TalentForgeTypography.bodySmall.copy(color = colors.textMuted),
                            maxLines = 1,
                        )
                        field()
                    }
                },
            )
            if (query.isNotEmpty()) Box(
                Modifier.size(48.dp)
                    .semantics { contentDescription = clearLabel }
                    .clickable(role = Role.Button, onClickLabel = clearLabel) { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("×", style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary))
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(colors.surfaceInteractive, shape)
                .border(1.dp, if (favoritesOnly) colors.focus else colors.borderStrong, shape)
                .toggleable(value = favoritesOnly, role = Role.Checkbox, onValueChange = onFavoritesChange)
                .padding(TalentForgeSpacing.rowGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        ) {
            BasicText(if (favoritesOnly) "★" else "☆", style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                modifier = Modifier.clearAndSetSemantics { })
            BasicText(stringResource(Res.string.builds_favorites_filter),
                style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary), modifier = Modifier.weight(1f))
        }
        val allClasses = stringResource(Res.string.builds_filter_all)
        val selectedName = selectedClass?.let { classNameResourceFor(it)?.let { resource -> stringResource(resource) } }
            ?: allClasses
        val description = stringResource(Res.string.builds_filter_description, selectedName)
        var expanded by remember { mutableStateOf(false) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val menuWidth = maxWidth
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(colors.surfaceInteractive, shape)
                    .border(1.dp, colors.borderStrong, shape)
                    .semantics { contentDescription = description }
                    .clickable(role = Role.DropdownList, onClickLabel = description) {
                        focus.clearFocus()
                        expanded = !expanded
                    }
                    .padding(horizontal = TalentForgeSpacing.rowGap, vertical = TalentForgeSpacing.inline),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
            ) {
                BasicText(
                    selectedName,
                    style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary),
                    modifier = Modifier.weight(1f),
                )
                BasicText("▾", style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary))
            }
            if (expanded) Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(menuWidth).heightIn(max = 320.dp)
                        .background(colors.surfaceElevated, shape)
                        .border(1.dp, colors.borderStrong, shape)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    for (token in listOf<ClassToken?>(null) + libraryClassTokens) {
                        val label = token?.let { classNameResourceFor(it)?.let { resource -> stringResource(resource) } }
                            ?: allClasses
                        val selected = token == selectedClass
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    onClassChange(token)
                                    expanded = false
                                }
                                .padding(TalentForgeSpacing.rowGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                        ) {
                            BasicText(label, style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary), modifier = Modifier.weight(1f))
                            if (selected) BasicText("✓", style = TalentForgeTypography.bodySmall.copy(color = colors.textPrimary))
                        }
                    }
                }
            }
        }
    }
}
