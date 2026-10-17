package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_disclaimer
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_license_hide_text
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_license_show_text
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_hide_modules
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_intro
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_linked_into
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_loading
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_module_count
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_show_modules
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_unavailable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_backup_android
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_backup_ios
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_delete
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_offline
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_permissions
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_policy_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_policy_link
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_sharing
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_privacy_storage
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_section_licenses
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_section_privacy
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_section_project
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_retry
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_talent_data_version
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.SecondaryActionButton
import io.github.alexeykravtsov95.talentforge.ui.library.SectionLabel
import io.github.alexeykravtsov95.talentforge.ui.library.dataVersionOf
import io.github.alexeykravtsov95.talentforge.ui.library.gameDisplayName
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.BackChevronButton
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The About screen's notices: what this platform ships, or why nothing is shown. */
sealed interface NoticesState {
    /** No notices are compiled for this platform's binary. */
    data object NotIncluded : NoticesState

    data object Loading : NoticesState

    data class Ready(val notices: OpenSourceNotices) : NoticesState

    /** The bundled notices could not be read or parsed — shown, never hidden as "none". */
    data object Failed : NoticesState
}

/**
 * **About** (M5.RC.2b) — a pushed destination from Home (Back returns to
 * Home; no persistent navigation, UX/UI spec 02 §1). One scrolling column:
 *
 * - the name, the installed version ([appVersion] from the host's package
 *   metadata; omitted when the host cannot report one — never invented) and
 *   the bundled talent-data version;
 * - the independent-project statement (the trademark-free draft, LR-2);
 * - the privacy summary — the verified facts of the draft policy, with the
 *   backup paragraph that is true for this platform ([backupStatement]) and
 *   a link to the public policy ONLY when an approved `https://` URL is
 *   configured ([privacyPolicyUrl]; none is yet, LR-5);
 * - the open-source notices of this platform's binary ([noticesPath]): every
 *   component with its licence and module list, and the full licence text.
 */
@Composable
fun AboutScreen(
    appVersion: AppVersion?,
    noticesPath: String?,
    backupStatement: BackupStatement,
    privacyPolicyUrl: String?,
    onBack: () -> Unit,
) {
    val policyLink = privacyPolicyLinkOf(privacyPolicyUrl)
    var attempt by remember { mutableStateOf(0) }
    var notices by remember(noticesPath) {
        mutableStateOf(if (noticesPath == null) NoticesState.NotIncluded else NoticesState.Loading)
    }
    LaunchedEffect(noticesPath, attempt) {
        if (noticesPath == null) return@LaunchedEffect
        notices = NoticesState.Loading
        notices = try {
            NoticesState.Ready(loadOpenSourceNotices(noticesPath))
        } catch (e: OpenSourceNoticesException) {
            NoticesState.Failed
        }
    }
    // Read HERE, in the screen's own scope (see HomeScreen): the load result can be
    // written off the UI thread in the desktop test harness.
    val shownNotices = notices

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
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevronButton(onClick = onBack)
            BasicText(
                text = stringResource(Res.string.about_title),
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TalentForgeSpacing.screenX),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
        ) {
            Spacer(Modifier.height(TalentForgeSpacing.hairline))
            BasicText("TalentForge", style = TalentForgeTypography.title.copy(color = colors.textPrimary))
            if (appVersion != null) {
                Meta(stringResource(Res.string.about_version, appVersion.name, appVersion.code.toString()))
            }
            Meta(
                stringResource(
                    Res.string.common_talent_data_version,
                    gameDisplayName(PRODUCT_BUNDLE.game),
                    dataVersionOf(PRODUCT_BUNDLE.clientBuild),
                ),
            )

            Section(stringResource(Res.string.about_section_project))
            Body(stringResource(Res.string.about_disclaimer))

            Section(stringResource(Res.string.about_section_privacy))
            Body(stringResource(Res.string.about_privacy_offline))
            Body(stringResource(Res.string.about_privacy_storage))
            Body(
                stringResource(
                    when (backupStatement) {
                        BackupStatement.AndroidBackupDisabled -> Res.string.about_privacy_backup_android
                        BackupStatement.IosDeviceBackup -> Res.string.about_privacy_backup_ios
                    },
                ),
            )
            Body(stringResource(Res.string.about_privacy_sharing))
            Body(stringResource(Res.string.about_privacy_permissions))
            Body(stringResource(Res.string.about_privacy_delete))
            if (policyLink != null) {
                val uriHandler = LocalUriHandler.current
                SecondaryActionButton(
                    text = stringResource(Res.string.about_privacy_policy_link),
                    onClickLabel = stringResource(Res.string.about_privacy_policy_action),
                    onClick = { uriHandler.openUri(policyLink) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section(stringResource(Res.string.about_section_licenses))
            when (shownNotices) {
                NoticesState.NotIncluded -> Body(stringResource(Res.string.about_licenses_unavailable))
                NoticesState.Loading -> Meta(stringResource(Res.string.about_licenses_loading))
                NoticesState.Failed -> InlineNotice(
                    text = stringResource(Res.string.about_licenses_failed),
                    actionText = stringResource(Res.string.common_retry),
                    onAction = { attempt += 1 },
                )
                is NoticesState.Ready -> NoticesContent(shownNotices.notices)
            }
            Spacer(Modifier.height(TalentForgeSpacing.group))
        }
    }
}

@Composable
private fun NoticesContent(notices: OpenSourceNotices) {
    Body(stringResource(Res.string.about_licenses_intro))
    for (component in notices.components) {
        ComponentCard(component, licenseNames = component.licenseIds.joinToString(", ") { notices.licenses.getValue(it).name })
    }
    for (license in notices.licenses.values) {
        LicenseText(license)
    }
}

@Composable
private fun ComponentCard(component: NoticeComponent, licenseNames: String) {
    val colors = talentForgeColors()
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated, RoundedCornerShape(TalentForgeRadius.medium))
            .border(1.dp, colors.borderDefault, RoundedCornerShape(TalentForgeRadius.medium))
            .padding(horizontal = TalentForgeSpacing.rowGap, vertical = TalentForgeSpacing.inline),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline),
    ) {
        BasicText(component.name, style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary))
        when (component) {
            is NoticeComponent.Packaged -> {
                var expanded by rememberSaveable(component.name) { mutableStateOf(false) }
                val moduleCount = component.modules.size
                Meta("$licenseNames · ${pluralStringResource(Res.plurals.about_licenses_module_count, moduleCount, moduleCount)}")
                Meta(component.url)
                Toggle(
                    expanded = expanded,
                    show = stringResource(Res.string.about_licenses_show_modules),
                    hide = stringResource(Res.string.about_licenses_hide_modules),
                    onToggle = { expanded = !expanded },
                )
                if (expanded) {
                    BasicText(component.modules.joinToString("\n"), style = AboutMonoStyle.copy(color = colors.textSecondary))
                }
            }
            is NoticeComponent.Linked -> {
                Meta("$licenseNames · ${stringResource(Res.string.about_licenses_version, component.version)}")
                Meta(component.url)
                Meta(stringResource(Res.string.about_licenses_linked_into, component.linkedInto))
                component.attribution?.let { Body(it) }
            }
        }
    }
}

@Composable
private fun LicenseText(license: NoticeLicense) {
    val colors = talentForgeColors()
    var expanded by rememberSaveable(license.id) { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var text by remember(license.textPath) { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(expanded, license.textPath, attempt) {
        if (!expanded || text?.isSuccess == true) return@LaunchedEffect
        text = null
        text = try {
            Result.success(readLegalResource(license.textPath))
        } catch (e: OpenSourceNoticesException) {
            Result.failure(e)
        }
    }
    val shownText = text
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline)) {
        BasicText(license.name, style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary))
        Toggle(
            expanded = expanded,
            show = stringResource(Res.string.about_license_show_text),
            hide = stringResource(Res.string.about_license_hide_text),
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            when {
                shownText == null -> Meta(stringResource(Res.string.about_licenses_loading))
                shownText.isSuccess -> BasicText(
                    shownText.getOrThrow(),
                    style = AboutMonoStyle.copy(color = colors.textSecondary),
                )
                else -> InlineNotice(
                    text = stringResource(Res.string.about_licenses_failed),
                    actionText = stringResource(Res.string.common_retry),
                    onAction = { attempt += 1 },
                )
            }
        }
    }
}

/** A 48 dp text action that shows or hides a block of detail. */
@Composable
private fun Toggle(expanded: Boolean, show: String, hide: String, onToggle: () -> Unit) {
    val label = if (expanded) hide else show
    BasicText(
        text = label,
        style = ToggleStyle.copy(color = talentForgeColors().textPrimary),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun Section(text: String) {
    Spacer(Modifier.height(TalentForgeSpacing.hairline))
    SectionLabel(text, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String) {
    BasicText(text, style = TalentForgeTypography.body.copy(color = talentForgeColors().textSecondary))
}

@Composable
private fun Meta(text: String) {
    BasicText(text, style = TalentForgeTypography.meta.copy(color = talentForgeColors().textMuted))
}

private val ToggleStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
)

private val AboutMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)
