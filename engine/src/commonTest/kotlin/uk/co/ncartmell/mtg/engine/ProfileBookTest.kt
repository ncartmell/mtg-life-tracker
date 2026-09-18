package uk.co.ncartmell.mtg.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileBookTest {

    private fun book(vararg names: String): ProfileBook =
        names.foldIndexed(ProfileBook()) { i, acc, name ->
            acc.add(name, PlayerColour.entries[i], id = "id-$name")
        }

    @Test
    fun `profiles can be added and looked up`() {
        val b = book("Nathan", "Sam")
        assertEquals(2, b.profiles.size)
        assertEquals("Nathan", b["id-Nathan"]?.name)
        assertNull(b["missing"])
    }

    @Test
    fun `names are trimmed and must be unique regardless of case`() {
        val b = ProfileBook().add("  Nathan  ", PlayerColour.RED, "a")
        assertEquals("Nathan", b["a"]?.name)
        assertFailsWith<IllegalArgumentException> { b.add("nathan", PlayerColour.BLUE, "b") }
    }

    @Test
    fun `a profile needs a name`() {
        assertFailsWith<IllegalArgumentException> { ProfileBook().add("   ", PlayerColour.RED, "a") }
    }

    @Test
    fun `colour can be changed`() {
        val b = book("Nathan").setColour("id-Nathan", PlayerColour.GOLD)
        assertEquals(PlayerColour.GOLD, b["id-Nathan"]?.colour)
    }

    @Test
    fun `a new profile has no record`() {
        val p = book("Nathan")["id-Nathan"]!!
        assertEquals(0, p.gamesPlayed)
        assertNull(p.winRate, "no games played means no win rate, not zero")
    }

    // --- recording results -----------------------------------------------------------

    private fun finishedGame(book: ProfileBook, winnerIndex: Int): GameState {
        val seats = book.profiles.map {
            SeatSetup(it.name, it.colour, profileId = it.id)
        }
        var game = GameEngine.newGame(GameSettings(seats.size, 20), seats)
        seats.indices.filter { it != winnerIndex }.forEach { seat ->
            game = GameEngine.eliminate(game, seat, LossReason.Conceded)
        }
        return game
    }

    @Test
    fun `a win and losses are recorded against profiles`() {
        val b = book("Nathan", "Sam", "Alex")
        val updated = b.recordResult(finishedGame(b, winnerIndex = 1))

        assertEquals(0, updated["id-Nathan"]!!.wins)
        assertEquals(1, updated["id-Nathan"]!!.losses)
        assertEquals(1, updated["id-Sam"]!!.wins)
        assertEquals(0, updated["id-Sam"]!!.losses)
        assertEquals(1, updated["id-Alex"]!!.losses)
    }

    @Test
    fun `guests are not recorded`() {
        val b = book("Nathan")
        val seats = listOf(
            SeatSetup("Nathan", PlayerColour.RED, profileId = "id-Nathan"),
            SeatSetup("Guest", PlayerColour.BLUE),
        )
        var game = GameEngine.newGame(GameSettings(2, 20), seats)
        game = GameEngine.eliminate(game, 1, LossReason.Conceded)

        val updated = b.recordResult(game)
        assertEquals(1, updated["id-Nathan"]!!.wins)
        assertEquals(1, updated.profiles.size, "no profile is created for the guest")
    }

    @Test
    fun `an unfinished game records nothing`() {
        val b = book("Nathan", "Sam")
        val seats = b.profiles.map { SeatSetup(it.name, it.colour, profileId = it.id) }
        val ongoing = GameEngine.newGame(GameSettings(2, 20), seats)

        assertEquals(b, b.recordResult(ongoing))
    }

    @Test
    fun `a draw records nothing`() {
        val b = book("Nathan", "Sam")
        val seats = b.profiles.map { SeatSetup(it.name, it.colour, profileId = it.id) }
        var game = GameEngine.newGame(GameSettings(2, 20), seats)
        game = GameEngine.eliminate(game, 0, LossReason.Effect)
        game = GameEngine.eliminate(game, 1, LossReason.Effect)

        assertEquals(GameOutcome.Draw, game.outcome)
        assertEquals(b, b.recordResult(game), "a draw is neither a win nor a loss")
    }

    @Test
    fun `win rate is wins over games played`() {
        var b = book("Nathan", "Sam")
        b = b.recordResult(finishedGame(b, winnerIndex = 0))
        b = b.recordResult(finishedGame(b, winnerIndex = 1))
        b = b.recordResult(finishedGame(b, winnerIndex = 0))

        val nathan = b["id-Nathan"]!!
        assertEquals(2, nathan.wins)
        assertEquals(1, nathan.losses)
        assertEquals(3, nathan.gamesPlayed)
        assertEquals(2.0 / 3.0, nathan.winRate!!)
    }

    // --- leaderboard -----------------------------------------------------------------

    @Test
    fun `leaderboard orders by wins then fewest losses then name`() {
        val b = ProfileBook(
            listOf(
                PlayerProfile("a", "Alex", PlayerColour.RED, wins = 3, losses = 4),
                PlayerProfile("b", "Sam", PlayerColour.BLUE, wins = 5, losses = 1),
                PlayerProfile("c", "Nathan", PlayerColour.GREEN, wins = 3, losses = 1),
                PlayerProfile("d", "Beth", PlayerColour.GOLD, wins = 3, losses = 1),
            ),
        )
        assertEquals(
            listOf("Sam", "Beth", "Nathan", "Alex"),
            b.leaderboard().map { it.name },
        )
    }

    @Test
    fun `leaderboard does not rank by win rate`() {
        val b = ProfileBook(
            listOf(
                PlayerProfile("a", "Lucky", PlayerColour.RED, wins = 1, losses = 0),
                PlayerProfile("b", "Regular", PlayerColour.BLUE, wins = 20, losses = 30),
            ),
        )
        assertEquals(
            listOf("Regular", "Lucky"),
            b.leaderboard().map { it.name },
            "one win from one game should not top a leaderboard",
        )
    }

    @Test
    fun `removing a profile leaves the rest intact`() {
        val b = book("Nathan", "Sam").remove("id-Nathan")
        assertEquals(listOf("Sam"), b.profiles.map { it.name })
    }

    @Test
    fun `used colours are reported`() {
        val b = ProfileBook()
            .add("Nathan", PlayerColour.RED, "a")
            .add("Sam", PlayerColour.BLUE, "b")
        assertEquals(setOf(PlayerColour.RED, PlayerColour.BLUE), b.usedColours)
        assertTrue(PlayerColour.GREEN !in b.usedColours)
    }
}
