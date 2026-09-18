package uk.co.ncartmell.mtg.app.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.co.ncartmell.mtg.engine.ProfileBook

/**
 * Loads and saves the [ProfileBook].
 *
 * Reads are tolerant: if the stored value is missing or cannot be parsed — an older
 * format, a partial write — the app starts with an empty book rather than refusing to
 * open. Losing a leaderboard is annoying; a life tracker that will not start mid-game
 * is worse.
 */
class ProfileRepository(private val storage: Storage) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): ProfileBook =
        storage.read(KEY)
            ?.let { runCatching { json.decodeFromString<ProfileBook>(it) }.getOrNull() }
            ?: ProfileBook()

    fun save(book: ProfileBook) {
        storage.write(KEY, json.encodeToString(book))
    }

    private companion object {
        const val KEY = "profiles.v1"
    }
}
