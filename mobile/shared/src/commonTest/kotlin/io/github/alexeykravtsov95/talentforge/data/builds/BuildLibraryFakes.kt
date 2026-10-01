package io.github.alexeykravtsov95.talentforge.data.builds

/**
 * Deterministic test doubles of the local-library collaborators: an in-memory
 * storage with controllable failures, a fixed/advancing clock and a sequential
 * id generator.
 */

/** In-memory [BuildLibraryStorage] that can be told to fail reads and/or writes. */
class InMemoryBuildLibraryStorage(
    var payload: String? = null,
    var failReads: Boolean = false,
    var failWrites: Boolean = false,
) : BuildLibraryStorage {

    var writeCount: Int = 0
        private set

    override fun read(): String? {
        if (failReads) throw BuildLibraryReadException("injected read failure")
        return payload
    }

    override fun write(payload: String) {
        if (failWrites) throw BuildLibraryWriteException("injected write failure")
        this.payload = payload
        writeCount += 1
    }
}

/** Fixed clock the test advances by hand; every timestamp is deterministic. */
class FixedBuildClock(startMillis: Long = 1_000L) : BuildClock {
    private var now: Long = startMillis

    fun advanceBy(millis: Long) {
        now += millis
    }

    override fun nowMillis(): Long = now
}

/** Sequential opaque ids ("id-1", "id-2", …) in creation order. */
class SequentialBuildIds : LocalBuildIdGenerator {
    private var next: Int = 0

    override fun next(): String = "id-${++next}"
}

/**
 * Scripted ids in order; once the script is exhausted it keeps returning the
 * last value (a generator "stuck" on one id — the collision-policy test).
 */
class ScriptedBuildIds(vararg script: String) : LocalBuildIdGenerator {
    private val remaining = ArrayDeque(script.toList())
    private val stuckOn = script.last()

    override fun next(): String = remaining.removeFirstOrNull() ?: stuckOn
}
