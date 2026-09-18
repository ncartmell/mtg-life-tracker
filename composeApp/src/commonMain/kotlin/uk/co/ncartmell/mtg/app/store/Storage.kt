package uk.co.ncartmell.mtg.app.store

/**
 * The smallest persistence surface the app needs: one named slot holding a string.
 *
 * Keeping it this narrow means each platform implements a handful of lines against
 * whatever it already has, and nothing above this layer knows which platform it is on.
 */
interface Storage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** Provided per platform. */
expect fun createStorage(): Storage
