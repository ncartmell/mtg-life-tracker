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

    /**
     * Brings a player back — for correcting a mistake, not for a game effect.
     *
     * Whatever was lethal is lifted just clear of its threshold. Clearing [lostTo] alone
     * does nothing: the counters that removed the player are still over the line, so the
     * very next check removes them again. A player restored from zero life comes back on
     * one, which is the smallest claim this can make about what their total should be.
     */
    fun restore(state: GameState, seat: Int): GameState {
        val settings = state.settings
        val next = state.copy(
            players = state.players.map { player ->
                if (player.seat != seat) {
                    player
                } else {
                    player.copy(
                        lostTo = null,
                        life = player.life.coerceAtLeast(1),
                        poison = if (settings.poisonEnabled) {
                            player.poison.coerceAtMost(settings.poisonThreshold - 1)
                        } else {
                            player.poison
                        },
                        commanderDamage = if (settings.commanderDamageEnabled) {
                            player.commanderDamage.mapValues {
                                it.value.coerceAtMost(settings.commanderDamageThreshold - 1)
                            }
                        } else {
                            player.commanderDamage
                        },
                    )
                }
            },
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

        val rounds = mutableListOf<Map<Int, Int>>()
        while (true) {
            val round = contenders.associateWith { random.nextInt(1, sides + 1) }
            rounds += round
            val best = round.values.max()
            val winners = round.filterValues { it == best }.keys
            if (winners.size == 1) {
                val winner = winners.first()
                return state.copy(
                    startingSeat = winner,
                    lastRoll = DiceRoll(rounds.toList(), winner),
                    turnSeat = winner,
                    turnCount = 1,
                )
            }
            contenders = winners.toList()
        }
    }

    /** Rolls dice for their own sake — a coin is two sides. */
    fun rollDice(sides: Int, count: Int = 1, random: Random = Random): DiceThrow {
        require(sides > 1) { "A die needs more than one side" }
        require(count in 1..20) { "Roll between one and twenty dice, got $count" }
        return DiceThrow(sides, List(count) { random.nextInt(1, sides + 1) })
    }

    /**
     * Passes the turn to the next seat still in the game.
     *
     * Seating order is seat order, and players who are out are skipped rather than given
     * a turn they cannot take.
     */
    fun nextTurn(state: GameState): GameState {
        val live = state.livePlayers.map { it.seat }.sorted()
        if (live.isEmpty() || state.isFinished) return state
        val current = state.turnSeat
            ?: return state.copy(turnSeat = live.first(), turnCount = state.turnCount + 1)
        val next = live.firstOrNull { it > current } ?: live.first()
        return state.copy(turnSeat = next, turnCount = state.turnCount + 1)
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
        return resolveOutcome(state.copy(players = players)).passTurnIfHolderIsOut()
    }

    /** Keeps the turn with somebody who can actually take it. */
    private fun GameState.passTurnIfHolderIsOut(): GameState {
        val holder = turnSeat ?: return this
        if (!player(holder).isOut || isFinished) return this
        val live = livePlayers.map { it.seat }.sorted()
        if (live.isEmpty()) return copy(turnSeat = null)
        // Does not count as a new turn: the turn was interrupted, not taken.
        return copy(turnSeat = live.firstOrNull { it > holder } ?: live.first())
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

    /**
     * A game ends when one player is left, or when nobody is.
     *
     * Star ends earlier: a player wins the moment both of the seats opposite them are
     * out, with two other players still very much in the game. Being last standing still
     * wins as well, which is what happens if the opposing pair never both fall.
     */
    private fun resolveOutcome(state: GameState): GameState {
        val alive = state.livePlayers
        if (state.settings.starFormat) {
            val byStar = alive.filter { player ->
                state.starOpponents(player.seat).all { state.player(it).isOut }
            }
            when {
                byStar.size == 1 ->
                    return state.copy(outcome = GameOutcome.Winner(byStar.single().seat))
                // Only reachable if a single change removes two players at once, since
                // otherwise the first of them would already have ended the game.
                byStar.size > 1 -> return state.copy(outcome = GameOutcome.Draw)
            }
        }
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
