package uk.co.ncartmell.mtg.engine

/**
 * A roll for first player made with a real die, one player at a time.
 *
 * [GameEngine.rollForFirstPlayer] settles the whole thing in a single call because it owns
 * the randomness and can simply roll for everybody at once. A physical die does not work
 * that way: it goes round the table and the numbers arrive one at a time, minutes apart,
 * so something has to remember whose number is being waited on. That is all this is — who
 * has yet to roll, what has been rolled so far, and which round of a tie-break it is on.
 *
 * The tie rule is the one [GameEngine.rollForFirstPlayer] already uses, and deliberately
 * so: only the players who tied roll again. Two ways to decide who starts, differing in
 * how ties are handled, would be one way too many.
 */
data class RollOff(
    /** Seats still in contention, in the order they will be asked to roll. */
    val contenders: List<Int>,
    /** Rounds already complete, oldest first. The first is the whole table's opening roll. */
    val rounds: List<Map<Int, Int>> = emptyList(),
    /** The round being rolled now, as far round the table as it has got. */
    val current: Map<Int, Int> = emptyMap(),
    /** Set once one seat has beaten the rest, which is what ends the roll-off. */
    val winningSeat: Int? = null,
) {
    init {
        require(contenders.isNotEmpty()) { "A roll-off needs somebody to roll" }
    }

    val isSettled: Boolean get() = winningSeat != null

    /** The seat the die is waiting on, or null once it has settled. */
    val awaiting: Int?
        get() = if (isSettled) null else contenders.firstOrNull { it !in current }

    /** How many of this round's contenders have rolled, for a "two of four" line. */
    val rolledThisRound: Int get() = current.size

    /** Which tie-break this is. Zero while the opening round is still going round. */
    val tieBreakNumber: Int get() = rounds.size

    /** The finished roll, ready for [GameEngine.applyRoll]. Null until it has settled. */
    val result: DiceRoll? get() = winningSeat?.let { DiceRoll(rounds, it) }

    /**
     * Records [value] against whichever seat is being waited on.
     *
     * A roll arriving after the thing has settled changes nothing rather than being an
     * error. The die keeps reporting every time it is picked up and put down, and the last
     * player to roll has no particular reason to stop holding it.
     */
    fun record(value: Int): RollOff {
        val seat = awaiting ?: return this
        val round = current + (seat to value)
        // Still going round the table.
        if (round.size < contenders.size) return copy(current = round)

        val best = round.values.max()
        val winners = round.filterValues { it == best }.keys
        return if (winners.size == 1) {
            copy(rounds = rounds + round, current = emptyMap(), winningSeat = winners.first())
        } else {
            // Only the tied players roll again, and they keep their seating order rather
            // than the order they happened to roll in — the die is still going one way
            // round the table and it should carry on going that way.
            copy(
                contenders = contenders.filter { it in winners },
                rounds = rounds + round,
                current = emptyMap(),
            )
        }
    }

    companion object {
        /**
         * Starts a roll-off between everybody still in [state], in seating order.
         *
         * Null when there is nobody left to roll, which is the same answer
         * [GameEngine.rollForFirstPlayer] gives by returning the state untouched.
         */
        fun start(state: GameState): RollOff? =
            state.seating.filterNot { state.player(it).isOut }
                .takeIf { it.isNotEmpty() }
                ?.let { RollOff(it) }
    }
}
