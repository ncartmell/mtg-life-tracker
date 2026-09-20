package uk.co.ncartmell.mtg.app

import uk.co.ncartmell.mtg.app.store.GameRepository
import uk.co.ncartmell.mtg.app.store.HistoryRepository
import uk.co.ncartmell.mtg.app.store.ProfileRepository
import uk.co.ncartmell.mtg.app.store.SetupRepository
import uk.co.ncartmell.mtg.app.store.Storage
import uk.co.ncartmell.mtg.engine.Counter
import uk.co.ncartmell.mtg.engine.GameSettings
import uk.co.ncartmell.mtg.engine.LossReason
import uk.co.ncartmell.mtg.engine.PlayerColour
import uk.co.ncartmell.mtg.engine.SeatSetup
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Storage that keeps its slots in memory, so a test needs no device and no files. */
private class FakeStorage(
    private val slots: MutableMap<String, String> = mutableMapOf(),
) : Storage {
    override fun read(key: String): String? = slots[key]
    override fun write(key: String, value: String) {
        slots[key] = value
    }
}

class AppStateTest {

    /** Moved on between actions that are meant to be separate presses rather than a hold. */
    private var now = 1_000L

    private fun appState(storage: Storage) = AppState(
        profiles = ProfileRepository(storage),
        history = HistoryRepository(storage),
        setups = SetupRepository(storage),
        inProgress = GameRepository(storage),
        random = Random(1),
        clock = { now },
    )

    /** A separate press, far enough after the last that it is not the same gesture. */
    private fun apart() {
        now += 5_000
    }

    private fun AppState.start(players: Int = 4, life: Int = 40) = startGame(
        GameSettings(playerCount = players, startingLife = life),
        (0 until players).map { SeatSetup(name = "P$it", colour = PlayerColour.entries[it]) },
    )

    // --- undo ---------------------------------------------------------------------------

    @Test
    fun `there is nothing to undo in a fresh game`() {
        val state = appState(FakeStorage()).apply { start() }
        assertFalse(state.canUndo)
    }

    @Test
    fun `undo steps a life change back`() {
        val state = appState(FakeStorage()).apply { start() }
        state.adjustLife(0, -7)
        assertEquals(33, state.game!!.player(0).life)

        state.undo()

        assertEquals(40, state.game!!.player(0).life)
        assertFalse(state.canUndo, "the only step there was has been taken")
    }

    @Test
    fun `undo walks back one press at a time`() {
        val state = appState(FakeStorage()).apply { start() }
        repeat(3) { apart(); state.adjustLife(0, -1) }
        assertEquals(37, state.game!!.player(0).life)

        state.undo()
        assertEquals(38, state.game!!.player(0).life)
        state.undo()
        assertEquals(39, state.game!!.player(0).life)
    }

    @Test
    fun `a held run is one step back, not one per repeat`() {
        val state = appState(FakeStorage()).apply { start() }
        // What a held minus looks like: repeats in quick succession on the same glyph.
        repeat(14) { now += 90; state.adjustLife(0, -1) }
        assertEquals(26, state.game!!.player(0).life)

        state.undo()

        assertEquals(40, state.game!!.player(0).life, "the whole hold, not a point of it")
        assertFalse(state.canUndo)
    }

    @Test
    fun `letting go and pressing again is a second step`() {
        val state = appState(FakeStorage()).apply { start() }
        repeat(3) { now += 90; state.adjustLife(0, -1) }
        apart()
        repeat(3) { now += 90; state.adjustLife(0, -1) }
        assertEquals(34, state.game!!.player(0).life)

        state.undo()
        assertEquals(37, state.game!!.player(0).life)
        state.undo()
        assertEquals(40, state.game!!.player(0).life)
    }

    @Test
    fun `a run on one player does not swallow a change to another`() {
        val state = appState(FakeStorage()).apply { start() }
        now += 90
        state.adjustLife(0, -1)
        now += 90
        state.adjustLife(1, -1)

        state.undo()

        assertEquals(40, state.game!!.player(1).life, "seat one's change is its own step")
        assertEquals(39, state.game!!.player(0).life, "seat zero's is still there")
    }

    @Test
    fun `undo ends the run, so the next press is not folded into it`() {
        val state = appState(FakeStorage()).apply { start() }
        repeat(3) { now += 90; state.adjustLife(0, -1) }
        state.undo()
        assertEquals(40, state.game!!.player(0).life)

        now += 90
        state.adjustLife(0, -1)
        state.undo()

        assertEquals(40, state.game!!.player(0).life)
    }

    @Test
    fun `a change that changes nothing is not worth a step`() {
        val state = appState(FakeStorage()).apply { start() }
        // Already at zero, so this does nothing at all.
        state.adjustCounter(0, Counter.ENERGY, -1)
        assertFalse(state.canUndo)
    }

    @Test
    fun `the stack is bounded`() {
        val state = appState(FakeStorage()).apply { start(life = 200) }
        repeat(40) { apart(); state.adjustLife(0, -1) }

        repeat(40) { state.undo() }

        // Twenty steps are kept, so twenty of the forty can be taken back.
        assertEquals(180, state.game!!.player(0).life)
        assertFalse(state.canUndo)
    }

    @Test
    fun `undoing the knockout that ended a game takes the win back with it`() {
        val storage = FakeStorage()
        val state = appState(storage).apply { start(players = 2) }

        state.eliminate(1, LossReason.Conceded)
        assertTrue(state.game!!.isFinished)
        assertEquals(1, state.games.games.size, "a finished game is recorded")

        state.undo()

        assertFalse(state.game!!.isFinished)
        assertEquals(
            0,
            state.games.games.size,
            "and undoing it takes the record back, or the win stays counted",
        )
    }

    @Test
    fun `a game re-finished after an undo is recorded once, not twice`() {
        val state = appState(FakeStorage()).apply { start(players = 2) }

        state.eliminate(1, LossReason.Conceded)
        state.undo()
        state.eliminate(1, LossReason.Conceded)

        assertTrue(state.game!!.isFinished)
        assertEquals(1, state.games.games.size)
    }

    @Test
    fun `a restart can be taken back`() {
        val state = appState(FakeStorage()).apply { start() }
        state.adjustLife(0, -12)
        assertEquals(28, state.game!!.player(0).life)

        apart()
        state.restart()
        assertEquals(40, state.game!!.player(0).life)

        state.undo()
        assertEquals(28, state.game!!.player(0).life, "the game as it stood before the restart")
    }

    @Test
    fun `starting a game clears whatever was on the stack`() {
        val state = appState(FakeStorage()).apply { start() }
        state.adjustLife(0, -1)
        assertTrue(state.canUndo)

        state.start()

        assertFalse(state.canUndo, "the previous game is not somewhere to step back into")
    }

    // --- keeping the game ------------------------------------------------------------------

    @Test
    fun `a game in progress is picked up again by the next AppState`() {
        val storage = FakeStorage()
        appState(storage).apply {
            start()
            adjustLife(2, -9)
            adjustPoison(1, 3)
        }

        val resumed = appState(storage)

        assertEquals(Screen.Game, resumed.screen)
        assertEquals(31, resumed.game!!.player(2).life)
        assertEquals(3, resumed.game!!.player(1).poison)
    }

    @Test
    fun `leaving a game leaves nothing to pick up`() {
        val storage = FakeStorage()
        appState(storage).apply {
            start()
            adjustLife(0, -1)
            leaveGame()
        }

        val resumed = appState(storage)

        assertNull(resumed.game)
        assertEquals(Screen.Setup, resumed.screen)
    }

    @Test
    fun `a finished game picked up again does not count its result a second time`() {
        val storage = FakeStorage()
        appState(storage).apply {
            start(players = 2)
            eliminate(1, LossReason.Conceded)
        }
        val first = appState(storage)
        assertEquals(1, first.games.games.size)

        // Whatever is done next to a resumed finished game must not record it again.
        first.restore(1)
        first.eliminate(1, LossReason.Conceded)

        assertEquals(1, first.games.games.size)
    }

    @Test
    fun `a restart that is interrupted still records the game that follows it`() {
        val storage = FakeStorage()
        appState(storage).apply {
            start(players = 2)
            eliminate(1, LossReason.Conceded)
            restart()
        }

        // Clearing the flag after saving rather than before left the restarted game
        // marked as already recorded, and its result could then never be counted.
        val resumed = appState(storage)
        assertNotNull(resumed.game)
        assertFalse(resumed.game!!.isFinished)

        resumed.eliminate(1, LossReason.Conceded)

        assertEquals(2, resumed.games.games.size, "the first game, and now the second")
    }
}
