package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.describe
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeRadius
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeSpacing
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M3A foundation smoke host — not production UI.
 *
 * Single screen proving the foundation end to end: Android and iOS boot the
 * same shared Compose application, and the shared repository reads the
 * packaged Forever dataset through the Compose resource source. The Home
 * screen, talent tree and remaining product UI arrive in later milestones.
 */
@Composable
fun App() {
    TalentForgeTheme {
        val state by produceState<SmokeState>(initialValue = SmokeState.Loading) {
            value = withContext(Dispatchers.Default) { loadBaseline() }
        }
        Box(modifier = Modifier.fillMaxSize().background(talentForgeColors().background)) {
            val current = state
            when (current) {
                SmokeState.Loading -> LoadingPane()
                is SmokeState.Ready -> BaselinePane(current.snapshot)
                is SmokeState.Failed -> ErrorPane(current.reason)
            }
        }
    }
}

/** Identity of the dataset packaged into this build. */
private val BASELINE_GAME = GameId("forever")
private const val BASELINE_CLIENT_BUILD = "1.60.1.70009"
private const val EXPECTED_CLASS_COUNT = 9

private sealed interface SmokeState {
    data object Loading : SmokeState

    data class Ready(val snapshot: BaselineSnapshot) : SmokeState

    data class Failed(val reason: String) : SmokeState
}

private data class BaselineSnapshot(
    val game: GameId,
    val clientBuild: String,
    val classCount: Int,
    val nodeCount: Int,
    val schemaVersion: Int,
)

private suspend fun loadBaseline(): SmokeState {
    val repository = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
    val bundle = DatasetBundleId(game = BASELINE_GAME, clientBuild = BASELINE_CLIENT_BUILD)

    val manifest = when (val outcome = repository.manifest(bundle)) {
        is DatasetOutcome.Loaded -> outcome.value
        is DatasetOutcome.Failed -> return SmokeState.Failed(outcome.error.describe())
    }

    var nodeCount = 0
    var schemaVersion: Int = 0
    for (entry in manifest.classes) {
        when (
            val outcome = repository.document(
                DatasetId(
                    game = manifest.game,
                    clientBuild = manifest.clientBuild,
                    classToken = ClassToken(entry.token.value),
                ),
            )
        ) {
            is DatasetOutcome.Loaded -> {
                schemaVersion = outcome.value.schemaVersion
                nodeCount += outcome.value.trees.sumOf { tree -> tree.nodes.size }
            }

            is DatasetOutcome.Failed -> return SmokeState.Failed(outcome.error.describe())
        }
    }

    if (manifest.classes.size != EXPECTED_CLASS_COUNT) {
        return SmokeState.Failed(
            "expected $EXPECTED_CLASS_COUNT bundled classes, found ${manifest.classes.size}",
        )
    }
    return SmokeState.Ready(
        BaselineSnapshot(
            game = manifest.game,
            clientBuild = manifest.clientBuild,
            classCount = manifest.classes.size,
            nodeCount = nodeCount,
            schemaVersion = schemaVersion,
        ),
    )
}

@Composable
private fun LoadingPane() {
    Column(
        modifier = Modifier.fillMaxSize().padding(TalentForgeSpacing.screenX),
        verticalArrangement = Arrangement.spacedBy(
            TalentForgeSpacing.inline,
            alignment = Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppTitle()
        SmokeText("loading bundled dataset…")
    }
}

@Composable
private fun BaselinePane(snapshot: BaselineSnapshot) {
    Column(
        modifier = Modifier.fillMaxSize().padding(TalentForgeSpacing.screenX),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        Spacer(Modifier.height(TalentForgeSpacing.section))
        AppTitle()
        SmokeText("M3A foundation smoke host — not production UI")
        Spacer(Modifier.height(TalentForgeSpacing.group))
        SnapshotCard {
            SnapshotRow("game", snapshot.game.value.replaceFirstChar { it.uppercase() })
            SnapshotRow("client build", snapshot.clientBuild)
            SnapshotRow("classes loaded", snapshot.classCount.toString())
            SnapshotRow("talent nodes", snapshot.nodeCount.toString())
            SnapshotRow("dataset contract", "v${snapshot.schemaVersion}")
        }
    }
}

@Composable
private fun ErrorPane(reason: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(TalentForgeSpacing.screenX),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        Spacer(Modifier.height(TalentForgeSpacing.section))
        AppTitle()
        Spacer(Modifier.height(TalentForgeSpacing.group))
        SnapshotCard {
            SmokeText(
                "bundled dataset failed to load",
                color = talentForgeColors().danger,
                style = TalentForgeTypography.value,
            )
            SmokeText(reason, style = TalentForgeTypography.bodySmall)
        }
    }
}

@Composable
private fun AppTitle() {
    SmokeText(
        "TalentForge",
        style = TalentForgeTypography.title,
        color = talentForgeColors().textPrimary,
    )
}

@Composable
private fun SmokeText(
    text: String,
    color: Color = talentForgeColors().textSecondary,
    style: TextStyle = TalentForgeTypography.body,
) {
    BasicText(
        text = text,
        style = style.copy(color = color),
    )
}

@Composable
private fun SnapshotCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(TalentForgeRadius.large)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(talentForgeColors().surfaceElevated, shape)
            .border(width = 1.dp, color = talentForgeColors().borderDefault, shape = shape)
            .padding(TalentForgeSpacing.group),
        verticalArrangement = Arrangement.spacedBy(TalentForgeSpacing.inline),
    ) {
        content()
    }
}

@Composable
private fun SnapshotRow(label: String, value: String) {
    Column {
        SmokeText(
            label,
            style = TalentForgeTypography.label,
            color = talentForgeColors().textMuted,
        )
        SmokeText(
            value,
            style = TalentForgeTypography.value,
            color = talentForgeColors().textPrimary,
        )
    }
}
