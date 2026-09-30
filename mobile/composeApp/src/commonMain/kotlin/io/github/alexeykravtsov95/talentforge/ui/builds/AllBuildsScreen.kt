package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.BuildCardModel
import io.github.alexeykravtsov95.talentforge.ui.library.ClassMonogramMark
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.LibraryData
import io.github.alexeykravtsov95.talentforge.ui.library.PrimaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.classMonogramOf
import io.github.alexeykravtsov95.talentforge.ui.library.gameDisplayName
import io.github.alexeykravtsov95.talentforge.ui.library.loadBuildsModel
import io.github.alexeykravtsov95.talentforge.ui.library.relativeBuildTime
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.BackChevronButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All Builds — the M5.1 BASIC Saved Builds list (spec D.1, management
 * actions deliberately absent until M5.2): every local record as a row
 * ("rows, not cards"), newest edited first, honest metadata (real class
 * name, version context, relative time, engine-computed spent/budget), tap
 * opens the build, Back returns here. No overflow menu, no rename/duplicate/
 * delete — no dead controls.
 */
@Composable
fun AllBuildsScreen(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    clockMillis: () -> Long,
    onBack: () -> Unit,
    onOpenBuild: (String) -> Unit,
    onNewBuild: () -> Unit,
) {
    var data by remember { mutableStateOf<LibraryData<List<BuildCardModel>>>(LibraryData.Loading) }
    var reloadToken by remember { mutableStateOf(0) }
    LaunchedEffect(reloadToken) {
        data = LibraryData.Loading
        data = withContext(Dispatchers.Default) { loadBuildsModel(repository, datasets) }
    }

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
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevronButton(onClick = onBack)
            BasicText(
                text = when (val current = data) {
                    is LibraryData.Ready -> "Builds ${current.value.size}"
                    else -> "Builds"
                },
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = "+",
                style = BuildsNewButtonStyle.copy(color = colors.textPrimary),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .padding(horizontal = TalentForgeSpacing.screenX)
                    .clickable(role = Role.Button, onClickLabel = "start a new build", onClick = onNewBuild),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TalentForgeSpacing.screenX),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        ) {
            Spacer(Modifier.height(TalentForgeSpacing.inline))
            when (val current = data) {
                LibraryData.Loading -> BasicText(
                    "Loading your builds…",
                    style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                )

                is LibraryData.Failed -> Column(
                    verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
                ) {
                    BasicText(
                        "Couldn't load your builds",
                        style = TalentForgeTypography.componentTitle.copy(color = colors.danger),
                    )
                    InlineNotice(
                        text = "The local build library could not be read",
                        actionText = "Retry",
                        onAction = { reloadToken += 1 },
                    )
                }

                is LibraryData.Ready -> if (current.value.isEmpty()) {
                    // D.6 empty: one line, one hint, one action.
                    Spacer(Modifier.height(TalentForgeSpacing.group))
                    BasicText(
                        "No saved builds yet.",
                        style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                    )
                    BasicText(
                        "Create a build to start planning talents.",
                        style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                    )
                    PrimaryActionButton(
                        text = "+ New build",
                        onClickLabel = "start a new build",
                        onClick = onNewBuild,
                    )
                } else {
                    for (card in current.value) {
                        BuildRow(card = card, clockMillis = clockMillis) {
                            onOpenBuild(card.record.localId)
                        }
                    }
                }
            }
            Spacer(Modifier.height(TalentForgeSpacing.group))
        }
    }
}

/** D.1 row: name, `class · version · recency`, and the spent/budget column. */
@Composable
private fun BuildRow(
    card: BuildCardModel,
    clockMillis: () -> Long,
    onClick: () -> Unit,
) {
    val colors = talentForgeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = "open ${card.title}", onClick = onClick)
            .padding(vertical = TalentForgeSpacing.hairline),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        ClassMonogramMark(
            monogram = classMonogramOf(card.classDisplayName ?: card.record.build.classToken.value),
            accent = classAccentFor(card.record.build.classToken).color,
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                text = card.title,
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                maxLines = 1,
            )
            BasicText(
                text = listOfNotNull(
                    card.classDisplayName ?: card.record.build.classToken.value,
                    gameDisplayName(card.record.build.game),
                    relativeBuildTime(clockMillis(), card.record.updatedAt),
                ).joinToString(" · "),
                style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                maxLines = 1,
            )
        }
        if (card.spentPoints != null && card.pointBudget != null) {
            BasicText(
                text = "${card.spentPoints} / ${card.pointBudget}",
                style = BuildsPointsStyle.copy(color = colors.textPrimary),
            )
        }
    }
}

private val BuildsPointsStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)

private val BuildsNewButtonStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 22.sp,
    lineHeight = 26.sp,
    fontWeight = FontWeight.SemiBold,
)
