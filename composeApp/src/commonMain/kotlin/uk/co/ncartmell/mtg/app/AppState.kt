package uk.co.ncartmell.mtg.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.co.ncartmell.mtg.app.store.ProfileRepository
import uk.co.ncartmell.mtg.app.store.createStorage
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
    private val random: Random = Random,
) {
    var screen by mutableStateOf(Screen.Setup)
        private set

    var book by mutableStateOf(profiles.load())
        private set

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
        game = GameEngine.newGame(settings, seats)
        resultRecorded = false
        screen = Screen.Game
    }

    fun restart() = updateGame { GameEngine.restart(it) }.also { resultRecorded = false }

    fun rollForFirstPlayer() = updateGame { GameEngine.rollForFirstPlayer(it, random) }

    fun adjustLife(seat: Int, delta: Int) = updateGame { GameEngine.adjustLife(it, seat, delta) }

    fun adjustPoison(seat: Int, delta: Int) = updateGame { GameEngine.adjustPoison(it, seat, delta) }

    fun adjustCommanderDamage(seat: Int, from: CommanderId, delta: Int) =
        updateGame { GameEngine.adjustCommanderDamage(it, seat, from, delta) }

    fun setCommanderCount(seat: Int, count: Int) =
        updateGame { GameEngine.setCommanderCount(it, seat, count) }

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
        }
    }
}
