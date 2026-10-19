package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildClock
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildIdGenerator
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.builds.RandomLocalBuildIdGenerator
import io.github.alexeykravtsov95.talentforge.ui.about.AboutScreen
import io.github.alexeykravtsov95.talentforge.ui.about.AppVersion
import io.github.alexeykravtsov95.talentforge.ui.about.LicensesScreen
import io.github.alexeykravtsov95.talentforge.ui.about.platformBackupStatement
import io.github.alexeykravtsov95.talentforge.ui.about.platformOpenSourceNoticesPath
import io.github.alexeykravtsov95.talentforge.ui.about.privacyPolicyUrlOf
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.builds.AllBuildsScreen
import io.github.alexeykravtsov95.talentforge.ui.home.HomeScreen
import io.github.alexeykravtsov95.talentforge.ui.lang.appLanguage
import io.github.alexeykravtsov95.talentforge.ui.library.BuildAutosaveCoordinator
import io.github.alexeykravtsov95.talentforge.ui.library.platformBuildClock
import io.github.alexeykravtsov95.talentforge.ui.navigation.PlatformBackHandler
import io.github.alexeykravtsov95.talentforge.ui.ota.DatasetResolution
import io.github.alexeykravtsov95.talentforge.ui.ota.DatasetLeaseHolder
import io.github.alexeykravtsov95.talentforge.ui.ota.EditorDatasetAccess
import io.github.alexeykravtsov95.talentforge.ui.ota.OtaRuntime
import io.github.alexeykravtsov95.talentforge.ui.ota.SessionDatasets
import io.github.alexeykravtsov95.talentforge.ui.newbuild.NewBuildScreen
import io.github.alexeykravtsov95.talentforge.ui.share.BuildCodeSharing
import io.github.alexeykravtsov95.talentforge.ui.share.ImportBuildScreen
import io.github.alexeykravtsov95.talentforge.ui.share.rememberPlatformBuildCodeSharing
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
 * - Home → Builds → tree (push): Back → Builds;
 * - Home or Builds → Import build (push, M5.SHARE.2): Back → where it was
 *   opened; a confirmed import REPLACES the import screen with the new
 *   record's editor, so Back from it returns to Home or Builds likewise;
 * - Home → About (push, M5.RC.2b): Back → Home;
 * - About → Third-party licenses (push, M5.UI.ABOUT.1): Back → About.
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

    data object ImportBuild : AppDestination

    data object About : AppDestination

    data object Licenses : AppDestination

    data class Editor(
        val localId: String,
        val openedFrom: EditorOrigin,
    ) : AppDestination
}

enum class EditorOrigin { CreatedNew, FromHome, FromAllBuilds, Imported }

/**
 * Save/restore of the back stack as plain strings (`home`, `new-build`,
 * `all-builds`, `import-build`, `about`, `licenses`, `editor␟localId␟origin`). A decoded stack must be non-empty
 * and rooted at Home — anything else (blank, malformed entries, a stack that
 * does not start with Home) fails closed to the initial Home-only stack:
 * navigation state is not user data, and a corrupt bundle must never restore
 * a wrong or rootless screen.
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
    AppDestination.ImportBuild -> "import-build"
    AppDestination.About -> "about"
    AppDestination.Licenses -> "licenses"
    is AppDestination.Editor ->
        "editor$DESTINATION_SEPARATOR${destination.localId}$DESTINATION_SEPARATOR${destination.openedFrom.name}"
}

internal fun decodeDestinations(values: List<String>): List<AppDestination>? {
    // the stack is always rooted at Home; a rootless/plain-value payload is
    // corrupt and must not restore anything
    if (values.isEmpty() || values.first() != encodeDestination(AppDestination.Home)) return null
    val decoded = values.map { value ->
        when (value) {
            "home" -> AppDestination.Home
            "new-build" -> AppDestination.NewBuild
            "all-builds" -> AppDestination.AllBuilds
            "import-build" -> AppDestination.ImportBuild
            "about" -> AppDestination.About
            "licenses" -> AppDestination.Licenses
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
 * platform [storage] and its installed [appVersion] (About; `null` when the
 * host cannot report one) (and, in tests, a fixed clock/id generator); Home is the
 * production root and a small explicit back stack — no navigation framework
 * for four local screens — routes Home, New Build, All Builds and the
 * dataset-bound editor. The stack is `rememberSaveable`, so a configuration
 * change (Activity recreation) restores the exact destination. Android's
 * system back and the visible back affordances share one pop, at most one pop
 * is ever in flight (repeated Back during an autosave flush is a no-op), and
 * popping an editor first FLUSHES the app-level autosave coordinator:
 * navigation completes only after the newest current BuildState is confirmed
 * persisted — if the newest save failed, navigation visibly stays put with
 * the retryable error notice (the in-memory build never rolls back).
 */
@Composable
fun App(
    storage: BuildLibraryStorage,
    clock: BuildClock = platformBuildClock(),
    ids: LocalBuildIdGenerator = RandomLocalBuildIdGenerator(clock),
    sharing: BuildCodeSharing = rememberPlatformBuildCodeSharing(),
    appVersion: AppVersion? = null,
    /**
     * M5.OTA.3: the OTA client of this process, already recovered (`OtaRuntime.open`), or `null`
     * when the platform's OTA configuration is disabled — then the app runs exactly as before,
     * on its bundled datasets only.
     */
    ota: OtaRuntime? = null,
) {
    val environment = remember(storage, clock, ids) {
        AppEnvironment(
            repository = LocalBuildRepository(storage, clock, ids),
            // One session per app run without OTA: decoded icons are cached across editor opens.
            bundled = SessionDatasets.bundled(ComposeResourceDatasetAssetSource()),
        )
    }
    // With OTA every published snapshot gets its own collaborators (loaders cache per snapshot,
    // so two releases are never mixed); each screen takes the current one when it opens.
    val snapshot = ota?.snapshot?.collectAsState()?.value
    val latestSession = remember(environment, snapshot) {
        if (snapshot == null) environment.bundled else SessionDatasets.of(snapshot)
    }
    val otaStatus = ota?.status?.collectAsState()?.value
    if (ota != null) {
        // background check after the first frame, never before: the UI never waits for the network
        LaunchedEffect(ota) {
            withFrameNanos { }
            ota.coordinator.onAppStarted()
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, ota) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> ota.coordinator.onForeground()
                    Lifecycle.Event.ON_STOP -> ota.coordinator.onBackground()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
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

    // One logical pop at a time: repeated Back (chevron or Android system
    // back) while a pop's autosave flush is still suspended must not launch a
    // second pop — two concurrent pops could remove Home and leave an empty
    // stack. The flag is set synchronously in the click handler (before any
    // suspension), and only the coroutine that set it clears it. Plain
    // remember, deliberately not saveable: a composition that dies mid-flush
    // never popped, and a fresh composition starts with no pop in flight.
    val popInFlight = remember { mutableStateOf(false) }

    fun navigateUp() {
        if (destinations.size <= 1) return
        if (popInFlight.value) return
        popInFlight.value = true
        appScope.launch {
            try {
                val result = autosave.flush()
                if (result == null || !result.failed) {
                    // re-verify after the suspended flush: Home can never be
                    // removed even if the stack changed while we waited
                    if (destinations.size > 1) {
                        destinations = destinations.dropLast(1)
                    }
                }
                // else: the newest build is not on storage — stay on the
                // editor; its save-error notice stays visible and retryable
            } finally {
                popInFlight.value = false
            }
        }
    }

    PlatformBackHandler(enabled = destinations.size > 1, onBack = ::navigateUp)

    TalentForgeTheme {
        val destination = destinations.last()
        // the screen keeps the session it opened with (client design §4.5): an activation while
        // it is shown publishes a new snapshot only for screens opened afterwards
        val session = remember(destination, destinations.size) { latestSession }
        when (destination) {
            AppDestination.Home -> HomeScreen(
                repository = environment.repository,
                datasets = session.datasets,
                clockMillis = clock::nowMillis,
                onOpenBuild = { localId ->
                    destinations = destinations + AppDestination.Editor(localId, EditorOrigin.FromHome)
                },
                onAllBuilds = { destinations = destinations + AppDestination.AllBuilds },
                onNewBuild = { destinations = destinations + AppDestination.NewBuild },
                onImportBuild = { destinations = destinations + AppDestination.ImportBuild },
                onAbout = { destinations = destinations + AppDestination.About },
                autosave = autosave,
                sharing = sharing,
                product = session.product,
                dataUpdatedTo = otaStatus?.updatedTo,
                onDismissDataUpdate = { ota?.coordinator?.acknowledgeUpdate() },
            )

            AppDestination.About -> AboutScreen(
                appVersion = appVersion,
                backupStatement = platformBackupStatement,
                privacyPolicyUrl = privacyPolicyUrlOf(appLanguage()),
                onBack = ::navigateUp,
                onOpenLicenses = { destinations = destinations + AppDestination.Licenses },
                otaStatus = otaStatus,
                onCheckForUpdates = ota?.let { runtime -> { appScope.launch { runtime.coordinator.checkNow() } } },
            )

            AppDestination.Licenses -> LicensesScreen(
                noticesPath = platformOpenSourceNoticesPath,
                onBack = ::navigateUp,
            )

            AppDestination.NewBuild -> NewBuildScreen(
                repository = environment.repository,
                datasets = session.datasets,
                product = session.product,
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
                datasets = session.datasets,
                clockMillis = clock::nowMillis,
                onBack = ::navigateUp,
                onOpenBuild = { localId ->
                    destinations = destinations + AppDestination.Editor(localId, EditorOrigin.FromAllBuilds)
                },
                onNewBuild = { destinations = destinations + AppDestination.NewBuild },
                onImportBuild = { destinations = destinations + AppDestination.ImportBuild },
                sharing = sharing,
                autosave = autosave,
            )

            AppDestination.ImportBuild -> ImportBuildScreen(
                repository = environment.repository,
                datasets = session.datasets,
                sharing = sharing,
                onBack = ::navigateUp,
                resolveDataset = ota?.let { runtime ->
                    // a snapshot downloaded for the preview stays leased while this screen is open
                    val leases = remember(destination, destinations.size) { DatasetLeaseHolder() }
                    val resolve: suspend (DatasetBundleId) -> TalentDatasetRepository? = { bundle ->
                        (runtime.resolver.resolve(bundle) as? DatasetResolution.Available)?.let {
                            leases.hold(it.lease)
                            SessionDatasets.of(it.snapshot).datasets
                        }
                    }
                    resolve
                },
                onImported = { localId ->
                    // storage has confirmed the record: the editor REPLACES the
                    // import screen, so Back from it returns to Home or Builds.
                    // Were the stack to have moved on meanwhile, the record
                    // simply stays listed in All Builds.
                    if (destinations.last() == AppDestination.ImportBuild) {
                        destinations = destinations.dropLast(1) +
                            AppDestination.Editor(localId, EditorOrigin.Imported)
                    }
                },
            )

            is AppDestination.Editor -> {
                // an open editor leases the releases of the snapshot it actually reads — the one
                // it opened with, or the one its build is downloaded into — until it closes (§4.5)
                val access = remember(destination, destinations.size) { ota?.let { EditorDatasetAccess.open(it, session) } }
                val editorSession = access?.session ?: session
                TalentTreeBuildScreen(
                    localId = destination.localId,
                    repository = environment.repository,
                    datasets = editorSession.datasets,
                    autosave = autosave,
                    onBack = ::navigateUp,
                    icons = editorSession.icons,
                    descriptions = editorSession.descriptions,
                    localization = editorSession.localization,
                    sharing = sharing,
                    datasetAccess = access,
                )
            }
        }
    }
}

/** The app's data collaborators, built once per app run. */
private data class AppEnvironment(
    val repository: LocalBuildRepository,
    /** The bundled-only session (OTA disabled): today's wiring, unchanged. */
    val bundled: SessionDatasets,
)
