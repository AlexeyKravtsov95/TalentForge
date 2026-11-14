package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_copy_name_suffix
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_duplicate_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_duplicated
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_empty_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_empty_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_favorite_add
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_favorite_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_favorite_pending_save_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_favorite_remove
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_favorites_empty_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_favorites_empty_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_filter_empty_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_filter_empty_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_filter_reset
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_group_header
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_results_count
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_row_actions_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_row_actions_description
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_search_empty_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_search_empty_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.builds_title_count
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_new_build
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_new_build_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_open_build_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_retry
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_build
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_build_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_load_failed_body
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_load_failed_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.library_loading
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.BuildCardModel
import io.github.alexeykravtsov95.talentforge.ui.library.BuildFavoriteOutcome
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkMark
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkSlot
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNoticeTone
import io.github.alexeykravtsov95.talentforge.ui.library.LibraryData
import io.github.alexeykravtsov95.talentforge.ui.library.PrimaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.SecondaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.buildCardTitle
import io.github.alexeykravtsov95.talentforge.ui.library.classMonogramOf
import io.github.alexeykravtsov95.talentforge.ui.library.gameDisplayName
import io.github.alexeykravtsov95.talentforge.ui.library.loadBuildsModel
import io.github.alexeykravtsov95.talentforge.ui.library.localizedLibraryClassName
import io.github.alexeykravtsov95.talentforge.ui.library.localizedClassName
import io.github.alexeykravtsov95.talentforge.ui.library.relativeBuildTime
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.share.BuildCodeSharing
import io.github.alexeykravtsov95.talentforge.ui.share.text
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.BackChevronButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Which management overlay (M5.2a) is open, and for which record. The overlay
 * targets a record by its opaque `localId` — never by list position — so a
 * refresh underneath cannot redirect an action to the wrong build.
 */
private sealed interface ManagementOverlay {
    data object Hidden : ManagementOverlay

    data class Actions(val localId: String) : ManagementOverlay
}

/** The screen-level notice strip: duplicate success (auto-dismisses) or retryable duplicate failure. */
private sealed interface ManagementNotice {
    data object None : ManagementNotice

    /** `id` changes per event so the auto-dismiss timer restarts on repeated duplicates. */
    data class Duplicated(val id: Int) : ManagementNotice

    data class DuplicateFailed(val localId: String) : ManagementNotice

    data class FavoriteFailed(val localId: String, val value: Boolean, val pendingSave: Boolean) : ManagementNotice
}

/**
 * All Builds — the Saved Builds list (spec D.1) and, since M5.2a, the full
 * management surface (M5.SHARE.2: Home's Recent rows offer Rename, Share and
 * Delete through the same presenter): every row ends in a `⋯` button opening the
 * Rename / Duplicate / Delete sheet. Tap on the row still opens the build;
 * tap on `⋯` never does. Management operations run off the main thread, one
 * at a time (repeated presses are ignored while one is in flight), and every
 * success reloads the list from the repository — the screen never keeps a
 * second copy of the library as its source of truth. Rename/delete failures
 * keep their dialog open with a retryable error; a failed duplicate shows a
 * retryable notice. The list count, order and empty state all come from the
 * reload.
 *
 * M5.SHARE.2: Rename, `Share` and Delete in the row sheet go through the
 * app's one [BuildActionsPresenter] (also used by Home's Recent rows; the
 * editor uses its Rename only — never Delete): Share reads the
 * LATEST persisted build of exactly that `localId` at the tap (`load`, never
 * `open`, so no timestamp, name or order changes) and shows its code in the
 * export sheet; a read failure or a vanished record is a retryable notice.
 * The presenter's single-flight guard also runs Duplicate and Delete, so no
 * two management operations ever overlap.
 * `Import build` (also in the empty state) opens the shared import flow.
 */
@Composable
fun AllBuildsScreen(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    clockMillis: () -> Long,
    onBack: () -> Unit,
    onOpenBuild: (String) -> Unit,
    onNewBuild: () -> Unit,
    onImportBuild: () -> Unit,
    sharing: BuildCodeSharing,
    autosave: BuildAutosaveCoordinator,
) {
    var data by remember { mutableStateOf<LibraryData<List<BuildCardModel>>>(LibraryData.Loading) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var sortModeValue by rememberSaveable { mutableStateOf(BuildLibrarySortMode.LAST_EDITED.name) }
    var groupByClass by rememberSaveable { mutableStateOf(false) }
    val sortMode = BuildLibrarySortMode.entries.firstOrNull { it.name == sortModeValue }
        ?: BuildLibrarySortMode.LAST_EDITED
    var query by rememberSaveable { mutableStateOf("") }
    var selectedClassValue by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedClass = selectedClassValue?.let(::ClassToken)
    var reloadToken by remember { mutableStateOf(0) }
    LaunchedEffect(reloadToken) {
        data = LibraryData.Loading
        data = withContext(Dispatchers.Default) { loadBuildsModel(repository, datasets) }
    }

    var overlay by remember { mutableStateOf<ManagementOverlay>(ManagementOverlay.Hidden) }
    var notice by remember { mutableStateOf<ManagementNotice>(ManagementNotice.None) }
    var noticeEvent by remember { mutableStateOf(0) }
    val actions = rememberBuildActionsPresenter(
        autosave = autosave,
        repository = repository,
        onRenamed = { reloadToken += 1 },
        onRecordMissing = { reloadToken += 1 },
        onDeleted = { reloadToken += 1 },
    )
    val operationInFlight = actions.busy

    LaunchedEffect(notice) {
        if (notice is ManagementNotice.Duplicated) {
            delay(BuildManagementMetrics.SUCCESS_NOTICE_MS.toLong())
            if (notice is ManagementNotice.Duplicated) notice = ManagementNotice.None
        }
    }

    /** One management operation at a time; repeated presses are ignored while one runs. */
    fun launchManagement(action: suspend () -> Unit) {
        actions.launchExclusive(action)
    }

    /**
     * System Back closes the open modal first (the sheet or a dialog — same
     * as Cancel: never a write), and only a Back with NO modal open reaches
     * the app's navigation handler and returns to Home. Registered inside
     * this screen — i.e. AFTER the app-level handler — so Android's
     * newest-enabled-handler rule gives it precedence while a modal is open.
     * While an operation is in flight the handler stays ENABLED but does
     * nothing: Back is consumed, yet neither navigates nor cancels.
     */
    PlatformBackHandler(
        enabled = overlay != ManagementOverlay.Hidden || operationInFlight,
        onBack = {
            if (!operationInFlight) overlay = ManagementOverlay.Hidden
        },
    )

    // TF-RC1-COPY-NAME: a named copy gets the suffix of the CURRENT app
    // language; an unnamed copy stays unnamed (the repository persists none)
    val copyNameSuffix = stringResource(Res.string.builds_copy_name_suffix)

    fun duplicate(localId: String) {
        launchManagement {
            when (withContext(Dispatchers.Default) { repository.duplicateBuild(localId, copyNameSuffix) }) {
                is BuildLibraryOutcome.Loaded -> {
                    overlay = ManagementOverlay.Hidden
                    noticeEvent += 1
                    notice = ManagementNotice.Duplicated(noticeEvent)
                    reloadToken += 1
                }
                is BuildLibraryOutcome.Failed -> {
                    // close the sheet first: the retryable notice must be the
                    // visible answer, never text trapped under the sheet's scrim
                    overlay = ManagementOverlay.Hidden
                    notice = ManagementNotice.DuplicateFailed(localId)
                }
            }
        }
    }

    fun setFavorite(localId: String, value: Boolean) {
        launchManagement {
            when (val outcome = autosave.setFavorite(localId, value)) {
                is BuildFavoriteOutcome.Saved -> {
                    notice = ManagementNotice.None
                    reloadToken += 1
                }
                BuildFavoriteOutcome.PendingSaveFailed -> notice = ManagementNotice.FavoriteFailed(localId, value, pendingSave = true)
                is BuildFavoriteOutcome.Failed -> notice = ManagementNotice.FavoriteFailed(localId, value, pendingSave = false)
            }
        }
    }

    // like a failed duplicate, the sheet closes either way: the share sheet or
    // the retryable notice is the visible answer
    fun share(localId: String) {
        actions.sharePersisted(localId) { overlay = ManagementOverlay.Hidden }
    }

    val colors = talentForgeColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
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
                BackChevronButton(onClick = onBack, enabled = !operationInFlight)
                BasicText(
                    text = when (val current = data) {
                        is LibraryData.Ready -> stringResource(Res.string.builds_title_count, current.value.size)
                        else -> stringResource(Res.string.builds_title)
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
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(Res.string.common_new_build_action),
                            enabled = !operationInFlight,
                            onClick = onNewBuild,
                        ),
                )
            }
            actions.shareFailure?.let { failure ->
                InlineNotice(
                    text = failure.failure.text(),
                    actionText = stringResource(Res.string.common_retry),
                    onAction = { share(failure.localId) },
                )
            }
            when (val current = notice) {
                is ManagementNotice.Duplicated -> InlineNotice(
                    text = stringResource(Res.string.builds_duplicated),
                    actionText = null,
                    onAction = null,
                    tone = InlineNoticeTone.Positive,
                )

                is ManagementNotice.DuplicateFailed -> InlineNotice(
                    text = stringResource(Res.string.builds_duplicate_failed),
                    actionText = stringResource(Res.string.common_retry),
                    onAction = { duplicate(current.localId) },
                )

                is ManagementNotice.FavoriteFailed -> InlineNotice(
                    text = stringResource(if (current.pendingSave) Res.string.builds_favorite_pending_save_failed else Res.string.builds_favorite_failed),
                    actionText = stringResource(Res.string.common_retry),
                    onAction = { setFavorite(current.localId, current.value) },
                )

                ManagementNotice.None -> {}
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

                    is LibraryData.Ready -> if (current.value.isEmpty()) {
                        // D.6 empty: one line, one hint, one action.
                        Spacer(Modifier.height(TalentForgeSpacing.group))
                        BasicText(
                            stringResource(Res.string.builds_empty_title),
                            style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                        )
                        BasicText(
                            stringResource(Res.string.builds_empty_body),
                            style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                        )
                        PrimaryActionButton(
                            text = stringResource(Res.string.common_new_build),
                            onClickLabel = stringResource(Res.string.common_new_build_action),
                            enabled = !operationInFlight,
                            onClick = onNewBuild,
                        )
                        ImportBuildButton(onClick = onImportBuild, enabled = !operationInFlight)
                    } else {
                        BuildLibraryFilters(
                            query = query,
                            onQueryChange = { query = it },
                            selectedClass = selectedClass,
                            onClassChange = { selectedClassValue = it?.value },
                            favoritesOnly = favoritesOnly,
                            onFavoritesChange = { favoritesOnly = it },
                        )
                        BuildLibraryOrderingControl(
                            sortMode = sortMode,
                            onSortModeChange = { sortModeValue = it.name },
                            groupByClass = groupByClass,
                            onGroupByClassChange = { groupByClass = it },
                        )
                        val entries = current.value.map { card ->
                            BuildLibrarySearchEntry(
                                card,
                                buildCardTitle(card),
                                localizedLibraryClassName(card.record.build.classToken, card.classDisplayName),
                            )
                        }
                        val results = sortBuildLibrary(filterBuildLibrary(entries, query, selectedClass, favoritesOnly), sortMode)
                        BasicText(
                            stringResource(Res.string.builds_results_count, results.size, entries.size),
                            style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        if (results.isEmpty()) {
                            val noFavorites = favoritesOnly && entries.none { it.card.record.isFavorite }
                            val emptyTitle = when {
                                noFavorites -> Res.string.builds_favorites_empty_title
                                query.isNotBlank() -> Res.string.builds_search_empty_title
                                else -> Res.string.builds_filter_empty_title
                            }
                            val emptyBody = when {
                                noFavorites -> Res.string.builds_favorites_empty_body
                                query.isNotBlank() -> Res.string.builds_search_empty_body
                                else -> Res.string.builds_filter_empty_body
                            }
                            BasicText(
                                stringResource(emptyTitle),
                                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                            )
                            BasicText(
                                stringResource(emptyBody),
                                style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                            )
                            SecondaryActionButton(
                                text = stringResource(Res.string.builds_filter_reset),
                                onClickLabel = stringResource(Res.string.builds_filter_reset),
                                onClick = {
                                    query = ""
                                    selectedClassValue = null
                                    favoritesOnly = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        val groups = if (groupByClass) groupBuildLibrary(results, sortMode) else emptyList()
                        if (groupByClass) {
                            for (group in groups) {
                                BasicText(
                                    text = stringResource(Res.string.builds_group_header, group.className, group.entries.size),
                                    style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary, fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                                )
                                for (entry in group.entries) {
                                    val card = entry.card
                                    key(card.record.localId) {
                                        BuildRow(
                                            card = card,
                                            clockMillis = clockMillis,
                                            onClick = { onOpenBuild(card.record.localId) },
                                            onActions = { overlay = ManagementOverlay.Actions(card.record.localId) },
                                            onFavoriteChange = { setFavorite(card.record.localId, it) },
                                            busy = operationInFlight,
                                        )
                                    }
                                }
                            }
                        } else {
                            for (entry in results) {
                                val card = entry.card
                                key(card.record.localId) {
                                    BuildRow(
                                        card = card,
                                        clockMillis = clockMillis,
                                        onClick = { onOpenBuild(card.record.localId) },
                                        onActions = { overlay = ManagementOverlay.Actions(card.record.localId) },
                                        onFavoriteChange = { setFavorite(card.record.localId, it) },
                                        busy = operationInFlight,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(TalentForgeSpacing.inline))
                        ImportBuildButton(onClick = onImportBuild, enabled = !operationInFlight)
                    }
                }
                Spacer(Modifier.height(TalentForgeSpacing.group))
            }
        }

        // -------------------------------------------------- M5.2a management overlays
        val readyCards = (data as? LibraryData.Ready<List<BuildCardModel>>)?.value
        when (val current = overlay) {
            ManagementOverlay.Hidden -> {}

            is ManagementOverlay.Actions -> {
                val card = readyCards?.firstOrNull { it.record.localId == current.localId }
                if (card != null) {
                    BuildRowActionsSheet(
                        buildTitle = buildCardTitle(card),
                        busy = operationInFlight,
                        onDismiss = { overlay = ManagementOverlay.Hidden },
                        onRename = {
                            overlay = ManagementOverlay.Hidden
                            // a Draft starts with an empty field
                            actions.startRename(current.localId, card.record.name)
                        },
                        onDuplicate = { duplicate(current.localId) },
                        onShare = { share(current.localId) },
                        onDelete = {
                            overlay = ManagementOverlay.Hidden
                            actions.startDelete(current.localId)
                        },
                    )
                }
            }
        }
        BuildActionsOverlays(
            presenter = actions,
            sharing = sharing,
            titleOf = { localId ->
                readyCards?.firstOrNull { it.record.localId == localId }?.let { buildCardTitle(it) }.orEmpty()
            },
        )
    }
}

/** M5.SHARE.2: `Import build`, a full-width secondary action after the list (and in the empty state). */
@Composable
private fun ImportBuildButton(onClick: () -> Unit, enabled: Boolean) {
    SecondaryActionButton(
        text = stringResource(Res.string.import_build),
        onClickLabel = stringResource(Res.string.import_build_action),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * D.1 row: name, `class · version · recency`, the spent/budget column and the `⋯` management button.
 *
 * Responsive arrangement (PR #22 review): the artwork tile, the points
 * column and the 48 dp `⋯` button are fixed widths, so at large font scales
 * on narrow screens the full arrangement would crowd the build name into
 * unreadability. The row measures the WIDEST points label at the current
 * font scale and, when the title column would fall below
 * [MinBuildTitleWidth], switches to the compact arrangement: the points
 * become their OWN single-line element on the metadata row — measured at
 * natural width, never ellipsized — and the class/version/time meta line
 * takes what remains and ellipsizes where space does not permit. Layout
 * priority is exactly the review's: build name, spent/total points, then
 * class · version · recency. The artwork, the 48 dp actions target and the
 * click-to-open row are identical in both arrangements.
 */
@Composable
private fun BuildRow(
    card: BuildCardModel,
    clockMillis: () -> Long,
    onClick: () -> Unit,
    onActions: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    busy: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = isCompactRow(hasPoints = card.spentPoints != null && card.pointBudget != null)
        BuildRowContent(
            card = card,
            clockMillis = clockMillis,
            compact = compact,
            onClick = onClick,
            onActions = onActions,
            onFavoriteChange = onFavoriteChange,
            busy = busy,
        )
    }
}

/** True when the full row arrangement would starve the title column. */
@Composable
private fun BoxWithConstraintsScope.isCompactRow(hasPoints: Boolean): Boolean {
    if (!hasPoints) return false
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // the widest possible label ("51 / 51") measured at the CURRENT font scale
    val pointsWidth = with(density) {
        textMeasurer.measure("51 / 51", BuildsPointsStyle).size.width.toDp()
    }
    val fixedWidth = ClassArtworkSlot.Row.size + TalentForgeSpacing.inline + // artwork + gap
        pointsWidth + TalentForgeSpacing.inline + RowActionsButtonSize // points + gap + ⋯
    return maxWidth - fixedWidth < MinBuildTitleWidth
}

@Composable
private fun BuildRowContent(
    card: BuildCardModel,
    clockMillis: () -> Long,
    compact: Boolean,
    onClick: () -> Unit,
    onActions: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    busy: Boolean,
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
                enabled = !busy,
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (compact) {
                // the review's priority: the points are their OWN element,
                // measured at natural width and never ellipsized; the meta
                // line takes what remains and yields first
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                ) {
                    BasicText(
                        text = "${card.spentPoints} / ${card.pointBudget}",
                        style = BuildsPointsStyle.copy(color = colors.textPrimary),
                        maxLines = 1,
                        softWrap = false,
                    )
                    BasicText(
                        text = rowMetaLine(card, clockMillis),
                        style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                BasicText(
                    text = rowMetaLine(card, clockMillis),
                    style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (!compact && card.spentPoints != null && card.pointBudget != null) {
            BasicText(
                text = "${card.spentPoints} / ${card.pointBudget}",
                style = BuildsPointsStyle.copy(color = colors.textPrimary),
            )
        }
        Column {
            val favoriteDescription = stringResource(
                if (card.record.isFavorite) Res.string.builds_favorite_remove else Res.string.builds_favorite_add,
                title,
            )
            Box(
                Modifier.size(RowActionsButtonSize)
                    .semantics { contentDescription = favoriteDescription }
                    .toggleable(value = card.record.isFavorite, enabled = !busy, role = Role.Checkbox,
                        onValueChange = onFavoriteChange,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(if (card.record.isFavorite) "★" else "☆",
                    style = BuildsNewButtonStyle.copy(color = colors.textPrimary),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            RowActionsButton(title = title, onClick = onActions, enabled = !busy)
        }
    }
}

/** `Warrior · Forever · Just now` — only resolved fields, in fixed order. */
@Composable
private fun rowMetaLine(
    card: BuildCardModel,
    clockMillis: () -> Long,
): String = listOfNotNull(
    localizedClassName(card.record.build.classToken, card.classDisplayName) ?: card.record.build.classToken.value,
    gameDisplayName(card.record.build.game),
    relativeBuildTime(clockMillis(), card.record.updatedAt),
).joinToString(" · ")

/**
 * The row's `⋯` button — its own 48 dp click target, so pressing it opens the
 * management sheet and NEVER the build (the button's click target is a
 * sibling of the row's, not nested inside it). M5.SHARE.2: Home's Recent
 * rows use the same button.
 */
@Composable
internal fun RowActionsButton(title: String, onClick: () -> Unit, enabled: Boolean = true) {
    val description = stringResource(Res.string.builds_row_actions_description, title)
    Box(
        modifier = Modifier
            .size(RowActionsButtonSize)
            .semantics { contentDescription = description }
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(Res.string.builds_row_actions_action, title),
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "⋯",
            style = BuildsNewButtonStyle.copy(color = talentForgeColors().textPrimary),
        )
    }
}

/** The `⋯` button's own touch target width. */
private val RowActionsButtonSize = 48.dp

/**
 * The narrowest build-title column the full row arrangement may leave
 * readable (≈ 8 characters of ComponentTitle at 100 %) before the points
 * fold into the meta line.
 */
private val MinBuildTitleWidth = 112.dp

/** UX spec G numeric mono 17 · 600 — `internal` so the layout test measures the same style. */
internal val BuildsPointsStyle = androidx.compose.ui.text.TextStyle(
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
