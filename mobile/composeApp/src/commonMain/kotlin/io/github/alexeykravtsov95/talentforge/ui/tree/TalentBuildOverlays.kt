package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_sheet_close_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_build_actions_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_reset_notice
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_reset_talents
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_undo
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_undo_action
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The M4.3 build-action overlays, both built from the stable Compose
 * primitives already used by the inspector (no Material component is added):
 *
 * - **⋯ build actions** (UX/UI spec ADAPT 2 / E.2): one bottom sheet, the only
 *   new header control. In M4.3 it holds exactly the actions this milestone
 *   owns — `Reset talents`, separated and in the warning colour, because it is
 *   *recoverable* (UX/UI spec 03 §4). Rename/Duplicate arrive with the
 *   build-management milestone and are not shown as dead placeholders.
 * - **Reset feedback** (UX/UI spec 03 §4 / E.3): `Talents reset · N points
 *   cleared` + `Undo` — the same history mechanism the header Undo uses, never
 *   a separate reset backup. Placement follows the spec's notice model: the
 *   UX/UI spec places snackbars "at the top" (07: "The copy appears at the top
 *   … the snackbar offers Rename") and floats notices "over the top of the
 *   canvas" (01 ADAPT 3), so the reset notice floats at the top of the canvas
 *   region — anchored by the caller, inset from any side inspector — and can
 *   never cover the compact bar, the expanded sheet, the side/persistent
 *   panel or their action controls in any layout.
 */
object TalentBuildOverlayMetrics {

    /**
     * UX/UI spec 04: "Sheet / dialog 280 in · 220 out" (same motion family as
     * the inspector chrome).
     */
    const val SHEET_IN_MS: Int = TalentForgeMotion.SHEET_IN_MS

    /** UX/UI spec 04: sheet out 220 ms. */
    const val SHEET_OUT_MS: Int = TalentForgeMotion.SHEET_OUT_MS

    /**
     * How long the reset snackbar stays. No design source freezes a duration
     * for it (unlike the 2.6 s blocked-refund reason line, which B3 07 does
     * freeze) — implementation-safe 4 s, and Undo always dismisses at once.
     */
    const val RESET_SNACKBAR_MS: Int = 4000
}

/**
 * The ⋯ bottom sheet. Rendered by the screen inside a full-size scrim Box;
 * tapping the scrim dismisses without acting (sheets close first — UX/UI
 * spec 02 §1).
 */
@Composable
fun TalentBuildActionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onResetTalents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(TalentBuildOverlayMetrics.SHEET_IN_MS)),
        exit = fadeOut(tween(TalentBuildOverlayMetrics.SHEET_OUT_MS)),
        modifier = modifier,
    ) {
        val colors = talentForgeColors()
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = stringResource(Res.string.builds_sheet_close_action),
                    onClick = onDismiss,
                ),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(TalentBuildOverlayMetrics.SHEET_IN_MS)) { it } +
                    fadeIn(tween(TalentBuildOverlayMetrics.SHEET_IN_MS)),
                exit = slideOutVertically(tween(TalentBuildOverlayMetrics.SHEET_OUT_MS)) { it } +
                    fadeOut(tween(TalentBuildOverlayMetrics.SHEET_OUT_MS)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            colors.surfaceElevated,
                            RoundedCornerShape(
                                topStart = TalentForgeRadius.sheet,
                                topEnd = TalentForgeRadius.sheet,
                            ),
                        )
                        .padding(
                            horizontal = TalentForgeSpacing.sheetX,
                            vertical = TalentForgeSpacing.group,
                        ),
                    verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                ) {
                    BasicText(
                        text = stringResource(Res.string.editor_build_actions_title),
                        style = OverlayCaptionStyle.copy(color = colors.textMuted),
                    )
                    // UX/UI spec E.2: Reset talents sits separated, in the
                    // warning colour — recoverable, so no confirmation dialog.
                    val resetTalents = stringResource(Res.string.editor_reset_talents)
                    BasicText(
                        text = resetTalents,
                        style = OverlayActionStyle.copy(color = colors.warning),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = resetTalents,
                                onClick = onResetTalents,
                            )
                            .padding(vertical = TalentForgeSpacing.inline),
                    )
                }
            }
        }
    }
}

/** `null` when no reset feedback is showing. */
data class TalentResetNotice(
    val pointsCleared: Int,
    /** Changes per notice so auto-dismiss animations restart per reset. */
    val id: Int,
)

/**
 * The recoverable-reset snackbar: `Talents reset · N points cleared` + Undo
 * (UX/UI spec 03 §4; the N placeholder is pluralized normally — no design
 * source freezes a literal count string). The Undo action goes through the
 * SAME history mechanism as the header Undo — there is no separate one-off
 * reset backup.
 *
 * The caller anchors it at the **top** of the canvas region (see
 * [TalentBuildOverlayMetrics]): it slides down from the top edge, never from
 * the bottom, so its motion and its resting place agree.
 */
@Composable
fun TalentResetSnackbar(
    notice: TalentResetNotice?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = notice != null,
        enter = slideInVertically(tween(TalentBuildOverlayMetrics.SHEET_IN_MS)) { -it / 2 } +
            fadeIn(tween(TalentBuildOverlayMetrics.SHEET_IN_MS)),
        exit = slideOutVertically(tween(TalentBuildOverlayMetrics.SHEET_OUT_MS)) { -it / 2 } +
            fadeOut(tween(TalentBuildOverlayMetrics.SHEET_OUT_MS)),
        modifier = modifier,
    ) {
        val colors = talentForgeColors()
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val cleared = notice?.pointsCleared ?: 0
            val message = pluralStringResource(Res.plurals.editor_reset_notice, cleared, cleared)
            // M5.REL.2b: the notice beside Undo, as before; a longer translation wraps at
            // words, and moves above Undo only when a word cannot fit beside it
            TextBesideActions(
                text = message,
                textStyle = OverlayActionStyle,
                gap = TalentForgeSpacing.inline,
                modifier = Modifier
                    .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.medium))
                    .padding(
                        start = TalentForgeSpacing.rowGap,
                        top = TalentForgeSpacing.inline,
                        bottom = TalentForgeSpacing.inline,
                        end = TalentForgeSpacing.hairline,
                    ),
                content = { BasicText(text = message, style = OverlayActionStyle.copy(color = colors.textPrimary)) },
            ) {
                BasicText(
                    text = stringResource(Res.string.editor_undo),
                    style = OverlayUndoStyle.copy(color = colors.focus),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(Res.string.editor_undo_action),
                            onClick = onUndo,
                        )
                        .padding(horizontal = TalentForgeSpacing.inline, vertical = TalentForgeSpacing.inline),
                )
            }
        }
    }
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

private val OverlayUndoStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)
