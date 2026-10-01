package io.github.alexeykravtsov95.talentforge.data.builds

import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * Typed failure of a local-library operation. Nothing here is ever silently
 * swallowed: a failed library load must surface as a recoverable error state,
 * never as "the user has zero builds" (which would invite an overwrite that
 * destroys the stored payload).
 */
sealed interface BuildLibraryError {

    /** The platform storage threw while reading. */
    data class StorageRead(val detail: String) : BuildLibraryError

    /** The platform storage threw while writing (the last payload is retained). */
    data class StorageWrite(val detail: String) : BuildLibraryError

    /** The stored payload is structurally invalid. */
    data class Malformed(val detail: String) : BuildLibraryError

    /** The stored payload declares an unsupported `storageVersion`. */
    data class UnsupportedStorageVersion(val actual: Int, val supported: Int) : BuildLibraryError

    /** The operation targets a `localId` the library does not contain. */
    data class UnknownRecord(val localId: String) : BuildLibraryError

    /**
     * The id generator kept returning ids the library already contains for the
     * whole bounded retry policy — a new record was NOT persisted and the
     * stored payload is untouched.
     */
    data class IdCollision(val attempts: Int) : BuildLibraryError
}

/** Outcome of a [LocalBuildRepository] operation (mirrors `DatasetOutcome`). */
sealed interface BuildLibraryOutcome<out T> {
    data class Loaded<out T>(val value: T) : BuildLibraryOutcome<T>

    data class Failed(val error: BuildLibraryError) : BuildLibraryOutcome<Nothing>
}

/** Source of "now" in epoch milliseconds. Injected so tests are deterministic. */
fun interface BuildClock {
    fun nowMillis(): Long
}

/** Source of opaque local build ids. Injected so tests are deterministic. */
fun interface LocalBuildIdGenerator {
    fun next(): String
}

/**
 * Default id generator: opaque, time-ordered prefix + random suffix. No UUID
 * dependency — `kotlin.random.Random` is stable common API, and a local id only
 * has to be unique inside one device's library. The random part keeps the
 * full positive-long range verbatim (base 36): every transformation of an
 * opaque id needs a reason, and shortening one only discards entropy.
 */
class RandomLocalBuildIdGenerator(
    private val clock: BuildClock,
    private val random: Random = Random.Default,
) : LocalBuildIdGenerator {

    override fun next(): String =
        clock.nowMillis().toString(RADIX) + "-" +
            random.nextLong(from = 0, until = Long.MAX_VALUE).toString(RADIX)

    private companion object {
        const val RADIX = 36
    }
}

/**
 * Owner of the app-private local build library: parsing, deterministic
 * serialization, create/update/open/list, typed failures.
 *
 * Every operation is a full read–modify–write over [BuildLibraryStorage] (the
 * payload is small and preferences-backed), so two repository instances over
 * the same storage — including a fresh app process — always observe each
 * other's writes. A write happens only after the read payload parsed and the
 * whole mutated library re-encoded successfully; a failing write therefore
 * never replaces the last persisted payload.
 *
 * Ordering (frozen by the UX/UI spec, "sorted by last edited"):
 * `updatedAt` descending, then `createdAt` descending, then `localId`
 * ascending — deterministic even when a fixed test clock makes timestamps
 * equal. The persisted byte order is the codec's canonical `localId` order and
 * carries no meaning.
 */
class LocalBuildRepository(
    private val storage: BuildLibraryStorage,
    private val clock: BuildClock,
    private val idGenerator: LocalBuildIdGenerator,
) {

    private companion object {

        /**
         * Bounded id-regeneration policy: with a real random generator a
         * second collision is astronomically unlikely; the bound exists so a
         * broken/fixed generator fails typed instead of looping forever.
         */
        const val MAX_ID_ATTEMPTS = 8
    }

    /** The whole library in canonical domain order; empty when nothing was stored. */
    fun load(): BuildLibraryOutcome<List<LocalBuildRecord>> = when (val outcome = readAll()) {
        is ReadOutcome.Records -> BuildLibraryOutcome.Loaded(outcome.records.inLibraryOrder())
        is ReadOutcome.Failed -> BuildLibraryOutcome.Failed(outcome.error)
    }

    /**
     * Creates and persists a new Draft (`name == null`) around [build] and
     * returns it. The draft exists on storage before any editor opens — a
     * Draft is unnamed, never unsaved.
     *
     * Id-collision policy: a generated candidate is usable only if it is
     * **nonblank** and **unique** within the existing library (the reader
     * rejects both blank and duplicate ids, so the writer must never produce
     * either). An unusable candidate is regenerated up to [MAX_ID_ATTEMPTS]
     * times — a blank candidate consumes an attempt exactly like a collision;
     * ids are opaque but never blank, and an invalid id is never normalized.
     * If every attempt fails, the operation fails typed
     * ([BuildLibraryError.IdCollision]) without writing — the stored payload
     * stays byte-for-byte intact.
     */
    fun createDraft(build: BuildFile): BuildLibraryOutcome<LocalBuildRecord> =
        when (val outcome = readAll()) {
            is ReadOutcome.Failed -> BuildLibraryOutcome.Failed(outcome.error)
            is ReadOutcome.Records -> {
                val record = newUniqueRecord(build, outcome.records)
                    ?: return BuildLibraryOutcome.Failed(
                        BuildLibraryError.IdCollision(attempts = MAX_ID_ATTEMPTS),
                    )
                when (val written = writeAll(outcome.records + record)) {
                    is BuildLibraryOutcome.Failed -> BuildLibraryOutcome.Failed(written.error)
                    is BuildLibraryOutcome.Loaded -> BuildLibraryOutcome.Loaded(record)
                }
            }
        }

    /** A draft record whose generated id is nonblank and absent from [existing], or `null` after the bounded retries. */
    private fun newUniqueRecord(build: BuildFile, existing: List<LocalBuildRecord>): LocalBuildRecord? {
        val taken = existing.mapTo(HashSet(existing.size)) { it.localId }
        val now = clock.nowMillis()
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = idGenerator.next()
            if (candidate.isNotBlank() && candidate !in taken) {
                return LocalBuildRecord(
                    localId = candidate,
                    name = null,
                    createdAt = now,
                    updatedAt = now,
                    lastOpenedAt = null,
                    build = build,
                )
            }
        }
        return null
    }

    /**
     * Autosave of an existing record's build: replaces [build], advances
     * `updatedAt`, and preserves `localId`, `name`, `createdAt` and
     * `lastOpenedAt` exactly.
     */
    fun saveBuild(localId: String, build: BuildFile): BuildLibraryOutcome<LocalBuildRecord> =
        mutate(localId) { record ->
            record.copy(build = build, updatedAt = clock.nowMillis())
        }

    /**
     * Opens a record: advances `lastOpenedAt` (and nothing else). Touching is
     * a real write and fails closed like every other operation.
     */
    fun open(localId: String): BuildLibraryOutcome<LocalBuildRecord> =
        mutate(localId) { record ->
            record.copy(lastOpenedAt = clock.nowMillis())
        }

    // ------------------------------------------------------------------ internals

    private sealed interface ReadOutcome {
        data class Records(val records: List<LocalBuildRecord>) : ReadOutcome

        data class Failed(val error: BuildLibraryError) : ReadOutcome
    }

    private fun readAll(): ReadOutcome {
        val payload = try {
            storage.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BuildLibraryReadException) {
            return ReadOutcome.Failed(BuildLibraryError.StorageRead(e.message ?: "read failure"))
        }
        if (payload == null) {
            return ReadOutcome.Records(emptyList())
        }
        val records = try {
            LocalBuildLibraryCodec.decode(payload)
        } catch (e: LocalBuildLibraryFormatException.UnsupportedVersion) {
            return ReadOutcome.Failed(
                BuildLibraryError.UnsupportedStorageVersion(
                    actual = e.actualVersion,
                    supported = LocalBuildLibraryCodec.STORAGE_VERSION,
                ),
            )
        } catch (e: LocalBuildLibraryFormatException.Malformed) {
            return ReadOutcome.Failed(BuildLibraryError.Malformed(e.message ?: "malformed payload"))
        }
        return ReadOutcome.Records(records)
    }

    private fun writeAll(records: List<LocalBuildRecord>): BuildLibraryOutcome<Unit> = try {
        storage.write(LocalBuildLibraryCodec.encode(records))
        BuildLibraryOutcome.Loaded(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BuildLibraryWriteException) {
        BuildLibraryOutcome.Failed(BuildLibraryError.StorageWrite(e.message ?: "write failure"))
    } catch (e: LocalBuildLibraryFormatException.Malformed) {
        // write-boundary safety net (see the codec): a record set the reader
        // would reject — duplicate local ids — is a typed failure, never a
        // persisted payload and never an escaping exception
        BuildLibraryOutcome.Failed(BuildLibraryError.Malformed(e.message ?: "invalid records"))
    }

    private fun mutate(
        localId: String,
        transform: (LocalBuildRecord) -> LocalBuildRecord,
    ): BuildLibraryOutcome<LocalBuildRecord> = when (val outcome = readAll()) {
        is ReadOutcome.Failed -> BuildLibraryOutcome.Failed(outcome.error)
        is ReadOutcome.Records -> {
            val index = outcome.records.indexOfFirst { it.localId == localId }
            if (index < 0) {
                return BuildLibraryOutcome.Failed(BuildLibraryError.UnknownRecord(localId))
            }
            val updated = transform(outcome.records[index])
            val next = outcome.records.toMutableList().apply { set(index, updated) }
            when (val written = writeAll(next)) {
                is BuildLibraryOutcome.Failed -> BuildLibraryOutcome.Failed(written.error)
                is BuildLibraryOutcome.Loaded -> BuildLibraryOutcome.Loaded(updated)
            }
        }
    }
}

/** Canonical in-memory order: last edited first, fully deterministic. */
internal fun List<LocalBuildRecord>.inLibraryOrder(): List<LocalBuildRecord> =
    sortedWith(
        compareByDescending<LocalBuildRecord> { it.updatedAt }
            .thenByDescending { it.createdAt }
            .thenBy { it.localId },
    )
