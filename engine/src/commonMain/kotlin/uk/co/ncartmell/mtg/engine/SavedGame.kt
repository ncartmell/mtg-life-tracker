package uk.co.ncartmell.mtg.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A game kept across a restart, so that the app being killed does not cost the table its
 * game.
 *
 * Phones reap backgrounded apps for their own reasons — somebody takes a photo, something
 * else wants the memory — and a life tracker that forgets six players' totals at that
 * moment is worse than a piece of paper.
 *
 * [resultRecorded] travels with the game rather than being recomputed. A finished game
 * read back from storage would otherwise look unrecorded, and the next change made to it
 * would write the same win to the leaderboard a second time.
 */
@Serializable
data class SavedGame(
    val game: GameState,
    val resultRecorded: Boolean = false,
)

/**
 * The one JSON configuration a saved game may be written or read with.
 *
 * Commander damage is keyed by [CommanderId], which is a class rather than a string or an
 * enum, and JSON will not use it as a key until it is told to write such maps as flat
 * arrays. Keeping the configuration here, rather than building one at each call site,
 * means the reader and the writer cannot be set up differently — which would not show up
 * until a real game failed to come back.
 */
val SavedGameJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    allowStructuredMapKeys = true
}
