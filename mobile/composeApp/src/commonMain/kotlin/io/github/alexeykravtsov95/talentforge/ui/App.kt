package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildClock
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildIdGenerator
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.builds.RandomLocalBuildIdGenerator
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.builds.AllBuildsScreen
import io.github.alexeykravtsov95.talentforge.ui.home.HomeScreen
import io.github.alexeykravtsov95.talentforge.ui.library.platformBuildClock
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.newbuild.NewBuildScreen
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentTreeBuildScreen

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
 * state between screens. Navigation state is not the persistence model.
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
 * App entry shared by the Android, iOS and desktop hosts.
 *
 * Since M5.1 the app is a local multi-build application: the host injects its
 * platform [storage] (and, in tests, a fixed clock/id generator); Home is the
 * production root and a small explicit back stack — no navigation framework
 * for four local screens — routes Home, New Build, All Builds and the
 * dataset-bound editor. Android's system back and the visible back
 * affordances share one pop.
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
    val backStack = remember { mutableStateListOf<AppDestination>(AppDestination.Home) }

    fun navigateUp() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    PlatformBackHandler(enabled = backStack.size > 1, onBack = ::navigateUp)

    TalentForgeTheme {
        when (val destination = backStack.last()) {
            AppDestination.Home -> HomeScreen(
                repository = environment.repository,
                datasets = environment.datasets,
                clockMillis = clock::nowMillis,
                onOpenBuild = { localId ->
                    backStack.add(AppDestination.Editor(localId, EditorOrigin.FromHome))
                },
                onAllBuilds = { backStack.add(AppDestination.AllBuilds) },
                onNewBuild = { backStack.add(AppDestination.NewBuild) },
            )

            AppDestination.NewBuild -> NewBuildScreen(
                repository = environment.repository,
                datasets = environment.datasets,
                onBack = ::navigateUp,
                onDraftCreated = { localId ->
                    // the tree REPLACES New build (spec 02 §1): pop this screen
                    // and push the editor, so Back from the new draft → Home
                    backStack.removeAt(backStack.lastIndex)
                    backStack.add(AppDestination.Editor(localId, EditorOrigin.CreatedNew))
                },
            )

            AppDestination.AllBuilds -> AllBuildsScreen(
                repository = environment.repository,
                datasets = environment.datasets,
                clockMillis = clock::nowMillis,
                onBack = ::navigateUp,
                onOpenBuild = { localId ->
                    backStack.add(AppDestination.Editor(localId, EditorOrigin.FromAllBuilds))
                },
                onNewBuild = { backStack.add(AppDestination.NewBuild) },
            )

            is AppDestination.Editor -> TalentTreeBuildScreen(
                localId = destination.localId,
                repository = environment.repository,
                datasets = environment.datasets,
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
