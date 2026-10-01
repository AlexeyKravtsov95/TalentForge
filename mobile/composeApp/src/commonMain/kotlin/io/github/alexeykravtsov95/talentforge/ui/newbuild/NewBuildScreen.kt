package io.github.alexeykravtsov95.talentforge.ui.newbuild

import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.ClassMonogramMark
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.NewBuildChoices
import io.github.alexeykravtsov95.talentforge.ui.library.NewBuildClassChoice
import io.github.alexeykravtsov95.talentforge.ui.library.SectionLabel
import io.github.alexeykravtsov95.talentforge.ui.library.classMonogramOf
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadNewBuildChoices
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import io.github.alexeykravtsov95.talentforge.ui.tree.BackChevronButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * New Build — ONE screen: version context + class grid (UX/UI spec C).
 * Forever is currently the only supported version, so it is plain
 * non-interactive context (no Change control — that appears only once a
 * second selectable version exists). Picking a class has no extra
 * confirmation step: it loads the real document, builds the real engine,
 * takes the real `emptyState()`, binds the exact dataset identity, PERSISTS
 * the draft, and only then replaces this screen with the tree (Back from the
 * new draft therefore goes to Home). A failed creation never opens an editor
 * that looks saved — the error notice stays on this screen and tapping the
 * class again retries.
 */
@Composable
fun NewBuildScreen(
    repository: LocalBuildRepository,
    datasets: TalentDatasetRepository,
    onBack: () -> Unit,
    onDraftCreated: (String) -> Unit,
) {
    var choices by remember { mutableStateOf<NewBuildChoices?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    LaunchedEffect(reloadToken) {
        failed = false
        choices = withContext(Dispatchers.Default) { loadNewBuildChoices(datasets) }
        failed = choices == null
    }

    // one creation at a time: a second tap during an in-flight create must not
    // produce a second draft
    var creating by remember { mutableStateOf(false) }
    var creationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val onClassSelected: (ClassToken) -> Unit = { token ->
        if (!creating) {
            creating = true
            creationError = null
            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    createClassDraft(repository, datasets, token)
                }
                creating = false
                when (result) {
                    is DraftCreation.Created -> onDraftCreated(result.record.localId)
                    is DraftCreation.Failed -> creationError = result.failure.describeNewBuildError()
                }
            }
        }
    }

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
                .height(44.dp)
                .padding(start = TalentForgeSpacing.inline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevronButton(onClick = onBack)
            BasicText(
                text = "New build",
                style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TalentForgeSpacing.screenX),
            verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
        ) {
            Spacer(Modifier.height(TalentForgeSpacing.inline))
            when {
                failed -> Column(
                    verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.rowGap),
                ) {
                    BasicText(
                        "Couldn't load the class list",
                        style = TalentForgeTypography.componentTitle.copy(color = colors.danger),
                    )
                    InlineNotice(
                        text = "The bundled talent data could not be read",
                        actionText = "Retry",
                        onAction = { reloadToken += 1 },
                    )
                }

                choices == null -> BasicText(
                    "Loading classes…",
                    style = TalentForgeTypography.bodySmall.copy(color = colors.textSecondary),
                )

                else -> {
                    choices?.let { ready ->
                        VersionContext(ready)
                        if (creationError != null) {
                            InlineNotice(
                                text = creationError!!,
                                actionText = null,
                                onAction = null,
                            )
                        }
                        ClassGrid(
                            classes = ready.classes,
                            creating = creating,
                            onClassSelected = onClassSelected,
                        )
                    }
                }
            }
            Spacer(Modifier.height(TalentForgeSpacing.group))
        }
    }
}

/**
 * The version block (spec C.1): Forever as plain context text — deliberately
 * NOT a control while it is the only selectable version ("no Change, no
 * sheet").
 */
@Composable
private fun VersionContext(choices: NewBuildChoices) {
    val colors = talentForgeColors()
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline)) {
        SectionLabel("GAME VERSION")
        BasicText(
            text = choices.gameDisplayName,
            style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
        )
        BasicText(
            text = choices.versionDetail,
            style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
        )
        BasicText(
            text = "Builds save to this device as you edit.",
            style = TalentForgeTypography.bodySmall.copy(color = colors.textMuted),
        )
    }
}

/** The 3-column class grid (spec C/H): marks in class hue, name always visible. */
@Composable
private fun ClassGrid(
    classes: List<NewBuildClassChoice>,
    creating: Boolean,
    onClassSelected: (ClassToken) -> Unit,
) {
    val colors = talentForgeColors()
    Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
        SectionLabel("CLASS")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // spec H: the class grid is ALWAYS 3 columns; taller cards at 412 dp+.
            val cardHeight = if (maxWidth.value >= 412f) 96.dp else 84.dp
            val columns = 3
            Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
                classes.chunked(columns).forEach { rowClasses ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (choice in rowClasses) {
                            ClassCard(
                                choice = choice,
                                dimmed = creating,
                                minHeight = cardHeight,
                                modifier = Modifier.weight(1f),
                                onClick = { onClassSelected(choice.token) },
                            )
                        }
                        repeat(columns - rowClasses.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One class mark card. A class whose document failed to load renders disabled
 * with its locale-independent TOKEN as the honest label — no invented name,
 * no substitution, no fallback to another class's dataset.
 */
@Composable
private fun ClassCard(
    choice: NewBuildClassChoice,
    dimmed: Boolean,
    minHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val enabled = choice.available && !dimmed
    val label = choice.displayName ?: choice.token.value
    Column(
        modifier = modifier
            .heightIn(min = minHeight)
            .background(
                if (choice.available) colors.surfaceElevated else colors.surface,
                RoundedCornerShape(TalentForgeRadius.large),
            )
            .then(
                if (enabled) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "create a $label build",
                        onClick = onClick,
                    )
                } else {
                    Modifier.semantics(mergeDescendants = true) {
                        role = Role.Button
                        if (!choice.available) disabled()
                    }
                },
            )
            .padding(TalentForgeSpacing.inline),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClassMonogramMark(
            monogram = classMonogramOf(label),
            accent = classAccentFor(choice.token).color,
            dimmed = !choice.available,
        )
        BasicText(
            text = label,
            style = TalentForgeTypography.bodySmall.copy(
                color = if (choice.available) colors.textPrimary else colors.textMuted,
            ),
            maxLines = 2,
        )
    }
}

private fun DraftCreation.Failure.describeNewBuildError(): String = when (this) {
    is DraftCreation.Failure.Dataset -> "the class talent data could not be read"
    is DraftCreation.Failure.Engine -> "the allocation engine rejected this dataset ($reason)"
    is DraftCreation.Failure.Library -> "the draft could not be saved on this device"
}
