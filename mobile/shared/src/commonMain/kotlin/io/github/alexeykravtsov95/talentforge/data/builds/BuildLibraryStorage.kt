package io.github.alexeykravtsov95.talentforge.data.builds

/**
 * App-private key/value cell that persists the serialized local build library
 * (one string). Implementations live at the platform edge (Android
 * `SharedPreferences`, iOS `NSUserDefaults`, desktop `java.util.prefs`) and are
 * injected into the common app boundary by each host — domain code never sees a
 * platform type.
 *
 * The contract is deliberately tiny: storage only stores/retrieves the last
 * written payload. Parsing, validation and ordering belong to
 * [LocalBuildRepository]; a storage implementation must not inspect, migrate or
 * "repair" the payload.
 *
 * Failure contract: a read that cannot be completed throws
 * [BuildLibraryReadException], a write that cannot be completed throws
 * [BuildLibraryWriteException]. A library that was never written is **not** a
 * failure: [read] returns `null` and the repository treats it as an empty
 * library. A failed write must leave the previously persisted payload intact
 * (all built-in stores write one string atomically or not at all).
 */
interface BuildLibraryStorage {

    /** The last written payload, or `null` when nothing was ever written. */
    fun read(): String?

    /** Persists [payload] as the one and only library payload. */
    fun write(payload: String)

    companion object {

        /** The single storage cell key shared by every platform implementation. */
        const val STORAGE_KEY = "talentforge.localBuildLibrary"
    }
}

/** A platform storage could not read the persisted payload. */
class BuildLibraryReadException(detail: String) :
    Exception("local build library read failed: $detail")

/** A platform storage could not write the payload (the old payload is retained). */
class BuildLibraryWriteException(detail: String) :
    Exception("local build library write failed: $detail")
