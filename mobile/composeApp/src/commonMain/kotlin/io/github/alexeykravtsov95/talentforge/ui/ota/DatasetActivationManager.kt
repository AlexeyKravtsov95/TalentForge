package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.ota.CatalogVerifier
import io.github.alexeykravtsov95.talentforge.data.ota.ClientBuildVersion
import io.github.alexeykravtsov95.talentforge.data.ota.DatasetSnapshot
import io.github.alexeykravtsov95.talentforge.data.ota.InstalledRelease
import io.github.alexeykravtsov95.talentforge.data.ota.OtaErrorCode
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaReleaseManifest
import io.github.alexeykravtsov95.talentforge.data.ota.OtaState
import io.github.alexeykravtsov95.talentforge.data.ota.RejectedRelease
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseId
import io.github.alexeykravtsov95.talentforge.data.ota.ReleaseVerifier
import io.github.alexeykravtsov95.talentforge.data.ota.RetentionPlan
import io.github.alexeykravtsov95.talentforge.data.ota.StagingPolicy
import io.github.alexeykravtsov95.talentforge.data.ota.VerifiedCatalog
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the app bundles: its product (the rollback floor, OD-3), every bundled build, and their source. */
class BundleInventory(
    val product: DatasetBundleId,
    val clientBuilds: Set<String>,
    val assets: DatasetAssetSource,
) {
    init {
        require(product.clientBuild in clientBuilds) { "the bundled product must be bundled" }
    }
}

/** One installed release that passed its read-time manifest check. */
class IntactRelease(val record: InstalledRelease, val manifest: OtaReleaseManifest)

/** What `recover()` found and did (diagnostics and tests). */
data class RecoveryReport(
    val stateReadable: Boolean,
    val deletedPartFiles: Int,
    val deletedStaging: List<Pair<String, StagingPolicy.GarbageReason>>,
    val quarantined: List<String>,
    val adopted: List<String>,
    val keptStaging: List<String>,
    val catalogPayloadAvailable: Boolean,
)

/**
 * Owns the persisted [OtaState] and every transition of it (client design §4.1, §4.3–§4.6):
 * startup recovery, catalog acceptance, install records, activation, rejection records,
 * quarantine and retention. Each transition is one atomic replace of `state.json` (see
 * [DatasetCache]) made under one lock, and is followed by a new immutable [DatasetSnapshot]
 * for screens opened afterwards — open sessions keep theirs.
 *
 * It never deletes a release that a state names unless retention decided so after a successful
 * check, never deletes anything because the state is unreadable, and has no access to the
 * saved-build library.
 */
class DatasetActivationManager(
    private val cache: DatasetCache,
    private val bundle: BundleInventory,
    /** Re-reads the persisted payload of the last accepted catalog (§7.2). */
    private val catalogs: CatalogVerifier,
    /** Where corruption reports found while reading a release are handled. */
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    @Volatile
    private var state: OtaState = OtaState.EMPTY

    @Volatile
    private var lastCatalog: VerifiedCatalog? = null

    @Volatile
    private var intact: Map<String, IntactRelease> = emptyMap()

    private var generation = 0L
    private val snapshots = MutableStateFlow(buildSnapshot())

    /** The snapshot new sessions are created with; activation publishes a new one. */
    val snapshot: StateFlow<DatasetSnapshot> = snapshots.asStateFlow()

    fun currentState(): OtaState = state

    /** The last accepted catalog, known without the network (§7.2); `null` before the first one. */
    fun lastAcceptedCatalog(): VerifiedCatalog? = lastCatalog

    fun intactRelease(releaseId: String): IntactRelease? = intact[releaseId]

    fun intactReleases(): Collection<IntactRelease> = intact.values

    /**
     * Startup recovery (contract §7.4, client design §4.1 and §4.7) — local only, before the
     * first screen. Deletes `state.json.tmp`, every `*.part`, `trash/` and safely identifiable
     * staging garbage; keeps every other staging directory for a resume; never deletes an
     * installed release (a broken one is moved to `quarantine/`); reads the state strictly
     * (unreadable → bundled product only, nothing deleted); falls back from a broken active
     * release to the previous one, else the bundle. A local I/O failure leaves the app on its
     * bundled data.
     */
    fun recover(): RecoveryReport = try {
        recoverOrThrow()
    } catch (e: OtaIoException) {
        state = OtaState.EMPTY
        intact = emptyMap()
        lastCatalog = null
        publish()
        RecoveryReport(false, 0, emptyList(), emptyList(), emptyList(), emptyList(), catalogPayloadAvailable = false)
    }

    private fun recoverOrThrow(): RecoveryReport {
        val store = cache.store
        store.delete(cache.stateTempPath)
        store.delete(cache.trashDir)
        val parts = cache.partFiles()
        parts.forEach(store::delete)

        val read = cache.readState()
        var current = (read as? DatasetCache.StateRead.Valid)?.state ?: OtaState.EMPTY
        val readable = read !is DatasetCache.StateRead.Unreadable

        // the payload of the last accepted catalog: named by the state, written before it
        val seen = current.catalog
        lastCatalog = seen?.let { s -> cache.readCatalogPayload(s.payloadSha256)?.let { catalogs.reloadAccepted(it, s) } }
        if (readable && seen != null) cache.deleteOtherCatalogPayloads(seen.payloadSha256)

        // installed releases: the recorded ones must be intact; unrecorded ones are adopted
        // when the last accepted catalog pins exactly their manifest bytes (a crash between the
        // staging rename and the state write), otherwise left untouched
        val found = HashMap<String, IntactRelease>()
        val quarantined = ArrayList<String>()
        val adopted = ArrayList<String>()
        val onDisk = cache.installedDirectoryNames().toSet()
        for (record in current.installed) {
            if (record.releaseId !in onDisk) {
                current = dropRelease(current, record.releaseId)
                continue
            }
            val manifest = cache.readReleaseManifest(record.releaseId)
                ?.let { ReleaseVerifier.readInstalledManifest(it, record.manifestSha256, record.releaseId, cache.game) }
            if (manifest == null || manifest.clientBuild != record.clientBuild) {
                cache.quarantine(record.releaseId)
                quarantined += record.releaseId
                current = dropRelease(current, record.releaseId)
            } else {
                found[record.releaseId] = IntactRelease(record, manifest)
            }
        }
        val catalog = lastCatalog
        if (catalog != null) {
            for (name in onDisk - found.keys - quarantined.toSet()) {
                adopt(name, catalog)?.let {
                    found[name] = it
                    current = current.withInstalled(it.record)
                    adopted += name
                }
            }
        }

        // staging: keep for resume unless safely identifiable garbage
        val deletedStaging = ArrayList<Pair<String, StagingPolicy.GarbageReason>>()
        val kept = ArrayList<String>()
        for (entry in cache.stagingEntries()) {
            when (val verdict = StagingPolicy.recoveryVerdict(entry.name, entry.attempt, current.installed)) {
                StagingPolicy.RecoveryVerdict.Keep -> kept += entry.name
                is StagingPolicy.RecoveryVerdict.Delete -> {
                    cache.deleteViaTrash(cache.stagingFor(entry.name))
                    deletedStaging += entry.name to verdict.reason
                }
            }
        }
        for (entry in store.list(cache.stagingDir).filterNot { it.isDirectory }) {
            store.delete("${cache.stagingDir}/${entry.name}")
        }
        store.delete(cache.trashDir) // garbage staging went through trash/

        if (readable && current != (read as? DatasetCache.StateRead.Valid)?.state) cache.writeState(current)
        state = current
        intact = found
        publish()
        return RecoveryReport(readable, parts.size, deletedStaging, quarantined, adopted, kept, catalogPayloadAvailable = lastCatalog != null)
    }

    private fun adopt(releaseId: String, catalog: VerifiedCatalog): IntactRelease? {
        val entry = catalog.catalog.release(releaseId) ?: return null
        val bytes = cache.readReleaseManifest(releaseId) ?: return null
        val manifest = ReleaseVerifier.readInstalledManifest(bytes, entry.manifestSha256, releaseId, cache.game) ?: return null
        if (manifest.clientBuild != entry.clientBuild) return null
        return IntactRelease(InstalledRelease(releaseId, entry.clientBuild, entry.manifestSha256), manifest)
    }

    private fun dropRelease(state: OtaState, releaseId: String): OtaState =
        if (state.active == releaseId) state.withoutBrokenActive() else state.withoutInstalled(releaseId)

    /**
     * Persists an accepted catalog (§6.1: before acting on it): the payload file first, then
     * the state that names it, then the payloads no state names any more. Unrecorded installed
     * releases this catalog pins are adopted in the same state write — after an unreadable state
     * this is how "the next valid catalog rebuilds the state" (§7.2).
     */
    suspend fun acceptCatalog(verified: VerifiedCatalog) = mutex.withLock {
        cache.writeCatalogPayload(verified.payloadSha256, verified.payload)
        var next = state.withCatalog(verified.seen)
        val found = HashMap(intact)
        for (name in cache.installedDirectoryNames()) {
            if (name in found) continue
            adopt(name, verified)?.let {
                found[name] = it
                next = next.withInstalled(it.record)
            }
        }
        cache.writeState(next)
        state = next
        lastCatalog = verified
        cache.deleteOtherCatalogPayloads(verified.payloadSha256)
        if (found.size != intact.size) {
            intact = found
            publish()
        }
    }

    /** Records a release that was just renamed into `releases/` (no activation). */
    suspend fun recordInstalled(release: IntactRelease) = mutex.withLock {
        val next = state.withInstalled(release.record)
        cache.writeState(next)
        state = next
        intact = intact + (release.record.releaseId to release)
        publish()
    }

    /**
     * Makes an installed, intact release active: `previous = active; active = id` in one atomic
     * state write (client design §4.3). Only then do **new** snapshots use it.
     */
    suspend fun activate(releaseId: String) = mutex.withLock {
        val release = requireNotNull(intact[releaseId]) { "release $releaseId is not installed" }
        val next = state.withInstalled(release.record).activated(releaseId)
        if (next != state) {
            cache.writeState(next)
            state = next
        }
        publish()
    }

    /** A release rejected for what it is: never retried for the same manifest bytes (§4.7). */
    suspend fun recordRejected(releaseId: String, manifestSha256: String, code: OtaErrorCode) = mutex.withLock {
        val next = state.withRejected(RejectedRelease(releaseId, manifestSha256, code))
        cache.writeState(next)
        state = next
    }

    /**
     * A file of an installed release failed its read-time check (client design §4.6): the
     * release is quarantined and new snapshots use the previous release or the bundle; the
     * next check installs it again. Open sessions keep their snapshot (that asset fails typed).
     */
    fun reportCorrupt(releaseId: String) {
        scope.launch { quarantineCorrupt(releaseId) }
    }

    suspend fun quarantineCorrupt(releaseId: String) = mutex.withLock {
        if (releaseId !in intact) return@withLock
        cache.quarantine(releaseId)
        val next = dropRelease(state, releaseId)
        cache.writeState(next)
        state = next
        intact = intact - releaseId
        publish()
    }

    /**
     * Executes a [RetentionPlan] (client design §5.3): the state forgets the releases first
     * (one atomic write), then their directories go through `trash/`; stale staging,
     * quarantine and trash are cleared. A crash in between leaves directories the state no
     * longer names — never a state naming a deleted release.
     */
    suspend fun collectGarbage(plan: RetentionPlan) = mutex.withLock {
        var next = state
        for (id in plan.deleteReleases) next = next.withoutInstalled(id)
        if (next != state) {
            cache.writeState(next)
            state = next
        }
        for (id in plan.deleteReleases) cache.deleteViaTrash(cache.releaseDir(id))
        for (name in plan.deleteStaging) cache.deleteViaTrash(cache.stagingFor(name))
        cache.clearQuarantine()
        cache.clearTrash()
        if (plan.deleteReleases.isNotEmpty()) {
            intact = intact - plan.deleteReleases
            publish()
        }
    }

    // ------------------------------------------------------------ snapshots

    private fun publish() {
        snapshots.value = buildSnapshot()
    }

    /**
     * The effective product (client design §4.4) is the active release when it is intact and
     * not older than the bundled product, otherwise the bundled product. Every other client
     * build is read from its newest installed revision, else from the bundle.
     */
    private fun buildSnapshot(): DatasetSnapshot {
        val sources = LinkedHashMap<DatasetBundleId, DatasetSnapshot.BuildSource>()
        for (build in bundle.clientBuilds) {
            sources[DatasetBundleId(bundle.product.game, build)] = DatasetSnapshot.BuildSource(bundle.assets, null)
        }
        val releases = intact.values.sortedBy { ReleaseId.parseOrNull(it.record.releaseId)!! }
        for (release in releases) sources[bundleOf(release)] = sourceOf(release)
        val active = state.active?.let { intact[it] }
        val product = if (active != null &&
            ClientBuildVersion.parse(active.record.clientBuild) >= ClientBuildVersion.parse(bundle.product.clientBuild)
        ) {
            bundleOf(active).also { sources[it] = sourceOf(active) }
        } else {
            bundle.product
        }
        return DatasetSnapshot(product, sources, ++generation)
    }

    private fun bundleOf(release: IntactRelease) = DatasetBundleId(GameId(cache.game), release.record.clientBuild)

    private fun sourceOf(release: IntactRelease) = DatasetSnapshot.BuildSource(
        ReleaseAssetSource(cache.store, cache.releaseDir(release.record.releaseId), release.record.releaseId, release.manifest) { id, _ ->
            reportCorrupt(id)
        },
        release.record.releaseId,
    )
}
