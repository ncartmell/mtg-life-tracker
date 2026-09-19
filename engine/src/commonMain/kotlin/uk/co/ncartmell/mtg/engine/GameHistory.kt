package uk.co.ncartmell.mtg.engine

import kotlinx.serialization.Serializable

/** One seat as it stood when a game finished. */
@Serializable
data class RecordedSeat(
    val seat: Int,
    val name: String,
    val colour: PlayerColour,
    val profileId: String? = null,
    val lostTo: LossReason? = null,
    val life: Int = 0,
) {
    val wasGuest: Boolean get() = profileId == null
}

/** A finished game, kept so the totals on the leaderboard can be traced back to games. */
@Serializable
data class GameRecord(
    val playedAt: Long,
    val settings: GameSettings,
    val seats: List<RecordedSeat>,
    /** Empty for a draw; more than one when a team won. */
    val winningSeats: List<Int> = emptyList(),
    val turns: Int = 0,
) {
    val wasDraw: Boolean get() = winningSeats.isEmpty()
    val winners: List<RecordedSeat> get() = seats.filter { it.seat in winningSeats }
    val winner: RecordedSeat? get() = winners.firstOrNull()

    fun seatFor(profileId: String): RecordedSeat? = seats.firstOrNull { it.profileId == profileId }
    fun involved(profileId: String): Boolean = seatFor(profileId) != null
    fun wasWonBy(profileId: String): Boolean = winners.any { it.profileId == profileId }
}

/**
 * Every finished game, newest first.
 *
 * The running totals on a profile are a summary; this is what they are a summary of, so
 * a head-to-head record can be worked out after the fact rather than having to be
 * anticipated. Capped, because a life tracker has no business growing without limit.
 */
@Serializable
data class GameHistory(val games: List<GameRecord> = emptyList()) {

    fun record(game: GameState, playedAt: Long): GameHistory {
        val outcome = game.outcome ?: return this
        val record = GameRecord(
            playedAt = playedAt,
            settings = game.settings,
            seats = game.players.sortedBy { it.seat }.map { player ->
                RecordedSeat(
                    seat = player.seat,
                    name = player.name,
                    colour = player.colour,
                    profileId = player.profileId,
                    lostTo = player.lostTo,
                    life = player.life,
                )
            },
            winningSeats = outcome.winningSeats,
            turns = game.turnCount,
        )
        return copy(games = (listOf(record) + games).take(MAX_GAMES))
    }

    fun forProfile(profileId: String): List<GameRecord> = games.filter { it.involved(profileId) }

    /**
     * How [profileId] has fared against [opponentId] in the games they both played.
     *
     * Draws count for neither, the same way they do on the leaderboard.
     */
    fun headToHead(profileId: String, opponentId: String): HeadToHead {
        val shared = games.filter { it.involved(profileId) && it.involved(opponentId) }
        return HeadToHead(
            played = shared.size,
            won = shared.count { it.wasWonBy(profileId) },
            lost = shared.count { it.wasWonBy(opponentId) },
        )
    }

    companion object {
        const val MAX_GAMES = 200
    }
}

@Serializable
data class HeadToHead(val played: Int, val won: Int, val lost: Int)
