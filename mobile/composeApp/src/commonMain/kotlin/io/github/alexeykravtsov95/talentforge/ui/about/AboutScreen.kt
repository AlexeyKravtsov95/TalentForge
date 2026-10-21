package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_disclaimer
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_entry_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_entry_detail
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_backup_android
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_backup_ios
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_delete
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_hide_details
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_offline
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_online
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_permissions
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_permissions_online
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_policy_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_policy_link
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_sharing
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_show_details
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_storage
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_updates
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_section_privacy
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_section_project
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_tagline
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.app_icon
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_talent_data_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_check_now
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_checking
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_data_build
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_data_source_bundled
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_data_source_release
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.ota_section_talent_data
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.SecondaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.dataVersionOf
import io.github.alexeykravtsov95.talentforge.ui.library.gameDisplayName
import io.github.alexeykravtsov95.talentforge.ui.ota.OtaStatus
import io.github.alexeykravtsov95.talentforge.ui.ota.text
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.stringResource

/**
 * **About** (M5.RC.2b, simplified in M5.UI.ABOUT.1) — a pushed destination
 * from Home (Back returns to Home; no persistent navigation, UX/UI spec 02 §1).
 * One scrolling column, free of technology and dependency names:
 *
 * - the app icon (the approved launcher artwork), the name, what the app does,
 *   and the installed version ([appVersion] from the host's package metadata;
 *   omitted when the host cannot report one — never invented);
 * - the talent data in use; the OTA data source and "check now" ONLY in
 *   OTA-enabled builds ([otaStatus] and [onCheckForUpdates] both set);
 * - the independent-project statement (the trademark-free draft, LR-2);
 * - the privacy summary: its first statement always visible, the remaining
 *   verified statements (with the backup paragraph true for this platform,
 *   [backupStatement]) behind one explicit 48 dp toggle — in OTA-enabled builds
 *   the "online" first statement, the talent data updates paragraph and the
 *   internet-use permissions statement instead of the offline ones
 *   (M5.OTA.PROD.3) — and the link to the
 *   published privacy policy of the UI language ([privacyPolicyUrl],
 *   M5.LEGAL.LINK), opened in the system browser, ONLY when one is published
 *   for this platform (Android; none for iOS yet, so `null` there);
 * - one row that opens the third-party licenses ([onOpenLicenses],
 *   [LicensesScreen]), where every open-source notice lives.
 */
@Composable
fun AboutScreen(
    appVersion: AppVersion?,
    backupStatement: BackupStatement,
    privacyPolicyUrl: String?,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    /** M5.OTA.3, OTA-enabled builds only: the data version in use and the "check now" action (OD-5). */
    otaStatus: OtaStatus? = null,
    onCheckForUpdates: (() -> Unit)? = null,
) {
    val policyLink = privacyPolicyLinkOf(privacyPolicyUrl)
    var privacyDetails by rememberSaveable { mutableStateOf(false) }
    // M5.OTA.PROD.3: an OTA-enabled build downloads talent data, so its privacy statements say so
    val otaEnabled = otaStatus != null && onCheckForUpdates != null

    AboutScaffold(title = stringResource(Res.string.about_title), onBack = onBack) {
        AppIdentity(appVersion)
        AboutBody(stringResource(Res.string.about_tagline))

        AboutSection(stringResource(Res.string.ota_section_talent_data))
        AboutMeta(
            stringResource(
                Res.string.common_talent_data_version,
                gameDisplayName(PRODUCT_BUNDLE.game),
                dataVersionOf(otaStatus?.productBuild ?: PRODUCT_BUNDLE.clientBuild),
            ),
        )
        if (otaStatus != null && onCheckForUpdates != null) {
            AboutMeta(stringResource(Res.string.ota_data_build, otaStatus.productBuild))
            AboutMeta(
                otaStatus.productReleaseId?.let { stringResource(Res.string.ota_data_source_release, it) }
                    ?: stringResource(Res.string.ota_data_source_bundled),
            )
            val outcome = otaStatus.lastOutcome
            when {
                otaStatus.checking -> AboutBody(stringResource(Res.string.ota_checking))
                outcome != null -> AboutBody(outcome.text())
            }
            SecondaryActionButton(
                text = stringResource(Res.string.ota_check_now),
                onClickLabel = stringResource(Res.string.ota_check_now),
                onClick = onCheckForUpdates,
                enabled = !otaStatus.checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AboutSection(stringResource(Res.string.about_section_project))
        AboutBody(stringResource(Res.string.about_disclaimer))

        AboutSection(stringResource(Res.string.about_section_privacy))
        AboutBody(stringResource(if (otaEnabled) Res.string.about_privacy_online else Res.string.about_privacy_offline))
        if (privacyDetails) {
            AboutBody(stringResource(Res.string.about_privacy_storage))
            if (otaEnabled) AboutBody(stringResource(Res.string.about_privacy_updates))
            AboutBody(
                stringResource(
                    when (backupStatement) {
                        BackupStatement.AndroidBackupDisabled -> Res.string.about_privacy_backup_android
                        BackupStatement.IosDeviceBackup -> Res.string.about_privacy_backup_ios
                    },
                ),
            )
            AboutBody(stringResource(Res.string.about_privacy_sharing))
            AboutBody(stringResource(if (otaEnabled) Res.string.about_privacy_permissions_online else Res.string.about_privacy_permissions))
            AboutBody(stringResource(Res.string.about_privacy_delete))
        }
        AboutToggle(
            expanded = privacyDetails,
            show = stringResource(Res.string.about_privacy_show_details),
            hide = stringResource(Res.string.about_privacy_hide_details),
            onToggle = { privacyDetails = !privacyDetails },
        )
        if (policyLink != null) {
            val uriHandler = LocalUriHandler.current
            SecondaryActionButton(
                text = stringResource(Res.string.about_privacy_policy_link),
                onClickLabel = stringResource(Res.string.about_privacy_policy_action),
                onClick = { uriHandler.openUri(policyLink) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(TalentForgeSpacing.hairline))
        LicensesEntry(onClick = onOpenLicenses)
    }
}

/**
 * The approved launcher artwork, the name and the installed version, side by
 * side — or the name below the icon when both do not fit (a narrow phone at a
 * large font scale). The name is one word and stays one line: it keeps the
 * ScreenTitle size wherever it fits and, only where even the full width is too
 * narrow for it, shrinks to the size that fits — measured from the platform
 * font's real glyphs, since Linux, Android, iOS and macOS fonts differ in
 * width. Never broken, cut or ellipsized.
 */
@Composable
private fun AppIdentity(appVersion: AppVersion?) {
    val colors = talentForgeColors()
    val iconShape = RoundedCornerShape(TalentForgeRadius.large)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // decorative: the name next to it says what it is
        Image(
            bitmap = imageResource(Res.drawable.app_icon),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .size(AppIconSize)
                .clip(iconShape)
                .border(1.dp, colors.borderDefault, iconShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline)) {
            BasicText(
                text = "TalentForge",
                style = TalentForgeTypography.title.copy(color = colors.textPrimary),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(maxFontSize = TalentForgeTypography.title.fontSize),
            )
            if (appVersion != null) {
                AboutMeta(stringResource(Res.string.about_version, appVersion.name, appVersion.code.toString()))
            }
        }
    }
}

/** The one way into the third-party licenses: a full-width row with a forward chevron. */
@Composable
private fun LicensesEntry(onClick: () -> Unit) {
    val colors = talentForgeColors()
    val shape = RoundedCornerShape(TalentForgeRadius.medium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.borderDefault, shape)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(Res.string.about_licenses_entry_action),
                onClick = onClick,
            )
            .padding(horizontal = TalentForgeSpacing.rowGap, vertical = TalentForgeSpacing.inline),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline),
        ) {
            BasicText(
                stringResource(Res.string.about_licenses_title),
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
            )
            AboutMeta(stringResource(Res.string.about_licenses_entry_detail))
        }
        val chevron = colors.textSecondary
        Box(
            Modifier
                .size(width = 10.dp, height = 16.dp)
                .drawBehind { drawForwardChevron(chevron) },
        )
    }
}

/** A › chevron: two strokes meeting in a right point (the mirror of the Back chevron). */
private fun DrawScope.drawForwardChevron(color: Color) {
    val stroke = 2.dp.toPx()
    val point = Offset(size.width * 0.8f, size.height * 0.5f)
    drawLine(color, start = Offset(size.width * 0.2f, size.height * 0.1f), end = point, strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, start = Offset(size.width * 0.2f, size.height * 0.9f), end = point, strokeWidth = stroke, cap = StrokeCap.Round)
}

private val AppIconSize = 64.dp
