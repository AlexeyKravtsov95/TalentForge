package io.github.alexeykravtsov95.talentforge.data.ota

/**
 * The staging, resume and recovery policy of client design §4.7 (contract §7.3–§7.4) as pure
 * functions: the composeApp managers list directories, read `attempt.json` and query free
 * space, and ask this object what to do. Nothing here touches a file.
 *
 * A staging directory is **progress**, never a release: it survives process death so that
 * verified files are not downloaded again, it is never read for the UI, and it is never made
 * active.
 */
object StagingPolicy {

    /** Free space kept beyond what an attempt needs (contract §7.3). */
    const val SPACE_MARGIN_BYTES: Long = 10L * 1024 * 1024

    /** An attempt that has not progressed for this long is stale (contract §7.4). */
    const val STALE_AFTER_SECONDS: Long = 30L * 86_400

    // ---------------------------------------------------------------- startup (recover())

    enum class GarbageReason { NOT_A_RELEASE_ID, ATTEMPT_MISSING_OR_UNREADABLE, ATTEMPT_OF_ANOTHER_RELEASE, ALREADY_INSTALLED }

    sealed interface RecoveryVerdict {
        data object Keep : RecoveryVerdict

        data class Delete(val reason: GarbageReason) : RecoveryVerdict
    }

    /**
     * What `recover()` does with `staging/<name>/`: keep it for a later resume, unless it is
     * safely identifiable garbage — a name that is not a release id, a missing or unreadable
     * `attempt.json`, an `attempt.json` naming another release, or a release already installed
     * with the same manifest SHA-256. Staged files are not hashed here; the resume does that.
     */
    fun recoveryVerdict(name: String, attempt: InstallAttempt?, installed: List<InstalledRelease>): RecoveryVerdict = when {
        ReleaseId.parseOrNull(name) == null -> RecoveryVerdict.Delete(GarbageReason.NOT_A_RELEASE_ID)
        attempt == null -> RecoveryVerdict.Delete(GarbageReason.ATTEMPT_MISSING_OR_UNREADABLE)
        attempt.releaseId != name -> RecoveryVerdict.Delete(GarbageReason.ATTEMPT_OF_ANOTHER_RELEASE)
        installed.any { it.releaseId == name && it.manifestSha256 == attempt.manifestSha256 } ->
            RecoveryVerdict.Delete(GarbageReason.ALREADY_INSTALLED)

        else -> RecoveryVerdict.Keep
    }

    // ---------------------------------------------------------------- an attempt starts

    sealed interface AttemptStart {
        /** `attempt.json` pins the same manifest bytes as the verified catalog: reuse the staged files (after re-checking each). */
        data class Resume(val attempt: InstallAttempt) : AttemptStart

        /** The staging directory belongs to other bytes (or is unreadable): delete it and start from nothing. */
        data class Restart(val reason: RestartReason) : AttemptStart

        /** No staging directory yet: create it and write `attempt.json` atomically. */
        data object Create : AttemptStart
    }

    enum class RestartReason { ATTEMPT_MISSING_OR_UNREADABLE, DIFFERENT_MANIFEST }

    fun startAttempt(stagingExists: Boolean, attempt: InstallAttempt?, entry: CatalogRelease): AttemptStart = when {
        !stagingExists -> AttemptStart.Create
        attempt == null -> AttemptStart.Restart(RestartReason.ATTEMPT_MISSING_OR_UNREADABLE)
        !attempt.pins(entry) -> AttemptStart.Restart(RestartReason.DIFFERENT_MANIFEST)
        else -> AttemptStart.Resume(attempt)
    }

    // ---------------------------------------------------------------- free space

    sealed interface SpaceDecision {
        data object Proceed : SpaceDecision

        /** `INSUFFICIENT_STORAGE`: keep the verified staged files for a later resume. */
        data class InsufficientKeepStaging(val requiredBytes: Long, val availableBytes: Long) : SpaceDecision

        /** `INSUFFICIENT_STORAGE` below the margin itself: delete the staging directory too. */
        data class InsufficientDeleteStaging(val requiredBytes: Long, val availableBytes: Long) : SpaceDecision
    }

    /**
     * Before the first byte of an attempt (and after `ENOSPC`): free space must cover the files
     * still missing plus [SPACE_MARGIN_BYTES]. Below the margin itself the staging directory is
     * deleted so the app does not hold the device's last free space.
     */
    fun space(freeBytes: Long, missingBytes: Long): SpaceDecision {
        val required = missingBytes + SPACE_MARGIN_BYTES
        return when {
            freeBytes >= required -> SpaceDecision.Proceed
            freeBytes < SPACE_MARGIN_BYTES -> SpaceDecision.InsufficientDeleteStaging(required, freeBytes)
            else -> SpaceDecision.InsufficientKeepStaging(required, freeBytes)
        }
    }

    /** The bytes still to acquire: files of the manifest whose verified copy is not staged. */
    fun missingBytes(manifest: OtaReleaseManifest, verifiedStagedPaths: Set<String>): Long =
        manifest.files.filter { it.path !in verifiedStagedPaths }.sumOf { it.byteLength }

    // ---------------------------------------------------------------- an attempt ends

    sealed interface AttemptEnd {
        /** Keep `staging/X` for a resume; the state and the active dataset are untouched. */
        data object KeepForResume : AttemptEnd

        /** Delete `staging/X` and record `{X, manifestSha256, code}`; never retry these bytes. */
        data class RejectRelease(val code: OtaErrorCode) : AttemptEnd

        /** Delete `staging/X` (space below the margin); retried later like any transient failure. */
        data object DeleteStaging : AttemptEnd
    }

    fun attemptEnd(failure: OtaFailure, spaceDecision: SpaceDecision? = null): AttemptEnd = when {
        spaceDecision is SpaceDecision.InsufficientDeleteStaging -> AttemptEnd.DeleteStaging
        failure.installClass() == InstallFailureClass.RELEASE_REJECTED ->
            AttemptEnd.RejectRelease((failure as OtaFailure.Contract).code)

        else -> AttemptEnd.KeepForResume
    }

    // ---------------------------------------------------------------- retention (after a successful check)

    /**
     * Is `staging/<name>/` stale (contract §7.4, client design §5.3)? Only asked after a
     * successful catalog check, never for the directory of a running attempt: stale when the
     * release is no longer listed in the verified catalog, when it is not [wanted] (active,
     * needed by a saved build, or requested on demand), or when the attempt has not progressed
     * for [STALE_AFTER_SECONDS].
     */
    fun isStale(name: String, attempt: InstallAttempt?, catalog: OtaCatalog, wanted: Set<String>, now: UtcInstant): Boolean {
        if (catalog.release(name) == null) return true
        if (name !in wanted) return true
        val updatedAt = attempt?.updatedAt ?: return true
        return now.microsSince(updatedAt) > STALE_AFTER_SECONDS * 1_000_000L
    }
}
