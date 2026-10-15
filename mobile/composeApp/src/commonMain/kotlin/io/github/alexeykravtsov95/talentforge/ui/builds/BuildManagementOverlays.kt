package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_action_delete
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_action_duplicate
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_action_rename
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_action_share
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_delete_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_delete_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_delete_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_dialog_close_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_rename_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_rename_field_description
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_rename_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_sheet_close_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_cancel
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_save
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import org.jetbrains.compose.resources.stringResource

/**
 * The M5.2a build-management overlays of All Builds, built from the same
 * stable primitives as the editor's build-action sheet (no Material
 * component): the row `⋯` bottom sheet (Rename / Duplicate / Share — M5.SHARE.2
 * — / Delete — Delete separated and in the danger colour, the one
 * irreversible action) plus two centered confirmation dialogs.
 *
 * Input rules of every overlay: the SCRIM always consumes touches, so the
 * interface underneath is unreachable while a modal is open — but it only
 * DISMISSES when no operation is in flight; the SURFACE itself also consumes
 * stray taps, so touching the sheet/dialog anywhere outside an explicit
 * action (Rename / Duplicate / Delete / Cancel / Save) never closes it. The
 * caller additionally disables the actions while a repository operation is
 * running, so a retry is always possible and nothing half-completes.
 */
object BuildManagementMetrics {

    /** How long the duplicate success notice stays before auto-dismissing. */
    const val SUCCESS_NOTICE_MS: Int = 4000
}

/**
 * The `⋯` bottom sheet. Rendered by the screen inside a full-size scrim Box;
 * tapping the scrim dismisses without acting (unless an operation runs).
 */
@Composable
fun BuildRowActionsSheet(
    buildTitle: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    Box(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClickLabel = if (busy) null else stringResource(Res.string.builds_sheet_close_action),
            ) { if (!busy) onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(
                    colors.surfaceElevated,
                    RoundedCornerShape(
                        topStart = TalentForgeRadius.sheet,
                        topEnd = TalentForgeRadius.sheet,
                    ),
                )
                .pointerInput(Unit) {
                    // a tap on the surface itself is never a dismiss — only
                    // the explicit actions below act
                    detectTapGestures { }
                }
                .padding(
                    horizontal = TalentForgeSpacing.sheetX,
                    vertical = TalentForgeSpacing.group,
                ),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        ) {
            BasicText(
                text = buildTitle,
                style = OverlayCaptionStyle.copy(color = colors.textMuted),
                maxLines = 1,
            )
            SheetAction(
                text = stringResource(Res.string.builds_action_rename),
                busy = busy,
                color = colors.textPrimary,
                onClick = onRename,
            )
            SheetAction(
                text = stringResource(Res.string.builds_action_duplicate),
                busy = busy,
                color = colors.textPrimary,
                onClick = onDuplicate,
            )
            SheetAction(
                text = stringResource(Res.string.builds_action_share),
                busy = busy,
                color = colors.textPrimary,
                onClick = onShare,
            )
            // the one irreversible action: separated and in the danger colour
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.borderDefault),
            )
            SheetAction(
                text = stringResource(Res.string.builds_action_delete),
                busy = busy,
                color = colors.danger,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun SheetAction(
    text: String,
    busy: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val colors = talentForgeColors()
    BasicText(
        text = text,
        style = OverlayActionStyle.copy(color = if (busy) colors.textMuted else color),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = text, enabled = !busy, onClick = onClick)
            .padding(vertical = TalentForgeSpacing.inline),
    )
}

/**
 * The rename dialog: the current name pre-filled (empty for a Draft), a live
 * `n / limit` hint, Cancel and Save. Save is disabled for a blank or
 * over-limit name; a failed save keeps the typed text and shows a retryable
 * error line inside the dialog.
 */
@Composable
fun RenameBuildDialog(
    text: String,
    onTextChange: (String) -> Unit,
    maxLength: Int,
    busy: Boolean,
    error: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val trimmed = text.trim()
    val blank = trimmed.isEmpty()
    val overLimit = trimmed.length > maxLength
    val saveEnabled = !busy && !blank && !overLimit
    val fieldDescription = stringResource(Res.string.builds_rename_field_description)
    DialogScrim(busy = busy, onDismiss = onCancel, modifier = modifier) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.large))
                .border(1.dp, colors.borderDefault, RoundedCornerShape(TalentForgeRadius.large))
                .pointerInput(Unit) {
                    // a tap on the dialog surface itself is never a dismiss
                    // — only Cancel and Save act
                    detectTapGestures { }
                }
                .padding(TalentForgeSpacing.group),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        ) {
            BasicText(
                text = stringResource(Res.string.builds_rename_title),
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
            )
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = DialogInputStyle.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.focus),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(colors.surfaceInteractive, RoundedCornerShape(TalentForgeRadius.medium))
                    .border(
                        1.dp,
                        if (overLimit) colors.danger else colors.borderStrong,
                        RoundedCornerShape(TalentForgeRadius.medium),
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .semantics { contentDescription = fieldDescription },
            )
            BasicText(
                text = "${text.length} / $maxLength",
                style = OverlayCaptionStyle.copy(color = if (overLimit) colors.danger else colors.textMuted),
            )
            if (error) {
                BasicText(
                    text = stringResource(Res.string.builds_rename_failed),
                    style = OverlayActionStyle.copy(color = colors.danger),
                )
            }
            DialogButtonRow {
                DialogTextButton(
                    text = stringResource(Res.string.common_cancel),
                    color = colors.textSecondary,
                    enabled = !busy,
                    onClick = onCancel,
                )
                DialogTextButton(
                    text = stringResource(Res.string.common_save),
                    color = colors.focus,
                    enabled = saveEnabled,
                    onClick = onSave,
                )
            }
        }
    }
}

/**
 * The delete confirmation: `Delete "<title>"?`, the irreversibility line, and
 * Cancel / Delete with Delete in the danger colour. A failed delete keeps the
 * dialog open with a retryable error line.
 */
@Composable
fun DeleteBuildDialog(
    buildTitle: String,
    busy: Boolean,
    error: Boolean,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    DialogScrim(busy = busy, onDismiss = onCancel, modifier = modifier) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.large))
                .border(1.dp, colors.borderDefault, RoundedCornerShape(TalentForgeRadius.large))
                .pointerInput(Unit) {
                    // a tap on the dialog surface itself is never a dismiss
                    // — only Cancel and Delete act
                    detectTapGestures { }
                }
                .padding(TalentForgeSpacing.group),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        ) {
            BasicText(
                text = stringResource(Res.string.builds_delete_title, buildTitle),
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
            )
            BasicText(
                text = stringResource(Res.string.builds_delete_body),
                style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
            )
            if (error) {
                BasicText(
                    text = stringResource(Res.string.builds_delete_failed),
                    style = OverlayActionStyle.copy(color = colors.danger),
                )
            }
            DialogButtonRow {
                DialogTextButton(
                    text = stringResource(Res.string.common_cancel),
                    color = colors.textSecondary,
                    enabled = !busy,
                    onClick = onCancel,
                )
                DialogTextButton(
                    text = stringResource(Res.string.builds_action_delete),
                    color = colors.danger,
                    enabled = !busy,
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * Centered dialog content over a full-size scrim. The scrim ALWAYS consumes
 * touches (the interface underneath is unreachable while the dialog is
 * open), but dismisses on tap only while [busy] is `false` — during an
 * operation a scrim tap is swallowed, never a dismiss.
 */
@Composable
internal fun DialogScrim(
    busy: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(talentForgeColors().scrim)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClickLabel = if (busy) null else stringResource(Res.string.builds_dialog_close_action),
            ) { if (!busy) onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = TalentForgeSpacing.screenX)) { content() }
    }
}

/**
 * The dialog's end-aligned action row. A row that cannot hold every button
 * on one line (long translations at large font scales) wraps WHOLE buttons
 * onto the next line, still end-aligned — a button label is never broken
 * mid-word (M5.REL.2a); at the reference sizes it is the same single row.
 */
@Composable
private fun DialogButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun DialogTextButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = talentForgeColors()
    BasicText(
        text = text,
        style = OverlayActionStyle.copy(color = if (enabled) color else colors.disabled),
        softWrap = false,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = text, enabled = enabled, onClick = onClick)
            .padding(horizontal = TalentForgeSpacing.inline),
    )
}

// ------------------------------------------------------------------ typography

private val OverlayCaptionStyle = TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
)

private val OverlayActionStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

private val DialogInputStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
)
