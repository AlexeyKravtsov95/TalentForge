package io.github.alexeykravtsov95.talentforge.ui.builds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildMigrationResult
import io.github.alexeykravtsov95.talentforge.allocation.MigrationLossReason
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_available
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_checking
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_patches
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_full
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_partial
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_transferred
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_lost
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_remaining
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_talent_change
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_talent_fallback
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_removed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_entry_changed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_rank_reduced
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_prerequisite
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_tier_gate
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_budget
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_unsupported
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_name
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_confirm
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_refresh
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_stale
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_save_pending
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_data_unavailable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_bad_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_bad_class
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_bad_tree
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_bad_source
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_bad_target
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_source_missing
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_target_missing
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_source_invalid
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_migration_target_invalid
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_cancel
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.appLanguage
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.describeLibraryError
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NewerBuildDataLabel() {
    BasicText(
        stringResource(Res.string.build_migration_available),
        style = TalentForgeTypography.meta.copy(color = talentForgeColors().warning),
    )
}

@Composable
internal fun BuildMigrationDialog(
    dialog: MigrationDialogState,
    name: String,
    busy: Boolean,
    onNameChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = talentForgeColors()
    val preview = dialog.preview
    val problem = dialog.problem
    val length = name.trim().length
    val maxLength = LocalBuildRepository.MAX_BUILD_NAME_LENGTH
    val shape = RoundedCornerShape(TalentForgeRadius.large)
    DialogScrim(
        busy = busy,
        onDismiss = onCancel,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
    ) {
        Column(
            Modifier.widthIn(max = 420.dp)
                .fillMaxWidth()
                .background(colors.surfaceElevated, shape)
                .border(1.dp, colors.borderDefault, shape)
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(TalentForgeSpacing.group),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
            ) {
                BasicText(
                    stringResource(Res.string.build_migration_title),
                    style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                )
                if (preview == null && problem == null) {
                    MigrationBodyText(stringResource(Res.string.build_migration_checking))
                }
                if (preview != null) {
                    MigrationPreviewContent(preview)
                    val label = stringResource(Res.string.build_migration_name)
                    BasicText(label, style = TalentForgeTypography.meta.copy(color = colors.textPrimary))
                    BasicTextField(
                        value = name,
                        onValueChange = onNameChange,
                        enabled = !busy,
                        singleLine = true,
                        textStyle = TalentForgeTypography.body.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.focus),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .background(colors.surfaceInteractive, RoundedCornerShape(TalentForgeRadius.medium))
                            .border(
                                1.dp,
                                if (length > maxLength) colors.danger else colors.borderStrong,
                                RoundedCornerShape(TalentForgeRadius.medium),
                            )
                            .padding(TalentForgeSpacing.inline)
                            .semantics { contentDescription = label },
                    )
                    MigrationBodyText("$length / $maxLength")
                }
                if (problem != null) {
                    BasicText(
                        problem.text(),
                        style = TalentForgeTypography.body.copy(color = colors.warning),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            DialogButtonRow {
                DialogTextButton(stringResource(Res.string.common_cancel), colors.textSecondary, !busy, onCancel)
                when {
                    preview != null -> DialogTextButton(
                        stringResource(Res.string.build_migration_confirm),
                        colors.focus,
                        !busy && length in 1..maxLength && problem != MigrationProblem.PendingSaveFailed,
                        onConfirm,
                    )
                    problem == MigrationProblem.Stale || problem == MigrationProblem.DatasetsUnavailable ->
                        DialogTextButton(stringResource(Res.string.build_migration_refresh), colors.focus, !busy, onRefresh)
                }
            }
        }
    }
}

@Composable
private fun MigrationPreviewContent(preview: MigrationPreview.Ready) {
    val plan = preview.plan
    MigrationBodyText(stringResource(Res.string.build_migration_patches, plan.source.clientBuild, plan.build.clientBuild))
    if (plan.losses.isEmpty()) {
        MigrationBodyText(stringResource(Res.string.build_migration_full))
        return
    }
    MigrationBodyText(stringResource(Res.string.build_migration_partial))
    MigrationBodyText(stringResource(Res.string.build_migration_transferred, plan.transferredPoints))
    MigrationBodyText(stringResource(Res.string.build_migration_lost, plan.lostPoints))
    MigrationBodyText(stringResource(Res.string.build_migration_remaining, plan.remainingPoints))
    val russian = appLanguage() == AppLanguage.RUSSIAN
    val colors = talentForgeColors()
    for (loss in plan.losses) {
        val localizedName = if (russian) preview.russianNames[loss.source.nodeId] else null
        val name = localizedName ?: loss.name ?: stringResource(Res.string.build_migration_talent_fallback, loss.source.nodeId)
        Column {
            BasicText(
                stringResource(Res.string.build_migration_talent_change, name, loss.source.rank, loss.transferredRank),
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
            )
            for (reason in loss.reasons) MigrationBodyText(reason.text())
        }
    }
}

@Composable
private fun MigrationBodyText(text: String) {
    BasicText(text, style = TalentForgeTypography.body.copy(color = talentForgeColors().textSecondary))
}

@Composable
private fun MigrationLossReason.text(): String = stringResource(when (this) {
    MigrationLossReason.TALENT_REMOVED -> Res.string.build_migration_removed
    MigrationLossReason.ENTRY_CHANGED -> Res.string.build_migration_entry_changed
    MigrationLossReason.RANK_REDUCED -> Res.string.build_migration_rank_reduced
    MigrationLossReason.PREREQUISITE -> Res.string.build_migration_prerequisite
    MigrationLossReason.TIER_GATE -> Res.string.build_migration_tier_gate
    MigrationLossReason.POINT_BUDGET -> Res.string.build_migration_budget
    MigrationLossReason.UNSUPPORTED_RULE -> Res.string.build_migration_unsupported
})

@Composable
private fun MigrationProblem.text(): String = when (this) {
    MigrationProblem.Stale -> stringResource(Res.string.build_migration_stale)
    MigrationProblem.PendingSaveFailed -> stringResource(Res.string.build_migration_save_pending)
    MigrationProblem.DatasetsUnavailable -> stringResource(Res.string.build_migration_data_unavailable)
    is MigrationProblem.Library -> error.describeLibraryError()
    is MigrationProblem.Preview -> stringResource(when (val result = result) {
        BuildMigrationResult.IneligibleVersion -> Res.string.build_migration_bad_version
        BuildMigrationResult.IncompatibleClass -> Res.string.build_migration_bad_class
        BuildMigrationResult.UnmatchedTree -> Res.string.build_migration_bad_tree
        is BuildMigrationResult.InvalidSource -> Res.string.build_migration_bad_source
        is BuildMigrationResult.InvalidTarget -> Res.string.build_migration_bad_target
        is BuildMigrationResult.InvalidDataset -> result.side.datasetMessage(invalid = true)
        is BuildMigrationResult.DatasetUnavailable -> {
            when (val error = result.error.unwrapManifest()) {
                is DatasetLoadError.ClassEntryMissing, is DatasetLoadError.ClassMismatch -> Res.string.build_migration_bad_class
                else -> result.side.datasetMessage(
                    invalid = error !is DatasetLoadError.AssetMissing && error !is DatasetLoadError.AssetUnreadable,
                )
            }
        }
        is BuildMigrationResult.Ready -> error("A migration plan is not a failure")
    })
}

private fun BuildMigrationResult.Side.datasetMessage(invalid: Boolean) = when (this) {
    BuildMigrationResult.Side.SOURCE ->
        if (invalid) Res.string.build_migration_source_invalid else Res.string.build_migration_source_missing
    BuildMigrationResult.Side.TARGET ->
        if (invalid) Res.string.build_migration_target_invalid else Res.string.build_migration_target_missing
}

private tailrec fun DatasetLoadError.unwrapManifest(): DatasetLoadError =
    if (this is DatasetLoadError.ManifestUnavailable) error.unwrapManifest() else this
