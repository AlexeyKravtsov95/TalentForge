package io.github.alexeykravtsov95.talentforge.data.ota

/** Contract §6.2 decisions — the same names as the reference's `ActivationDecision`. */
enum class ActivationDecision {
    /** Install the catalog's active release (§6.3–6.4), then make it active (§7). */
    ACTIVATE,

    /** The active release is already the catalog's: nothing is downloaded. */
    NO_CHANGE,

    /** The active release needs a newer app (`minDataLevel` above the app's level). */
    KEEP_CURRENT_INCOMPATIBLE,

    /** The active release is older than the bundled product, the floor of every rollback (OD-3). */
    KEEP_BUNDLED_FLOOR,
}

/**
 * Contract §6.2 (`decide_activation`): given a verified catalog, what the client does. The
 * catalog's `activeReleaseId` is the only release ever activated automatically — never "the
 * newest release the app can read" — and never one older than the bundled product.
 */
object ActivationPolicy {
    /**
     * @param currentReleaseId the active OTA release, `null` while the bundled product is in use.
     * @return the decision and the catalog's active release it is about.
     */
    fun decide(
        verified: VerifiedCatalog,
        supportedDataLevel: Int,
        bundledProductBuild: String,
        currentReleaseId: String?,
    ): Pair<ActivationDecision, CatalogRelease> {
        val active = verified.activeRelease
        val decision = when {
            active.minDataLevel > supportedDataLevel -> ActivationDecision.KEEP_CURRENT_INCOMPATIBLE
            ClientBuildVersion.parse(active.clientBuild) < ClientBuildVersion.parse(bundledProductBuild) ->
                ActivationDecision.KEEP_BUNDLED_FLOOR

            currentReleaseId == active.releaseId -> ActivationDecision.NO_CHANGE
            else -> ActivationDecision.ACTIVATE
        }
        return decision to active
    }
}
