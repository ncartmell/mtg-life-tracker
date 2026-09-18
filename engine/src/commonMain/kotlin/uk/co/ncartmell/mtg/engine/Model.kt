package uk.co.ncartmell.mtg.engine

import kotlinx.serialization.Serializable

/** Colour a player picks for their profile; used for their panel on the board. */
@Serializable
enum class PlayerColour(val label: String, val argb: Long) {
    WHITE("White", 0xFFEFE6C8),
    BLUE("Blue", 0xFF2F6FB5),
    BLACK("Black", 0xFF3A3742),
    RED("Red", 0xFFC0392B),
    GREEN("Green", 0xFF2E7D4F),
    GOLD("Gold", 0xFFB8912F),
    PURPLE("Purple", 0xFF6B4A8F),
    TEAL("Teal", 0xFF2A7E7B),
    ORANGE("Orange", 0xFFC2662B),
    PINK("Pink", 0xFFB5548A),
}

/**
 * A saved player. Profiles persist between games and carry the win/loss record.
 *
 * Seats in a game may also be filled by guests, which have no profile and whose results
 * are not recorded.
 */
@Serializable
data class PlayerProfile(
    val id: String,
    val name: String,
    val colour: PlayerColour,
    val wins: Int = 0,
    val losses: Int = 0,
) {
    val gamesPlayed: Int get() = wins + losses

    /** Win rate in the range 0.0..1.0, or null when the player has not finished a game. */
    val winRate: Double? get() = if (gamesPlayed == 0) null else wins.toDouble() / gamesPlayed
}

/** Identifies one of a player's commanders — a seat may have two. */
@Serializable
data class CommanderId(val seat: Int, val index: Int) {
    init {
        require(index in 0..1) { "A player may have at most two commanders, got index $index" }
    }
}

/** Why a player is out of the game. */
@Serializable
sealed interface LossReason {
    @Serializable
    data object LifeDepleted : LossReason

    @Serializable
    data object Poison : LossReason

    /** Twenty-one or more damage from a single commander. */
    @Serializable
    data class CommanderDamage(val from: CommanderId) : LossReason

    /** Drew from an empty library. */
    @Serializable
    data object Milled : LossReason

    /** An effect that removes a player outright, rather than by reducing a counter. */
    @Serializable
    data object Effect : LossReason

    @Serializable
    data object Conceded : LossReason
}

/** How a game ended. */
@Serializable
sealed interface GameOutcome {
    @Serializable
    data class Winner(val seat: Int) : GameOutcome

    /** Everybody left at the same time — rare, but legal. */
    @Serializable
    data object Draw : GameOutcome
}

/**
 * The rules a game is played under.
 *
 * Thresholds are configurable because the defaults differ by format, and because house
 * rules exist.
 */
@Serializable
data class GameSettings(
    val playerCount: Int,
    val startingLife: Int,
    val commanderDamageEnabled: Boolean = true,
    val poisonEnabled: Boolean = true,
    val poisonThreshold: Int = 10,
    val commanderDamageThreshold: Int = 21,
) {
    init {
        require(playerCount in MIN_PLAYERS..MAX_PLAYERS) {
            "Player count must be between $MIN_PLAYERS and $MAX_PLAYERS, got $playerCount"
        }
        require(startingLife > 0) { "Starting life must be positive, got $startingLife" }
        require(poisonThreshold > 0) { "Poison threshold must be positive" }
        require(commanderDamageThreshold > 0) { "Commander damage threshold must be positive" }
    }

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 6

        /** Starting life totals offered in setup; any value is accepted. */
        val COMMON_LIFE_TOTALS = listOf(20, 25, 30, 40)

        fun commander(playerCount: Int) = GameSettings(
            playerCount = playerCount,
            startingLife = 40,
            commanderDamageEnabled = true,
        )

        fun standard(playerCount: Int) = GameSettings(
            playerCount = playerCount,
            startingLife = 20,
            commanderDamageEnabled = false,
        )
    }
}

/** One seat at the table for the duration of a single game. */
@Serializable
data class PlayerState(
    val seat: Int,
    val name: String,
    val colour: PlayerColour,
    /** Null for a guest — results are not recorded against a profile. */
    val profileId: String? = null,
    val life: Int,
    val poison: Int = 0,
    val commanderCount: Int = 1,
    /** Damage received, keyed by the commander that dealt it. */
    val commanderDamage: Map<CommanderId, Int> = emptyMap(),
    val lostTo: LossReason? = null,
) {
    init {
        require(commanderCount in 1..2) { "A player has one or two commanders, got $commanderCount" }
    }

    val isOut: Boolean get() = lostTo != null

    /** Highest damage taken from any single commander — the number that matters for the rule. */
    val highestCommanderDamage: Int get() = commanderDamage.values.maxOrNull() ?: 0

    fun damageFrom(commander: CommanderId): Int = commanderDamage[commander] ?: 0
}

/** A game in progress, or finished. */
@Serializable
data class GameState(
    val settings: GameSettings,
    val players: List<PlayerState>,
    /** Seat chosen to take the first turn, once decided. */
    val startingSeat: Int? = null,
    val lastRoll: DiceRoll? = null,
    val outcome: GameOutcome? = null,
) {
    val isFinished: Boolean get() = outcome != null

    val livePlayers: List<PlayerState> get() = players.filterNot { it.isOut }

    fun player(seat: Int): PlayerState =
        players.firstOrNull { it.seat == seat }
            ?: error("No player in seat $seat")

    /** Every commander at the table other than this seat's own. */
    fun opposingCommanders(seat: Int): List<CommanderId> =
        players.filter { it.seat != seat }
            .flatMap { other -> (0 until other.commanderCount).map { CommanderId(other.seat, it) } }
}

/** The roll used to decide who goes first. Kept so the UI can show it if it wants to. */
@Serializable
data class DiceRoll(
    val results: Map<Int, Int>,
    val winningSeat: Int,
) {
    val highest: Int get() = results.values.maxOrNull() ?: 0
}
