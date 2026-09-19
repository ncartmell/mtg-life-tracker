package uk.co.ncartmell.mtg.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameHistoryTest {

    private fun game(vararg profileIds: String?) = GameEngine.newGame(
        GameSettings(playerCount = profileIds.size, startingLife = 20),
        profileIds.mapIndexed { i, id ->
            SeatSetup(name = "P$i", colour = PlayerColour.entries[i], profileId = id)
        },
    )

    private fun finish(state: GameState, losers: List<Int>): GameState =
        losers.fold(state) { acc, seat -> GameEngine.eliminate(acc, seat, LossReason.Conceded) }

    @Test
    fun `an unfinished game records nothing`() {
        val history = GameHistory().record(game("a", "b", "c"), playedAt = 1)
        assertTrue(history.games.isEmpty())
    }

    @Test
    fun `a finished game keeps every seat and who won`() {
        val finished = finish(game("a", "b", "c"), listOf(1, 2))
        val record = GameHistory().record(finished, playedAt = 42).games.single()

        assertEquals(42, record.playedAt)
        assertEquals(3, record.seats.size)
        assertEquals(0, record.winningSeat)
        assertEquals("a", record.winner?.profileId)
        assertEquals(LossReason.Conceded, record.seats[1].lostTo)
        assertNull(record.seats[0].lostTo)
    }

    @Test
    fun `guests are recorded too, they are simply not a profile`() {
        val finished = finish(game("a", null), listOf(1))
        val record = GameHistory().record(finished, playedAt = 1).games.single()

        assertEquals(2, record.seats.size)
        assertTrue(record.seats[1].wasGuest)
        assertEquals(emptyList(), record.seats.filter { it.profileId == "ghost" })
    }

    @Test
    fun `newest game comes first`() {
        var history = GameHistory()
        history = history.record(finish(game("a", "b"), listOf(1)), playedAt = 1)
        history = history.record(finish(game("a", "b"), listOf(0)), playedAt = 2)

        assertEquals(listOf(2L, 1L), history.games.map { it.playedAt })
    }

    @Test
    fun `history is capped and drops the oldest`() {
        var history = GameHistory()
        repeat(GameHistory.MAX_GAMES + 10) { i ->
            history = history.record(finish(game("a", "b"), listOf(1)), playedAt = i.toLong())
        }
        assertEquals(GameHistory.MAX_GAMES, history.games.size)
        assertEquals((GameHistory.MAX_GAMES + 9).toLong(), history.games.first().playedAt)
        assertEquals(10L, history.games.last().playedAt)
    }

    @Test
    fun `head to head counts only the games both played`() {
        var history = GameHistory()
        history = history.record(finish(game("a", "b"), listOf(1)), playedAt = 1)
        history = history.record(finish(game("a", "b"), listOf(1)), playedAt = 2)
        history = history.record(finish(game("a", "b"), listOf(0)), playedAt = 3)
        // A game "a" played without "b" must not count towards their head to head.
        history = history.record(finish(game("a", "c"), listOf(1)), playedAt = 4)

        val record = history.headToHead("a", "b")
        assertEquals(3, record.played)
        assertEquals(2, record.won)
        assertEquals(1, record.lost)
    }

    @Test
    fun `a draw counts for neither side`() {
        val drawn = finish(game("a", "b"), listOf(0, 1))
        assertEquals(GameOutcome.Draw, drawn.outcome)
        val history = GameHistory().record(drawn, playedAt = 1)

        assertTrue(history.games.single().wasDraw)
        val record = history.headToHead("a", "b")
        assertEquals(1, record.played)
        assertEquals(0, record.won)
        assertEquals(0, record.lost)
    }

    @Test
    fun `a profile's own games can be picked out`() {
        var history = GameHistory()
        history = history.record(finish(game("a", "b"), listOf(1)), playedAt = 1)
        history = history.record(finish(game("b", "c"), listOf(1)), playedAt = 2)

        assertEquals(1, history.forProfile("a").size)
        assertEquals(2, history.forProfile("b").size)
    }
}
