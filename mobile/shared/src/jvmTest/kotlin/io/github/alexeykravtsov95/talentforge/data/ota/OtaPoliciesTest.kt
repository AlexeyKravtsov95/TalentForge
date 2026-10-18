package io.github.alexeykravtsov95.talentforge.data.ota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure §4.7 / §5.3 policies, the state codec and the failure classification. */
class OtaPoliciesTest {

    private val sha = "sha256:" + "a".repeat(64)
    private val otherSha = "sha256:" + "b".repeat(64)
    private val now = UtcInstant.parseOrNull("2026-10-18T12:00:00Z")!!

    private fun installed(id: String, sha256: String = sha) = InstalledRelease(id, id.substringBefore("-r"), sha256)

    private fun entry(id: String, sha256: String = sha, length: Long = 100) =
        CatalogRelease(id, id.substringBefore("-r"), 1, sha256, length, 1000, "2026-10-17T23:30:00Z")

    private fun attempt(id: String, sha256: String = sha, length: Long = 100, updatedAt: UtcInstant = now) =
        InstallAttempt(id, sha256, length, updatedAt, updatedAt)

    private fun catalog(vararg ids: String, active: String = ids.last()) = OtaCatalog(
        schemaVersion = 1,
        game = "forever",
        channel = OtaChannel.PRODUCTION,
        sequence = 3,
        issuedAt = "2026-10-18T00:00:00Z",
        expiresAt = "2026-12-02T00:00:00Z",
        activeReleaseId = active,
        releases = ids.map { entry(it) },
    )

    // ------------------------------------------------------------ state codec

    @Test
    fun theStateRoundTripsAndIsReadStrictly() {
        val state = OtaState(
            catalog = SeenCatalog(2, sha),
            active = "1.60.1.70450-r1",
            previous = "1.60.1.70374-r1",
            installed = listOf(installed("1.60.1.70374-r1"), installed("1.60.1.70450-r1", otherSha)),
            rejected = listOf(RejectedRelease("1.60.1.70450-r2", sha, OtaErrorCode.CONTENT_INVALID)),
        )
        val bytes = OtaStateCodec.encode(state, "forever", OtaChannel.PRODUCTION)
        assertEquals(state, OtaStateCodec.decodeOrNull(bytes, "forever", OtaChannel.PRODUCTION))
        // another game or channel is not this channel's state
        assertNull(OtaStateCodec.decodeOrNull(bytes, "forever", OtaChannel.STAGING))
        assertNull(OtaStateCodec.decodeOrNull(bytes, "other", OtaChannel.PRODUCTION))
        val text = bytes.decodeToString()
        for (broken in listOf(
            "garbage",
            "",
            text.dropLast(1),
            text.replace("\"sequence\":2", "\"sequence\":\"2\""),
            text.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            text.replace("\"active\":\"1.60.1.70450-r1\"", "\"active\":\"1.60.1.70009-r1\""),
            text.replace("{\"format\"", "{\"extra\":1,\"format\""),
            text.replace("\"format\"", "\"format\":\"x\",\"format\""),
            text.replace("CONTENT_INVALID", "SOMETHING_ELSE"),
        )) {
            assertNull(OtaStateCodec.decodeOrNull(broken.encodeToByteArray(), "forever", OtaChannel.PRODUCTION), broken)
        }
    }

    @Test
    fun stateTransitionsKeepTheInvariants() {
        val a = installed("1.60.1.70374-r1")
        val b = installed("1.60.1.70450-r1")
        val state = OtaState.EMPTY.withInstalled(a).activated(a.releaseId).withInstalled(b).activated(b.releaseId)
        assertEquals(b.releaseId, state.active)
        assertEquals(a.releaseId, state.previous)
        val broken = state.withoutBrokenActive()
        assertEquals(a.releaseId, broken.active)
        assertNull(broken.previous)
        assertEquals(listOf(a), broken.installed)
        val rejected = (1..100).fold(OtaState.EMPTY) { s, i ->
            s.withRejected(RejectedRelease("1.60.1.70450-r$i", sha, OtaErrorCode.LAYOUT_INVALID))
        }
        assertEquals(OtaState.MAX_REJECTED, rejected.rejected.size)
        assertTrue(rejected.isRejected("1.60.1.70450-r100", sha))
        assertFalse(rejected.isRejected("1.60.1.70450-r100", otherSha))
    }

    @Test
    fun attemptRecordsAreStrict() {
        val record = attempt("1.60.1.70450-r1")
        val bytes = InstallAttemptCodec.encode(record)
        assertEquals(record, InstallAttemptCodec.decodeOrNull(bytes))
        assertNull(InstallAttemptCodec.decodeOrNull(null))
        assertNull(InstallAttemptCodec.decodeOrNull("{}".encodeToByteArray()))
        assertNull(InstallAttemptCodec.decodeOrNull(bytes.decodeToString().replace("-r1", "-r0").encodeToByteArray()))
    }

    // ------------------------------------------------------------ staging

    @Test
    fun recoveryDeletesOnlySafelyIdentifiableGarbage() {
        val id = "1.60.1.70450-r1"
        val keep = StagingPolicy.recoveryVerdict(id, attempt(id), emptyList())
        assertEquals(StagingPolicy.RecoveryVerdict.Keep, keep)
        fun reason(v: StagingPolicy.RecoveryVerdict) = (v as StagingPolicy.RecoveryVerdict.Delete).reason
        assertEquals(StagingPolicy.GarbageReason.NOT_A_RELEASE_ID, reason(StagingPolicy.recoveryVerdict("tmp", attempt(id), emptyList())))
        assertEquals(StagingPolicy.GarbageReason.ATTEMPT_MISSING_OR_UNREADABLE, reason(StagingPolicy.recoveryVerdict(id, null, emptyList())))
        assertEquals(
            StagingPolicy.GarbageReason.ATTEMPT_OF_ANOTHER_RELEASE,
            reason(StagingPolicy.recoveryVerdict(id, attempt("1.60.1.70450-r2"), emptyList())),
        )
        assertEquals(
            StagingPolicy.GarbageReason.ALREADY_INSTALLED,
            reason(StagingPolicy.recoveryVerdict(id, attempt(id), listOf(installed(id)))),
        )
        // installed with other bytes: the staging belongs to a new attempt and survives
        assertEquals(StagingPolicy.RecoveryVerdict.Keep, StagingPolicy.recoveryVerdict(id, attempt(id), listOf(installed(id, otherSha))))
    }

    @Test
    fun anAttemptResumesOnlyForTheSameManifestBytes() {
        val id = "1.60.1.70450-r1"
        assertEquals(StagingPolicy.AttemptStart.Create, StagingPolicy.startAttempt(false, null, entry(id)))
        assertIs<StagingPolicy.AttemptStart.Resume>(StagingPolicy.startAttempt(true, attempt(id), entry(id)))
        assertEquals(
            StagingPolicy.AttemptStart.Restart(StagingPolicy.RestartReason.DIFFERENT_MANIFEST),
            StagingPolicy.startAttempt(true, attempt(id), entry(id, otherSha)),
        )
        assertEquals(
            StagingPolicy.AttemptStart.Restart(StagingPolicy.RestartReason.DIFFERENT_MANIFEST),
            StagingPolicy.startAttempt(true, attempt(id), entry(id, length = 101)),
        )
        assertEquals(
            StagingPolicy.AttemptStart.Restart(StagingPolicy.RestartReason.ATTEMPT_MISSING_OR_UNREADABLE),
            StagingPolicy.startAttempt(true, null, entry(id)),
        )
    }

    @Test
    fun freeSpaceDecisionsFollowTheMargin() {
        val margin = StagingPolicy.SPACE_MARGIN_BYTES
        assertEquals(StagingPolicy.SpaceDecision.Proceed, StagingPolicy.space(freeBytes = 5_000_000 + margin, missingBytes = 5_000_000))
        assertIs<StagingPolicy.SpaceDecision.InsufficientKeepStaging>(StagingPolicy.space(5_000_000 + margin - 1, 5_000_000))
        assertIs<StagingPolicy.SpaceDecision.InsufficientDeleteStaging>(StagingPolicy.space(margin - 1, 0))
    }

    @Test
    fun transientFailuresKeepTheStagingAndVerdictsRejectTheRelease() {
        for (failure in listOf(
            OtaFailure.Network("down"), OtaFailure.Http(503), OtaFailure.Io("disk"), OtaFailure.Cancelled,
            OtaFailure.InsufficientStorage(1, 0), OtaFailure.ManifestHashMismatch(""), OtaFailure.BlobSizeMismatch(""),
            OtaFailure.BlobHashMismatch(""),
        )) {
            assertEquals(InstallFailureClass.TRANSIENT, failure.installClass(), failure.toString())
            assertEquals(StagingPolicy.AttemptEnd.KeepForResume, StagingPolicy.attemptEnd(failure))
        }
        for (code in listOf(
            OtaErrorCode.MANIFEST_INVALID, OtaErrorCode.UNSUPPORTED_SCHEMA_VERSION, OtaErrorCode.RELEASE_IDENTITY_MISMATCH,
            OtaErrorCode.INCOMPATIBLE_DATA_LEVEL, OtaErrorCode.LAYOUT_INVALID, OtaErrorCode.CONTENT_INVALID,
            OtaErrorCode.CONTENT_IDENTITY_MISMATCH, OtaErrorCode.RELEASE_INCOMPLETE, OtaErrorCode.BUILD_IDENTITY_CONFLICT,
        )) {
            assertEquals(StagingPolicy.AttemptEnd.RejectRelease(code), StagingPolicy.attemptEnd(code.failure("x")))
        }
        val belowMargin = StagingPolicy.space(0, 0)
        assertEquals(StagingPolicy.AttemptEnd.DeleteStaging, StagingPolicy.attemptEnd(OtaFailure.InsufficientStorage(1, 0), belowMargin))
        // every contract code maps to its own typed failure with the same code
        for (code in OtaErrorCode.entries) assertEquals(code, code.failure("d").code)
    }

    // ------------------------------------------------------------ retention

    @Test
    fun retentionKeepsActivePreviousReferencedAndLeasedReleasesOnly() {
        val a = "1.60.1.70374-r1"
        val b = "1.60.1.70450-r1"
        val x = "1.60.1.70245-r1"
        val z = "1.60.1.70338-r1"
        val state = OtaState(SeenCatalog(3, sha), active = b, previous = a, installed = listOf(installed(a), installed(b), installed(x), installed(z)), rejected = emptyList())
        val plan = RetentionPolicy.plan(
            state = state,
            installed = state.installed,
            libraryBuilds = setOf("1.60.1.70245", "1.60.1.70009"),
            leased = emptySet(),
            bundledBuilds = setOf("1.60.1.70009"),
            staging = emptyList(),
            runningAttempt = null,
            catalog = catalog(a, b, active = b),
            onDemand = emptySet(),
            now = now,
        )
        assertEquals(setOf(z), plan.deleteReleases)
        val leasedPlan = RetentionPolicy.plan(state, state.installed, setOf("1.60.1.70245"), setOf(z), emptySet(), emptyList(), null, catalog(a, b), emptySet(), now)
        assertEquals(emptySet(), leasedPlan.deleteReleases)
        // an unreadable library protects every installed release
        val blind = RetentionPolicy.plan(state, state.installed, null, emptySet(), emptySet(), emptyList(), null, catalog(a, b), emptySet(), now)
        assertEquals(emptySet(), blind.deleteReleases)
    }

    @Test
    fun onlyTheNewestRevisionOfAReferencedBuildIsKept() {
        val r1 = "1.60.1.70245-r1"
        val r2 = "1.60.1.70245-r2"
        val state = OtaState(null, null, null, listOf(installed(r1), installed(r2)), emptyList())
        val plan = RetentionPolicy.plan(state, state.installed, setOf("1.60.1.70245"), emptySet(), emptySet(), emptyList(), null, null, emptySet(), now)
        assertEquals(setOf(r1), plan.deleteReleases)
    }

    @Test
    fun staleStagingIsDeletedButNeverTheRunningAttempt() {
        val listed = "1.60.1.70450-r1"
        val unlisted = "1.60.1.70338-r1"
        val old = "1.60.1.70374-r1"
        val state = OtaState.EMPTY
        val staging = listOf(
            StagingEntry(listed, attempt(listed)),
            StagingEntry(unlisted, attempt(unlisted)),
            StagingEntry(old, attempt(old, updatedAt = now.plusSeconds(-31L * 86_400))),
        )
        val plan = RetentionPolicy.plan(state, emptyList(), emptySet(), emptySet(), emptySet(), staging, null, catalog(old, listed, active = listed), setOf(old), now)
        assertEquals(setOf(unlisted, old), plan.deleteStaging)
        val running = RetentionPolicy.plan(state, emptyList(), emptySet(), emptySet(), emptySet(), staging, unlisted, catalog(old, listed, active = listed), emptySet(), now)
        assertFalse(unlisted in running.deleteStaging)
        // without a verified catalog nothing in staging is judged stale
        assertEquals(emptySet(), RetentionPolicy.plan(state, emptyList(), emptySet(), emptySet(), emptySet(), staging, null, null, emptySet(), now).deleteStaging)
    }
}
