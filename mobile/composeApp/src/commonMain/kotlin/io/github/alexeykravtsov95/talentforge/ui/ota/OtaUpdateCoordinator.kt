package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.ActivationDecision
import io.github.alexeykravtsov95.talentforge.data.ota.ActivationPolicy
import io.github.alexeykravtsov95.talentforge.data.ota.OtaClock
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFailure
import io.github.alexeykravtsov95.talentforge.data.ota.OtaIoException
import io.github.alexeykravtsov95.talentforge.data.ota.OtaResult
import io.github.alexeykravtsov95.talentforge.data.ota.RetentionPolicy
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import io.github.alexeykravtsov95.talentforge.data.ota.VerifiedCatalog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How one update check ended. */
sealed interface CheckOutcome {
    /** Nothing to install: `NO_CHANGE` or `KEEP_BUNDLED_FLOOR` (contract §6.2). */
    data class Current(val decision: ActivationDecision, val releaseId: String) : CheckOutcome

    /** The catalog's active release needs a newer app (`KEEP_CURRENT_INCOMPATIBLE`); nothing was downloaded. */
    data class NeedsNewerApp(val releaseId: String, val minDataLevel: Int) : CheckOutcome

    /** The catalog's active release was installed (or found installed) and activated for new sessions. */
    data class Activated(val releaseId: String, val clientBuild: String, val report: InstallReport) : CheckOutcome

    /** The attempt was abandoned; the active dataset and the library are untouched (contract §11). */
    data class Failed(val failure: OtaFailure) : CheckOutcome
}

/** What the UI may show about OTA (OD-5): the data version in use, a check in progress, the last outcome. */
data class OtaStatus(
    val productBuild: String,
    val productReleaseId: String?,
    val checking: Boolean = false,
    val progress: InstallProgress? = null,
    val lastOutcome: CheckOutcome? = null,
    val lastSuccessfulCheckAt: UtcInstant? = null,
    /** The product build an activation of this app run switched new builds to (the one-time "updated" line). */
    val updatedTo: String? = null,
)

/**
 * When and how the app checks for a new release (client design §4.2): after the first frame,
 * on return to the foreground when the last successful check is older than [CHECK_INTERVAL_SECONDS],
 * and on the explicit "check now" action. One attempt at a time — a trigger while one runs joins
 * it. Failures back off (1 h, 2 h, 4 h … 24 h) for the automatic triggers. Leaving the app
 * cancels the attempt cooperatively; its staging directory stays for the next one.
 *
 * Nothing here blocks the UI: the first screen is built from the recovered local state, and a
 * check only ever publishes a new snapshot for sessions opened afterwards.
 */
class OtaUpdateCoordinator(
    private val config: OtaConfig,
    private val catalog: RemoteDatasetCatalog,
    private val downloads: DatasetDownloadManager,
    private val activation: DatasetActivationManager,
    private val cache: DatasetCache,
    private val bundle: BundleInventory,
    private val clock: OtaClock,
    private val leases: SnapshotLeases,
    /** The client builds the saved-build library references; `null` when it cannot be read. Read-only. */
    private val libraryBuilds: suspend () -> Set<String>?,
    private val scope: CoroutineScope,
) {
    private val gate = Mutex()
    private var current: Deferred<CheckOutcome>? = null
    private var consecutiveFailures = 0
    private var nextAutomaticCheck: UtcInstant? = null

    private val mutableStatus = MutableStateFlow(statusFromSnapshot())
    val status: StateFlow<OtaStatus> = mutableStatus.asStateFlow()

    /** After the first frame (never before: the UI does not wait for the network). */
    fun onAppStarted() {
        scope.launch { start(automatic = true, ignoreSchedule = true) }
    }

    /** Return to the foreground: checks when the last successful check is older than the interval. */
    fun onForeground() {
        scope.launch { start(automatic = true, ignoreSchedule = false) }
    }

    /** Leaving the app cancels the attempt cooperatively (§4.2); staging stays for a resume. */
    fun onBackground() {
        scope.launch { gate.withLock { current?.cancel() } }
    }

    /** The explicit "check for talent data updates" action: ignores the schedule, joins a running attempt. */
    suspend fun checkNow(): CheckOutcome = start(automatic = false, ignoreSchedule = true)!!.await()

    /** The running attempt (or `null`), joined or started under one lock: two triggers never run two attempts. */
    private suspend fun start(automatic: Boolean, ignoreSchedule: Boolean): Deferred<CheckOutcome>? = gate.withLock {
        current?.takeIf { it.isActive }?.let { return@withLock it }
        if (automatic && !ignoreSchedule) {
            val due = nextAutomaticCheck
            if (due != null && clock.now() < due) return@withLock null
        }
        scope.async { runCheck() }.also { current = it }
    }

    private suspend fun runCheck(): CheckOutcome {
        mutableStatus.update { it.copy(checking = true, progress = null) }
        val productBefore = activation.snapshot.value.product
        val outcome = try {
            check()
        } catch (e: CancellationException) {
            mutableStatus.update { it.copy(checking = false, progress = null) }
            throw e
        } catch (e: OtaIoException) {
            CheckOutcome.Failed(OtaFailure.Io(e.message ?: "I/O failure"))
        }
        val now = clock.now()
        val succeeded = outcome !is CheckOutcome.Failed
        if (succeeded) {
            consecutiveFailures = 0
            nextAutomaticCheck = now.plusSeconds(CHECK_INTERVAL_SECONDS)
        } else {
            consecutiveFailures++
            val backoff = minOf(MAX_BACKOFF_SECONDS, FIRST_BACKOFF_SECONDS shl minOf(consecutiveFailures - 1, 5))
            nextAutomaticCheck = now.plusSeconds(backoff)
        }
        val productAfter = activation.snapshot.value.product
        mutableStatus.update {
            statusFromSnapshot().copy(
                lastOutcome = outcome,
                lastSuccessfulCheckAt = if (succeeded) now else it.lastSuccessfulCheckAt,
                updatedTo = if (productAfter != productBefore && outcome is CheckOutcome.Activated) productAfter.clientBuild else it.updatedTo,
            )
        }
        return outcome
    }

    /** The UI saw the "updated" line. */
    fun acknowledgeUpdate() {
        mutableStatus.update { it.copy(updatedTo = null) }
    }

    /** The automatic schedule: when the next automatic check may run (tests and diagnostics). */
    fun nextAutomaticCheckAt(): UtcInstant? = nextAutomaticCheck

    private suspend fun check(): CheckOutcome {
        val verified = when (val result = catalog.fetchAndAccept()) {
            is OtaResult.Err -> return CheckOutcome.Failed(result.failure)
            is OtaResult.Ok -> result.value
        }
        val (decision, active) = ActivationPolicy.decide(
            verified = verified,
            supportedDataLevel = config.supportedDataLevel,
            bundledProductBuild = bundle.product.clientBuild,
            currentReleaseId = activation.currentState().active,
        )
        val outcome = when (decision) {
            ActivationDecision.KEEP_CURRENT_INCOMPATIBLE -> CheckOutcome.NeedsNewerApp(active.releaseId, active.minDataLevel)
            ActivationDecision.KEEP_BUNDLED_FLOOR, ActivationDecision.NO_CHANGE -> CheckOutcome.Current(decision, active.releaseId)
            ActivationDecision.ACTIVATE -> when (
                val installed = downloads.install(active) { progress -> mutableStatus.update { it.copy(progress = progress) } }
            ) {
                is OtaResult.Err -> CheckOutcome.Failed(installed.failure)
                is OtaResult.Ok -> {
                    activation.activate(active.releaseId)
                    CheckOutcome.Activated(active.releaseId, active.clientBuild, installed.value)
                }
            }
        }
        collectGarbage(verified)
        return outcome
    }

    /** Retention after a successful catalog check (client design §5.3), never during an install. */
    private suspend fun collectGarbage(verified: VerifiedCatalog) {
        val running = downloads.runningAttempts()
        val library = try {
            libraryBuilds()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val plan = RetentionPolicy.plan(
            state = activation.currentState(),
            installed = activation.intactReleases().map { it.record },
            libraryBuilds = library,
            leased = leases.leased(),
            bundledBuilds = bundle.clientBuilds,
            staging = cache.stagingEntries().filterNot { it.name in running },
            runningAttempt = null,
            catalog = verified.catalog,
            onDemand = emptySet(),
            now = clock.now(),
        )
        activation.collectGarbage(plan)
    }

    private fun statusFromSnapshot(): OtaStatus {
        val snapshot = activation.snapshot.value
        return OtaStatus(snapshot.product.clientBuild, snapshot.productReleaseId)
    }

    companion object {
        /** Return to the foreground re-checks when the last successful check is older than 6 h (OD-5). */
        const val CHECK_INTERVAL_SECONDS: Long = 6L * 3600
        const val FIRST_BACKOFF_SECONDS: Long = 3600
        const val MAX_BACKOFF_SECONDS: Long = 24L * 3600
    }
}
