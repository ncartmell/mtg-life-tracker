package uk.co.ncartmell.mtg.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The states a player can be in that are not a number going up and down: the city's
 * blessing, where they have got to in the Undercity, and the capped counters.
 */
class PlayerStatesTest {

    private fun game(players: Int = 4) = GameEngine.newGame(
        GameSettings(playerCount = players, startingLife = 40),
        (0 until players).map { SeatSetup(name = "P$it", colour = PlayerColour.entries[it]) },
    )

    // --- capped counters ---------------------------------------------------------------

    @Test
    fun `the ring stops tempting at four`() {
        var state = game()
        state = GameEngine.adjustCounter(state, 0, Counter.RING, 10)
        assertEquals(4, state.player(0)[Counter.RING])
    }

    @Test
    fun `speed stops at four and still comes back down`() {
        var state = game()
        repeat(9) { state = GameEngine.adjustCounter(state, 1, Counter.SPEED, 1) }
        assertEquals(4, state.player(1)[Counter.SPEED])

        state = GameEngine.adjustCounter(state, 1, Counter.SPEED, -3)
        assertEquals(1, state.player(1)[Counter.SPEED])
    }

    @Test
    fun `an uncapped counter is left alone`() {
        var state = game()
        state = GameEngine.adjustCounter(state, 0, Counter.RAD, 17)
        state = GameEngine.adjustCounter(state, 0, Counter.STORM, 40)
        assertEquals(17, state.player(0)[Counter.RAD])
        assertEquals(40, state.player(0)[Counter.STORM])
    }

    @Test
    fun `no counter goes below zero`() {
        var state = game()
        state = GameEngine.adjustCounter(state, 0, Counter.RING, -5)
        assertEquals(0, state.player(0)[Counter.RING])
        assertEquals(emptyList(), state.player(0).activeCounters)
    }

    // --- commander tax -------------------------------------------------------------------

    @Test
    fun `each of a player's commanders is taxed separately`() {
        var state = GameEngine.setCommanderCount(game(), 0, 2)
        state = GameEngine.adjustCommanderTax(state, 0, 0, 3)
        state = GameEngine.adjustCommanderTax(state, 0, 1, 1)

        // One number between two partners was the old shape, and it was wrong: each
        // commander climbs its own ladder.
        assertEquals(3, state.player(0).taxOn(0))
        assertEquals(1, state.player(0).taxOn(1))
    }

    @Test
    fun `tax belongs to the player whose commander it is`() {
        var state = game()
        state = GameEngine.adjustCommanderTax(state, 0, 0, 2)
        assertEquals(2, state.player(0).taxOn(0))
        assertEquals(0, state.player(1).taxOn(0))
    }

    @Test
    fun `tax never goes below zero`() {
        val state = GameEngine.adjustCommanderTax(game(), 0, 0, -4)
        assertEquals(0, state.player(0).taxOn(0))
    }

    @Test
    fun `dropping to one commander drops the second one's tax with it`() {
        var state = GameEngine.setCommanderCount(game(), 0, 2)
        state = GameEngine.adjustCommanderTax(state, 0, 0, 2)
        state = GameEngine.adjustCommanderTax(state, 0, 1, 5)

        state = GameEngine.setCommanderCount(state, 0, 1)

        assertEquals(2, state.player(0).taxOn(0))
        assertEquals(0, state.player(0).taxOn(1), "the commander it belonged to is gone")
    }

    @Test
    fun `tax survives being saved and read back`() {
        var state = GameEngine.setCommanderCount(game(), 0, 2)
        state = GameEngine.adjustCommanderTax(state, 0, 1, 4)
        val json = SavedGameJson
        val back = json.decodeFromString<SavedGame>(
            json.encodeToString(SavedGame.serializer(), SavedGame(state)),
        ).game
        assertEquals(4, back.player(0).taxOn(1))
    }

    // --- the city's blessing -----------------------------------------------------------

    @Test
    fun `the city's blessing is not exclusive, unlike the monarchy`() {
        var state = game()
        state = GameEngine.setCitysBlessing(state, 0, true)
        state = GameEngine.setCitysBlessing(state, 2, true)

        assertTrue(state.player(0).citysBlessing)
        assertTrue(state.player(2).citysBlessing)
        assertFalse(state.player(1).citysBlessing)
    }

    @Test
    fun `taking the monarchy does move it off whoever had it`() {
        var state = game()
        state = GameEngine.setMonarch(state, 0)
        state = GameEngine.setMonarch(state, 2)
        assertEquals(2, state.monarchSeat)
    }

    // --- day and night -------------------------------------------------------------------

    @Test
    fun `a game starts as neither day nor night`() {
        assertEquals(TimeOfDay.NEITHER, game().timeOfDay)
    }

    @Test
    fun `day and night is one value for the table`() {
        val state = GameEngine.setTimeOfDay(game(), TimeOfDay.NIGHT)
        assertEquals(TimeOfDay.NIGHT, state.timeOfDay)
    }

    // --- the Undercity ---------------------------------------------------------------------

    @Test
    fun `a player outside the dungeon ventures in at the secret entrance`() {
        val state = game()
        assertNull(state.player(0).undercityRoom)
        assertEquals(listOf(UndercityRoom.SECRET_ENTRANCE), GameEngine.ventureOptions(state, 0))
    }

    @Test
    fun `the branches match the token`() {
        assertEquals(
            listOf(UndercityRoom.FORGE, UndercityRoom.LOST_WELL),
            UndercityRoom.SECRET_ENTRANCE.leadsTo,
        )
        assertEquals(
            listOf(UndercityRoom.TRAP, UndercityRoom.ARENA),
            UndercityRoom.FORGE.leadsTo,
        )
        assertEquals(
            listOf(UndercityRoom.ARENA, UndercityRoom.STASH),
            UndercityRoom.LOST_WELL.leadsTo,
        )
        assertEquals(listOf(UndercityRoom.ARCHIVES), UndercityRoom.TRAP.leadsTo)
        assertEquals(
            listOf(UndercityRoom.ARCHIVES, UndercityRoom.CATACOMBS),
            UndercityRoom.ARENA.leadsTo,
        )
        assertEquals(listOf(UndercityRoom.CATACOMBS), UndercityRoom.STASH.leadsTo)
        assertEquals(listOf(UndercityRoom.THRONE), UndercityRoom.ARCHIVES.leadsTo)
        assertEquals(listOf(UndercityRoom.THRONE), UndercityRoom.CATACOMBS.leadsTo)
        assertEquals(emptyList(), UndercityRoom.THRONE.leadsTo)
    }

    @Test
    fun `every room is reachable from the entrance`() {
        val seen = mutableSetOf(UndercityRoom.SECRET_ENTRANCE)
        val queue = ArrayDeque(listOf(UndercityRoom.SECRET_ENTRANCE))
        while (queue.isNotEmpty()) {
            queue.removeFirst().leadsTo.forEach { if (seen.add(it)) queue.addLast(it) }
        }
        assertEquals(UndercityRoom.entries.toSet(), seen)
    }

    @Test
    fun `venturing walks the dungeon one room at a time`() {
        var state = game()
        state = GameEngine.ventureTo(state, 0, UndercityRoom.SECRET_ENTRANCE)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.LOST_WELL)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.STASH)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.CATACOMBS)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.THRONE)

        assertEquals(UndercityRoom.THRONE, state.player(0).undercityRoom)
    }

    @Test
    fun `a room that does not follow the current one is refused`() {
        val state = GameEngine.ventureTo(game(), 0, UndercityRoom.SECRET_ENTRANCE)
        assertFailsWith<IllegalArgumentException> {
            GameEngine.ventureTo(state, 0, UndercityRoom.THRONE)
        }
    }

    @Test
    fun `finishing the dungeon starts a fresh run rather than a dead end`() {
        var state = game()
        state = GameEngine.ventureTo(state, 0, UndercityRoom.SECRET_ENTRANCE)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.FORGE)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.TRAP)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.ARCHIVES)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.THRONE)

        // Completing a dungeon removes it, so the next venture enters a new one.
        assertEquals(listOf(UndercityRoom.SECRET_ENTRANCE), GameEngine.ventureOptions(state, 0))
        state = GameEngine.ventureTo(state, 0, UndercityRoom.SECRET_ENTRANCE)
        assertEquals(UndercityRoom.SECRET_ENTRANCE, state.player(0).undercityRoom)
    }

    @Test
    fun `each player walks their own dungeon`() {
        var state = game()
        state = GameEngine.ventureTo(state, 0, UndercityRoom.SECRET_ENTRANCE)
        state = GameEngine.ventureTo(state, 0, UndercityRoom.FORGE)
        state = GameEngine.ventureTo(state, 1, UndercityRoom.SECRET_ENTRANCE)

        assertEquals(UndercityRoom.FORGE, state.player(0).undercityRoom)
        assertEquals(UndercityRoom.SECRET_ENTRANCE, state.player(1).undercityRoom)
        assertNull(state.player(2).undercityRoom)
    }

    @Test
    fun `a venture entered by mistake can be backed out of`() {
        var state = GameEngine.ventureTo(game(), 0, UndercityRoom.SECRET_ENTRANCE)
        state = GameEngine.leaveUndercity(state, 0)
        assertNull(state.player(0).undercityRoom)
    }

    @Test
    fun `all of it survives being saved and read back`() {
        var state = game()
        state = GameEngine.setCitysBlessing(state, 1, true)
        state = GameEngine.setTimeOfDay(state, TimeOfDay.NIGHT)
        state = GameEngine.adjustCounter(state, 1, Counter.RING, 3)
        state = GameEngine.adjustCounter(state, 1, Counter.RAD, 2)
        state = GameEngine.ventureTo(state, 1, UndercityRoom.SECRET_ENTRANCE)
        state = GameEngine.ventureTo(state, 1, UndercityRoom.FORGE)

        val json = SavedGameJson
        val back = json.decodeFromString<SavedGame>(
            json.encodeToString(SavedGame.serializer(), SavedGame(state)),
        ).game

        assertTrue(back.player(1).citysBlessing)
        assertEquals(TimeOfDay.NIGHT, back.timeOfDay)
        assertEquals(3, back.player(1)[Counter.RING])
        assertEquals(2, back.player(1)[Counter.RAD])
        assertEquals(UndercityRoom.FORGE, back.player(1).undercityRoom)
    }
}
