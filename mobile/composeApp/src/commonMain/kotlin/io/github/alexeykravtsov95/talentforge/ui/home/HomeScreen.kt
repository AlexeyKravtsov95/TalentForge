package io.github.alexeykravtsov95.talentforge.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.BASELINE_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.BuildCardModel
import io.github.alexeykravtsov95.talentforge.ui.library.ClassMonogramMark
import io.github.alexeykravtsov95.talentforge.ui.library.HomeModel
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.LibraryData
import io.github.alexeykravtsov95.talentforge.ui.library.PrimaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.SectionLabel
import io.github.alexeykravtsov95.talentforge.ui.library.SecondaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.classMonogramOf
import io.github.alexeykravtsov95.talentforge.ui.library.dataVersionOf
import io.github.alexeykravtsov95.talentforge.ui.library.gameDisplayName
import io.github.alexeykravtsov95.talentforge.ui.library.loadHomeModel
import io.github.alexeykravtsov95.talentforge.ui.library.relativeBuildTime
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home — the app root (UX/UI spec B). Hierarchy exactly as frozen:
 * Continue (the strongest action, the most recently edited build) → Recent
 * builds (3; 4 on 412 dp+ screens) → All builds · N → New build (secondary,
 * pinned in the thumb zone). The new-user state collapses to one sentence,
 * one primary action and the data status — no placeholder cards, no
 * illustration, no fake builds.
 *
 * A failed library load is a recoverable error state with Retry — never a
 * faked empty Home (which would invite an overwrite of the stored payload).
 * Card metadata shows only facts resolved from real data: the real class
 * display name, spent/budget computed by the real engine, relative time —
 * nothing invented, unresolved fields are simply absent.
 */
@Composable
fun HomeScreen(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    clockMillis: () -> Long,
    onOpenBuild: (String) -> Unit,
    onAllBuilds: () -> Unit,
    onNewBuild: () -> Unit,
) {
    var data by remember { mutableStateOf<LibraryData<HomeModel>>(LibraryData.Loading) }
    var reloadToken by remember { mutableStateOf(0) }
    LaunchedEffect(reloadToken) {
        data = LibraryData.Loading
        data = withContext(Dispatchers.Default) { loadHomeModel(repository, datasets) }
    }

    val colors = talentForgeColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TalentForgeSpacing.screenX),
                verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
            ) {
                Spacer(Modifier.height(TalentForgeSpacing.group))
                BrandHeader()
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

                    is LibraryData.Ready -> HomeContent(
                        model = current.value,
                        clockMillis = clockMillis,
                        onOpenBuild = onOpenBuild,
                        onAllBuilds = onAllBuilds,
                    )
                }
                Spacer(Modifier.height(TalentForgeSpacing.group))
            }
            // New build: secondary, pinned in the thumb zone (spec B.1).
            SecondaryActionButton(
                text = "+ New build",
                onClickLabel = "start a new build",
                onClick = onNewBuild,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = TalentForgeSpacing.screenX,
                        end = TalentForgeSpacing.screenX,
                        bottom = TalentForgeSpacing.rowGap,
                    ),
            )
        }
    }
}

@Composable
private fun HomeContent(
    model: HomeModel,
    clockMillis: () -> Long,
    onOpenBuild: (String) -> Unit,
    onAllBuilds: () -> Unit,
) {
    val continueCard = model.continueCard
    if (continueCard == null) {
        NewUserContent()
        return
    }
    SectionLabel("CONTINUE")
    ContinueCard(card = continueCard, clockMillis = clockMillis, onOpen = onOpenBuild)

    if (model.recentCards.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("RECENT")
            Spacer(Modifier.weight(1f))
            AllBuildsLink(count = model.totalBuilds, onClick = onAllBuilds)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // UX spec H: 3 recent builds on a 360 dp phone, 4 at 412 dp+.
            val recentLimit = if (maxWidth.value >= 412f) 4 else 3
            Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
                for (card in model.recentCards.take(recentLimit)) {
                    RecentBuildRow(card = card, clockMillis = clockMillis, onClick = {
                        onOpenBuild(card.record.localId)
                    })
                }
            }
        }
    }
}

/** B.2 new user: one sentence, one primary action (the pinned New build), data status. */
@Composable
private fun NewUserContent() {
    val colors = talentForgeColors()
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap)) {
        BasicText(
            "Plan your first build",
            style = TalentForgeTypography.title.copy(color = colors.textPrimary),
        )
        BasicText(
            "Pick a version and class, then spend points on the talent tree. " +
                "Builds are stored on this device; no account is needed.",
            style = TalentForgeTypography.body.copy(color = colors.textSecondary),
        )
        BasicText(
            "${gameDisplayName(BASELINE_BUNDLE.game)} talent data " +
                "${dataVersionOf(BASELINE_BUNDLE.clientBuild)} · up to date",
            style = TalentForgeTypography.meta.copy(color = colors.textMuted),
        )
    }
}

/** The temporary brand mark: outlined diamond with an accent core + wordmark (spec B). */
@Composable
private fun BrandHeader() {
    val colors = talentForgeColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .drawBehind { drawBrandDiamond(strokeColor = colors.textSecondary, coreColor = colors.focus) },
        )
        BasicText(
            "TalentForge",
            style = HomeBrandStyle.copy(color = colors.textPrimary),
        )
    }
}

/** Spec B: the mark is a temporary "outlined diamond with an accent core". */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBrandDiamond(
    strokeColor: Color,
    coreColor: Color,
) {
    val stroke = 1.5.dp.toPx()
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f - stroke
    val diamond = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius, center.y)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius, center.y)
        close()
    }
    drawPath(diamond, color = strokeColor, style = Stroke(width = stroke))
    drawCircle(color = coreColor, radius = radius * 0.3f, center = center)
}

/** B.1: the strongest element — the most recently edited build, full-width action. */
@Composable
private fun ContinueCard(
    card: BuildCardModel,
    clockMillis: () -> Long,
    onOpen: (String) -> Unit,
) {
    val colors = talentForgeColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.large))
            .border(1.dp, colors.borderDefault, RoundedCornerShape(TalentForgeRadius.large))
            .padding(TalentForgeSpacing.inline),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        Row(
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
                    text = cardMetaLine(card, clockMillis),
                    style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                    maxLines = 1,
                )
            }
            card.pointsText()?.let { points ->
                BasicText(
                    text = points,
                    style = HomePointsStyle.copy(color = colors.textPrimary),
                )
            }
        }
        PrimaryActionButton(
            text = "Continue editing",
            onClickLabel = "continue editing ${card.title}",
            onClick = { onOpen(card.record.localId) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Compact recent-build row: mark, title, `spent / budget · time`. */
@Composable
private fun RecentBuildRow(
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
                text = recentMetaLine(card, clockMillis),
                style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                maxLines = 1,
            )
        }
    }
}

/** `All builds 6` link of the RECENT header row. */
@Composable
private fun AllBuildsLink(count: Int, onClick: () -> Unit) {
    BasicText(
        text = "All builds $count",
        style = HomeLinkStyle.copy(color = talentForgeColors().textSecondary),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = "open all builds", onClick = onClick)
            .padding(horizontal = TalentForgeSpacing.inline, vertical = TalentForgeSpacing.hairline),
    )
}

/** `Warrior · Forever · 2 h ago` — only fields the card actually resolved. */
private fun cardMetaLine(card: BuildCardModel, clockMillis: () -> Long): String =
    listOfNotNull(
        card.classDisplayName ?: card.record.build.classToken.value,
        gameDisplayName(card.record.build.game),
        relativeBuildTime(clockMillis(), card.record.updatedAt),
    ).joinToString(" · ")

/** `20 / 51 · Yesterday` (the spec's split-count slot holds the single-tree total). */
private fun recentMetaLine(card: BuildCardModel, clockMillis: () -> Long): String =
    listOfNotNull(
        card.pointsText(),
        relativeBuildTime(clockMillis(), card.record.updatedAt),
    ).joinToString(" · ")

private fun BuildCardModel.pointsText(): String? =
    if (spentPoints != null && pointBudget != null) "$spentPoints / $pointBudget" else null

/** Spec B: "the TalentForge wordmark at 17 sp, 650 weight". */
private val HomeBrandStyle = TextStyle(
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight(650),
)

/** UX spec G: numeric mono 17 · 600 (tabular). */
private val HomePointsStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)

private val HomeLinkStyle = TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
)
