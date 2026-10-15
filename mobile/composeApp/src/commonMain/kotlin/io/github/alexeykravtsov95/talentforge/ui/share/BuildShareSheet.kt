package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_close_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_code_description
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_copied
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_copied_no_sheet
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_copy_code
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_copy_code_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_send
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_send_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_title
import io.github.alexeykravtsov95.talentforge.ui.library.PrimaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.SecondaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** What the share sheet last reported to the player (announced politely to screen readers). */
private sealed interface ShareFeedback {
    data object None : ShareFeedback

    data object Copied : ShareFeedback

    /** Desktop: no share sheet exists, the code went to the clipboard instead. */
    data object CopiedNoSheet : ShareFeedback

    data class Failed(val reason: PlatformShareFailure) : ShareFeedback
}

/**
 * The M5.SHARE.2 export surface: a bottom sheet over the current screen with
 * the title, the exact [code] (selectable, monospace, wrapping inside a
 * bounded scrollable area), one line on what a code carries, **Share** (native
 * sheet) and **Copy code** (clipboard). Same primitives and modal rules as
 * the existing build-action sheets: the scrim consumes touches and closes
 * the sheet (unless a platform call is in flight), the surface itself never
 * closes on a stray tap, and system Back closes it first.
 *
 * Only [code] is ever handed to [sharing] — no label, line break or
 * decoration. A copy reports "Code copied"; a share reports nothing unless
 * the platform failed (opening a chooser is not proof of delivery) or the
 * host has no share sheet (the desktop copies instead and says so).
 * Cancelling the platform sheet is silent.
 */
@Composable
fun BuildShareSheet(
    code: String,
    buildTitle: String,
    sharing: BuildCodeSharing,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val scope = rememberCoroutineScope()
    var busy by remember(code) { mutableStateOf(false) }
    var feedback by remember(code) { mutableStateOf<ShareFeedback>(ShareFeedback.None) }

    fun run(action: suspend () -> ShareFeedback) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                feedback = action()
            } finally {
                busy = false
            }
        }
    }

    PlatformBackHandler(enabled = true) { if (!busy) onDismiss() }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClickLabel = if (busy) null else stringResource(Res.string.share_close_action),
            ) { if (!busy) onDismiss() },
    ) {
        val sheetMaxHeight = maxHeight
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = SHEET_MAX_WIDTH)
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(
                    colors.surfaceElevated,
                    RoundedCornerShape(topStart = TalentForgeRadius.sheet, topEnd = TalentForgeRadius.sheet),
                )
                .pointerInput(Unit) {
                    // a tap on the surface itself is never a dismiss
                    detectTapGestures { }
                }
                .padding(horizontal = TalentForgeSpacing.sheetX, vertical = TalentForgeSpacing.group),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
        ) {
            // the descriptive part scrolls when space is short (landscape phones,
            // 200 % text); the actions below it never leave the screen
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
            ) {
                BasicText(
                    text = stringResource(Res.string.share_title),
                    style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                )
                BasicText(
                    text = buildTitle,
                    style = SheetCaptionStyle.copy(color = colors.textMuted),
                    maxLines = 1,
                )
                val codeDescription = stringResource(Res.string.share_code_description)
                SelectionContainer(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceInteractive, RoundedCornerShape(TalentForgeRadius.medium))
                        .border(1.dp, colors.borderStrong, RoundedCornerShape(TalentForgeRadius.medium))
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .semantics { contentDescription = codeDescription },
                ) {
                    BasicText(
                        text = code,
                        style = CodeStyle.copy(color = colors.textPrimary),
                    )
                }
                // after the code: on a short screen the code is what stays in view
                BasicText(
                    text = stringResource(Res.string.share_body),
                    style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                )
            }
            PrimaryActionButton(
                text = stringResource(Res.string.share_send),
                onClickLabel = stringResource(Res.string.share_send_action),
                enabled = !busy,
                onClick = {
                    run {
                        when (val outcome = sharing.shareText(code)) {
                            TextShareOutcome.CopiedToClipboard -> ShareFeedback.CopiedNoSheet
                            is TextShareOutcome.Failed -> ShareFeedback.Failed(outcome.reason)
                            // a chooser that opened, an activity the player finished
                            // or a cancelled sheet: nothing to claim, nothing wrong
                            TextShareOutcome.SheetOpened,
                            TextShareOutcome.Completed,
                            TextShareOutcome.Cancelled,
                            -> ShareFeedback.None
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryActionButton(
                text = stringResource(Res.string.share_copy_code),
                onClickLabel = stringResource(Res.string.share_copy_code_action),
                enabled = !busy,
                onClick = {
                    run {
                        when (val written = sharing.copyToClipboard(code)) {
                            ClipboardWrite.Copied -> ShareFeedback.Copied
                            is ClipboardWrite.Failed -> ShareFeedback.Failed(written.reason)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val message = when (val current = feedback) {
                ShareFeedback.None -> null
                ShareFeedback.Copied -> stringResource(Res.string.share_copied) to colors.positive
                ShareFeedback.CopiedNoSheet -> stringResource(Res.string.share_copied_no_sheet) to colors.positive
                is ShareFeedback.Failed -> current.reason.text() to colors.warning
            }
            if (message != null) {
                BasicText(
                    text = message.first,
                    style = SheetFeedbackStyle.copy(color = message.second),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Tablets: the sheet stays a phone-width sheet instead of a stretched bar. */
private val SHEET_MAX_WIDTH = 560.dp

private val SheetCaptionStyle = TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
)

/** The code: monospace so look-alike characters stay distinct; wraps between any characters. */
private val CodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    lineHeight = 22.sp,
)

private val SheetFeedbackStyle = TextStyle(
    fontSize = 13.5.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)
