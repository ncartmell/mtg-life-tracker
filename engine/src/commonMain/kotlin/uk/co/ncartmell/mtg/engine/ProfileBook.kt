package uk.co.ncartmell.mtg.engine

import kotlinx.serialization.Serializable

/**
 * The saved player profiles and their records.
 *
 * Pure data with pure transitions, so the rules for recording a result and ordering the
 * leaderboard are testable without touching a filesystem. Persistence is somebody else's
 * problem — see the platform storage in the app module.
 */
@Serializable
data class ProfileBook(val profiles: List<PlayerProfile> = emptyList()) {

    operator fun get(id: String): PlayerProfile? = profiles.firstOrNull { it.id == id }

    /** Colours already taken, so the UI can steer people towards a free one. */
    val usedColours: Set<PlayerColour> get() = profiles.map { it.colour }.toSet()

    fun add(name: String, colour: PlayerColour, id: String): ProfileBook {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A profile needs a name" }
        require(profiles.none { it.id == id }) { "Profile $id already exists" }
        require(profiles.none { it.name.equals(trimmed, ignoreCase = true) }) {
            "A profile named '$trimmed' already exists"
        }
        return copy(profiles = profiles + PlayerProfile(id = id, name = trimmed, colour = colour))
    }

    fun rename(id: String, name: String): ProfileBook {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A profile needs a name" }
        require(profiles.none { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
            "A profile named '$trimmed' already exists"
        }
        return mapProfile(id) { it.copy(name = trimmed) }
    }

    fun setColour(id: String, colour: PlayerColour): ProfileBook =
        mapProfile(id) { it.copy(colour = colour) }

    fun remove(id: String): ProfileBook = copy(profiles = profiles.filterNot { it.id == id })

    /**
     * Records the result of a finished game against every seat that had a profile.
     *
     * Guests are ignored. A draw counts as neither a win nor a loss, on the grounds that
     * a record which claims everyone lost is less useful than one that says nothing.
     */
    fun recordResult(game: GameState): ProfileBook {
        val outcome = game.outcome ?: return this
        if (outcome is GameOutcome.Draw) return this
        // A team win is a win for everybody on the team, not just whoever was left.
        val winningSeats = outcome.winningSeats.toSet()

        val updates = game.players.mapNotNull { player ->
            player.profileId?.let { it to (player.seat in winningSeats) }
        }.toMap()

        return copy(
            profiles = profiles.map { profile ->
                when (updates[profile.id]) {
                    true -> profile.copy(wins = profile.wins + 1)
                    false -> profile.copy(losses = profile.losses + 1)
                    null -> profile
                }
            },
        )
    }

    /**
     * Leaderboard order: most wins first, then fewest losses, then alphabetically.
     *
     * Deliberately not ordered by win rate — in a group that plays casually, somebody who
     * has won their only game would sit above somebody with twenty wins, and nobody
     * reading a leaderboard means that.
     */
    fun leaderboard(): List<PlayerProfile> = profiles.sortedWith(
        compareByDescending<PlayerProfile> { it.wins }
            .thenBy { it.losses }
            .thenBy { it.name.lowercase() },
    )

    private fun mapProfile(id: String, block: (PlayerProfile) -> PlayerProfile): ProfileBook {
        require(profiles.any { it.id == id }) { "No profile $id" }
        return copy(profiles = profiles.map { if (it.id == id) block(it) else it })
    }
}
