package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_license_hide_text
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_license_show_text
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_hide_modules
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_intro
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_linked_into
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_loading
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_module_count
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_show_modules
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_unavailable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_retry
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The Licenses screen's notices: what this platform ships, or why nothing is shown. */
sealed interface NoticesState {
    /** No notices are compiled for this platform's binary. */
    data object NotIncluded : NoticesState

    data object Loading : NoticesState

    data class Ready(val notices: OpenSourceNotices) : NoticesState

    /** The bundled notices could not be read or parsed — shown, never hidden as "none". */
    data object Failed : NoticesState
}

/**
 * **Third-party licenses** (M5.UI.ABOUT.1) — pushed from About (Back returns
 * to About). The open-source notices of this platform's binary
 * ([noticesPath]), unchanged since M5.RC.2b: every component with its licences,
 * version or module list and required attribution, and the full text of every
 * licence behind its own toggle. A notices file that cannot be read is an
 * error with Retry, never an empty list.
 */
@Composable
fun LicensesScreen(
    noticesPath: String?,
    onBack: () -> Unit,
) {
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

    AboutScaffold(title = stringResource(Res.string.about_licenses_title), onBack = onBack) {
        when (shownNotices) {
            NoticesState.NotIncluded -> AboutBody(stringResource(Res.string.about_licenses_unavailable))
            NoticesState.Loading -> AboutMeta(stringResource(Res.string.about_licenses_loading))
            NoticesState.Failed -> InlineNotice(
                text = stringResource(Res.string.about_licenses_failed),
                actionText = stringResource(Res.string.common_retry),
                onAction = { attempt += 1 },
            )
            is NoticesState.Ready -> NoticesContent(shownNotices.notices)
        }
    }
}

@Composable
private fun NoticesContent(notices: OpenSourceNotices) {
    AboutBody(stringResource(Res.string.about_licenses_intro))
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
                AboutMeta("$licenseNames · ${pluralStringResource(Res.plurals.about_licenses_module_count, moduleCount, moduleCount)}")
                AboutMeta(component.url)
                AboutToggle(
                    expanded = expanded,
                    show = stringResource(Res.string.about_licenses_show_modules),
                    hide = stringResource(Res.string.about_licenses_hide_modules),
                    onToggle = { expanded = !expanded },
                )
                if (expanded) {
                    BasicText(component.modules.joinToString("\n"), style = LicenseMonoStyle.copy(color = colors.textSecondary))
                }
            }
            is NoticeComponent.Linked -> {
                AboutMeta("$licenseNames · ${stringResource(Res.string.about_licenses_version, component.version)}")
                AboutMeta(component.url)
                AboutMeta(stringResource(Res.string.about_licenses_linked_into, component.linkedInto))
                component.attribution?.let { AboutBody(it) }
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
        AboutToggle(
            expanded = expanded,
            show = stringResource(Res.string.about_license_show_text),
            hide = stringResource(Res.string.about_license_hide_text),
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            when {
                shownText == null -> AboutMeta(stringResource(Res.string.about_licenses_loading))
                shownText.isSuccess -> BasicText(
                    shownText.getOrThrow(),
                    style = LicenseMonoStyle.copy(color = colors.textSecondary),
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

private val LicenseMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)
