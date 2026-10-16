package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_cancel
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_talent_data_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_checking
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_clear
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_clear_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_confirm
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_continue
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_continue_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_storage
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_field_description
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_field_placeholder
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_paste
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_paste_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_preview_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_preview_points
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_preview_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_saving
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_title
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.share.BuildImportPreview
import io.github.alexeykravtsov95.talentforge.ui.builds.DialogScrim
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkMark
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkSlot
import io.github.alexeykravtsov95.talentforge.ui.library.PrimaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.SecondaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.classMonogramOf
import io.github.alexeykravtsov95.talentforge.ui.library.gameDisplayName
import io.github.alexeykravtsov95.talentforge.ui.library.localizedClassName
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.BackChevronButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * **Import build** (M5.SHARE.2) — the one import flow, opened from Home and
 * from All Builds (the host's back stack returns to whichever opened it).
 *
 * The player pastes a code (native paste in the field, or the explicit Paste
 * button), taps Continue, and gets a confirmation surface built from the
 * validated [BuildImportPreview]: localized class, `Forever · talent data
 * <exact client build>`, points, and what importing does. Only **Import**
 * writes — one new independent Draft via `createDraft`, of exactly the
 * validated build on exactly its own client build — and [onImported]
 * receives the new id once storage confirmed it. Cancel and Back never write.
 *
 * The input survives recomposition and configuration changes
 * (`rememberSaveable`) but is never stored in the build library. The field
 * never changes the pasted text (no case change, no trimming), refuses text
 * over the share-code input limit, and scrolls inside a bounded height so a
 * long paste cannot push Continue off screen; Continue stays pinned above
 * the keyboard (the screen pads by the safe-drawing insets, IME included).
 */
@Composable
fun ImportBuildScreen(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    sharing: BuildCodeSharing,
    onBack: () -> Unit,
    onImported: (String) -> Unit,
) {
    val input = rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val controller = remember(repository, datasets) {
        BuildImportController(input, datasets, repository, scope, Dispatchers.Default)
    }
    val phase = controller.phase
    val saving = phase is ImportPhase.Confirming && phase.saving || phase is ImportPhase.Imported
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun hideKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // Back closes the confirmation surface first (never a write); while the
    // new record is being written Back is held here, like New Build's creation.
    PlatformBackHandler(enabled = phase is ImportPhase.Confirming || saving) {
        if (!saving) controller.dismissPreview()
    }

    val colors = talentForgeColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures { hideKeyboard() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevronButton(onClick = { if (!saving) onBack() })
                BasicText(
                    text = stringResource(Res.string.import_title),
                    style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                    modifier = Modifier.weight(1f),
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
                BasicText(
                    text = stringResource(Res.string.import_body),
                    style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                )
                CodeField(
                    text = controller.text,
                    onTextChange = controller::onInputChange,
                    enabled = !saving,
                    onDone = ::hideKeyboard,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
                    TextAction(
                        text = stringResource(Res.string.import_paste),
                        onClickLabel = stringResource(Res.string.import_paste_action),
                        enabled = !controller.busy,
                        onClick = { scope.launch { controller.onPasted(sharing.readClipboardText()) } },
                    )
                    TextAction(
                        text = stringResource(Res.string.import_clear),
                        onClickLabel = stringResource(Res.string.import_clear_action),
                        enabled = !saving && controller.text.isNotEmpty(),
                        onClick = controller::clear,
                    )
                }
                when (phase) {
                    is ImportPhase.Checking -> StatusLine(
                        text = stringResource(Res.string.import_checking),
                        color = colors.textSecondary,
                    )

                    is ImportPhase.Problem -> StatusLine(
                        text = importMessageOf(phase.problem).text(),
                        color = colors.warning,
                    )

                    else -> {}
                }
                Spacer(Modifier.height(TalentForgeSpacing.inline))
            }
            PrimaryActionButton(
                text = stringResource(Res.string.import_continue),
                onClickLabel = stringResource(Res.string.import_continue_action),
                enabled = controller.canContinue,
                onClick = {
                    hideKeyboard()
                    controller.submit()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = TalentForgeSpacing.screenX,
                        end = TalentForgeSpacing.screenX,
                        top = TalentForgeSpacing.inline,
                        bottom = TalentForgeSpacing.rowGap,
                    ),
            )
        }

        if (phase is ImportPhase.Confirming) {
            ImportPreviewDialog(
                preview = phase.preview,
                saving = phase.saving,
                saveFailed = phase.saveError != null,
                onImport = { controller.confirm(onImported) },
                onCancel = controller::dismissPreview,
            )
        }
    }
}

/** The multiline code field: exact text, monospace, no auto-correction, bounded height with inner scroll. */
@Composable
private fun CodeField(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    val colors = talentForgeColors()
    val description = stringResource(Res.string.import_field_description)
    val placeholder = stringResource(Res.string.import_field_placeholder)
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        enabled = enabled,
        minLines = FIELD_MIN_LINES,
        maxLines = FIELD_MAX_LINES,
        textStyle = FieldStyle.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.focus),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceInteractive, RoundedCornerShape(TalentForgeRadius.medium))
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(TalentForgeRadius.medium))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) {
                    BasicText(placeholder, style = FieldStyle.copy(color = colors.textMuted))
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun TextAction(
    text: String,
    onClickLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = talentForgeColors()
    BasicText(
        text = text,
        style = TextActionStyle.copy(color = if (enabled) colors.textPrimary else colors.disabled),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, enabled = enabled, onClick = onClick)
            .padding(horizontal = TalentForgeSpacing.inline, vertical = 12.dp),
    )
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    BasicText(
        text = text,
        style = StatusStyle.copy(color = color),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The confirmation surface: what will be imported, and the one write. While
 * the write runs both actions are disabled and the scrim does not dismiss; a
 * failed write keeps the preview with a retryable error line.
 *
 * Import and Cancel are full-width stacked buttons pinned under the scrolling
 * details: their labels wrap instead of being clipped, so "Импортировать" at
 * 200 % on a 320 dp phone stays a whole, tappable action.
 */
@Composable
private fun ImportPreviewDialog(
    preview: BuildImportPreview,
    saving: Boolean,
    saveFailed: Boolean,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = talentForgeColors()
    val className = localizedClassName(preview.classToken, preview.className) ?: preview.className
    DialogScrim(busy = saving, onDismiss = onCancel) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.large))
                .border(1.dp, colors.borderDefault, RoundedCornerShape(TalentForgeRadius.large))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = TalentForgeSpacing.screenX, vertical = TalentForgeSpacing.group),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
            ) {
                BasicText(
                    text = stringResource(Res.string.import_preview_title),
                    style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                ) {
                    ClassArtworkMark(
                        classToken = preview.classToken,
                        slot = ClassArtworkSlot.Row,
                        monogram = classMonogramOf(preview.className),
                        accent = classAccentFor(preview.classToken).color,
                    )
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            text = className,
                            style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                        )
                        // the EXACT client build of the code: an import is never migrated
                        BasicText(
                            text = stringResource(
                                Res.string.common_talent_data_version,
                                gameDisplayName(preview.game),
                                preview.clientBuild,
                            ),
                            style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                        )
                    }
                }
                BasicText(
                    text = stringResource(Res.string.import_preview_points, preview.spentPoints, preview.pointBudget),
                    style = PointsStyle.copy(color = colors.textPrimary),
                )
                BasicText(
                    text = stringResource(Res.string.import_preview_body),
                    style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                )
                when {
                    saving -> StatusLine(stringResource(Res.string.import_saving), colors.textSecondary)
                    saveFailed -> StatusLine(stringResource(Res.string.import_error_storage), colors.danger)
                }
            }
            val confirm = stringResource(Res.string.import_confirm)
            val cancel = stringResource(Res.string.common_cancel)
            PrimaryActionButton(
                text = confirm,
                onClickLabel = confirm,
                enabled = !saving,
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryActionButton(
                text = cancel,
                onClickLabel = cancel,
                enabled = !saving,
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A share code is at most 170 characters for the bundled data: 4 lines at 360 dp, then the field scrolls. */
private const val FIELD_MIN_LINES = 4
private const val FIELD_MAX_LINES = 8

private val FieldStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    lineHeight = 22.sp,
)

private val TextActionStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

private val StatusStyle = TextStyle(
    fontSize = 13.5.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

/** UX spec G numeric mono 17 · 600. */
private val PointsStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)
