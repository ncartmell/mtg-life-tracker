package uk.co.ncartmell.mtg.app.store

import kotlinx.serialization.encodeToString
import uk.co.ncartmell.mtg.engine.SavedGame
import uk.co.ncartmell.mtg.engine.SavedGameJson

/**
 * The game in progress, written on every change.
 *
 * This is the only store that is hot: a held +/- repeats several times a second and each
 * one lands here. The platforms it matters on are cheap about it — Android's apply() is
 * asynchronous and NSUserDefaults is held in memory — and the desktop write is one atomic
 * move rather than a copy.
 */
class GameRepository(private val storage: Storage) {

    private val json = SavedGameJson

    /**
     * Tolerant, like the other stores. A game that cannot be read is a game that was not
     * saved: the table starts a new one, which is annoying, rather than meeting a crash.
     */
    fun load(): SavedGame? =
        storage.read(KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<SavedGame>(it) }.getOrNull() }

    fun save(saved: SavedGame) {
        storage.write(KEY, json.encodeToString(saved))
    }

    /** Storage has no delete, and an empty slot reads back as no game at all. */
    fun clear() {
        storage.write(KEY, "")
    }

    private companion object {
        const val KEY = "game.v1"
    }
}
