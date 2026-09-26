package uk.co.ncartmell.mtg.app.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The die this device last used, so the next game does not start with a scan.
 *
 * A group that owns a die uses the same one every week. Making them find it again each
 * launch is the same friction as making them pick the format again, and the answer is the
 * same: remember it and offer it back.
 *
 * Only the identifier and the name are kept. The identifier is whatever the platform
 * needs to reconnect — a MAC address on Android, a system UUID on iOS — and is meaningless
 * on any other device, which is why this is never synced anywhere.
 */
@Serializable
data class DieMemory(
    val id: String? = null,
    val name: String? = null,
    /** Whether the die lights up for turns and tokens as well as for rolls. */
    val lightsTheTable: Boolean = true,
) {
    /** A die worth trying to reconnect to. */
    val known: Boolean get() = !id.isNullOrBlank()
}

class DieRepository(private val storage: Storage) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): DieMemory =
        storage.read(KEY)
            ?.let { runCatching { json.decodeFromString<DieMemory>(it) }.getOrNull() }
            ?: DieMemory()

    fun save(memory: DieMemory) {
        storage.write(KEY, json.encodeToString(memory))
    }

    private companion object {
        const val KEY = "die.v1"
    }
}
