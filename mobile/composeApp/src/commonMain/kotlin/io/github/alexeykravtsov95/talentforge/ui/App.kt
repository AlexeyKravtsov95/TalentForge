package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildClock
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildIdGenerator
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.builds.RandomLocalBuildIdGenerator
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.builds.AllBuildsScreen
import io.github.alexeykravtsov95.talentforge.ui.home.HomeScreen
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.platformBuildClock
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.newbuild.NewBuildScreen
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeBuildScreen
import kotlinx.coroutines.launch

/**
 * The app's destination model (UX/UI spec 02 §1 — "Root: Home. No persistent
 * navigation; native system back everywhere"):
 *
 * - Home → New build → pick class: the tree REPLACES New build, so Back from
 *   a new draft goes to Home;
 * - Home → Continue / Recent → tree (push): Back → Home;
 * - Home → Builds → tree (push): Back → Builds.
 *
 * The editor opens by opaque local build id — never by passing mutable UI
 * state between screens. Navigation state is not the persistence model: the
 * back stack persists ONLY these navigation facts (see [DestinationStackSaver])
 * across configuration restoration, never editor/build state — the editing
 * session restores through its own M4.3 saveable form.
 */
sealed interface AppDestination {
    data object Home : AppDestination

    data object NewBuild : AppDestination

    data object AllBuilds : AppDestination

    data class Editor(
        val localId: String,
        val openedFrom: EditorOrigin,
    ) : AppDestination
}

enum class EditorOrigin { CreatedNew, FromHome, FromAllBuilds }

/**
 * Save/restore of the back stack as plain strings (`home`, `new-build`,
 * `all-builds`, `editor␟localId␟origin`). A malformed entry fails closed to
 * the initial stack (Home only) — navigation state is not user data, and a
 * corrupt bundle must never restore a wrong screen.
 */
internal val DestinationStackSaver = listSaver<List<AppDestination>, String>(
    save = { stack -> stack.map(::encodeDestination) },
    restore = { values -> decodeDestinations(values) ?: listOf(AppDestination.Home) },
)

private const val DESTINATION_SEPARATOR = ""

internal fun encodeDestination(destination: AppDestination): String = when (destination) {
    AppDestination.Home -> "home"
    AppDestination.NewBuild -> "new-build"
    AppDestination.AllBuilds -> "all-builds"
    is AppDestination.Editor ->
        "editor$DESTINATION_SEPARATOR${destination.localId}$DESTINATION_SEPARATOR${destination.openedFrom.name}"
}

internal fun decodeDestinations(values: List<String>): List<AppDestination>? {
    if (values.isEmpty()) return null
    val decoded = values.map { value ->
        when (value) {
            "home" -> AppDestination.Home
            "new-build" -> AppDestination.NewBuild
            "all-builds" -> AppDestination.AllBuilds
            else -> {
                val parts = value.split(DESTINATION_SEPARATOR)
                val localId = parts.getOrNull(1)
                val origin = parts.getOrNull(2)
                    ?.let { part -> EditorOrigin.entries.firstOrNull { it.name == part } }
                // exactly three fields — trailing drift fails closed to Home
                if (parts.size != 3 || parts.first() != "editor" || localId.isNullOrEmpty() || origin == null) {
                    null
                } else {
                    AppDestination.Editor(localId = localId, openedFrom = origin)
                }
            }
        }
    }
    return if (decoded.any { it == null }) null else decoded.filterNotNull()
}

/**
 * App entry shared by the Android, iOS and desktop hosts.
 *
 * Since M5.1 the app is a local multi-build application: the host injects its
 * platform [storage] (and, in tests, a fixed clock/id generator); Home is the
 * production root and a small explicit back stack — no navigation framework
 * for four local screens — routes Home, New Build, All Builds and the
 * dataset-bound editor. The stack is `rememberSaveable`, so a configuration
 * change (Activity recreation) restores the exact destination. Android's
 * system back and the visible back affordances share one pop, and popping an
 * editor first FLUSHES the app-level autosave coordinator: navigation
 * completes only after the newest current BuildState is confirmed persisted —
 * if the newest save failed, navigation visibly stays put with the retryable
 * error notice (the in-memory build never rolls back).
 */
@Composable
fun App(
    storage: BuildLibraryStorage,
    clock: BuildClock = platformBuildClock(),
    ids: LocalBuildIdGenerator = RandomLocalBuildIdGenerator(clock),
) {
    val environment = remember(storage, clock, ids) {
        AppEnvironment(
            repository = LocalBuildRepository(storage, clock, ids),
            datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource()),
        )
    }
    val appScope = rememberCoroutineScope()
    // The autosave pipeline lives ABOVE every destination: leaving the editor
    // never cancels a pending save (its consumer runs in the app scope).
    val autosave = remember(environment) {
        BuildAutosaveCoordinator(environment.repository, appScope)
    }
    var destinations by rememberSaveable(stateSaver = DestinationStackSaver) {
        mutableStateOf<List<AppDestination>>(listOf(AppDestination.Home))
    }

    fun navigateUp() {
        if (destinations.size <= 1) return
        appScope.launch {
            val result = autosave.flush()
            if (result == null || !result.failed) {
                destinations = destinations.dropLast(1)
            }
            // else: the newest build is not on storage — stay on the editor;
            // its save-error notice stays visible and retryable
        }
    }

    PlatformBackHandler(enabled = destinations.size > 1, onBack = ::navigateUp)

    TalentForgeTheme {
        when (val destination = destinations.last()) {
            AppDestination.Home -> HomeScreen(
                repository = environment.repository,
                datasets = environment.datasets,
                clockMillis = clock::nowMillis,
                onOpenBuild = { localId ->
                    destinations = destinations + AppDestination.Editor(localId, EditorOrigin.FromHome)
                },
                onAllBuilds = { destinations = destinations + AppDestination.AllBuilds },
                onNewBuild = { destinations = destinations + AppDestination.NewBuild },
            )

            AppDestination.NewBuild -> NewBuildScreen(
                repository = environment.repository,
                datasets = environment.datasets,
                onBack = ::navigateUp,
                onDraftCreated = { localId ->
                    // the tree REPLACES New build (spec 02 §1): pop this screen
                    // and push the editor, so Back from the new draft → Home
                    destinations = destinations.dropLast(1) +
                        AppDestination.Editor(localId, EditorOrigin.CreatedNew)
                },
            )

            AppDestination.AllBuilds -> AllBuildsScreen(
                repository = environment.repository,
                datasets = environment.datasets,
                clockMillis = clock::nowMillis,
                onBack = ::navigateUp,
                onOpenBuild = { localId ->
                    destinations = destinations + AppDestination.Editor(localId, EditorOrigin.FromAllBuilds)
                },
                onNewBuild = { destinations = destinations + AppDestination.NewBuild },
            )

            is AppDestination.Editor -> TalentTreeBuildScreen(
                localId = destination.localId,
                repository = environment.repository,
                datasets = environment.datasets,
                autosave = autosave,
                onBack = ::navigateUp,
            )
        }
    }
}

/** The app's two data collaborators, built once per app run. */
private data class AppEnvironment(
    val repository: LocalBuildRepository,
    val datasets: BundledTalentDatasetRepository,
)
