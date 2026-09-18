package uk.co.ncartmell.mtg.engine

import kotlin.random.Random

/**
 * The rules of a game, as pure transitions over [GameState].
 *
 * Every function returns a new state rather than mutating, so the UI can hold one
 * [GameState] and the engine never needs to know the UI exists. Elimination is applied
 * automatically after any change that could cause it — a caller cannot forget to check.
 */
object GameEngine {

    /** Builds the opening state. Seating order is the order of [seats]. */
    fun newGame(settings: GameSettings, seats: List<SeatSetup>): GameState {
        require(seats.size == settings.playerCount) {
            "Expected ${settings.playerCount} seats, got ${seats.size}"
        }
        require(seats.map { it.profileId }.filterNotNull().toSet().size ==
            seats.count { it.profileId != null }) {
            "The same profile cannot occupy two seats"
        }
        return GameState(
            settings = settings,
            players = seats.mapIndexed { index, seat ->
                PlayerState(
                    seat = index,
                    name = seat.name,
                    colour = seat.colour,
                    profileId = seat.profileId,
                    life = settings.startingLife,
                    commanderCount = seat.commanderCount,
                )
            },
        )
    }

    /**
     * Starts the same game again — same settings, same players, counters reset.
     *
     * The first-player roll is cleared, because who goes first should be decided again.
     */
    fun restart(state: GameState): GameState = GameState(
        settings = state.settings,
        players = state.players.map {
            it.copy(
                life = state.settings.startingLife,
                poison = 0,
                commanderDamage = emptyMap(),
                cannotLose = false,
                lostTo = null,
            )
        },
    )

    /** Adds [delta] to a player's life. Negative values are damage. */
    fun adjustLife(state: GameState, seat: Int, delta: Int): GameState =
        updatePlayer(state, seat) { it.copy(life = it.life + delta) }

    /** Adds [delta] poison counters. */
    fun adjustPoison(state: GameState, seat: Int, delta: Int): GameState {
        if (!state.settings.poisonEnabled) return state
        return updatePlayer(state, seat) {
            it.copy(poison = (it.poison + delta).coerceAtLeast(0))
        }
    }

    /**
     * Records commander damage dealt to [seat] by [from].
     *
     * Commander damage is also a loss of life, so this changes both. That is the rule
     * players most often get wrong when tracking by hand, which is most of the reason
     * this app exists.
     */
    fun adjustCommanderDamage(
        state: GameState,
        seat: Int,
        from: CommanderId,
        delta: Int,
    ): GameState {
        if (!state.settings.commanderDamageEnabled) return state
        require(from.seat != seat) { "A commander cannot deal commander damage to its own controller" }

        return updatePlayer(state, seat) { player ->
            val current = player.damageFrom(from)
            val updated = (current + delta).coerceAtLeast(0)
            // Only the applied portion changes life — clamping at zero must not gain life.
            val applied = updated - current
            player.copy(
                commanderDamage = player.commanderDamage + (from to updated),
                life = player.life - applied,
            )
        }
    }

    /** Sets how many commanders a player has. Damage from a removed commander is dropped. */
    fun setCommanderCount(state: GameState, seat: Int, count: Int): GameState {
        require(count in 1..2) { "A player has one or two commanders, got $count" }
        val next = state.copy(
            players = state.players.map { player ->
                if (player.seat != seat) {
                    // Other players may hold damage from a commander that no longer exists.
                    player.copy(
                        commanderDamage = player.commanderDamage.filterKeys {
                            it.seat != seat || it.index < count
                        },
                    )
                } else {
                    player.copy(commanderCount = count)
                }
            },
        )
        return applyEliminations(next)
    }

    /**
     * Marks a player as unable to lose the game, for effects such as Platinum Angel.
     *
     * Counters are not frozen: life still falls, poison still accumulates, and the moment
     * the flag is cleared every threshold is applied at once. That is what happens at a
     * table when the permanent granting it is destroyed, so it is what the engine does.
     */
    fun setCannotLose(state: GameState, seat: Int, value: Boolean): GameState {
        val next = state.copy(
            players = state.players.map {
                if (it.seat == seat) it.copy(cannotLose = value) else it
            },
        )
        return applyEliminations(next)
    }

    /** Removes a player for a reason the engine cannot detect itself. */
    fun eliminate(state: GameState, seat: Int, reason: LossReason): GameState {
        val next = state.copy(
            players = state.players.map {
                if (it.seat == seat && !it.isOut) it.copy(lostTo = reason) else it
            },
        )
        return resolveOutcome(next)
    }

    /** Brings a player back — for correcting a mistake, not for a game effect. */
    fun restore(state: GameState, seat: Int): GameState {
        val next = state.copy(
            players = state.players.map { if (it.seat == seat) it.copy(lostTo = null) else it },
            outcome = null,
        )
        return applyEliminations(next)
    }

    /**
     * Rolls a die for every player still in the game and picks the highest.
     *
     * Ties are re-rolled among the tied players only, which is what people do at a table.
     */
    fun rollForFirstPlayer(state: GameState, random: Random = Random, sides: Int = 20): GameState {
        require(sides > 1) { "A die needs more than one side" }
        var contenders = state.livePlayers.map { it.seat }
        if (contenders.isEmpty()) return state

        var results: Map<Int, Int>
        while (true) {
            results = contenders.associateWith { random.nextInt(1, sides + 1) }
            val best = results.values.max()
            val winners = results.filterValues { it == best }.keys
            if (winners.size == 1) {
                return state.copy(
                    startingSeat = winners.first(),
                    lastRoll = DiceRoll(results, winners.first()),
                )
            }
            contenders = winners.toList()
        }
    }

    // --- internals -------------------------------------------------------------------

    private fun updatePlayer(
        state: GameState,
        seat: Int,
        block: (PlayerState) -> PlayerState,
    ): GameState {
        val target = state.player(seat)
        // A player who is already out does not take further damage.
        if (target.isOut || state.isFinished) return state
        val next = state.copy(
            players = state.players.map { if (it.seat == seat) block(it) else it },
        )
        return applyEliminations(next)
    }

    /** Applies every loss condition the engine can detect from the counters. */
    private fun applyEliminations(state: GameState): GameState {
        val players = state.players.map { player ->
            if (player.isOut) return@map player
            val reason = detectLoss(player, state.settings)
            if (reason != null) player.copy(lostTo = reason) else player
        }
        return resolveOutcome(state.copy(players = players))
    }

    private fun detectLoss(player: PlayerState, settings: GameSettings): LossReason? {
        // Nothing the engine can detect applies while this is set. Being removed by hand
        // still does, so conceding and "something just killed me" remain available.
        if (player.cannotLose) return null
        if (player.life <= 0) return LossReason.LifeDepleted
        if (settings.poisonEnabled && player.poison >= settings.poisonThreshold) {
            return LossReason.Poison
        }
        if (settings.commanderDamageEnabled) {
            val lethal = player.commanderDamage.entries
                .firstOrNull { it.value >= settings.commanderDamageThreshold }
            if (lethal != null) return LossReason.CommanderDamage(lethal.key)
        }
        return null
    }

    /** A game ends when one player is left, or when nobody is. */
    private fun resolveOutcome(state: GameState): GameState {
        val alive = state.livePlayers
        return when {
            alive.size == 1 -> state.copy(outcome = GameOutcome.Winner(alive.single().seat))
            alive.isEmpty() -> state.copy(outcome = GameOutcome.Draw)
            else -> state.copy(outcome = null)
        }
    }
}

/** What the setup screen collects for one seat before a game starts. */
data class SeatSetup(
    val name: String,
    val colour: PlayerColour,
    val profileId: String? = null,
    val commanderCount: Int = 1,
)
