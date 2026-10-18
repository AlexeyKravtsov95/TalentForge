package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.ActivationDecision
import io.github.alexeykravtsov95.talentforge.data.ota.OtaFileStore
import io.github.alexeykravtsov95.talentforge.data.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Process death: thrown by [CrashingStore], never caught by the client (it is an [Error]). */
class SimulatedCrash : Error("simulated process death")

/**
 * Kills the "process" at one mutating file operation — [crashAt] counts writeSynced,
 * replaceAtomically, rename and delete calls — either before the operation took effect (a torn
 * write: half the bytes, or the `.tmp` of an atomic replace left behind) or right after it.
 * After the crash every operation fails, like a dead process: nothing more reaches the disk.
 */
class CrashingStore(
    private val delegate: OtaFileStore,
    private val crashAt: Int = Int.MAX_VALUE,
    private val after: Boolean = false,
    private val crashOnPath: ((op: String, path: String) -> Boolean)? = null,
) : OtaFileStore by delegate {
    var mutations = 0
        private set

    /** Counting and killing start only once the device is up (its startup recover() is not a kill point). */
    @Volatile
    var armed = false

    fun arm() {
        armed = true
    }

    @Volatile
    var dead = false
        private set

    private fun <T> mutate(op: String, path: String, before: () -> Unit, action: () -> T): T {
        if (dead) throw SimulatedCrash()
        if (!armed) return action()
        val index = ++mutations
        val trigger = if (crashOnPath != null) crashOnPath.invoke(op, path) else index == crashAt
        if (!trigger) return action()
        dead = true
        if (after) {
            action()
        } else {
            before()
        }
        throw SimulatedCrash()
    }

    override fun readOrNull(path: String): ByteArray? {
        if (dead) throw SimulatedCrash()
        return delegate.readOrNull(path)
    }

    override fun writeSynced(path: String, bytes: ByteArray) =
        mutate("write", path, { delegate.writeSynced(path, bytes.copyOf(bytes.size / 2)) }) { delegate.writeSynced(path, bytes) }

    override fun replaceAtomically(path: String, bytes: ByteArray) =
        mutate("replace", path, { delegate.writeSynced("$path.tmp", bytes.copyOf(bytes.size / 2)) }) { delegate.replaceAtomically(path, bytes) }

    override fun rename(from: String, to: String) = mutate("rename", "$from -> $to", {}) { delegate.rename(from, to) }

    override fun delete(path: String) = mutate("delete", path, {}) { delegate.delete(path) }
}

/**
 * S10 and the PR #42 review item: the process dies at every phase of an install and activation
 * — during a blob write, after blob renames, around `release.json`, around the staging rename,
 * around the state replace, and between the catalog payload write and the state write. After
 * each kill, `recover()` runs on the same directory and a new check follows.
 */
class OtaCrashRecoveryTest {

    private val config = OtaConfig.forTesting(OtaTestRepo.keyring, baseUrl = "https://ota.test", parallelism = 1)

    private fun catalog(name: String) = OtaTestRepo.fixture("catalogs/$name.json")

    private fun stagedPaths(device: OtaDevice): Set<String> {
        val root = device.directory.resolve("v1/staging/$B_ID")
        return root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it == "attempt.json" || it == "release.json" }.toSet()
    }

    /** The invariants every recovered device must satisfy, whatever the kill point. */
    private fun assertConsistent(device: OtaDevice, library: String?, label: String) {
        val runtime = device.runtime
        assertTrue(runtime.recovery.stateReadable, "$label: the atomic replace never leaves an unreadable state")
        val state = runtime.activation.currentState()
        val seen = state.catalog
        if (seen != null) {
            val payload = device.directory.resolve("v1/catalog/production/${seen.payloadSha256.removePrefix("sha256:")}.payload")
            assertTrue(payload.isFile, "$label: the state names a payload that is on disk")
            assertEquals(seen.payloadSha256.removePrefix("sha256:"), sha256Hex(payload.readBytes()), "$label: and it is the bytes of that sequence")
            assertEquals(seen.sequence, runtime.activation.lastAcceptedCatalog()?.catalog?.sequence, "$label: the listed releases stay known offline")
            assertEquals(1, device.directory.resolve("v1/catalog/production").listFiles()!!.size, "$label: orphan payloads removed")
        }
        val files = device.directory.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertTrue(files.none { it.endsWith(".part") }, "$label: no .part survives recovery")
        assertTrue(files.none { it == "state.json.tmp" }, "$label: no state.json.tmp survives recovery")
        assertTrue(!device.directory.resolve("v1/trash").exists(), "$label: trash cleared")
        val snapshot = runtime.snapshot.value
        when (val release = snapshot.productReleaseId) {
            null -> assertEquals(A_BUILD, snapshot.product.clientBuild, "$label: the bundle")
            A_ID, B_ID -> {
                assertEquals(release, state.active, "$label: the product is the active release")
                assertNotNull(runtime.activation.intactRelease(release), "$label: an active release is intact")
            }
            else -> fail("$label: unexpected product $release")
        }
        assertEquals(library, device.library.payload, "$label: the library is untouched")
    }

    @Test
    fun aKillAtAnyPointOfAnInstallLeavesTheOldOrTheNewDatasetAndResumes() {
        // a successful run counts the mutating operations of one full install + activation
        val total = OtaDevice(config = config, storeWrapper = { CrashingStore(it) }).use { device ->
            device.server.publish(TestRelease.A, TestRelease.B)
            device.server.catalog = catalog("catalog-b-active")
            (device.store as CrashingStore).arm()
            assertIs<CheckOutcome.Activated>(device.check())
            (device.store as CrashingStore).mutations
        }
        assertTrue(total > TestRelease.B.manifest.files.size * 2, "every file is a .part write and a rename")
        val points = ((1..8) + (9 until total step 89) + ((total - 12)..total)).filter { it in 1..total }.toSortedSet()
        val report = StringBuilder("kill points (of $total mutating operations):\n")
        for (point in points) {
            for (after in listOf(false, true)) {
                val label = "kill ${if (after) "after" else "before"} op $point"
                OtaDevice(config = config, storeWrapper = { CrashingStore(it, crashAt = point, after = after) }).use { device ->
                    device.server.publish(TestRelease.A, TestRelease.B)
                    device.server.catalog = catalog("catalog-b-active")
                    val library = device.library.payload
                    (device.store as CrashingStore).arm()
                    val crashed = try {
                        device.check()
                        false
                    } catch (_: SimulatedCrash) {
                        true
                    }
                    assertTrue(crashed, "$label: the kill point was reached")
                    device.restart { it }
                    assertConsistent(device, library, label)
                    val installedAtRestart = device.runtime.activation.intactRelease(B_ID) != null
                    val staged = stagedPaths(device)
                    device.server.requests.clear()
                    val outcome = device.check()
                    when (outcome) {
                        is CheckOutcome.Activated -> Unit
                        is CheckOutcome.Current -> assertEquals(ActivationDecision.NO_CHANGE, outcome.decision, label)
                        else -> fail("$label: $outcome")
                    }
                    assertEquals(B_ID, device.runtime.activation.currentState().active, label)
                    val requested = device.server.blobRequests().toSet()
                    if (installedAtRestart) {
                        assertTrue(requested.isEmpty(), "$label: installed before the kill, activated without downloading")
                    } else {
                        val stagedHashes = TestRelease.B.manifest.files.filter { it.path in staged }.map { it.sha256Hex }.toSet()
                        val missingHashes = TestRelease.B.manifest.files.filter { it.path !in staged }.map { it.sha256Hex }.toSet()
                        assertTrue((requested - TestRelease.B.entry.manifestSha256.removePrefix("sha256:")).all { it in missingHashes }, "$label: no verified file is downloaded again")
                        assertTrue(stagedHashes.none { it in requested && it !in missingHashes }, label)
                    }
                    assertConsistent(device, library, "$label, after the next check")
                    report.append("  $label: ${if (installedAtRestart) "installed, activated without download" else "resumed with ${staged.size} staged files, ${requested.size} blobs requested"}\n")
                }
            }
        }
        java.io.File("build/reports").mkdirs()
        java.io.File("build/reports/ota-crash-recovery.txt").writeText(report.toString())
        println(report)
    }

    @Test
    fun aKillBetweenTheCatalogPayloadAndTheStateKeepsThemConsistent() {
        for (after in listOf(false, true)) {
            val label = if (after) "after the state write" else "between the payload and the state write"
            var armed = false
            OtaDevice(config = config, storeWrapper = { CrashingStore(it, crashOnPath = { op, path -> armed && op == "replace" && path == "v1/state.json" }, after = after) }).use { device ->
                device.server.publish(TestRelease.A, TestRelease.B)
                device.server.catalog = catalog("catalog-b-active")
                (device.store as CrashingStore).arm()
                assertIs<CheckOutcome.Activated>(device.check())
                val library = device.library.payload
                // a rollback catalog (sequence 3) arrives; the process dies at its state write
                device.server.catalog = catalog("catalog-rollback-to-a")
                armed = true
                try {
                    device.check()
                    fail("$label: no kill")
                } catch (_: SimulatedCrash) {
                }
                armed = false
                device.restart { it }
                assertConsistent(device, library, label)
                val state = device.runtime.activation.currentState()
                if (after) {
                    assertEquals(3L, state.catalog!!.sequence, "$label: the committed state names sequence 3")
                } else {
                    assertEquals(2L, state.catalog!!.sequence, "$label: the old state and its own payload")
                }
                // every release the accepted catalog listed is still known without the network
                val listed = device.runtime.activation.lastAcceptedCatalog()!!.catalog.releases.map { it.releaseId }.toSet()
                assertEquals(setOf(A_ID, B_ID), listed)
                assertEquals(B_ID, state.active, "$label: activation only follows a committed decision")
                // the next check finishes the rollback
                assertIs<CheckOutcome.Activated>(device.check())
                assertEquals(A_ID, device.runtime.activation.currentState().active)
                assertConsistent(device, library, "$label, after the next check")
            }
        }
    }

    @Test
    fun aKillDuringTheFirstEverStateWriteLeavesNoState() {
        OtaDevice(config = config, storeWrapper = { CrashingStore(it, crashOnPath = { op, path -> op == "replace" && path == "v1/state.json" }) }).use { device ->
            device.server.publish(TestRelease.A, TestRelease.B)
            device.server.catalog = catalog("catalog-b-active")
            val library = device.library.payload
            (device.store as CrashingStore).arm()
            try {
                device.check()
                fail("no kill")
            } catch (_: SimulatedCrash) {
            }
            device.restart { it }
            assertNull(device.runtime.activation.currentState().catalog, "no state committed")
            assertEquals(0, device.directory.resolve("v1/catalog/production").listFiles().orEmpty().size, "the orphan payload is not kept (state absent)")
            assertConsistent(device, library, "first state write")
        }
    }
}
