package io.github.alexeykravtsov95.talentforge.ui.newbuild

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkMark
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkSlot
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.InlineNotice
import io.github.alexeykravtsov95.talentforge.ui.library.NewBuildChoices
import io.github.alexeykravtsov95.talentforge.ui.library.NewBuildClassChoice
import io.github.alexeykravtsov95.talentforge.ui.library.classMonogramOf
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.library.loadNewBuildChoices
import io.github.alexeykravtsov95.talentforge.ui.motion.prefersReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
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
 * Class Selection (New Build) — the B+ "Modern Fantasy Utility" redesign
 * (M5.UI.2). One screen: `Choose a class` header with the real version
 * context, then the nine classes of the bundle manifest as a grid of their
 * supplied artwork on restrained dark surfaces — the painting carries the
 * fantasy identity, no gradients or ornamental frames are added.
 *
 * Picking a class has no confirmation step: it loads the real document,
 * builds the real engine, takes the real `emptyState()`, binds the exact
 * dataset identity, PERSISTS the draft, and only then replaces this screen
 * with the tree (Back from the new draft therefore goes to Home). While the
 * creation is in flight the tapped card shows the spec's amber progress
 * mark, every other card dims, duplicate taps do nothing, and Back (header
 * chevron and Android system back) is HELD on this screen until the
 * repository call settles — leaving mid-creation could orphan a persisted
 * draft or race `onDraftCreated`. A failed creation never opens an editor
 * that looks saved: the typed error notice stays here and tapping the class
 * again retries.
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

    // one creation at a time: the token of the in-flight card (also which
    // card shows the progress mark); a second tap during it must not produce
    // a second draft
    var creatingToken by remember { mutableStateOf<ClassToken?>(null) }
    var creationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val onClassSelected: (ClassToken) -> Unit = { token ->
        val creating = creatingToken
        val isAvailable = choices?.classes.orEmpty().any { it.token == token && it.available }
        if (creating == null && isAvailable) {
            creatingToken = token
            creationError = null
            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    createClassDraft(repository, datasets, token)
                }
                creatingToken = null
                when (result) {
                    is DraftCreation.Created -> onDraftCreated(result.record.localId)
                    is DraftCreation.Failed -> creationError = result.failure.describeNewBuildError()
                }
            }
        }
    }

    // the deterministic Back policy above: while a creation is in flight the
    // system back is consumed here (staying on Class Selection). This handler
    // composes INSIDE the destination, so on Android it takes precedence over
    // the App-level pop for exactly the duration of the creation.
    PlatformBackHandler(enabled = creatingToken != null, onBack = {})

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
                .heightIn(min = 44.dp)
                .padding(start = TalentForgeSpacing.inline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevronButton(onClick = { if (creatingToken == null) onBack() })
            Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.hairline)) {
                BasicText(
                    text = "Choose a class",
                    style = TalentForgeTypography.componentTitle.copy(color = colors.textPrimary),
                )
                choices?.let { ready ->
                    BasicText(
                        text = "${ready.gameDisplayName} · talent data ${ready.dataVersion}",
                        style = TalentForgeTypography.meta.copy(color = colors.textSecondary),
                    )
                }
            }
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
                        "Couldn't load classes",
                        style = TalentForgeTypography.componentTitle.copy(color = colors.danger),
                    )
                    InlineNotice(
                        text = "The local talent data could not be read.",
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
                        if (creationError != null) {
                            InlineNotice(
                                text = creationError!!,
                                actionText = null,
                                onAction = null,
                            )
                        }
                        ClassGrid(
                            classes = ready.classes,
                            creatingToken = creatingToken,
                            onClassSelected = onClassSelected,
                        )
                    }
                }
            }
            Spacer(Modifier.height(TalentForgeSpacing.group))
        }
    }
}

/** The responsive decision of the class grid: column count + artwork slot. */
internal data class ClassPickerLayout(val columns: Int, val slot: ClassArtworkSlot)

/**
 * The deterministic class-grid policy (no hardcoded screen sizes): three
 * columns while the WIDEST class label — measured with the real text style at
 * the CURRENT font scale — still fits on one line of a three-column card,
 * otherwise the two-column layout (labels may then wrap and cards grow
 * vertically). The standard 56 dp tile is used while it keeps at least
 * [PICKER_TILE_CLEARANCE] of clear space on each side of the card; narrower
 * grids drop to the compact 46 dp tile.
 */
internal fun classPickerLayout(gridWidth: Dp, widestLabel: Dp): ClassPickerLayout {
    fun cardContentWidth(columns: Int): Dp =
        (gridWidth - PICKER_COLUMN_GAP * (columns - 1)) / columns - PICKER_CARD_INNER_PADDING * 2

    val columns = if (cardContentWidth(3) >= widestLabel) 3 else 2
    val slot = if (cardContentWidth(columns) >= ClassArtworkSlot.Picker.size + PICKER_TILE_CLEARANCE * 2) {
        ClassArtworkSlot.Picker
    } else {
        ClassArtworkSlot.PickerCompact
    }
    return ClassPickerLayout(columns, slot)
}

/**
 * The 3-column class grid (spec H responsive): supplied artwork in the
 * picker slot, name always visible below it.
 */
@Composable
private fun ClassGrid(
    classes: List<NewBuildClassChoice>,
    creatingToken: ClassToken?,
    onClassSelected: (ClassToken) -> Unit,
) {
    val nameStyle = TalentForgeTypography.bodySmall
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // the honest readability input: the widest label this grid will render,
    // laid out with the card's real style at the current font scale
    val widestLabel = remember(classes, nameStyle) {
        with(density) {
            classes.maxOf { choice ->
                textMeasurer.measure(labelOf(choice), nameStyle).size.width.toDp()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = classPickerLayout(maxWidth, widestLabel)
        Column(verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline)) {
            classes.chunked(layout.columns).forEach { rowClasses ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PICKER_COLUMN_GAP),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (choice in rowClasses) {
                        ClassCard(
                            choice = choice,
                            slot = layout.slot,
                            creatingThis = creatingToken == choice.token,
                            blocked = creatingToken != null,
                            onClick = { onClassSelected(choice.token) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(layout.columns - rowClasses.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun labelOf(choice: NewBuildClassChoice): String =
    choice.displayName ?: choice.token.value

/**
 * One class card: the supplied artwork (or the monogram fallback for a token
 * without art), the class name below it, on the restrained elevated surface.
 * A class whose document failed to load renders disabled and dimmed with its
 * locale-independent TOKEN as the honest label — no invented name, no
 * substitution, no fallback to another class's dataset. While ANY creation is
 * in flight every card except the creating one dims and disables; the
 * creating card shows the spec's amber progress mark over its artwork.
 */
@Composable
private fun ClassCard(
    choice: NewBuildClassChoice,
    slot: ClassArtworkSlot,
    creatingThis: Boolean,
    blocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = talentForgeColors()
    val label = labelOf(choice)
    val enabled = choice.available && !blocked
    val dimmed = !choice.available || (blocked && !creatingThis)
    Column(
        modifier = modifier
            .then(if (dimmed) Modifier.alpha(DIMMED_CARD_ALPHA) else Modifier)
            .heightIn(min = slot.size + PICKER_CARD_MIN_EXTRA_HEIGHT)
            .background(
                if (choice.available) colors.surfaceElevated else colors.surface,
                RoundedCornerShape(TalentForgeRadius.large),
            )
            .border(1.dp, colors.borderDefault, RoundedCornerShape(TalentForgeRadius.large))
            .then(
                if (enabled) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "create a $label build",
                        onClick = onClick,
                    )
                } else {
                    // keeps Button semantics + the reported disabled state for
                    // unavailable classes (and the busy cards of an in-flight
                    // creation, which must not be tappable)
                    Modifier.semantics(mergeDescendants = true) {
                        role = Role.Button
                        disabled()
                    }
                },
            )
            .padding(PICKER_CARD_INNER_PADDING),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ClassArtworkMark(
                classToken = choice.token,
                slot = slot,
                monogram = classMonogramOf(label),
                accent = classAccentFor(choice.token).color,
            )
            if (creatingThis) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = "Creating build" },
                ) {
                    CreatingBuildSpinner()
                }
            }
        }
        BasicText(
            text = label,
            style = TalentForgeTypography.bodySmall.copy(
                color = if (choice.available) colors.textPrimary else colors.textMuted,
            ),
            maxLines = 2,
        )
    }
}

/**
 * The compact progress mark of the creating card: the B+ spec's accent
 * spinner (the primary-action amber), drawn over the artwork tile. Honors
 * reduced motion — the mark stays, but no animation runs at all.
 */
@Composable
private fun CreatingBuildSpinner(modifier: Modifier = Modifier) {
    if (prefersReducedMotion()) {
        SpinnerCanvas(modifier) { 0f }
    } else {
        val transition = rememberInfiniteTransition(label = "creating-build-spinner")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(SPINNER_ROTATION_MS, easing = LinearEasing)),
            label = "creating-build-spinner-angle",
        )
        SpinnerCanvas(modifier) { angle }
    }
}

@Composable
private fun SpinnerCanvas(modifier: Modifier, startAngle: () -> Float) {
    val colors = talentForgeColors()
    Canvas(modifier.size(SPINNER_SIZE)) {
        val stroke = SPINNER_STROKE.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = colors.scrim,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = colors.primaryAction,
            startAngle = startAngle(),
            sweepAngle = SPINNER_SWEEP,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private fun DraftCreation.Failure.describeNewBuildError(): String = when (this) {
    is DraftCreation.Failure.Dataset -> "The class talent data could not be read."
    is DraftCreation.Failure.Engine -> "The allocation engine rejected this dataset ($reason)."
    is DraftCreation.Failure.Library -> "The draft could not be saved on this device."
}

/** Column gap and the horizontal padding inside a picker card (both `inline`). */
private val PICKER_COLUMN_GAP = TalentForgeSpacing.inline
private val PICKER_CARD_INNER_PADDING = TalentForgeSpacing.inline

/** The clear space a standard tile keeps on each side of the card (a `rowGap`). */
private val PICKER_TILE_CLEARANCE = TalentForgeSpacing.rowGap

/** Card floor: vertical padding + art→name gap + one name line, beyond the tile. */
private val PICKER_CARD_MIN_EXTRA_HEIGHT = 44.dp

/** Dimming of unavailable cards and of the non-creating cards during a creation. */
private const val DIMMED_CARD_ALPHA = 0.45f

/** The spinner of the creating card: 24 dp ring, 2.5 dp stroke, ~1.1 s per turn. */
private val SPINNER_SIZE = 24.dp
private val SPINNER_STROKE = 2.5.dp
private const val SPINNER_ROTATION_MS = 1_100
private const val SPINNER_SWEEP = 300f
