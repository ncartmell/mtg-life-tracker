package uk.co.ncartmell.mtg.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.co.ncartmell.mtg.app.store.HistoryRepository
import uk.co.ncartmell.mtg.app.ui.boardLayout
import uk.co.ncartmell.mtg.app.ui.clockwiseOrder
import uk.co.ncartmell.mtg.app.store.ProfileRepository
import uk.co.ncartmell.mtg.app.store.SetupMemory
import uk.co.ncartmell.mtg.app.store.SetupRepository
import uk.co.ncartmell.mtg.app.store.GameRepository
import uk.co.ncartmell.mtg.app.store.createStorage
import uk.co.ncartmell.mtg.app.store.nowMillis
import uk.co.ncartmell.mtg.engine.Counter
import uk.co.ncartmell.mtg.engine.DiceThrow
import uk.co.ncartmell.mtg.engine.PanelPaint
import uk.co.ncartmell.mtg.engine.PlanarFace
import uk.co.ncartmell.mtg.engine.GameHistory
import uk.co.ncartmell.mtg.engine.CommanderId
import uk.co.ncartmell.mtg.engine.GameEngine
import uk.co.ncartmell.mtg.engine.GameSettings
import uk.co.ncartmell.mtg.engine.GameState
import uk.co.ncartmell.mtg.engine.LossReason
import uk.co.ncartmell.mtg.engine.PlayerColour
import uk.co.ncartmell.mtg.engine.SavedGame
import uk.co.ncartmell.mtg.engine.TimeOfDay
import uk.co.ncartmell.mtg.engine.UndercityRoom
import uk.co.ncartmell.mtg.engine.ProfileBook
import uk.co.ncartmell.mtg.engine.SeatSetup
import kotlin.random.Random

enum class Screen { Setup, Game, Leaderboard }

/**
 * Holds everything the UI needs and forwards every rule decision to the engine.
 *
 * There is deliberately no logic here beyond wiring: if a behaviour is worth testing it
 * belongs in `:engine`, which has no Compose dependency and runs its tests on any JDK.
 */
class AppState(
    private val profiles: ProfileRepository = ProfileRepository(createStorage()),
    private val history: HistoryRepository = HistoryRepository(createStorage()),
    private val setups: SetupRepository = SetupRepository(createStorage()),
    private val inProgress: GameRepository = GameRepository(createStorage()),
    private val random: Random = Random,
    private val clock: () -> Long = ::nowMillis,
) {
    /** Whatever was on the table when the app last stopped. Read once, before anything. */
    private val resumed = inProgress.load()

    var screen by mutableStateOf(if (resumed == null) Screen.Setup else Screen.Game)
        private set

    var book by mutableStateOf(profiles.load())
        private set

    var games by mutableStateOf(history.load())
        private set

    /** The last dice thrown for their own sake, so the board can show them. */
    var lastThrow by mutableStateOf<DiceThrow?>(null)
        private set

    /** The last face of the planar die, kept so the board can show it. */
    var lastPlanarFace by mutableStateOf<PlanarFace?>(null)
        private set

    /** How the last game was set up, offered again as the starting point for the next. */
    var lastSetup by mutableStateOf(setups.load())
        private set

    fun rememberSetup(memory: SetupMemory) {
        lastSetup = memory
        setups.save(memory)
    }

    var game by mutableStateOf(resumed?.game)
        private set

    /** True once the finished game has been written to the profiles, so it counts once. */
    private var resultRecorded = resumed?.resultRecorded ?: false

    // --- navigation ------------------------------------------------------------------

    fun show(screen: Screen) {
        this.screen = screen
    }

    // --- profiles --------------------------------------------------------------------

    fun addProfile(name: String, colour: PlayerColour) = mutateBook {
        it.add(name, colour, id = newProfileId())
    }

    fun setDefeatMessage(id: String, message: String) = mutateBook { book ->
        book.copy(
            profiles = book.profiles.map {
                if (it.id == id) it.copy(defeatMessage = message.trim().ifEmpty { null }) else it
            },
        )
    }

    /**
     * Saves a painted panel against a profile, so a regular is the same colour next week.
     *
     * A seat filled by a guest has nowhere to save to, which is why the setup screen keeps
     * the paint per seat as well and only writes through when there is a profile behind it.
     */
    fun setProfilePaint(id: String, paint: PanelPaint) = mutateBook { book ->
        book.copy(profiles = book.profiles.map { if (it.id == id) it.copy(paint = paint) else it })
    }

    fun renameProfile(id: String, name: String) = mutateBook { it.rename(id, name) }

    fun removeProfile(id: String) = mutateBook { it.remove(id) }

    /** Swallows the duplicate-name and empty-name errors so the UI can stay simple. */
    private fun mutateBook(block: (ProfileBook) -> ProfileBook) {
        runCatching { block(book) }.onSuccess {
            book = it
            profiles.save(it)
        }
    }

    private fun newProfileId(): String =
        "p-" + random.nextLong().toString(16).removePrefix("-") + "-" + book.profiles.size

    // --- game ------------------------------------------------------------------------

    fun startGame(settings: GameSettings, seats: List<SeatSetup>) {
        game = GameEngine.newGame(
            settings = settings,
            seats = seats,
            startedAt = clock(),
            // Turns follow the board, not the seat numbers. The layout is the thing that
            // decides who is sitting next to whom, so it is the thing asked.
            seatingOrder = clockwiseOrder(boardLayout(settings.playerCount, settings.format)),
        )
        resultRecorded = false
        lastThrow = null
        screen = Screen.Game
        persist()
    }

    fun restart() {
        // Cleared before the game is replaced, not after: updateGame is what writes the
        // game to storage, and it writes this flag alongside it. Setting it afterwards
        // saved the new game still marked as already recorded, and a restart that was
        // interrupted then came back as a game whose result could never be counted.
        resultRecorded = false
        lastThrow = null
        lastPlanarFace = null
        updateGame { GameEngine.restart(it, startedAt = clock()) }
    }

    fun rollForFirstPlayer() =
        updateGame { GameEngine.rollForFirstPlayer(it, random, at = clock()) }

    fun nextTurn() = updateGame { GameEngine.nextTurn(it, at = clock()) }

    fun adjustCounter(seat: Int, counter: Counter, delta: Int) =
        updateGame { GameEngine.adjustCounter(it, seat, counter, delta) }

    fun setMonarch(seat: Int?) = updateGame { GameEngine.setMonarch(it, seat) }

    fun setInitiative(seat: Int?) = updateGame { GameEngine.setInitiative(it, seat) }

    fun setCitysBlessing(seat: Int, value: Boolean) =
        updateGame { GameEngine.setCitysBlessing(it, seat, value) }

    fun setTimeOfDay(value: TimeOfDay) = updateGame { GameEngine.setTimeOfDay(it, value) }

    /** Where this seat could venture next; empty when there is no game. */
    fun ventureOptions(seat: Int): List<UndercityRoom> =
        game?.let { GameEngine.ventureOptions(it, seat) } ?: emptyList()

    fun ventureTo(seat: Int, room: UndercityRoom) =
        updateGame { GameEngine.ventureTo(it, seat, room) }

    fun leaveUndercity(seat: Int) = updateGame { GameEngine.leaveUndercity(it, seat) }

    fun rollPlanarDie() {
        lastPlanarFace = GameEngine.rollPlanarDie(random)
    }

    fun planeswalkTo(plane: String) = updateGame { GameEngine.planeswalkTo(it, plane) }

    fun rollDice(sides: Int, count: Int = 1) {
        lastThrow = GameEngine.rollDice(sides, count, random)
    }

    fun clearThrow() {
        lastThrow = null
        lastPlanarFace = null
    }

    fun adjustLife(seat: Int, delta: Int) = updateGame { GameEngine.adjustLife(it, seat, delta) }

    fun adjustPoison(seat: Int, delta: Int) = updateGame { GameEngine.adjustPoison(it, seat, delta) }

    fun adjustCommanderDamage(seat: Int, from: CommanderId, delta: Int) =
        updateGame { GameEngine.adjustCommanderDamage(it, seat, from, delta) }

    fun adjustCommanderTax(seat: Int, index: Int, delta: Int) =
        updateGame { GameEngine.adjustCommanderTax(it, seat, index, delta) }

    fun setCommanderCount(seat: Int, count: Int) =
        updateGame { GameEngine.setCommanderCount(it, seat, count) }

    fun setCannotLose(seat: Int, value: Boolean) =
        updateGame { GameEngine.setCannotLose(it, seat, value) }

    fun eliminate(seat: Int, reason: LossReason) =
        updateGame { GameEngine.eliminate(it, seat, reason) }

    fun restore(seat: Int) = updateGame { GameEngine.restore(it, seat) }

    fun leaveGame() {
        game = null
        screen = Screen.Setup
        persist()
    }

    private fun updateGame(block: (GameState) -> GameState) {
        val current = game ?: return
        val next = block(current)
        game = next
        // Record the result the moment a game finishes, exactly once.
        if (next.isFinished && !resultRecorded) {
            resultRecorded = true
            book = book.recordResult(next).also(profiles::save)
            games = games.record(next, clock()).also(history::save)
        }
        persist()
    }

    /** Called wherever [game] changes, which is the only place it can become stale. */
    private fun persist() {
        game?.let { inProgress.save(SavedGame(it, resultRecorded)) } ?: inProgress.clear()
    }
}
