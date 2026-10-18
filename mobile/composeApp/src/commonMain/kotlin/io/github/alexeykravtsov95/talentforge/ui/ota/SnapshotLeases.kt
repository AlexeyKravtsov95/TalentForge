package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.DatasetSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory leases of the releases open sessions read (client design §4.5): retention never
 * deletes a leased release.
 *
 * Leasing and retention are decided on one table updated atomically: retention first *claims*
 * the releases it will delete — only those no lease holds — and a claimed release cannot be
 * leased until the deletion is over. So a lease either exists before the claim (the release is
 * kept) or is refused (the caller takes a newer snapshot); a lease is never granted on a release
 * being deleted. Acquired on the UI thread or a worker, read by GC.
 */
class SnapshotLeases {
    private class Table(val counts: Map<String, Int>, val claimed: Set<String>)

    private val table = MutableStateFlow(Table(emptyMap(), emptySet()))

    fun leased(): Set<String> = table.value.counts.keys

    /** A lease on [releaseIds], or `null` when retention has claimed one of them for deletion. */
    fun tryAcquire(releaseIds: Set<String>): Lease? {
        var granted = false
        table.update { current ->
            granted = releaseIds.none { it in current.claimed }
            if (!granted) current else Table(current.counts + releaseIds.associateWith { (current.counts[it] ?: 0) + 1 }, current.claimed)
        }
        return if (granted) Lease(releaseIds) else null
    }

    /** Retention: claims every candidate no lease holds and returns them — the only releases it may delete. */
    internal fun claimForDeletion(candidates: Set<String>): Set<String> {
        var claimed = emptySet<String>()
        table.update { current ->
            claimed = candidates - current.counts.keys
            Table(current.counts, current.claimed + claimed)
        }
        return claimed
    }

    /** Retention is over for [releaseIds]: a reinstalled release of the same id can be leased again. */
    internal fun deletionFinished(releaseIds: Set<String>) {
        table.update { Table(it.counts, it.claimed - releaseIds) }
    }

    inner class Lease internal constructor(private val releaseIds: Set<String>) {
        private val released = MutableStateFlow(false)

        fun release() {
            if (!released.compareAndSet(expect = false, update = true)) return
            table.update { current ->
                val next = current.counts.toMutableMap()
                for (id in releaseIds) {
                    val count = (next[id] ?: 0) - 1
                    if (count <= 0) next.remove(id) else next[id] = count
                }
                Table(next, current.claimed)
            }
        }
    }
}

/** A snapshot together with the lease that keeps every release it reads on disk. */
class LeasedSnapshot(val snapshot: DatasetSnapshot, val lease: SnapshotLeases.Lease)
