package uk.co.ncartmell.mtg.engine

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A game has to survive being written out and read back, counters and all, or resuming it
 * quietly hands the table a different game from the one they were playing.
 */
class SavedGameTest {

    // The same configuration the app writes with, not a lookalike.
    private val json = SavedGameJson

    private fun game(players: Int = 4) = GameEngine.newGame(
        GameSettings(playerCount = players, startingLife = 40),
        (0 until players).map { SeatSetup(name = "P$it", colour = PlayerColour.entries[it]) },
        startedAt = 1_000L,
    )

    private fun roundTrip(saved: SavedGame) =
        json.decodeFromString<SavedGame>(json.encodeToString(saved))

    @Test
    fun `a game in progress comes back exactly as it went in`() {
        var state = game()
        state = GameEngine.adjustLife(state, 0, -13)
        state = GameEngine.adjustPoison(state, 1, 4)
        state = GameEngine.adjustCommanderDamage(state, 2, CommanderId(0, 0), 7)
        state = GameEngine.adjustCounter(state, 3, Counter.ENERGY, 5)
        state = GameEngine.setMonarch(state, 1)
        state = GameEngine.nextTurn(state, at = 2_000L)

        assertEquals(SavedGame(state, false), roundTrip(SavedGame(state, false)))
    }

    @Test
    fun `the paint a panel was given survives the round trip`() {
        val paint = PanelPaint(0x11223344, 0x55667788, PanelStyle.FADE)
        val state = GameEngine.newGame(
            GameSettings(playerCount = 2, startingLife = 20),
            listOf(
                SeatSetup(name = "A", colour = PlayerColour.BLUE, paint = paint),
                SeatSetup(name = "B", colour = PlayerColour.RED),
            ),
        )
        assertEquals(paint, roundTrip(SavedGame(state)).game.player(0).panel)
    }

    @Test
    fun `a finished game remembers that its result was already counted`() {
        var state = game(players = 2)
        state = GameEngine.adjustLife(state, 1, -40)
        assertTrue(state.isFinished)

        // The flag is the whole reason this is not just a GameState. Recomputing it from
        // the game would say "finished and therefore unrecorded", and the next change
        // made to a resumed game would write the same win to the leaderboard again.
        val back = roundTrip(SavedGame(state, resultRecorded = true))
        assertTrue(back.resultRecorded)
        assertTrue(back.game.isFinished)
        assertEquals(listOf(0), back.game.outcome?.winningSeats)
    }

    @Test
    fun `an unfinished game comes back unrecorded`() {
        val back = roundTrip(SavedGame(game()))
        assertFalse(back.resultRecorded)
        assertFalse(back.game.isFinished)
    }

    @Test
    fun `who is out and why survives, so the board reads the same on resume`() {
        var state = game()
        state = GameEngine.eliminate(state, 2, LossReason.Conceded)
        state = GameEngine.adjustPoison(state, 3, 10)

        val back = roundTrip(SavedGame(state)).game
        assertEquals(LossReason.Conceded, back.player(2).lostTo)
        assertEquals(LossReason.Poison, back.player(3).lostTo)
        assertFalse(back.player(0).isOut)
    }

    @Test
    fun `the seating order survives, so turns keep going the same way round`() {
        val state = GameEngine.newGame(
            GameSettings(playerCount = 4, startingLife = 40),
            (0 until 4).map { SeatSetup(name = "P$it", colour = PlayerColour.entries[it]) },
            seatingOrder = listOf(0, 1, 3, 2),
        )
        assertEquals(listOf(0, 1, 3, 2), roundTrip(SavedGame(state)).game.seating)
    }
}
