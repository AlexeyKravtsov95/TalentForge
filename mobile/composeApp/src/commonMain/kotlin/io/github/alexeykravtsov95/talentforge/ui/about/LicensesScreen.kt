package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_license_applies_to
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_license_open_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_group_action
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_includes
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_intro
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_license_count
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_loading
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_section_groups
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_section_included
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_section_notices
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_section_texts
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_title
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_unavailable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.about_licenses_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.common_retry
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
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
 * **Open-source licenses** (M5.UI.ABOUT.1, grouped in M5.UI.ABOUT.2) — pushed
 * from About (Back returns to About). The open-source notices of this
 * platform's binary ([noticesPath]) on three levels, each one Back from the
 * last (the visible chevron and the system Back agree):
 *
 * 1. the list: the statements the licences require in the product
 *    documentation, verbatim and up front (iOS: the IJG and Adobe DNG
 *    notices), then one row per [NoticeGroup] naming every component it
 *    includes;
 * 2. a group: each of its components with its licences, version and required
 *    statement, and one row per licence text;
 * 3. a licence: every component it applies to, and its full original text.
 *
 * Every component and every licence text of the generated file stays
 * reachable; only the module coordinates are left out of the screen (they stay
 * in the generated notices). A notices file that cannot be read is an error
 * with Retry, never an empty list.
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

    // the page shown: the list, a group (its index) or a licence text (its id, opened from that group)
    var openGroup by rememberSaveable { mutableStateOf<Int?>(null) }
    var openLicense by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = (shownNotices as? NoticesState.Ready)?.notices
    val group = openGroup?.let { index -> ready?.groups?.getOrNull(index) }
    val license = openLicense?.let { id -> ready?.licenses?.get(id) }?.takeIf { group != null }
    val listScroll = rememberScrollState()

    // Registered after the app's own handler: while a group is open (a licence opens only from
    // one), the system Back closes the innermost page. It reads the page state when it runs —
    // never values captured by an earlier composition.
    PlatformBackHandler(enabled = group != null) {
        if (openLicense != null) openLicense = null else openGroup = null
    }

    when {
        ready != null && license != null -> key("license", license.id) {
            LicensePage(ready, license, onBack = { openLicense = null })
        }
        ready != null && group != null -> key("group", openGroup) {
            GroupPage(ready, group, onBack = { openGroup = null }, onOpenLicense = { openLicense = it })
        }
        else -> AboutScaffold(title = stringResource(Res.string.about_licenses_title), onBack = onBack, scrollState = listScroll) {
            when (shownNotices) {
                NoticesState.NotIncluded -> AboutBody(stringResource(Res.string.about_licenses_unavailable))
                NoticesState.Loading -> AboutMeta(stringResource(Res.string.about_licenses_loading))
                NoticesState.Failed -> InlineNotice(
                    text = stringResource(Res.string.about_licenses_failed),
                    actionText = stringResource(Res.string.common_retry),
                    onAction = { attempt += 1 },
                )
                is NoticesState.Ready -> GroupList(shownNotices.notices, onOpenGroup = { openGroup = it })
            }
        }
    }
}

/** The first level: the required statements, then one row per group naming everything it includes. */
@Composable
private fun GroupList(notices: OpenSourceNotices, onOpenGroup: (Int) -> Unit) {
    AboutBody(stringResource(Res.string.about_licenses_intro))
    val attributions = notices.attributions
    if (attributions.isNotEmpty()) {
        AboutSection(stringResource(Res.string.about_licenses_section_notices))
        for (component in attributions) RequiredStatement(component)
    }
    AboutSection(stringResource(Res.string.about_licenses_section_groups))
    notices.groups.forEachIndexed { index, group ->
        // the licence names while they read as one short line; a count beyond that (Skiko's 15)
        val licenseCount = group.licenseIds.size
        val licenses = if (licenseCount <= MAX_NAMED_LICENSES) {
            group.licenseIds.joinToString(", ") { notices.licenses.getValue(it).name }
        } else {
            pluralStringResource(Res.plurals.about_licenses_license_count, licenseCount, licenseCount)
        }
        val includes = group.linked.takeIf { it.isNotEmpty() }
            ?.let { linked -> stringResource(Res.string.about_licenses_includes, linked.joinToString(", ") { it.name }) }
        AboutNavigationRow(
            title = group.head.name,
            detail = listOfNotNull(licenses, includes).joinToString("\n"),
            onClickLabel = stringResource(Res.string.about_licenses_group_action, group.head.name),
            onClick = { onOpenGroup(index) },
        )
    }
}

/** A statement a licence requires in the product documentation, verbatim, above the component it belongs to. */
@Composable
private fun RequiredStatement(component: NoticeComponent.Linked) {
    val statement = component.attribution ?: return
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline)) {
        BasicText(statement, style = TalentForgeTypography.body.copy(color = talentForgeColors().textPrimary))
        AboutMeta(component.name)
    }
}

/** The second level: every component of [group] with its licences, then one row per licence text. */
@Composable
private fun GroupPage(notices: OpenSourceNotices, group: NoticeGroup, onBack: () -> Unit, onOpenLicense: (String) -> Unit) {
    AboutScaffold(title = group.head.name, onBack = onBack) {
        AboutMeta(group.head.url)
        AboutMeta(licenseNames(notices, group.head))
        if (group.linked.isNotEmpty()) {
            AboutSection(stringResource(Res.string.about_licenses_section_included))
            for (component in group.linked) LinkedComponent(notices, component)
        }
        AboutSection(stringResource(Res.string.about_licenses_section_texts))
        for (id in group.licenseIds) {
            val license = notices.licenses.getValue(id)
            AboutNavigationRow(
                title = license.name,
                detail = null,
                onClickLabel = stringResource(Res.string.about_license_open_action, license.name),
                onClick = { onOpenLicense(id) },
            )
        }
    }
}

@Composable
private fun LinkedComponent(notices: OpenSourceNotices, component: NoticeComponent.Linked) {
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline)) {
        BasicText(component.name, style = TalentForgeTypography.componentTitle.copy(color = talentForgeColors().textPrimary))
        AboutMeta("${licenseNames(notices, component)} · ${stringResource(Res.string.about_licenses_version, component.version)}")
        AboutMeta(component.url)
        component.attribution?.let { AboutBody(it) }
    }
}

private fun licenseNames(notices: OpenSourceNotices, component: NoticeComponent): String =
    component.licenseIds.joinToString(", ") { notices.licenses.getValue(it).name }

/** The third level: what [license] applies to, and its full original text (never translated). */
@Composable
private fun LicensePage(notices: OpenSourceNotices, license: NoticeLicense, onBack: () -> Unit) {
    val colors = talentForgeColors()
    var attempt by remember { mutableStateOf(0) }
    var text by remember(license.textPath) { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(license.textPath, attempt) {
        if (text?.isSuccess == true) return@LaunchedEffect
        text = null
        text = try {
            Result.success(readLegalResource(license.textPath))
        } catch (e: OpenSourceNoticesException) {
            Result.failure(e)
        }
    }
    val shownText = text
    AboutScaffold(title = license.name, onBack = onBack) {
        AboutMeta(
            stringResource(
                Res.string.about_license_applies_to,
                notices.componentsUnder(license.id).joinToString(", ") { it.name },
            ),
        )
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

private const val MAX_NAMED_LICENSES = 2

private val LicenseMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)
