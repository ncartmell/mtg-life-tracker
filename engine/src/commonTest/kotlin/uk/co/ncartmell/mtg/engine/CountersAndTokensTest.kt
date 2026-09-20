package uk.co.ncartmell.mtg.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CountersAndTokensTest {

    private fun game(players: Int = 4) = GameEngine.newGame(
        GameSettings(playerCount = players, startingLife = 20),
        (0 until players).map { SeatSetup(name = "P$it", colour = PlayerColour.entries[it]) },
    )

    // --- counters ---------------------------------------------------------------------

    @Test
    fun `a counter starts at zero and is not carried until it is used`() {
        val state = game()
        assertEquals(0, state.player(0)[Counter.ENERGY])
        assertEquals(emptyList(), state.player(0).activeCounters)
    }

    @Test
    fun `counters are tracked separately per player and per kind`() {
        var state = game()
        state = GameEngine.adjustCounter(state, 0, Counter.ENERGY, 3)
        state = GameEngine.adjustCounter(state, 0, Counter.EXPERIENCE, 2)
        state = GameEngine.adjustCounter(state, 1, Counter.STORM, 5)

        assertEquals(3, state.player(0)[Counter.ENERGY])
        assertEquals(2, state.player(0)[Counter.EXPERIENCE])
        assertEquals(0, state.player(0)[Counter.STORM])
        assertEquals(5, state.player(1)[Counter.STORM])
        assertEquals(0, state.player(1)[Counter.ENERGY])
    }

    @Test
    fun `a counter does not go below zero, and clears itself when it reaches it`() {
        var state = game()
        state = GameEngine.adjustCounter(state, 0, Counter.STORM, 2)
        state = GameEngine.adjustCounter(state, 0, Counter.STORM, -5)

        assertEquals(0, state.player(0)[Counter.STORM])
        assertTrue(
            state.player(0).counters.isEmpty(),
            "a spent counter is dropped, not kept as a zero",
        )
    }

    @Test
    fun `active counters list only what is in play`() {
        var state = game()
        state = GameEngine.adjustCounter(state, 0, Counter.ENERGY, 4)
        state = GameEngine.adjustCounter(state, 0, Counter.STORM, 1)

        assertEquals(
            listOf(Counter.ENERGY to 4, Counter.STORM to 1),
            state.player(0).activeCounters,
        )
    }

    @Test
    fun `restarting clears counters`() {
        var state = game()
        state = GameEngine.adjustCounter(state, 0, Counter.ENERGY, 4)
        state = GameEngine.restart(state)
        assertEquals(emptyList(), state.player(0).activeCounters)
    }

    // --- monarch and initiative -------------------------------------------------------

    @Test
    fun `only one player holds the monarchy`() {
        var state = GameEngine.setMonarch(game(), 2)
        assertEquals(2, state.monarchSeat)

        state = GameEngine.setMonarch(state, 3)
        assertEquals(3, state.monarchSeat, "taking it takes it from whoever had it")
    }

    @Test
    fun `the monarchy and the initiative are held separately`() {
        var state = GameEngine.setMonarch(game(), 1)
        state = GameEngine.setInitiative(state, 2)

        assertEquals(1, state.monarchSeat)
        assertEquals(2, state.initiativeSeat)
    }

    @Test
    fun `neither can be given to a player who is out`() {
        var state = GameEngine.eliminate(game(), 1, LossReason.Conceded)
        state = GameEngine.setMonarch(state, 1)
        state = GameEngine.setInitiative(state, 1)

        assertNull(state.monarchSeat)
        assertNull(state.initiativeSeat)
    }

    @Test
    fun `a player who is knocked out drops both`() {
        var state = GameEngine.setMonarch(game(), 1)
        state = GameEngine.setInitiative(state, 1)
        state = GameEngine.adjustLife(state, 1, -20)

        assertTrue(state.player(1).isOut)
        assertNull(state.monarchSeat, "the crown does not stay on a dead player")
        assertNull(state.initiativeSeat)
    }

    // --- planechase -------------------------------------------------------------------

    @Test
    fun `the planar die is four blanks, one chaos and one planeswalk`() {
        val faces = (1..6).map { GameEngine.rollPlanarDie(Random(it.toLong())) }
        assertTrue(faces.all { it in PlanarFace.entries }, faces.toString())

        // Over many rolls the shape of the die should show through.
        val many = (1..6000).map { GameEngine.rollPlanarDie(Random(it.toLong())) }
        val blanks = many.count { it == PlanarFace.BLANK }
        assertTrue(blanks > many.size * 0.5, "blank is four faces of six, got $blanks")
        assertTrue(many.any { it == PlanarFace.CHAOS })
        assertTrue(many.any { it == PlanarFace.PLANESWALK })
    }

    @Test
    fun `planeswalking names the plane and counts the walk`() {
        var state = GameEngine.planeswalkTo(game(), "Academy at Tolaria West")
        assertEquals("Academy at Tolaria West", state.currentPlane)
        assertEquals(1, state.planeswalks)

        state = GameEngine.planeswalkTo(state, "  Naar Isle  ")
        assertEquals("Naar Isle", state.currentPlane, "trimmed")
        assertEquals(2, state.planeswalks)
    }

    @Test
    fun `walking nowhere clears the plane`() {
        var state = GameEngine.planeswalkTo(game(), "Somewhere")
        state = GameEngine.planeswalkTo(state, "   ")
        assertNull(state.currentPlane)
    }

    // --- timers -----------------------------------------------------------------------

    @Test
    fun `a game and its first turn are stamped when it starts`() {
        val state = GameEngine.newGame(
            GameSettings(playerCount = 2, startingLife = 20),
            listOf(
                SeatSetup("A", PlayerColour.WHITE),
                SeatSetup("B", PlayerColour.BLUE),
            ),
            startedAt = 1_000L,
        )
        assertEquals(1_000L, state.startedAt)
        assertEquals(1_000L, state.turnStartedAt)
    }

    @Test
    fun `passing the turn restamps the turn clock but not the game clock`() {
        var state = GameEngine.newGame(
            GameSettings(playerCount = 2, startingLife = 20),
            listOf(
                SeatSetup("A", PlayerColour.WHITE),
                SeatSetup("B", PlayerColour.BLUE),
            ),
            startedAt = 1_000L,
        )
        state = GameEngine.nextTurn(state, at = 9_000L)

        assertEquals(1_000L, state.startedAt, "the game clock keeps running")
        assertEquals(9_000L, state.turnStartedAt)
    }
}
