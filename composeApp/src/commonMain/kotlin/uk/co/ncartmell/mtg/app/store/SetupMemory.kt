package uk.co.ncartmell.mtg.app.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.co.ncartmell.mtg.engine.Format

/**
 * What the last game was set up as, so the next one starts from it.
 *
 * A group plays the same format at the same life total most weeks, and making them pick
 * it again every time is friction for no reason.
 */
@Serializable
data class SetupMemory(
    val format: Format = Format.FREE_FOR_ALL,
    val playerCount: Int = 4,
    val startingLife: Int = 40,
    val commanderDamage: Boolean = true,
    val poison: Boolean = true,
    val planechase: Boolean = false,
)

class SetupRepository(private val storage: Storage) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): SetupMemory =
        storage.read(KEY)
            ?.let { runCatching { json.decodeFromString<SetupMemory>(it) }.getOrNull() }
            ?: SetupMemory()

    fun save(memory: SetupMemory) {
        storage.write(KEY, json.encodeToString(memory))
    }

    private companion object {
        const val KEY = "setup.v1"
    }
}
