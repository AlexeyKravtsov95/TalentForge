package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.data.builds.BuildClock
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryReadException
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryWriteException
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildIdGenerator

/**
 * Deterministic desktop-test doubles for the M5.1 app environment: an
 * in-memory storage shared across "app instances" (the restart tests) with
 * controllable failures, a fixed/advancing clock and sequential ids.
 */
class FakeBuildLibraryStorage(
    var payload: String? = null,
    var failReads: Boolean = false,
    var failWrites: Boolean = false,
    /** Artificial write latency, so tests can hold an autosave flush in flight. */
    var writeDelayMillis: Long = 0,
) : BuildLibraryStorage {

    var writeCount: Int = 0
        private set

    override fun read(): String? {
        if (failReads) throw BuildLibraryReadException("injected read failure")
        return payload
    }

    override fun write(payload: String) {
        if (failWrites) throw BuildLibraryWriteException("injected write failure")
        if (writeDelayMillis > 0) Thread.sleep(writeDelayMillis)
        this.payload = payload
        writeCount += 1
    }
}

class FixedClock(startMillis: Long = 1_000L) : BuildClock {
    private var now: Long = startMillis

    fun advanceBy(millis: Long) {
        now += millis
    }

    override fun nowMillis(): Long = now
}

class SequentialIds : LocalBuildIdGenerator {
    private var next: Int = 0

    override fun next(): String = "local-${++next}"
}
