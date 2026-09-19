package uk.co.ncartmell.mtg.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {

    private fun game(format: Format, life: Int = 20, players: Int = format.players.first) =
        GameEngine.newGame(
            GameSettings(playerCount = players, startingLife = life, format = format),
            (0 until players).map {
                SeatSetup(name = "P${it + 1}", colour = PlayerColour.entries[it])
            },
        )

    // --- teams -------------------------------------------------------------------------

    @Test
    fun `free-for-all makes everyone a team of one`() {
        val state = game(Format.FREE_FOR_ALL, players = 4)
        assertEquals(listOf(0, 1, 2, 3), state.teams)
        assertEquals(emptyList(), state.alliesOf(0))
    }

    @Test
    fun `two-headed giant pairs neighbouring seats`() {
        val state = game(Format.TWO_HEADED_GIANT)
        assertEquals(listOf(0, 1), state.seatsInTeam(0))
        assertEquals(listOf(2, 3), state.seatsInTeam(1))
        assertEquals(listOf(1), state.alliesOf(0))
    }

    @Test
    fun `archenemy is seat one against the rest`() {
        val state = game(Format.ARCHENEMY, players = 4)
        assertEquals(listOf(0), state.seatsInTeam(0))
        assertEquals(listOf(1, 2, 3), state.seatsInTeam(1))
        assertEquals(emptyList(), state.alliesOf(0), "the archenemy fights alone")
        assertEquals(listOf(2, 3), state.alliesOf(1))
    }

    @Test
    fun `emperor puts the emperor in the middle of each three`() {
        val state = game(Format.EMPEROR)
        assertEquals(listOf(0, 1, 2), state.seatsInTeam(0))
        assertEquals(listOf(3, 4, 5), state.seatsInTeam(1))
        assertEquals(1, state.emperorSeat(0))
        assertEquals(4, state.emperorSeat(1))
    }

    // --- shared life -------------------------------------------------------------------

    @Test
    fun `a two-headed giant team shares one life total`() {
        var state = game(Format.TWO_HEADED_GIANT, life = 30)
        state = GameEngine.adjustLife(state, seat = 0, delta = -7)

        assertEquals(23, state.player(0).life)
        assertEquals(23, state.player(1).life, "partners hold the same total")
        assertEquals(30, state.player(2).life, "the other team is untouched")
        assertEquals(30, state.player(3).life)
    }

    @Test
    fun `a two-headed giant team shares its poison`() {
        var state = game(Format.TWO_HEADED_GIANT, life = 30)
        state = GameEngine.adjustPoison(state, seat = 3, delta = 4)

        assertEquals(4, state.player(2).poison)
        assertEquals(4, state.player(3).poison)
        assertEquals(0, state.player(0).poison)
    }

    @Test
    fun `a shared total reaching zero takes both partners out at once`() {
        var state = game(Format.TWO_HEADED_GIANT, life = 30)
        state = GameEngine.adjustLife(state, seat = 0, delta = -30)

        assertTrue(state.player(0).isOut)
        assertTrue(state.player(1).isOut)
        assertEquals(GameOutcome.TeamWin(listOf(2, 3)), state.outcome)
    }

    @Test
    fun `two-headed giant defaults to thirty life and fifteen poison`() {
        val settings = GameSettings.defaultsFor(Format.TWO_HEADED_GIANT)
        assertEquals(30, settings.startingLife)
        assertEquals(15, settings.poisonThreshold)
        assertEquals(4, settings.playerCount)
    }

    // --- winning -----------------------------------------------------------------------

    @Test
    fun `a team wins only once every one of its opponents is out`() {
        var state = game(Format.TWO_HEADED_GIANT, life = 30)
        state = GameEngine.eliminate(state, 2, LossReason.Conceded)
        assertNull(state.outcome, "one half of a team is not the team")

        state = GameEngine.eliminate(state, 3, LossReason.Conceded)
        assertEquals(GameOutcome.TeamWin(listOf(0, 1)), state.outcome)
    }

    @Test
    fun `the archenemy wins by outlasting everybody`() {
        var state = game(Format.ARCHENEMY, players = 4)
        state = GameEngine.eliminate(state, 1, LossReason.Conceded)
        state = GameEngine.eliminate(state, 2, LossReason.Conceded)
        assertNull(state.outcome)

        state = GameEngine.eliminate(state, 3, LossReason.Conceded)
        assertEquals(GameOutcome.TeamWin(listOf(0)), state.outcome)
    }

    @Test
    fun `the alliance wins the moment the archenemy falls, however many are left`() {
        var state = game(Format.ARCHENEMY, players = 4)
        state = GameEngine.eliminate(state, 2, LossReason.Conceded)
        state = GameEngine.eliminate(state, 0, LossReason.Conceded)

        assertEquals(GameOutcome.TeamWin(listOf(1, 2, 3)), state.outcome)
        assertTrue(state.player(2).isOut, "a fallen ally still shares the win")
    }

    @Test
    fun `an emperor falling ends it even with both generals standing`() {
        var state = game(Format.EMPEROR)
        state = GameEngine.eliminate(state, 4, LossReason.Conceded)

        assertEquals(GameOutcome.TeamWin(listOf(0, 1, 2)), state.outcome)
        assertFalse(state.player(3).isOut)
        assertFalse(state.player(5).isOut)
    }

    @Test
    fun `generals falling does not end it while the emperor stands`() {
        var state = game(Format.EMPEROR)
        state = GameEngine.eliminate(state, 3, LossReason.Conceded)
        state = GameEngine.eliminate(state, 5, LossReason.Conceded)

        assertNull(state.outcome)
        assertFalse(state.teamIsOut(1))
    }

    @Test
    fun `restoring an emperor puts their team back in the game`() {
        var state = game(Format.EMPEROR)
        state = GameEngine.eliminate(state, 4, LossReason.Conceded)
        assertEquals(GameOutcome.TeamWin(listOf(0, 1, 2)), state.outcome)

        state = GameEngine.restore(state, 4)
        assertNull(state.outcome)
    }

    // --- a team win counts for the whole team -------------------------------------------

    @Test
    fun `both partners are credited with a team win`() {
        val book = ProfileBook()
            .add("Ann", PlayerColour.WHITE, id = "a")
            .add("Bob", PlayerColour.BLUE, id = "b")
            .add("Cat", PlayerColour.RED, id = "c")
            .add("Dan", PlayerColour.GREEN, id = "d")
        var state = GameEngine.newGame(
            GameSettings(playerCount = 4, startingLife = 30, format = Format.TWO_HEADED_GIANT),
            listOf("a", "b", "c", "d").mapIndexed { i, id ->
                SeatSetup(name = id, colour = PlayerColour.entries[i], profileId = id)
            },
        )
        state = GameEngine.adjustLife(state, seat = 2, delta = -30)

        val after = book.recordResult(state)
        assertEquals(1, after["a"]?.wins)
        assertEquals(1, after["b"]?.wins, "the partner won too")
        assertEquals(1, after["c"]?.losses)
        assertEquals(1, after["d"]?.losses)
    }

    @Test
    fun `history keeps every winning seat`() {
        var state = game(Format.TWO_HEADED_GIANT, life = 30)
        state = GameEngine.adjustLife(state, seat = 2, delta = -30)
        val record = GameHistory().record(state, playedAt = 1).games.single()

        assertEquals(listOf(0, 1), record.winningSeats)
        assertEquals(2, record.winners.size)
        assertFalse(record.wasDraw)
    }

    // --- table sizes --------------------------------------------------------------------

    @Test
    fun `a format only accepts a table it can be played on`() {
        assertFailsWith<IllegalArgumentException> {
            GameSettings(playerCount = 3, startingLife = 30, format = Format.TWO_HEADED_GIANT)
        }
        assertFailsWith<IllegalArgumentException> {
            GameSettings(playerCount = 4, startingLife = 20, format = Format.EMPEROR)
        }
        assertFailsWith<IllegalArgumentException> {
            GameSettings(playerCount = 2, startingLife = 20, format = Format.ARCHENEMY)
        }
    }
}
