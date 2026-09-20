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
 * Rule decisions belong in `:engine`, which has no Compose dependency and runs its tests
 * on any JDK. What is left here is wiring, and the two things wiring alone gets wrong:
 * recording a finished game exactly once, and stepping back out of a mistake.
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

    /**
     * One step back, holding everything a single change can touch.
     *
     * Not just the game: finishing one writes to the leaderboard and the history as well,
     * so undoing the knockout that ended it has to take those back too, or the win stays
     * counted against somebody who did not win.
     */
    private data class Step(
        val game: GameState,
        val book: ProfileBook,
        val games: GameHistory,
        val resultRecorded: Boolean,
    )

    /**
     * Deliberately not saved with the game. The game itself is what has to survive being
     * killed; how the table arrived at it does not, and writing twenty copies of it on
     * every press of a held minus would make the one genuinely hot write in the app
     * twenty times the size.
     */
    private var past by mutableStateOf<List<Step>>(emptyList())

    val canUndo: Boolean get() = past.isNotEmpty()

    /** What the last change was, and when, so a held run folds into one step. */
    private var lastKind: String? = null
    private var lastAt = 0L

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
        past = emptyList()
        lastKind = null
        persist()
    }

    /**
     * Steps back to before the last change, leaderboard and history included.
     *
     * A mis-tap is the ordinary way to lose work here — the glyphs repeat while held, so
     * overshooting is easy, and tapping back the other way is only tolerable for life.
     */
    fun undo() {
        val step = past.lastOrNull() ?: return
        // Whatever run was in progress has been taken back, so the next change starts a
        // step of its own rather than folding into the one just undone.
        lastKind = null
        past = past.dropLast(1)
        game = step.game
        resultRecorded = step.resultRecorded
        // Only written back when they actually moved, so an ordinary undo does not
        // rewrite the profile list on every press.
        if (step.book != book) book = step.book.also(profiles::save)
        if (step.games != games) games = step.games.also(history::save)
        persist()
    }

    fun restart() {
        val current = game ?: return
        // Snapshot before anything moves, so what is remembered is the game as it stood
        // and the flag as it stood with it. Restarting does not go through updateGame:
        // a restarted game is never finished, so there is no result to record.
        remember(current)
        lastKind = null
        resultRecorded = false
        lastThrow = null
        lastPlanarFace = null
        game = GameEngine.restart(current, startedAt = clock())
        persist()
    }

    fun rollForFirstPlayer() =
        updateGame { GameEngine.rollForFirstPlayer(it, random, at = clock()) }

    fun nextTurn() = updateGame { GameEngine.nextTurn(it, at = clock()) }

    fun adjustCounter(seat: Int, counter: Counter, delta: Int) =
        updateGame("counter:$seat:$counter") { GameEngine.adjustCounter(it, seat, counter, delta) }

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

    fun adjustLife(seat: Int, delta: Int) =
        updateGame("life:$seat") { GameEngine.adjustLife(it, seat, delta) }

    fun adjustPoison(seat: Int, delta: Int) =
        updateGame("poison:$seat") { GameEngine.adjustPoison(it, seat, delta) }

    fun adjustCommanderDamage(seat: Int, from: CommanderId, delta: Int) =
        updateGame("cmdr:$seat:$from") { GameEngine.adjustCommanderDamage(it, seat, from, delta) }

    fun adjustCommanderTax(seat: Int, index: Int, delta: Int) =
        updateGame("tax:$seat:$index") { GameEngine.adjustCommanderTax(it, seat, index, delta) }

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
        past = emptyList()
        lastKind = null
        persist()
    }

    /**
     * [kind] names what is being changed, for the changes that repeat while held.
     *
     * A hold is one gesture and should be one step back. Taking a player from forty to
     * twenty-six in a single press is the ordinary way to overshoot, and undoing that a
     * point at a time is no better than pressing the other glyph fourteen times. Changes
     * that cannot repeat pass nothing, and always get a step of their own.
     */
    private fun updateGame(kind: String? = null, block: (GameState) -> GameState) {
        val current = game ?: return
        val next = block(current)
        // Nothing moved, so there is nothing to step back to. Without this, minus on a
        // counter already at zero would fill the stack with copies of the same game and
        // undo would appear to do nothing several times running.
        if (next == current) return
        val at = clock()
        val continuing = kind != null && kind == lastKind && at - lastAt <= COALESCE_MS
        if (!continuing) remember(current)
        lastKind = kind
        lastAt = at
        game = next
        // Record the result the moment a game finishes, exactly once.
        if (next.isFinished && !resultRecorded) {
            resultRecorded = true
            book = book.recordResult(next).also(profiles::save)
            games = games.record(next, clock()).also(history::save)
        }
        persist()
    }

    private fun remember(current: GameState) {
        // Bounded, because an undo stack that grows for the length of a game is a memory
        // leak with a friendly name. Nobody steps back twenty changes after a mis-tap.
        past = (past + Step(current, book, games, resultRecorded)).takeLast(UNDO_DEPTH)
    }

    /** Called wherever [game] changes, which is the only place it can become stale. */
    private fun persist() {
        game?.let { inProgress.save(SavedGame(it, resultRecorded)) } ?: inProgress.clear()
    }

    private companion object {
        const val UNDO_DEPTH = 20

        /** Comfortably longer than the gap between repeats of a held glyph. */
        const val COALESCE_MS = 700L
    }
}
