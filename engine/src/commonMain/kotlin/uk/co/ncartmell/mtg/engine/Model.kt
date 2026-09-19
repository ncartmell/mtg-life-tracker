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
    /**
     * Star: five players sit in a ring and each has two opponents — the two they are not
     * sitting next to. You win when both of yours are out, however many players are left.
     */
    val starFormat: Boolean = false,
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
        require(!starFormat || playerCount == STAR_PLAYERS) {
            "Star is a $STAR_PLAYERS-player format, got $playerCount"
        }
    }

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 6

        /** Star is played by exactly five. */
        const val STAR_PLAYERS = 5

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
    /**
     * Set while an effect says this player cannot lose the game — Platinum Angel and the
     * like. Counters keep climbing underneath it; they are simply not acted on.
     */
    val cannotLose: Boolean = false,
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
    /** Whose turn it is, once somebody has started. */
    val turnSeat: Int? = null,
    /** How many turns have been taken in total, counting the first. */
    val turnCount: Int = 0,
    val outcome: GameOutcome? = null,
) {
    val isFinished: Boolean get() = outcome != null

    val livePlayers: List<PlayerState> get() = players.filterNot { it.isOut }

    fun player(seat: Int): PlayerState =
        players.firstOrNull { it.seat == seat }
            ?: error("No player in seat $seat")

    /**
     * The two seats opposite this one, in Star. Empty in every other format.
     *
     * Seats run round the table in order, so a seat is adjacent to the seats either side
     * of it and opposed to the other two.
     */
    fun starOpponents(seat: Int): List<Int> =
        if (!settings.starFormat) emptyList()
        else listOf((seat + 2) % players.size, (seat + 3) % players.size)

    /** Every commander at the table other than this seat's own. */
    fun opposingCommanders(seat: Int): List<CommanderId> =
        players.filter { it.seat != seat }
            .flatMap { other -> (0 until other.commanderCount).map { CommanderId(other.seat, it) } }
}

/**
 * The roll used to decide who goes first, kept in full.
 *
 * Every round is retained, not just the one that settled it. Keeping only the last round
 * meant that after a tie-break the players who were not in it had no number at all, which
 * looked like the app had simply forgotten them.
 */
@Serializable
data class DiceRoll(
    val rounds: List<Map<Int, Int>>,
    val winningSeat: Int,
) {
    init {
        require(rounds.isNotEmpty()) { "A roll has at least one round" }
    }

    /** What everybody rolled to begin with — the number that belongs on a panel. */
    val openingRoll: Map<Int, Int> get() = rounds.first()

    /** Rounds rolled to break a tie, if it came to that. */
    val tieBreaks: List<Map<Int, Int>> get() = rounds.drop(1)

    val wasTied: Boolean get() = rounds.size > 1

    /** The number that actually won, which is from the last round when there was a tie. */
    val winningRoll: Int get() = rounds.last()[winningSeat] ?: 0
}

/** A roll of dice made for its own sake, rather than to decide who starts. */
@Serializable
data class DiceThrow(val sides: Int, val values: List<Int>) {
    val total: Int get() = values.sum()

    /** A single coin is modelled as a two-sided die, so heads is 2 and tails is 1. */
    val isCoin: Boolean get() = sides == 2
}
