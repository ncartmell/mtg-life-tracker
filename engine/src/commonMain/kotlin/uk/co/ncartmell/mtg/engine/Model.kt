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

/**
 * How a table is divided up, and therefore what winning means.
 *
 * Every format here is some arrangement of teams: free-for-all is the degenerate case
 * where each player is a team of one, and the rest differ in how the teams are drawn and
 * in what it takes to knock one out. Keeping it to that one idea means the engine has a
 * single win rule rather than five.
 */
@Serializable
enum class Format(val label: String, val players: IntRange) {
    FREE_FOR_ALL("Free-for-all", 2..6),

    /** Five in a ring; your team of one beats the two seats you are not sitting next to. */
    STAR("Star", 5..5),

    /** Four in two pairs, each pair sharing one life total. */
    TWO_HEADED_GIANT("Two-Headed Giant", 4..4),

    /** Seat one against everybody else. */
    ARCHENEMY("Archenemy", 3..6),

    /** Six in two teams of three; a team falls when its emperor does. */
    EMPEROR("Emperor", 6..6),
    ;

    val isTeamGame: Boolean get() = this == TWO_HEADED_GIANT || this == ARCHENEMY || this == EMPEROR
    val sharesLife: Boolean get() = this == TWO_HEADED_GIANT

    fun allows(playerCount: Int): Boolean = playerCount in players
}

/** How a game ended. */
@Serializable
sealed interface GameOutcome {
    @Serializable
    data class Winner(val seat: Int) : GameOutcome

    /** A team won. Every seat listed shares the win. */
    @Serializable
    data class TeamWin(val seats: List<Int>) : GameOutcome

    /** Everybody left at the same time — rare, but legal. */
    @Serializable
    data object Draw : GameOutcome
    ;

    /** Every seat that won, however the win was shaped. */
    val winningSeats: List<Int>
        get() = when (this) {
            is Winner -> listOf(seat)
            is TeamWin -> seats
            Draw -> emptyList()
        }
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
    val format: Format = Format.FREE_FOR_ALL,
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
        require(format.allows(playerCount)) {
            "${format.label} takes ${format.players} players, got $playerCount"
        }
    }

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 6

        /** Star is played by exactly five. */
        const val STAR_PLAYERS = 5

        /** Starting life totals offered in setup; any value is accepted. */
        val COMMON_LIFE_TOTALS = listOf(20, 25, 30, 40)

        /** What a format expects before anybody changes it. */
        fun defaultsFor(format: Format): GameSettings = when (format) {
            Format.TWO_HEADED_GIANT -> GameSettings(
                playerCount = 4,
                startingLife = 30,
                format = format,
                // A shared life total takes twice the poison to kill.
                poisonThreshold = 15,
            )
            Format.STAR -> GameSettings(playerCount = 5, startingLife = 40, format = format)
            Format.EMPEROR -> GameSettings(playerCount = 6, startingLife = 20, format = format)
            Format.ARCHENEMY -> GameSettings(playerCount = 4, startingLife = 20, format = format)
            Format.FREE_FOR_ALL -> GameSettings(playerCount = 4, startingLife = 40)
        }

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
     * Which team a seat belongs to. In a free-for-all that is just the seat itself.
     *
     * Teams are drawn from seating, so who sits where is the whole setup: pairs sit
     * together in Two-Headed Giant, the archenemy takes seat one, and an emperor sits
     * between their two generals.
     */
    fun teamOf(seat: Int): Int = when (settings.format) {
        Format.TWO_HEADED_GIANT -> seat / 2
        Format.ARCHENEMY -> if (seat == 0) 0 else 1
        Format.EMPEROR -> seat / 3
        else -> seat
    }

    fun seatsInTeam(team: Int): List<Int> = players.map { it.seat }.filter { teamOf(it) == team }

    val teams: List<Int> get() = players.map { teamOf(it.seat) }.distinct().sorted()

    /** The seat whose loss takes a whole team with it, where a format has one. */
    fun emperorSeat(team: Int): Int? =
        if (settings.format != Format.EMPEROR) null else seatsInTeam(team).getOrNull(1)

    /**
     * A team is out when it can no longer win.
     *
     * Emperor is the exception worth naming: a team falls the moment its emperor does,
     * however healthy its generals still are.
     */
    fun teamIsOut(team: Int): Boolean {
        val seats = seatsInTeam(team)
        if (seats.isEmpty()) return true
        emperorSeat(team)?.let { return player(it).isOut }
        return seats.all { player(it).isOut }
    }

    /** Teammates of a seat, not counting the seat itself. */
    fun alliesOf(seat: Int): List<Int> = seatsInTeam(teamOf(seat)).filter { it != seat }

    /**
     * The two seats opposite this one, in Star. Empty in every other format.
     *
     * Seats run round the table in order, so a seat is adjacent to the seats either side
     * of it and opposed to the other two.
     */
    fun starOpponents(seat: Int): List<Int> =
        if (settings.format != Format.STAR) emptyList()
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
