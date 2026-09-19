package uk.co.ncartmell.mtg.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.co.ncartmell.mtg.app.store.HistoryRepository
import uk.co.ncartmell.mtg.app.store.ProfileRepository
import uk.co.ncartmell.mtg.app.store.SetupMemory
import uk.co.ncartmell.mtg.app.store.SetupRepository
import uk.co.ncartmell.mtg.app.store.createStorage
import uk.co.ncartmell.mtg.app.store.nowMillis
import uk.co.ncartmell.mtg.engine.Counter
import uk.co.ncartmell.mtg.engine.DiceThrow
import uk.co.ncartmell.mtg.engine.PanelStyle
import uk.co.ncartmell.mtg.engine.PlanarFace
import uk.co.ncartmell.mtg.engine.GameHistory
import uk.co.ncartmell.mtg.engine.CommanderId
import uk.co.ncartmell.mtg.engine.GameEngine
import uk.co.ncartmell.mtg.engine.GameSettings
import uk.co.ncartmell.mtg.engine.GameState
import uk.co.ncartmell.mtg.engine.LossReason
import uk.co.ncartmell.mtg.engine.PlayerColour
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
    private val random: Random = Random,
    private val clock: () -> Long = ::nowMillis,
) {
    var screen by mutableStateOf(Screen.Setup)
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

    var game by mutableStateOf<GameState?>(null)
        private set

    /** True once the finished game has been written to the profiles, so it counts once. */
    private var resultRecorded = false

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

    fun setPanelStyle(id: String, style: PanelStyle) = mutateBook { book ->
        book.copy(profiles = book.profiles.map { if (it.id == id) it.copy(style = style) else it })
    }

    fun setProfileColour(id: String, colour: PlayerColour) = mutateBook { it.setColour(id, colour) }

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
        game = GameEngine.newGame(settings, seats, startedAt = clock())
        resultRecorded = false
        lastThrow = null
        screen = Screen.Game
    }

    fun restart() {
        updateGame { GameEngine.restart(it, startedAt = clock()) }
        resultRecorded = false
        lastThrow = null
        lastPlanarFace = null
    }

    fun rollForFirstPlayer() =
        updateGame { GameEngine.rollForFirstPlayer(it, random, at = clock()) }

    fun nextTurn() = updateGame { GameEngine.nextTurn(it, at = clock()) }

    fun adjustCounter(seat: Int, counter: Counter, delta: Int) =
        updateGame { GameEngine.adjustCounter(it, seat, counter, delta) }

    fun setMonarch(seat: Int?) = updateGame { GameEngine.setMonarch(it, seat) }

    fun setInitiative(seat: Int?) = updateGame { GameEngine.setInitiative(it, seat) }

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
    }
}
