package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors

/** Test tag of the inspector's description block. */
const val TALENT_INSPECTOR_DESCRIPTION_TAG: String = "talent-inspector-description"

/** Test tag of the CURRENT / NEXT RANK comparison. */
const val TALENT_INSPECTOR_COMPARISON_TAG: String = "talent-inspector-comparison"

/** B3 04 column labels of the rank comparison. */
const val CURRENT_LABEL: String = "CURRENT"
const val NEXT_RANK_LABEL: String = "NEXT RANK"

/**
 * The description of the selected talent (M5.V2) and the B3 CURRENT / NEXT RANK
 * comparison. Values are set semibold; a value that is not shown is the muted
 * [MISSING_MARK], explained by one muted note line. Plain text only — the
 * spans come from verified `talent-tooltips.v1` segments.
 */
@Composable
internal fun InspectorDescriptionBlock(description: TalentInspectorDescription) {
    val colors = talentForgeColors()
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TALENT_INSPECTOR_DESCRIPTION_TAG),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        when (val body = description.body) {
            is DescriptionBody.Text -> {
                BasicText(
                    text = body.annotated(colors, base = colors.textPrimary, emphasis = colors.textPrimary),
                    style = DescriptionStyle,
                )
                body.missingNote?.let { note ->
                    BasicText(text = note, style = DescriptionNoteStyle.copy(color = colors.textMuted))
                }
            }

            DescriptionBody.Unavailable -> BasicText(
                text = DescriptionBody.Unavailable.TEXT,
                style = DescriptionNoteStyle.copy(color = colors.textMuted),
            )
        }
        when (val comparison = description.comparison) {
            is RankComparison.Value -> Row(
                modifier = Modifier.fillMaxWidth().testTag(TALENT_INSPECTOR_COMPARISON_TAG),
                horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
            ) {
                ComparisonColumn(CURRENT_LABEL, comparison.current ?: MISSING_MARK, Modifier.weight(1f))
                ComparisonColumn(NEXT_RANK_LABEL, comparison.next, Modifier.weight(1f))
            }

            is RankComparison.NextRankText -> Column(
                modifier = Modifier.fillMaxWidth().testTag(TALENT_INSPECTOR_COMPARISON_TAG),
                verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline),
            ) {
                BasicText(text = NEXT_RANK_LABEL, style = ComparisonLabelStyle.copy(color = colors.textMuted))
                BasicText(
                    text = comparison.body.annotated(
                        colors,
                        base = colors.textSecondary,
                        emphasis = colors.textPrimary,
                    ),
                    style = DescriptionStyle,
                )
            }

            null -> Unit
        }
    }
}

@Composable
private fun ComparisonColumn(label: String, value: String, modifier: Modifier) {
    val colors = talentForgeColors()
    Column(modifier) {
        BasicText(text = label, style = ComparisonLabelStyle.copy(color = colors.textMuted))
        BasicText(text = value, style = ComparisonValueStyle.copy(color = colors.textPrimary))
    }
}

/**
 * Plain text in [base]; values semibold (in [emphasis] when they changed
 * against the compared rank); a missing value as the muted [MISSING_MARK].
 */
private fun DescriptionBody.Text.annotated(
    colors: TalentForgeColors,
    base: Color,
    emphasis: Color,
): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        when (span) {
            is DescriptionSpan.Plain -> withStyle(SpanStyle(color = base)) { append(span.text) }
            is DescriptionSpan.Value -> withStyle(
                SpanStyle(
                    color = if (span.changed) emphasis else base,
                    fontWeight = FontWeight.SemiBold,
                ),
            ) { append(span.text) }

            DescriptionSpan.Missing -> withStyle(SpanStyle(color = colors.textMuted)) { append(span.text) }
        }
    }
}

/** UX/UI spec G BodySecondary (13.5 / 20): the description is detail, not a title. */
private val DescriptionStyle: TextStyle = TalentForgeTypography.bodySmall

/** B3 08: "Minimum text: … 12.5 sp for state lines" — the note is a state-line register. */
private val DescriptionNoteStyle = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp)

/** UX/UI spec G Label (12 / 16 · 500) for the CURRENT / NEXT RANK captions. */
private val ComparisonLabelStyle: TextStyle = TalentForgeTypography.label

/** UX/UI spec G Numeric (mono 17 · 600) for the compared values. */
private val ComparisonValueStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)
