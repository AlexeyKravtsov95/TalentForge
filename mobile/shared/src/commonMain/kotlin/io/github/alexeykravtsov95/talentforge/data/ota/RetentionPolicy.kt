package io.github.alexeykravtsov95.talentforge.data.ota

/** One staging directory as listed on disk, with its decoded `attempt.json` (if any). */
data class StagingEntry(val name: String, val attempt: InstallAttempt?)

/** What retention may delete — and only that. Quarantine and trash are always cleared. */
data class RetentionPlan(
    val deleteReleases: Set<String>,
    val deleteStaging: Set<String>,
)

/**
 * Retention (client design §5.3, contract §7.5) as a pure function.
 *
 * Kept, always: the active release, the previous release, every leased release (an open
 * editor), and for every client build a saved build references that is **not** bundled, its
 * newest installed revision. Everything else installed may go. When the saved-build library
 * cannot be read ([libraryBuilds] `null`), no installed release is deleted — only stale staging
 * (and quarantine and trash).
 *
 * Runs only after a successful catalog check or an explicit "free up space" action, never at
 * startup before the first snapshot and never during an install; the caller guarantees that.
 */
object RetentionPolicy {

    fun plan(
        state: OtaState,
        installed: List<InstalledRelease>,
        libraryBuilds: Set<String>?,
        leased: Set<String>,
        bundledBuilds: Set<String>,
        staging: List<StagingEntry>,
        runningAttempt: String?,
        catalog: OtaCatalog?,
        onDemand: Set<String>,
        now: UtcInstant,
    ): RetentionPlan {
        val keep = HashSet<String>()
        state.active?.let(keep::add)
        state.previous?.let(keep::add)
        keep += leased
        val neededBuilds = libraryBuilds.orEmpty() - bundledBuilds
        for (build in neededBuilds) {
            installed.filter { it.clientBuild == build }
                .maxByOrNull { ReleaseId.parseOrNull(it.releaseId)!! }
                ?.let { keep += it.releaseId }
        }
        val deleteReleases = if (libraryBuilds == null) {
            emptySet()
        } else {
            installed.map { it.releaseId }.filterNot { it in keep }.toSet()
        }

        val deleteStaging = if (catalog == null) {
            emptySet()
        } else {
            val wanted = HashSet<String>()
            wanted += catalog.activeReleaseId
            wanted += onDemand
            for (entry in staging) {
                val id = ReleaseId.parseOrNull(entry.name) ?: continue
                if (id.clientBuild in neededBuilds) wanted += entry.name
            }
            staging.filter { it.name != runningAttempt }
                .filter { StagingPolicy.isStale(it.name, it.attempt, catalog, wanted, now) }
                .map { it.name }
                .toSet()
        }
        return RetentionPlan(deleteReleases, deleteStaging)
    }
}
