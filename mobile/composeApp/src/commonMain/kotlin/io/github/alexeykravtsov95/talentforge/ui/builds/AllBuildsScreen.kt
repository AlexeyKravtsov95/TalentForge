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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.BuildCardModel
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkMark
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkSlot
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNoticeTone
import io.github.alexeykravtsov95.talentforge.ui.library.LibraryData
import io.github.alexeykravtsov95.talentforge.ui.library.PrimaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.classMonogramOf
import io.github.alexeykravtsov95.talentforge.ui.library.gameDisplayName
import io.github.alexeykravtsov95.talentforge.ui.library.loadBuildsModel
import io.github.alexeykravtsov95.talentforge.ui.library.relativeBuildTime
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.BackChevronButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which management overlay (M5.2a) is open, and for which record. The overlay
 * targets a record by its opaque `localId` — never by list position — so a
 * refresh underneath cannot redirect an action to the wrong build.
 */
private sealed interface ManagementOverlay {
    data object Hidden : ManagementOverlay

    data class Actions(val localId: String) : ManagementOverlay

    data class Rename(val localId: String) : ManagementOverlay

    data class DeleteConfirm(val localId: String) : ManagementOverlay
}

/** The screen-level notice strip: duplicate success (auto-dismisses) or retryable duplicate failure. */
private sealed interface ManagementNotice {
    data object None : ManagementNotice

    /** `id` changes per event so the auto-dismiss timer restarts on repeated duplicates. */
    data class Duplicated(val id: Int) : ManagementNotice

    data class DuplicateFailed(val localId: String) : ManagementNotice
}

/**
 * All Builds — the Saved Builds list (spec D.1) and, since M5.2a, the ONE
 * place builds are managed: every row ends in a `⋯` button opening the
 * Rename / Duplicate / Delete sheet. Tap on the row still opens the build;
 * tap on `⋯` never does. Management operations run off the main thread, one
 * at a time (repeated presses are ignored while one is in flight), and every
 * success reloads the list from the repository — the screen never keeps a
 * second copy of the library as its source of truth. Rename/delete failures
 * keep their dialog open with a retryable error; a failed duplicate shows a
 * retryable notice. The list count, order and empty state all come from the
 * reload.
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

    val scope = rememberCoroutineScope()
    var overlay by remember { mutableStateOf<ManagementOverlay>(ManagementOverlay.Hidden) }
    var renameText by remember { mutableStateOf("") }
    var overlayError by remember { mutableStateOf(false) }
    var operationInFlight by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ManagementNotice>(ManagementNotice.None) }
    var noticeEvent by remember { mutableStateOf(0) }

    LaunchedEffect(notice) {
        if (notice is ManagementNotice.Duplicated) {
            delay(BuildManagementMetrics.SUCCESS_NOTICE_MS.toLong())
            if (notice is ManagementNotice.Duplicated) notice = ManagementNotice.None
        }
    }

    /** One management operation at a time; repeated presses are ignored while one runs. */
    fun launchManagement(action: suspend () -> Unit) {
        if (operationInFlight) return
        operationInFlight = true
        scope.launch {
            try {
                action()
            } finally {
                operationInFlight = false
            }
        }
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
        enabled = overlay != ManagementOverlay.Hidden,
        onBack = {
            if (!operationInFlight) overlay = ManagementOverlay.Hidden
        },
    )

    fun rename(localId: String, newName: String) {
        launchManagement {
            when (withContext(Dispatchers.Default) { repository.renameBuild(localId, newName) }) {
                is BuildLibraryOutcome.Loaded -> {
                    overlay = ManagementOverlay.Hidden
                    overlayError = false
                    reloadToken += 1
                }
                is BuildLibraryOutcome.Failed -> overlayError = true
            }
        }
    }

    fun duplicate(localId: String) {
        launchManagement {
            when (withContext(Dispatchers.Default) { repository.duplicateBuild(localId) }) {
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

    fun delete(localId: String) {
        launchManagement {
            when (withContext(Dispatchers.Default) { repository.deleteBuild(localId) }) {
                is BuildLibraryOutcome.Loaded -> {
                    overlay = ManagementOverlay.Hidden
                    overlayError = false
                    reloadToken += 1
                }
                is BuildLibraryOutcome.Failed -> overlayError = true
            }
        }
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
            when (val current = notice) {
                is ManagementNotice.Duplicated -> InlineNotice(
                    text = "Build duplicated",
                    actionText = null,
                    onAction = null,
                    tone = InlineNoticeTone.Positive,
                )

                is ManagementNotice.DuplicateFailed -> InlineNotice(
                    text = "Couldn't duplicate the build",
                    actionText = "Retry",
                    onAction = { duplicate(current.localId) },
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
                            text = "New build",
                            onClickLabel = "start a new build",
                            onClick = onNewBuild,
                        )
                    } else {
                        for (card in current.value) {
                            BuildRow(
                                card = card,
                                clockMillis = clockMillis,
                                onClick = { onOpenBuild(card.record.localId) },
                                onActions = { overlay = ManagementOverlay.Actions(card.record.localId) },
                            )
                        }
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
                        buildTitle = card.title,
                        busy = operationInFlight,
                        onDismiss = { overlay = ManagementOverlay.Hidden },
                        onRename = {
                            renameText = card.record.name.orEmpty() // a Draft starts with an empty field
                            overlayError = false
                            overlay = ManagementOverlay.Rename(current.localId)
                        },
                        onDuplicate = { duplicate(current.localId) },
                        onDelete = {
                            overlayError = false
                            overlay = ManagementOverlay.DeleteConfirm(current.localId)
                        },
                    )
                }
            }

            is ManagementOverlay.Rename -> RenameBuildDialog(
                text = renameText,
                onTextChange = {
                    renameText = it
                    overlayError = false
                },
                maxLength = LocalBuildRepository.MAX_BUILD_NAME_LENGTH,
                busy = operationInFlight,
                error = overlayError,
                onSave = { rename(current.localId, renameText) },
                onCancel = { overlay = ManagementOverlay.Hidden },
            )

            is ManagementOverlay.DeleteConfirm -> {
                val card = readyCards?.firstOrNull { it.record.localId == current.localId }
                if (card != null) {
                    DeleteBuildDialog(
                        buildTitle = card.title,
                        busy = operationInFlight,
                        error = overlayError,
                        onDelete = { delete(current.localId) },
                        onCancel = { overlay = ManagementOverlay.Hidden },
                    )
                }
            }
        }
    }
}

/**
 * D.1 row: name, `class · version · recency`, the spent/budget column and the `⋯` management button.
 *
 * Responsive arrangement (PR #22 review): the artwork tile, the points
 * column and the 48 dp `⋯` button are fixed widths, so at large font scales
 * on narrow screens the full arrangement would crowd the build name into
 * unreadability. The row measures the WIDEST points label at the current
 * font scale and, when the title column would fall below
 * [MinBuildTitleWidth], folds the points into the meta line — the freed
 * width goes to the title while the artwork, the points FACT and the 48 dp
 * actions target are all preserved.
 */
@Composable
private fun BuildRow(
    card: BuildCardModel,
    clockMillis: () -> Long,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = isCompactRow(hasPoints = card.spentPoints != null && card.pointBudget != null)
        BuildRowContent(
            card = card,
            clockMillis = clockMillis,
            foldPointsIntoMeta = compact,
            onClick = onClick,
            onActions = onActions,
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
    foldPointsIntoMeta: Boolean,
    onClick: () -> Unit,
    onActions: () -> Unit,
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
        ClassArtworkMark(
            classToken = card.record.build.classToken,
            slot = ClassArtworkSlot.Row,
            monogram = classMonogramOf(card.classDisplayName ?: card.record.build.classToken.value),
            accent = classAccentFor(card.record.build.classToken).color,
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                text = card.title,
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            BasicText(
                text = rowMetaLine(card, clockMillis, includePoints = foldPointsIntoMeta),
                style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (!foldPointsIntoMeta && card.spentPoints != null && card.pointBudget != null) {
            BasicText(
                text = "${card.spentPoints} / ${card.pointBudget}",
                style = BuildsPointsStyle.copy(color = colors.textPrimary),
            )
        }
        RowActionsButton(title = card.title, onClick = onActions)
    }
}

/** `Warrior · Forever · [13 / 51 ·] Just now` — only resolved fields, in fixed order. */
private fun rowMetaLine(
    card: BuildCardModel,
    clockMillis: () -> Long,
    includePoints: Boolean,
): String = listOfNotNull(
    card.classDisplayName ?: card.record.build.classToken.value,
    gameDisplayName(card.record.build.game),
    if (includePoints && card.spentPoints != null && card.pointBudget != null) {
        "${card.spentPoints} / ${card.pointBudget}"
    } else {
        null
    },
    relativeBuildTime(clockMillis(), card.record.updatedAt),
).joinToString(" · ")

/**
 * The row's `⋯` button — its own 48 dp click target, so pressing it opens the
 * management sheet and NEVER the build (the button's click target is a
 * sibling of the row's, not nested inside it).
 */
@Composable
private fun RowActionsButton(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(RowActionsButtonSize)
            .semantics { contentDescription = "build actions for $title" }
            .clickable(role = Role.Button, onClickLabel = "manage $title", onClick = onClick),
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
