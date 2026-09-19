package uk.co.ncartmell.mtg.app.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.co.ncartmell.mtg.engine.GameHistory

/**
 * Loads and saves the [GameHistory], as tolerantly as the profiles are loaded: a history
 * that cannot be read starts empty rather than stopping the app from opening.
 */
class HistoryRepository(private val storage: Storage) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): GameHistory =
        storage.read(KEY)
            ?.let { runCatching { json.decodeFromString<GameHistory>(it) }.getOrNull() }
            ?: GameHistory()

    fun save(history: GameHistory) {
        storage.write(KEY, json.encodeToString(history))
    }

    private companion object {
        const val KEY = "history.v1"
    }
}
