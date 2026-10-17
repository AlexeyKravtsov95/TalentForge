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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_new_build
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_new_build_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_open_build_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_retry
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_about
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_about_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_all_builds_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_all_builds_link
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_continue_editing
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_continue_editing_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_empty_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_empty_data_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_empty_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_section_continue
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.home_section_recent
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_build
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_build_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_load_failed_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_load_failed_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_loading
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.builds.BuildActionsOverlays
import io.github.alexeykravtsov95.talentforge.ui.builds.BuildRowActionsSheet
import io.github.alexeykravtsov95.talentforge.ui.builds.RowActionsButton
import io.github.alexeykravtsov95.talentforge.ui.builds.rememberBuildActionsPresenter
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.BuildCardModel
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkMark
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkSlot
import io.github.alexeykravtsov95.talentforge.ui.library.HomeModel
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.LibraryData
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.PrimaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.SectionLabel
import io.github.alexeykravtsov95.talentforge.ui.library.SecondaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.buildCardTitle
import io.github.alexeykravtsov95.talentforge.ui.library.classMonogramOf
import io.github.alexeykravtsov95.talentforge.ui.library.dataVersionOf
import io.github.alexeykravtsov95.talentforge.ui.library.gameDisplayName
import io.github.alexeykravtsov95.talentforge.ui.library.loadHomeModel
import io.github.alexeykravtsov95.talentforge.ui.library.localizedClassName
import io.github.alexeykravtsov95.talentforge.ui.library.relativeBuildTime
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.share.BuildCodeSharing
import io.github.alexeykravtsov95.talentforge.ui.share.text
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Home — the app root, redesigned in M5.UI.1 to the approved B+ "Modern
 * Fantasy Utility" layout. Hierarchy exactly as approved: Continue (the
 * strongest action, the most recently edited build) → Recent builds (3; 4 on
 * 412 dp+ screens) → All builds · N → New build (pinned in the thumb zone:
 * the single PRIMARY action of the typography-only empty state, a neutral
 * secondary once builds exist). The empty state is one heading, one
 * workflow sentence and the data status — no placeholder cards, no
 * illustration, no fake builds, no decorative talent-tree fragment.
 *
 * All Builds stays reachable from Home at EVERY library size — including
 * exactly one build, where no Recent row exists to host the link (the link
 * then renders as its own row after the Continue card).
 *
 * A failed library load is a recoverable error state with Retry — never a
 * faked empty Home (which would invite an overwrite of the stored payload).
 * Card metadata shows only facts resolved from real data: the real class
 * display name, spent/budget computed by the real engine, relative time —
 * nothing invented, unresolved fields are simply absent.
 *
 * M5.SHARE.2: a low-emphasis `Import build` text action sits under the
 * pinned New build in every state — an empty library included — so a code
 * received from another player can always be brought in. Every Recent row
 * ends in its own 48 dp `⋯` button (tapping the row still opens the build,
 * tapping `⋯` never does) whose sheet offers Rename, Share and Delete through
 * the app's one [BuildActionsPresenter] — the same dialogs, rules and export
 * as All Builds (the editor has Rename and Share, never Delete). Delete is
 * separated, in the danger colour and confirmed first. A rename or delete
 * reloads Home from storage; a rename never reorders (no `updatedAt` change).
 */
@Composable
fun HomeScreen(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    clockMillis: () -> Long,
    onOpenBuild: (String) -> Unit,
    onAllBuilds: () -> Unit,
    onNewBuild: () -> Unit,
    onImportBuild: () -> Unit,
    onAbout: () -> Unit,
    autosave: BuildAutosaveCoordinator,
    sharing: BuildCodeSharing,
) {
    var data by remember { mutableStateOf<LibraryData<HomeModel>>(LibraryData.Loading) }
    var reloadToken by remember { mutableStateOf(0) }
    LaunchedEffect(reloadToken) {
        data = LibraryData.Loading
        data = withContext(Dispatchers.Default) { loadHomeModel(repository, datasets) }
    }
    // Read HERE, in HomeScreen's own scope, not only inside BoxWithConstraints'
    // subcomposition: when the load result is written off the UI thread (the
    // unconfined effect dispatcher of the desktop UI-test harness), a change
    // observed only by the subcomposition was never applied and Home stayed
    // on "Loading" until the waitUntil timeout.
    val shown = data

    // M5.SHARE.2: the Recent row whose ⋯ sheet is open, by opaque id
    var actionsFor by remember { mutableStateOf<String?>(null) }
    val actions = rememberBuildActionsPresenter(
        autosave = autosave,
        repository = repository,
        onRenamed = { reloadToken += 1 },
        onRecordMissing = { reloadToken += 1 },
        onDeleted = { reloadToken += 1 },
    )
    // Back closes the open sheet first (never while its operation runs)
    PlatformBackHandler(enabled = actionsFor != null) { if (!actions.busy) actionsFor = null }

    val colors = talentForgeColors()
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // B+ empty-state alignment: the copy starts in the UPPER-MIDDLE of
            // the usable content (the ~80 % of the screen above the pinned CTA),
            // not right under the header. Computed here because the scrollable
            // content column receives unbounded height and cannot derive it.
            val emptyCopyTopGap = maxHeight * EMPTY_COPY_TOP_GAP_FRACTION
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
                    BrandHeader(onAbout = onAbout)
                    actions.shareFailure?.let { failure ->
                        InlineNotice(
                            text = failure.failure.text(),
                            actionText = stringResource(Res.string.common_retry),
                            onAction = { actions.sharePersisted(failure.localId) },
                        )
                    }
                    when (val current = shown) {
                        LibraryData.Loading -> BasicText(
                            stringResource(Res.string.library_loading),
                            style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                        )

                        is LibraryData.Failed -> Column(
                            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
                        ) {
                            BasicText(
                                stringResource(Res.string.library_load_failed_title),
                                style = TalentForgeTypography.componentTitle.copy(color = colors.danger),
                            )
                            InlineNotice(
                                text = stringResource(Res.string.library_load_failed_body),
                                actionText = stringResource(Res.string.common_retry),
                                onAction = { reloadToken += 1 },
                            )
                        }

                        is LibraryData.Ready -> HomeContent(
                            model = current.value,
                            clockMillis = clockMillis,
                            emptyCopyTopGap = emptyCopyTopGap,
                            onOpenBuild = onOpenBuild,
                            onAllBuilds = onAllBuilds,
                            onRecentActions = { localId -> actionsFor = localId },
                        )
                    }
                    Spacer(Modifier.height(TalentForgeSpacing.group))
                }
                // New build, pinned in the thumb zone (B+): the PRIMARY action of
                // the empty state, a neutral secondary once builds exist.
                // Loading/Failed keep the secondary so the pinned slot never
                // changes identity mid-session.
                if (shown is LibraryData.Ready && shown.value.continueCard == null) {
                    PrimaryActionButton(
                        text = stringResource(Res.string.common_new_build),
                        onClickLabel = stringResource(Res.string.common_new_build_action),
                        onClick = onNewBuild,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = TalentForgeSpacing.screenX,
                                end = TalentForgeSpacing.screenX,
                            ),
                    )
                } else {
                    SecondaryActionButton(
                        text = stringResource(Res.string.common_new_build),
                        onClickLabel = stringResource(Res.string.common_new_build_action),
                        onClick = onNewBuild,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = TalentForgeSpacing.screenX,
                                end = TalentForgeSpacing.screenX,
                            ),
                    )
                }
                ImportBuildAction(
                    onClick = onImportBuild,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = TalentForgeSpacing.screenX,
                            end = TalentForgeSpacing.screenX,
                            bottom = TalentForgeSpacing.hairline,
                        ),
                )
            }
        }

        // ------------------------------------------- M5.SHARE.2 Recent-row actions
        val cards = (shown as? LibraryData.Ready<HomeModel>)?.value?.let { listOfNotNull(it.continueCard) + it.recentCards }
        val sheetCard = actionsFor?.let { id -> cards?.firstOrNull { it.record.localId == id } }
        if (sheetCard != null) {
            BuildRowActionsSheet(
                buildTitle = buildCardTitle(sheetCard),
                busy = actions.busy,
                onDismiss = { actionsFor = null },
                onRename = {
                    actionsFor = null
                    // a Draft starts with an empty field
                    actions.startRename(sheetCard.record.localId, sheetCard.record.name)
                },
                onDuplicate = null,
                onShare = { actions.sharePersisted(sheetCard.record.localId) { actionsFor = null } },
                onDelete = {
                    actionsFor = null
                    actions.startDelete(sheetCard.record.localId)
                },
            )
        }
        BuildActionsOverlays(
            presenter = actions,
            sharing = sharing,
            titleOf = { localId -> cards?.firstOrNull { it.record.localId == localId }?.let { buildCardTitle(it) }.orEmpty() },
        )
    }
}

@Composable
private fun HomeContent(
    model: HomeModel,
    clockMillis: () -> Long,
    emptyCopyTopGap: Dp,
    onOpenBuild: (String) -> Unit,
    onAllBuilds: () -> Unit,
    onRecentActions: (String) -> Unit,
) {
    val continueCard = model.continueCard
    if (continueCard == null) {
        NewUserContent(emptyCopyTopGap = emptyCopyTopGap)
        return
    }
    SectionLabel(stringResource(Res.string.home_section_continue))
    ContinueCard(card = continueCard, clockMillis = clockMillis, onOpen = onOpenBuild)

    if (model.recentCards.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(stringResource(Res.string.home_section_recent))
            Spacer(Modifier.weight(1f))
            AllBuildsLink(count = model.totalBuilds, onClick = onAllBuilds)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // UX spec H: 3 recent builds on a 360 dp phone, 4 at 412 dp+.
            val recentLimit = if (maxWidth.value >= 412f) 4 else 3
            Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
                for (card in model.recentCards.take(recentLimit)) {
                    RecentBuildRow(
                        card = card,
                        clockMillis = clockMillis,
                        onClick = { onOpenBuild(card.record.localId) },
                        onActions = { onRecentActions(card.record.localId) },
                    )
                }
            }
        }
    } else {
        // B+: with exactly one build there is no RECENT section to host the
        // link — All Builds still must stay reachable, so it renders as its
        // own end-aligned row after the Continue card.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            AllBuildsLink(count = model.totalBuilds, onClick = onAllBuilds)
        }
    }
}

/**
 * B+ empty state: typography only — the heading, one workflow sentence and
 * the real bundled data version; the single primary `New build` action is
 * the pinned bottom button. The copy block starts in the upper-middle of
 * the usable content ([emptyCopyTopGap], derived from the screen height),
 * below the brand header — no illustration, no decoration.
 */
@Composable
private fun NewUserContent(emptyCopyTopGap: Dp) {
    val colors = talentForgeColors()
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap)) {
        Spacer(Modifier.height(emptyCopyTopGap))
        BasicText(
            stringResource(Res.string.home_empty_title),
            style = TalentForgeTypography.title.copy(color = colors.textPrimary),
        )
        BasicText(
            stringResource(Res.string.home_empty_body),
            style = TalentForgeTypography.body.copy(color = colors.textSecondary),
        )
        BasicText(
            stringResource(
                Res.string.home_empty_data_version,
                gameDisplayName(PRODUCT_BUNDLE.game),
                dataVersionOf(PRODUCT_BUNDLE.clientBuild),
            ),
            style = TalentForgeTypography.meta.copy(color = colors.textMuted),
        )
    }
}

/**
 * The temporary brand mark: outlined diamond with an accent core + wordmark
 * (spec B), and (M5.RC.2b) the low-emphasis `About` link at the row's end — a
 * 48 dp text target, the only way to the About screen (no persistent chrome).
 */
@Composable
private fun BrandHeader(onAbout: () -> Unit) {
    val colors = talentForgeColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
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
        Spacer(Modifier.weight(1f))
        BasicText(
            text = stringResource(Res.string.home_about),
            style = HomeLinkStyle.copy(color = colors.textSecondary),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(Res.string.home_about_action),
                    onClick = onAbout,
                )
                .padding(horizontal = TalentForgeSpacing.inline, vertical = 16.dp),
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

/**
 * B.1/B+: the strongest element — the most recently edited build. The B+
 * card: class artwork (44 dp tile), title, `class · version · recency`
 * metadata, the engine-computed spent/budget over a simple progress bar,
 * and the primary `Continue editing` action.
 */
@Composable
private fun ContinueCard(
    card: BuildCardModel,
    clockMillis: () -> Long,
    onOpen: (String) -> Unit,
) {
    val colors = talentForgeColors()
    val title = buildCardTitle(card)
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
            ClassArtworkMark(
                classToken = card.record.build.classToken,
                slot = ClassArtworkSlot.Continue,
                monogram = classMonogramOf(card.classDisplayName ?: card.record.build.classToken.value),
                accent = classAccentFor(card.record.build.classToken).color,
            )
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = cardMetaLine(card, clockMillis),
                    style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (card.spentPoints != null && card.pointBudget != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
            ) {
                PointsProgress(
                    spent = card.spentPoints,
                    budget = card.pointBudget,
                    // the bar must not squeeze the numeric column
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    text = "${card.spentPoints} / ${card.pointBudget}",
                    style = HomePointsStyle.copy(color = colors.textPrimary),
                )
            }
        }
        PrimaryActionButton(
            text = stringResource(Res.string.home_continue_editing),
            onClickLabel = stringResource(Res.string.home_continue_editing_action, title),
            onClick = { onOpen(card.record.localId) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The B+ "simple progress bar": a 4 dp track on the interactive surface with
 * the primary amber fill of `spent / budget`. Non-interactive by design —
 * the editor, not Home, manipulates points.
 */
@Composable
private fun PointsProgress(
    spent: Int,
    budget: Int,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val fraction = if (budget <= 0) 0f else (spent.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
    val barShape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .height(4.dp)
            .background(colors.surfaceInteractive, barShape),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(colors.primaryAction, barShape),
        )
    }
}

/**
 * Compact recent-build row: artwork, title, `spent / budget · time`, and
 * (M5.SHARE.2) the 48 dp `⋯` actions button — a sibling of the row's own
 * click target, so pressing it never opens the build.
 */
@Composable
private fun RecentBuildRow(
    card: BuildCardModel,
    clockMillis: () -> Long,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    val colors = talentForgeColors()
    val title = buildCardTitle(card)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(Res.string.common_open_build_action, title),
                onClick = onClick,
            )
            .padding(vertical = TalentForgeSpacing.hairline),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        ClassArtworkMark(
            classToken = card.record.build.classToken,
            slot = ClassArtworkSlot.Row,
            monogram = classMonogramOf(card.classDisplayName ?: card.record.build.classToken.value),
            accent = classAccentFor(card.record.build.classToken).color,
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = recentMetaLine(card, clockMillis),
                style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowActionsButton(title = title, onClick = onActions)
    }
}

/**
 * M5.SHARE.2: the low-emphasis `Import build` text action under the pinned
 * New build — a 48 dp target whose label wraps (never clips) at large text.
 */
@Composable
private fun ImportBuildAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(Res.string.import_build_action),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(Res.string.import_build),
            style = HomeImportStyle.copy(color = talentForgeColors().textSecondary),
            modifier = Modifier.padding(horizontal = TalentForgeSpacing.inline, vertical = TalentForgeSpacing.hairline),
        )
    }
}

/** `All builds 6` link of the RECENT header row (or its own row at one build). */
@Composable
private fun AllBuildsLink(count: Int, onClick: () -> Unit) {
    BasicText(
        text = stringResource(Res.string.home_all_builds_link, count),
        style = HomeLinkStyle.copy(color = talentForgeColors().textSecondary),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(Res.string.home_all_builds_action),
                onClick = onClick,
            )
            .padding(horizontal = TalentForgeSpacing.inline, vertical = TalentForgeSpacing.hairline),
    )
}

/** `Warrior · Forever · 2 h ago` — only fields the card actually resolved. */
@Composable
private fun cardMetaLine(card: BuildCardModel, clockMillis: () -> Long): String =
    listOfNotNull(
        localizedClassName(card.record.build.classToken, card.classDisplayName) ?: card.record.build.classToken.value,
        gameDisplayName(card.record.build.game),
        relativeBuildTime(clockMillis(), card.record.updatedAt),
    ).joinToString(" · ")

/** `20 / 51 · Yesterday` (the spec's split-count slot holds the single-tree total). */
@Composable
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

private val HomeImportStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
)

private val HomeLinkStyle = TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
)

/**
 * The B+ empty state's copy gap: ~16 % of the screen height below the brand
 * header puts the heading in the upper-middle of the usable content (the
 * area above the pinned CTA) on every supported phone height.
 */
private const val EMPTY_COPY_TOP_GAP_FRACTION = 0.16f
