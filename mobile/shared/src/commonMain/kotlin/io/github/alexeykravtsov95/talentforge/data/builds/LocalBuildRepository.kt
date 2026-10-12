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
     * A requested build name failed validation (blank after trimming, or
     * longer than the limit). No write happened; the stored library is
     * untouched.
     */
    data class InvalidBuildName(val reason: Reason, val maxLength: Int) : BuildLibraryError {

        sealed interface Reason {

            /** Blank (or whitespace-only) after trimming. */
            data object Blank : Reason

            /** Longer than [InvalidBuildName.maxLength] after trimming. */
            data class TooLong(val length: Int) : Reason
        }
    }

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
 * serialization, create/update/open/list/rename/duplicate/delete, typed
 * failures.
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

    companion object {

        /**
         * Maximum build-name length after trimming — one rule shared by the
         * domain and the rename dialog (the UI reads this constant; it never
         * re-declares the limit).
         */
        const val MAX_BUILD_NAME_LENGTH: Int = 60

        /**
         * Bounded id-regeneration policy: with a real random generator a
         * second collision is astronomically unlikely; the bound exists so a
         * broken/fixed generator fails typed instead of looping forever.
         */
        private const val MAX_ID_ATTEMPTS = 8

        /** Suffix of a duplicated build's initial name. */
        private const val COPY_NAME_SUFFIX = " (copy)"

        /**
         * The fallback title of an unnamed record — the same "Draft" the card
         * model shows; the duplicate's initial name is derived from it.
         */
        private const val DRAFT_TITLE = "Draft"
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
        val localId = newUniqueId(existing.mapTo(HashSet(existing.size)) { it.localId }) ?: return null
        val now = clock.nowMillis()
        return LocalBuildRecord(
            localId = localId,
            name = null,
            createdAt = now,
            updatedAt = now,
            lastOpenedAt = null,
            build = build,
        )
    }

    /** A generated candidate that is nonblank and absent from [taken], or `null` after the bounded retries. */
    private fun newUniqueId(taken: Set<String>): String? {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = idGenerator.next()
            if (candidate.isNotBlank() && candidate !in taken) {
                return candidate
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

    /**
     * Renames a record (M5.2a). The name is trimmed; a blank result or a name
     * longer than [MAX_BUILD_NAME_LENGTH] is a typed
     * [BuildLibraryError.InvalidBuildName] that never reads or writes storage.
     * Renaming is metadata: `localId`, the [BuildFile], `createdAt` and
     * `lastOpenedAt` are preserved exactly, and — deliberately — so is
     * `updatedAt`: a rename must not masquerade as a talent change, reordering
     * the "last edited first" list. Renaming to the record's current name is a
     * successful no-op that does not write.
     */
    fun renameBuild(localId: String, newName: String): BuildLibraryOutcome<LocalBuildRecord> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return BuildLibraryOutcome.Failed(
                BuildLibraryError.InvalidBuildName(
                    reason = BuildLibraryError.InvalidBuildName.Reason.Blank,
                    maxLength = MAX_BUILD_NAME_LENGTH,
                ),
            )
        }
        if (trimmed.length > MAX_BUILD_NAME_LENGTH) {
            return BuildLibraryOutcome.Failed(
                BuildLibraryError.InvalidBuildName(
                    reason = BuildLibraryError.InvalidBuildName.Reason.TooLong(trimmed.length),
                    maxLength = MAX_BUILD_NAME_LENGTH,
                ),
            )
        }
        return mutate(localId) { record ->
            if (record.name == trimmed) record else record.copy(name = trimmed)
        }
    }

    /**
     * Duplicates a record into an INDEPENDENT new record (M5.2a): a fresh
     * unique `localId` (the same bounded collision policy as
     * [createDraft]), the source's exact [BuildFile] payload,
     * `createdAt`/`updatedAt` = now, `lastOpenedAt = null`, and the initial
     * name derived from the source's CURRENT name with ` (copy)` appended —
     * `Draft (copy)` for a Draft, and a copy of a copy appends once more
     * (`Build (copy)` → `Build (copy) (copy)`), always shortened to
     * [MAX_BUILD_NAME_LENGTH] while keeping the suffix. Duplicate names are
     * allowed: identity is `localId`, never the name. The source record and
     * its neighbours are untouched; later edits of either build never reach
     * the other.
     */
    fun duplicateBuild(localId: String): BuildLibraryOutcome<LocalBuildRecord> =
        when (val outcome = readAll()) {
            is ReadOutcome.Failed -> BuildLibraryOutcome.Failed(outcome.error)
            is ReadOutcome.Records -> {
                val source = outcome.records.firstOrNull { it.localId == localId }
                    ?: return BuildLibraryOutcome.Failed(BuildLibraryError.UnknownRecord(localId))
                val copyId = newUniqueId(outcome.records.mapTo(HashSet(outcome.records.size)) { it.localId })
                    ?: return BuildLibraryOutcome.Failed(
                        BuildLibraryError.IdCollision(attempts = MAX_ID_ATTEMPTS),
                    )
                val now = clock.nowMillis()
                val copy = LocalBuildRecord(
                    localId = copyId,
                    name = copyNameOf(source.name),
                    createdAt = now,
                    updatedAt = now,
                    lastOpenedAt = null,
                    build = source.build,
                )
                when (val written = writeAll(outcome.records + copy)) {
                    is BuildLibraryOutcome.Failed -> BuildLibraryOutcome.Failed(written.error)
                    is BuildLibraryOutcome.Loaded -> BuildLibraryOutcome.Loaded(copy)
                }
            }
        }

    /**
     * The copy's initial name: the source's CURRENT name with ` (copy)`
     * appended — so a duplicate of a duplicate accumulates one suffix per
     * copy (`Build (copy)` → `Build (copy) (copy)`) — shortened to the
     * name-length limit while always keeping the suffix.
     */
    private fun copyNameOf(name: String?): String =
        (name ?: DRAFT_TITLE).take(MAX_BUILD_NAME_LENGTH - COPY_NAME_SUFFIX.length) + COPY_NAME_SUFFIX

    /**
     * Deletes exactly one record (M5.2a). Every other record is untouched; a
     * missing record is a typed [BuildLibraryError.UnknownRecord]. A failed
     * read or write leaves the stored payload byte-for-byte intact — a failed
     * delete is never reported as a successful one.
     */
    fun deleteBuild(localId: String): BuildLibraryOutcome<Unit> =
        when (val outcome = readAll()) {
            is ReadOutcome.Failed -> BuildLibraryOutcome.Failed(outcome.error)
            is ReadOutcome.Records -> {
                val index = outcome.records.indexOfFirst { it.localId == localId }
                if (index < 0) {
                    return BuildLibraryOutcome.Failed(BuildLibraryError.UnknownRecord(localId))
                }
                val remaining = outcome.records.toMutableList().apply { removeAt(index) }
                when (val written = writeAll(remaining)) {
                    is BuildLibraryOutcome.Failed -> BuildLibraryOutcome.Failed(written.error)
                    is BuildLibraryOutcome.Loaded -> BuildLibraryOutcome.Loaded(Unit)
                }
            }
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
            val source = outcome.records[index]
            val updated = transform(source)
            if (updated == source) {
                // a transform that changes nothing (e.g. renaming to the
                // record's current name) is a successful no-op: no write, no
                // timestamp churn, no neighbour risk
                return BuildLibraryOutcome.Loaded(updated)
            }
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
