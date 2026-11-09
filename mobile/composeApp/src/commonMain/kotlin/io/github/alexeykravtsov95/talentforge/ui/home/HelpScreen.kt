package io.github.alexeykravtsov95.talentforge.ui.home

import androidx.compose.runtime.Composable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.help_step_builds
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.help_step_class
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.help_step_offline
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.help_step_offline_disabled
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.help_step_talents
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.help_title
import io.github.alexeykravtsov95.talentforge.ui.about.AboutBody
import io.github.alexeykravtsov95.talentforge.ui.about.AboutScaffold
import org.jetbrains.compose.resources.stringResource

/** Repeatable, optional guidance using the same scrolling frame and Back as About. */
@Composable
internal fun HelpScreen(onBack: () -> Unit, otaEnabled: Boolean) {
    AboutScaffold(title = stringResource(Res.string.help_title), onBack = onBack) {
        AboutBody(stringResource(Res.string.help_step_class))
        AboutBody(stringResource(Res.string.help_step_talents))
        AboutBody(stringResource(Res.string.help_step_builds))
        AboutBody(stringResource(if (otaEnabled) Res.string.help_step_offline else Res.string.help_step_offline_disabled))
    }
}
