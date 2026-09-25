package uk.co.ncartmell.mtg.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RollOffTest {

    private fun game(players: Int = 4, seatingOrder: List<Int> = emptyList()) =
        GameEngine.newGame(
            GameSettings(playerCount = players, startingLife = 40, format = Format.FREE_FOR_ALL),
            (0 until players).map {
                SeatSetup(name = "P${it + 1}", colour = PlayerColour.entries[it])
            },
            seatingOrder = seatingOrder,
        )

    /** Feeds a whole round in, in the order the seats will be asked for it. */
    private fun RollOff.recordAll(vararg values: Int) = values.fold(this) { it, v -> it.record(v) }

    // --- going round the table ---------------------------------------------------------

    @Test
    fun `asks each seat in turn`() {
        var roll = RollOff(listOf(0, 1, 2, 3))
        assertEquals(0, roll.awaiting)
        roll = roll.record(12)
        assertEquals(1, roll.awaiting, "the die moves on once a number has landed")
        assertEquals(1, roll.rolledThisRound)
        roll = roll.record(4)
        assertEquals(2, roll.awaiting)
    }

    @Test
    fun `the highest roll wins outright`() {
        val roll = RollOff(listOf(0, 1, 2, 3)).recordAll(12, 4, 19, 7)
        assertTrue(roll.isSettled)
        assertNull(roll.awaiting, "nobody is being waited on once it is decided")
        assertEquals(2, roll.winningSeat)
        assertEquals(DiceRoll(listOf(mapOf(0 to 12, 1 to 4, 2 to 19, 3 to 7)), 2), roll.result)
    }

    @Test
    fun `nothing is settled until the last seat has rolled`() {
        val roll = RollOff(listOf(0, 1, 2, 3)).recordAll(20, 1, 1)
        assertFalse(roll.isSettled, "a twenty is not a win while somebody has yet to roll")
        assertNull(roll.result)
        assertEquals(3, roll.awaiting)
    }

    @Test
    fun `one contender settles on one roll`() {
        val roll = RollOff(listOf(2)).record(5)
        assertEquals(2, roll.winningSeat)
        assertEquals(5, roll.result?.winningRoll)
    }

    // --- ties --------------------------------------------------------------------------

    @Test
    fun `only the tied seats roll again`() {
        val roll = RollOff(listOf(0, 1, 2, 3)).recordAll(19, 4, 19, 7)
        assertFalse(roll.isSettled)
        assertEquals(listOf(0, 2), roll.contenders, "the two who tied, and nobody else")
        assertEquals(0, roll.awaiting)
        assertEquals(1, roll.tieBreakNumber)
        assertEquals(0, roll.rolledThisRound, "the tie-break starts empty")
    }

    @Test
    fun `a tie-break keeps seating order rather than the order they rolled in`() {
        // Seat 3 rolls before seat 1 in the opening round, because the table is seated
        // 3, 1, 0, 2 — and the die carries on going that way round for the tie-break.
        val roll = RollOff(listOf(3, 1, 0, 2)).recordAll(2, 19, 5, 19)
        assertEquals(listOf(1, 2), roll.contenders)
        assertEquals(1, roll.awaiting)
    }

    @Test
    fun `the opening roll survives a tie-break`() {
        val roll = RollOff(listOf(0, 1, 2, 3))
            .recordAll(19, 4, 19, 7)
            .recordAll(11, 15)
        val result = assertNotNull(roll.result)
        assertEquals(2, result.winningSeat)
        assertEquals(15, result.winningRoll, "the number that actually won")
        assertEquals(
            mapOf(0 to 19, 1 to 4, 2 to 19, 3 to 7),
            result.openingRoll,
            "the seats that were knocked out in round one still have their numbers",
        )
        assertTrue(result.wasTied)
        assertEquals(listOf(mapOf(0 to 11, 2 to 15)), result.tieBreaks)
    }

    @Test
    fun `a tie-break can tie again`() {
        val roll = RollOff(listOf(0, 1, 2, 3))
            .recordAll(19, 4, 19, 7)
            .recordAll(11, 11)
            .recordAll(3, 18)
        val result = assertNotNull(roll.result)
        assertEquals(2, result.winningSeat)
        assertEquals(3, result.rounds.size, "every round is kept, not just the last")
        assertEquals(2, result.tieBreaks.size)
    }

    @Test
    fun `a roll after it has settled changes nothing`() {
        val settled = RollOff(listOf(0, 1)).recordAll(12, 4)
        assertEquals(settled, settled.record(20), "the winner keeps fidgeting with the die")
    }

    // --- starting from a game ----------------------------------------------------------

    @Test
    fun `starts with everybody still in, in seating order`() {
        val state = game(players = 4, seatingOrder = listOf(0, 1, 3, 2))
        assertEquals(listOf(0, 1, 3, 2), assertNotNull(RollOff.start(state)).contenders)
    }

    @Test
    fun `leaves out players who are already knocked out`() {
        val state = GameEngine.eliminate(game(players = 4), seat = 1, reason = LossReason.Conceded)
        assertEquals(listOf(0, 2, 3), assertNotNull(RollOff.start(state)).contenders)
    }

    @Test
    fun `rejects a roll-off with nobody in it`() {
        assertFailsWith<IllegalArgumentException> { RollOff(emptyList()) }
    }

    // --- landing on the game -----------------------------------------------------------

    @Test
    fun `applying a finished roll starts the game the same way a virtual one does`() {
        val state = game(players = 4)
        val roll = assertNotNull(RollOff.start(state)).recordAll(3, 8, 20, 1).result
        val next = GameEngine.applyRoll(state, assertNotNull(roll), at = 1_000L)
        assertEquals(2, next.startingSeat)
        assertEquals(2, next.turnSeat)
        assertEquals(1, next.turnCount)
        assertEquals(1_000L, next.turnStartedAt)
        assertEquals(roll, next.lastRoll)
    }
}
