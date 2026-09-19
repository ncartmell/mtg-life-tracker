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
    /** Shown in place of the usual reason when this player is knocked out. */
    val defeatMessage: String? = null,
    val style: PanelStyle = PanelStyle.SOLID,
) {
    val gamesPlayed: Int get() = wins + losses

    /** Win rate in the range 0.0..1.0, or null when the player has not finished a game. */
    val winRate: Double? get() = if (gamesPlayed == 0) null else wins.toDouble() / gamesPlayed
}

/**
 * Counters a player accumulates that do not, on their own, end their game.
 *
 * Poison is deliberately not here: it is a loss condition with a threshold, and the
 * engine has to act on it. These are numbers a player needs to remember, which is a
 * different job — the engine keeps them and otherwise leaves them alone.
 */
@Serializable
enum class Counter(val label: String, val short: String) {
    ENERGY("Energy", "E"),
    EXPERIENCE("Experience", "XP"),
    STORM("Storm", "Storm"),
    COMMANDER_TAX("Commander tax", "Tax"),
}

/** How a player's panel is painted, so two people on similar colours still differ. */
@Serializable
enum class PanelStyle(val label: String) {
    SOLID("Solid"),
    FADE("Fade"),
    CORNER("Corner"),
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
    /** Planechase layers over any format: a planar die, and whatever plane is in play. */
    val planechase: Boolean = false,
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
    /** Everything else worth remembering. Absent means zero. */
    val counters: Map<Counter, Int> = emptyMap(),
    /** Copied from the profile when the game starts, so it survives a profile edit. */
    val defeatMessage: String? = null,
    val style: PanelStyle = PanelStyle.SOLID,
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

    operator fun get(counter: Counter): Int = counters[counter] ?: 0

    /** Only the counters in play, so a panel stays empty until one is actually used. */
    val activeCounters: List<Pair<Counter, Int>>
        get() = Counter.entries.mapNotNull { c ->
            (counters[c] ?: 0).takeIf { it != 0 }?.let { c to it }
        }
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
    /**
     * Who holds the monarchy and the initiative. Both are single-holder and both move
     * about, so the game holds them rather than any player.
     */
    val monarchSeat: Int? = null,
    val initiativeSeat: Int? = null,
    /**
     * When the game and the current turn began, in epoch millis.
     *
     * Wall-clock time is handed in rather than read: an engine that can tell the time is
     * one whose tests depend on when they run.
     */
    val startedAt: Long? = null,
    val turnStartedAt: Long? = null,
    /** Planechase: whatever plane is in play, named by whoever is running the game. */
    val currentPlane: String? = null,
    val planeswalks: Int = 0,
    /**
     * The seats in the order they sit round the table, clockwise.
     *
     * Turns pass in this order rather than in seat order, because the two are not the
     * same: on a two-by-two board, seat two sits opposite seat one, not next to it.
     * Empty falls back to seat order, which is right for a single row.
     */
    val seatingOrder: List<Int> = emptyList(),
    val outcome: GameOutcome? = null,
) {
    val isFinished: Boolean get() = outcome != null

    val livePlayers: List<PlayerState> get() = players.filterNot { it.isOut }

    /** Seating, falling back to seat order when nobody has supplied any. */
    val seating: List<Int>
        get() = seatingOrder.takeIf { it.isNotEmpty() } ?: players.map { it.seat }.sorted()

    /** The next seat round the table after [seat] that is still in the game. */
    fun nextLiveSeatAfter(seat: Int?): Int? {
        val live = seating.filter { !player(it).isOut }
        if (live.isEmpty()) return null
        val from = seat?.let { seating.indexOf(it) } ?: -1
        if (from < 0) return live.first()
        // Walk the ring from just after the current seat, so the first live seat found is
        // the next one clockwise however many players in a row are out.
        for (step in 1..seating.size) {
            val candidate = seating[(from + step) % seating.size]
            if (!player(candidate).isOut) return candidate
        }
        return live.first()
    }

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

/**
 * A face of the planar die: four blanks, one chaos, one planeswalk.
 *
 * Modelled as its own roll rather than as a d6 with a lookup, because the faces are what
 * a player acts on — nobody cares that blank happens to be four of the six.
 */
@Serializable
enum class PlanarFace(val label: String) {
    BLANK("No effect"),
    CHAOS("Chaos"),
    PLANESWALK("Planeswalk"),
}

/** A roll of dice made for its own sake, rather than to decide who starts. */
@Serializable
data class DiceThrow(val sides: Int, val values: List<Int>) {
    val total: Int get() = values.sum()

    /** A single coin is modelled as a two-sided die, so heads is 2 and tails is 1. */
    val isCoin: Boolean get() = sides == 2
}
