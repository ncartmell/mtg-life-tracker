package uk.co.ncartmell.mtg.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameEngineTest {

    private fun seats(n: Int) = (0 until n).map {
        SeatSetup(name = "P$it", colour = PlayerColour.entries[it])
    }

    private fun game(
        players: Int = 4,
        life: Int = 40,
        commander: Boolean = true,
    ) = GameEngine.newGame(
        GameSettings(playerCount = players, startingLife = life, commanderDamageEnabled = commander),
        seats(players),
    )

    // --- cannot lose -----------------------------------------------------------------

    @Test
    fun `a player who cannot lose survives zero life`() {
        var state = GameEngine.setCannotLose(game(), seat = 0, value = true)
        state = GameEngine.adjustLife(state, seat = 0, delta = -40)

        assertEquals(0, state.player(0).life)
        assertFalse(state.player(0).isOut)
        assertNull(state.outcome)
    }

    @Test
    fun `a player who cannot lose survives lethal poison and commander damage`() {
        var state = GameEngine.setCannotLose(game(), seat = 0, value = true)
        state = GameEngine.adjustPoison(state, seat = 0, delta = 10)
        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = CommanderId(1, 0), delta = 21)

        assertEquals(10, state.player(0).poison)
        assertEquals(21, state.player(0).damageFrom(CommanderId(1, 0)))
        assertFalse(state.player(0).isOut)
    }

    @Test
    fun `counters keep climbing underneath the flag`() {
        var state = GameEngine.setCannotLose(game(), seat = 0, value = true)
        state = GameEngine.adjustLife(state, seat = 0, delta = -55)

        // Not clamped at zero: the life total is still the truth of what has happened.
        assertEquals(-15, state.player(0).life)
        assertFalse(state.player(0).isOut)
    }

    @Test
    fun `clearing the flag applies every threshold that built up while it was set`() {
        var state = GameEngine.setCannotLose(game(), seat = 0, value = true)
        state = GameEngine.adjustLife(state, seat = 0, delta = -40)
        assertFalse(state.player(0).isOut)

        state = GameEngine.setCannotLose(state, seat = 0, value = false)

        assertTrue(state.player(0).isOut)
        assertEquals(LossReason.LifeDepleted, state.player(0).lostTo)
    }

    @Test
    fun `clearing the flag reports poison when poison is what was lethal`() {
        var state = GameEngine.setCannotLose(game(), seat = 0, value = true)
        state = GameEngine.adjustPoison(state, seat = 0, delta = 10)
        state = GameEngine.setCannotLose(state, seat = 0, value = false)

        assertEquals(LossReason.Poison, state.player(0).lostTo)
    }

    @Test
    fun `a player who cannot lose can still be removed by hand`() {
        var state = GameEngine.setCannotLose(game(), seat = 0, value = true)
        state = GameEngine.eliminate(state, seat = 0, reason = LossReason.Conceded)

        assertTrue(state.player(0).isOut)
        assertEquals(LossReason.Conceded, state.player(0).lostTo)
    }

    @Test
    fun `the flag protects only the seat it was set on`() {
        var state = GameEngine.setCannotLose(game(), seat = 0, value = true)
        state = GameEngine.adjustLife(state, seat = 0, delta = -40)
        state = GameEngine.adjustLife(state, seat = 1, delta = -40)

        assertFalse(state.player(0).isOut)
        assertTrue(state.player(1).isOut)
    }

    @Test
    fun `the last survivor still wins when everyone else is gone`() {
        var state = GameEngine.setCannotLose(game(players = 2), seat = 0, value = true)
        state = GameEngine.adjustLife(state, seat = 0, delta = -40)
        state = GameEngine.adjustLife(state, seat = 1, delta = -40)

        assertEquals(GameOutcome.Winner(0), state.outcome)
    }

    @Test
    fun `restarting clears the flag`() {
        var state = GameEngine.setCannotLose(game(), seat = 0, value = true)
        state = GameEngine.restart(state)

        assertFalse(state.player(0).cannotLose)
    }

    // --- setup -----------------------------------------------------------------------

    @Test
    fun `new game seats everyone on the starting life total`() {
        val state = game(players = 4, life = 40)
        assertEquals(4, state.players.size)
        assertTrue(state.players.all { it.life == 40 })
        assertTrue(state.players.all { !it.isOut })
        assertNull(state.outcome)
        assertEquals(listOf(0, 1, 2, 3), state.players.map { it.seat })
    }

    @Test
    fun `seat count must match the settings`() {
        assertFailsWith<IllegalArgumentException> {
            GameEngine.newGame(GameSettings(playerCount = 4, startingLife = 40), seats(3))
        }
    }

    @Test
    fun `the same profile cannot take two seats`() {
        val duplicated = listOf(
            SeatSetup("A", PlayerColour.RED, profileId = "p1"),
            SeatSetup("B", PlayerColour.BLUE, profileId = "p1"),
        )
        assertFailsWith<IllegalArgumentException> {
            GameEngine.newGame(GameSettings(playerCount = 2, startingLife = 20), duplicated)
        }
    }

    @Test
    fun `player count is bounded`() {
        assertFailsWith<IllegalArgumentException> { GameSettings(playerCount = 1, startingLife = 20) }
        assertFailsWith<IllegalArgumentException> { GameSettings(playerCount = 7, startingLife = 20) }
    }

    // --- life ------------------------------------------------------------------------

    @Test
    fun `life can be added and removed`() {
        var state = game(life = 40)
        state = GameEngine.adjustLife(state, seat = 0, delta = -5)
        assertEquals(35, state.player(0).life)
        state = GameEngine.adjustLife(state, seat = 0, delta = 3)
        assertEquals(38, state.player(0).life)
    }

    @Test
    fun `reaching zero life puts a player out`() {
        var state = game(players = 3, life = 20)
        state = GameEngine.adjustLife(state, seat = 1, delta = -20)
        assertTrue(state.player(1).isOut)
        assertEquals(LossReason.LifeDepleted, state.player(1).lostTo)
    }

    @Test
    fun `negative life also puts a player out`() {
        var state = game(players = 3, life = 20)
        state = GameEngine.adjustLife(state, seat = 1, delta = -25)
        assertEquals(-5, state.player(1).life)
        assertTrue(state.player(1).isOut)
    }

    @Test
    fun `a player who is out takes no further damage`() {
        var state = game(players = 3, life = 20)
        state = GameEngine.adjustLife(state, seat = 1, delta = -20)
        val afterDeath = state.player(1).life
        state = GameEngine.adjustLife(state, seat = 1, delta = -5)
        assertEquals(afterDeath, state.player(1).life)
    }

    // --- poison ----------------------------------------------------------------------

    @Test
    fun `ten poison counters put a player out`() {
        var state = game(players = 3)
        repeat(9) { state = GameEngine.adjustPoison(state, seat = 2, delta = 1) }
        assertFalse(state.player(2).isOut)
        state = GameEngine.adjustPoison(state, seat = 2, delta = 1)
        assertTrue(state.player(2).isOut)
        assertEquals(LossReason.Poison, state.player(2).lostTo)
    }

    @Test
    fun `poison cannot go below zero`() {
        var state = game()
        state = GameEngine.adjustPoison(state, seat = 0, delta = -3)
        assertEquals(0, state.player(0).poison)
    }

    @Test
    fun `poison is ignored when disabled`() {
        val settings = GameSettings(playerCount = 2, startingLife = 20, poisonEnabled = false)
        var state = GameEngine.newGame(settings, seats(2))
        state = GameEngine.adjustPoison(state, seat = 0, delta = 15)
        assertEquals(0, state.player(0).poison)
        assertFalse(state.player(0).isOut)
    }

    // --- commander damage ------------------------------------------------------------

    @Test
    fun `commander damage reduces life as well as being tracked`() {
        var state = game(players = 3, life = 40)
        val from = CommanderId(seat = 1, index = 0)
        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = from, delta = 7)

        assertEquals(7, state.player(0).damageFrom(from))
        assertEquals(33, state.player(0).life, "commander damage is also a loss of life")
    }

    @Test
    fun `twenty one damage from one commander is lethal even at high life`() {
        var state = game(players = 3, life = 100)
        val from = CommanderId(seat = 1, index = 0)
        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = from, delta = 20)
        assertFalse(state.player(0).isOut)

        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = from, delta = 1)
        assertTrue(state.player(0).isOut)
        assertEquals(LossReason.CommanderDamage(from), state.player(0).lostTo)
        assertTrue(state.player(0).life > 0, "still on positive life, but out on commander damage")
    }

    @Test
    fun `damage from two different commanders does not combine`() {
        var state = game(players = 3, life = 100)
        val a = CommanderId(seat = 1, index = 0)
        val b = CommanderId(seat = 2, index = 0)
        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = a, delta = 20)
        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = b, delta = 20)

        assertFalse(state.player(0).isOut, "40 total, but only 20 from each — not lethal")
        assertEquals(20, state.player(0).highestCommanderDamage)
    }

    @Test
    fun `a player with two commanders tracks each separately`() {
        val setup = listOf(
            SeatSetup("A", PlayerColour.RED),
            SeatSetup("B", PlayerColour.BLUE, commanderCount = 2),
        )
        var state = GameEngine.newGame(GameSettings(2, 40), setup)
        val first = CommanderId(seat = 1, index = 0)
        val second = CommanderId(seat = 1, index = 1)

        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = first, delta = 15)
        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = second, delta = 15)

        assertEquals(15, state.player(0).damageFrom(first))
        assertEquals(15, state.player(0).damageFrom(second))
        assertFalse(state.player(0).isOut)
        assertEquals(10, state.player(0).life, "30 damage total off 40 life")
    }

    @Test
    fun `reducing commander damage below zero does not gain life`() {
        var state = game(players = 2, life = 40)
        val from = CommanderId(seat = 1, index = 0)
        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = from, delta = 3)
        assertEquals(37, state.player(0).life)

        state = GameEngine.adjustCommanderDamage(state, seat = 0, from = from, delta = -10)
        assertEquals(0, state.player(0).damageFrom(from))
        assertEquals(40, state.player(0).life, "life returns to 40, not higher")
    }

    @Test
    fun `a commander cannot damage its own controller`() {
        val state = game(players = 2)
        assertFailsWith<IllegalArgumentException> {
            GameEngine.adjustCommanderDamage(state, seat = 0, from = CommanderId(0, 0), delta = 1)
        }
    }

    @Test
    fun `commander damage is ignored when disabled`() {
        var state = game(players = 2, life = 20, commander = false)
        state = GameEngine.adjustCommanderDamage(state, 0, CommanderId(1, 0), 21)
        assertEquals(20, state.player(0).life)
        assertFalse(state.player(0).isOut)
    }

    @Test
    fun `dropping a second commander clears damage attributed to it`() {
        val setup = listOf(
            SeatSetup("A", PlayerColour.RED),
            SeatSetup("B", PlayerColour.BLUE, commanderCount = 2),
        )
        var state = GameEngine.newGame(GameSettings(2, 40), setup)
        state = GameEngine.adjustCommanderDamage(state, 0, CommanderId(1, 1), 10)
        assertEquals(10, state.player(0).damageFrom(CommanderId(1, 1)))

        state = GameEngine.setCommanderCount(state, seat = 1, count = 1)
        assertEquals(0, state.player(0).damageFrom(CommanderId(1, 1)))
    }

    // --- other loss conditions -------------------------------------------------------

    @Test
    fun `mill concede and effect all remove a player`() {
        val reasons = listOf(LossReason.Milled, LossReason.Conceded, LossReason.Effect)
        reasons.forEach { reason ->
            var state = game(players = 4)
            state = GameEngine.eliminate(state, seat = 3, reason = reason)
            assertTrue(state.player(3).isOut)
            assertEquals(reason, state.player(3).lostTo)
        }
    }

    @Test
    fun `restoring a player undoes an elimination`() {
        var state = game(players = 3)
        state = GameEngine.eliminate(state, 1, LossReason.Conceded)
        assertTrue(state.player(1).isOut)

        state = GameEngine.restore(state, 1)
        assertFalse(state.player(1).isOut)
        assertNull(state.outcome)
    }

    @Test
    fun `restoring a player on zero life brings them back on one`() {
        var state = game(players = 3, life = 20)
        state = GameEngine.adjustLife(state, 1, -20)
        assertTrue(state.player(1).isOut)

        state = GameEngine.restore(state, 1)

        assertFalse(state.player(1).isOut, "restoring has to survive the next check")
        assertEquals(1, state.player(1).life)
    }

    @Test
    fun `restoring a player past zero life still brings them back on one`() {
        var state = game(players = 3, life = 20)
        state = GameEngine.adjustLife(state, 1, -35)
        state = GameEngine.restore(state, 1)

        assertFalse(state.player(1).isOut)
        assertEquals(1, state.player(1).life)
    }

    @Test
    fun `restoring a poisoned player drops them below the threshold`() {
        var state = game(players = 3)
        state = GameEngine.adjustPoison(state, 1, 10)
        assertTrue(state.player(1).isOut)

        state = GameEngine.restore(state, 1)

        assertFalse(state.player(1).isOut)
        assertEquals(9, state.player(1).poison)
    }

    @Test
    fun `restoring after commander damage drops that commander below the threshold`() {
        val from = CommanderId(2, 0)
        var state = game(players = 3, life = 40)
        state = GameEngine.adjustCommanderDamage(state, 1, from, 21)
        assertTrue(state.player(1).isOut)

        state = GameEngine.restore(state, 1)

        assertFalse(state.player(1).isOut)
        assertEquals(20, state.player(1).damageFrom(from))
    }

    @Test
    fun `restoring leaves everything that was not lethal alone`() {
        var state = game(players = 3, life = 20)
        state = GameEngine.adjustPoison(state, 1, 4)
        state = GameEngine.adjustLife(state, 1, -20)
        state = GameEngine.restore(state, 1)

        assertEquals(4, state.player(1).poison, "poison was never over the line")
        assertEquals(1, state.player(1).life)
    }

    @Test
    fun `restoring a player reopens a game that had been won`() {
        var state = game(players = 2, life = 20)
        state = GameEngine.adjustLife(state, 1, -20)
        assertEquals(GameOutcome.Winner(0), state.outcome)

        state = GameEngine.restore(state, 1)

        assertNull(state.outcome)
        assertFalse(state.player(1).isOut)
    }

    // --- outcome ---------------------------------------------------------------------

    @Test
    fun `the last player standing wins`() {
        var state = game(players = 3, life = 20)
        state = GameEngine.adjustLife(state, 0, -20)
        assertNull(state.outcome, "two left, game continues")

        state = GameEngine.adjustLife(state, 1, -20)
        assertEquals(GameOutcome.Winner(2), state.outcome)
    }

    @Test
    fun `everyone leaving at once is a draw`() {
        var state = game(players = 2, life = 20)
        state = GameEngine.eliminate(state, 0, LossReason.Effect)
        state = GameEngine.eliminate(state, 1, LossReason.Effect)
        assertEquals(GameOutcome.Draw, state.outcome)
    }

    @Test
    fun `a finished game ignores further changes`() {
        var state = game(players = 2, life = 20)
        state = GameEngine.adjustLife(state, 0, -20)
        assertEquals(GameOutcome.Winner(1), state.outcome)

        state = GameEngine.adjustLife(state, 1, -5)
        assertEquals(20, state.player(1).life)
    }

    // --- restart ---------------------------------------------------------------------

    @Test
    fun `restart keeps settings and players but resets everything else`() {
        var state = game(players = 3, life = 40)
        state = GameEngine.adjustLife(state, 0, -12)
        state = GameEngine.adjustPoison(state, 1, 4)
        state = GameEngine.adjustCommanderDamage(state, 2, CommanderId(0, 0), 6)
        state = GameEngine.eliminate(state, 1, LossReason.Conceded)
        state = GameEngine.rollForFirstPlayer(state, Random(1))

        val restarted = GameEngine.restart(state)

        assertEquals(state.settings, restarted.settings)
        assertEquals(state.players.map { it.name }, restarted.players.map { it.name })
        assertEquals(state.players.map { it.colour }, restarted.players.map { it.colour })
        assertTrue(restarted.players.all { it.life == 40 })
        assertTrue(restarted.players.all { it.poison == 0 })
        assertTrue(restarted.players.all { it.commanderDamage.isEmpty() })
        assertTrue(restarted.players.none { it.isOut })
        assertNull(restarted.outcome)
        assertNull(restarted.startingSeat, "who goes first is decided again")
    }

    @Test
    fun `restart preserves commander counts`() {
        val setup = listOf(
            SeatSetup("A", PlayerColour.RED, commanderCount = 2),
            SeatSetup("B", PlayerColour.BLUE),
        )
        val state = GameEngine.newGame(GameSettings(2, 40), setup)
        val restarted = GameEngine.restart(state)
        assertEquals(2, restarted.player(0).commanderCount)
    }

    // --- first player ----------------------------------------------------------------

    @Test
    fun `rolling picks a seat and records the roll`() {
        val state = GameEngine.rollForFirstPlayer(game(players = 4), Random(42))
        val seat = state.startingSeat
        assertTrue(seat != null && seat in 0..3)
        assertEquals(seat, state.lastRoll?.winningSeat)
        assertEquals(4, state.lastRoll?.results?.size)
    }

    @Test
    fun `rolling only considers players still in the game`() {
        var state = game(players = 4)
        state = GameEngine.eliminate(state, 0, LossReason.Conceded)
        state = GameEngine.eliminate(state, 1, LossReason.Conceded)
        state = GameEngine.rollForFirstPlayer(state, Random(7))
        assertTrue(state.startingSeat in listOf(2, 3))
        assertEquals(setOf(2, 3), state.lastRoll?.results?.keys)
    }

    @Test
    fun `ties are resolved rather than returned`() {
        // A one-sided die makes every roll a tie; the engine must still terminate on a
        // single winner by re-rolling among the tied players.
        repeat(20) { seed ->
            val state = GameEngine.rollForFirstPlayer(game(players = 4), Random(seed), sides = 2)
            val winner = state.startingSeat
            assertTrue(winner != null, "a winner is always produced")
            val results = state.lastRoll!!.results
            assertEquals(1, results.values.count { it == results.values.max() })
        }
    }

    @Test
    fun `a die needs more than one side`() {
        assertFailsWith<IllegalArgumentException> {
            GameEngine.rollForFirstPlayer(game(), Random(1), sides = 1)
        }
    }
}
