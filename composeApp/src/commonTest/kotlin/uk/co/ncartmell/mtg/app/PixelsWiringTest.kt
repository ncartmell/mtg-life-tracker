package uk.co.ncartmell.mtg.app

import uk.co.ncartmell.mtg.app.pixels.PixelsController
import uk.co.ncartmell.mtg.app.pixels.PixelsDevice
import uk.co.ncartmell.mtg.app.pixels.PixelsDieType
import uk.co.ncartmell.mtg.app.pixels.PixelsLink
import uk.co.ncartmell.mtg.app.pixels.PixelsListener
import uk.co.ncartmell.mtg.app.pixels.PixelsStatus
import uk.co.ncartmell.mtg.app.store.DieRepository
import uk.co.ncartmell.mtg.app.store.GameRepository
import uk.co.ncartmell.mtg.app.store.HistoryRepository
import uk.co.ncartmell.mtg.app.store.ProfileRepository
import uk.co.ncartmell.mtg.app.store.SetupRepository
import uk.co.ncartmell.mtg.app.store.Storage
import uk.co.ncartmell.mtg.engine.GameSettings
import uk.co.ncartmell.mtg.engine.LossReason
import uk.co.ncartmell.mtg.engine.PlayerColour
import uk.co.ncartmell.mtg.engine.SeatSetup
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Storage that keeps its slots in memory, so a test needs no device and no files. */
private class Slots(private val slots: MutableMap<String, String> = mutableMapOf()) : Storage {
    override fun read(key: String): String? = slots[key]
    override fun write(key: String, value: String) {
        slots[key] = value
    }
}

/** A die that is whatever the test needs it to be, and rolls when told to. */
private class FakeLink(override val supported: Boolean = true) : PixelsLink {
    private var listener: PixelsListener? = null

    /** Every blink asked for, as colour to flash count, so the test can read them back. */
    val blinks = mutableListOf<Pair<Int, Int>>()

    override fun listen(listener: PixelsListener?) {
        this.listener = listener
    }

    /** Every id the app asked to connect to, so a reconnect can be seen happening. */
    val connects = mutableListOf<String>()
    var scans = 0

    override fun scan() {
        scans++
    }

    override fun stopScan() = Unit

    override fun connect(id: String) {
        connects += id
    }

    override fun disconnect() = Unit

    override fun blink(rgb: Int, count: Int, durationMs: Int) {
        blinks += rgb to count
    }

    fun connectAs(name: String = "Bulbasaur", dieType: PixelsDieType = PixelsDieType.D20) =
        listener?.onStatus(PixelsStatus.Connected(name, dieType, batteryPercent = 90))

    fun dropOut() = listener?.onStatus(PixelsStatus.Idle)

    fun failToConnect() = listener?.onStatus(PixelsStatus.Failed("no die there"))

    fun find(device: PixelsDevice) = listener?.onFound(device)

    /** [face] is the number printed on the die; the link reports the index behind it. */
    fun roll(face: Int) = listener?.onRolled(face - 1)
}

/**
 * The wiring between a physical die and the game.
 *
 * The rules of a roll-off live in `:engine` and are tested there. What is worth pinning
 * down here is that the die is genuinely optional — that nothing it does is required for
 * the app to work, and that a die which is not connected cannot reach the game at all.
 */
class PixelsWiringTest {

    private var now = 1_000L

    /** One store per state, so a second one can read what the first wrote. */
    private val disk = Slots()

    private fun appState(link: FakeLink) = AppState(
        profiles = ProfileRepository(Slots()),
        history = HistoryRepository(Slots()),
        setups = SetupRepository(Slots()),
        inProgress = GameRepository(Slots()),
        random = Random(1),
        clock = { now },
        pixels = PixelsController(link, clock = { now }, dice = DieRepository(disk)),
    )

    private fun AppState.start(players: Int = 4) = startGame(
        GameSettings(playerCount = players, startingLife = 40),
        (0 until players).map { SeatSetup(name = "P$it", colour = PlayerColour.entries[it]) },
    )

    /** Far enough apart that two rolls are two rolls rather than one die settling. */
    private fun roll(link: FakeLink, face: Int) {
        now += 2_000
        link.roll(face)
    }

    // --- optional ----------------------------------------------------------------------

    @Test
    fun `a platform without bluetooth reports itself unsupported and does nothing`() {
        val state = appState(FakeLink(supported = false)).apply { start() }
        assertEquals(PixelsStatus.Unsupported, state.pixels.status)
        assertTrue(!state.pixels.supported)
        assertTrue(!state.pixels.isConnected)

        state.startRollOff()
        assertNull(state.rollOff, "there is nothing to roll with")
    }

    @Test
    fun `a roll-off cannot start without a die connected`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        state.startRollOff()
        assertNull(state.rollOff)

        link.connectAs()
        state.startRollOff()
        assertNotNull(state.rollOff, "and can once there is one")
    }

    @Test
    fun `the app's own roll is untouched by any of this`() {
        val state = appState(FakeLink()).apply { start() }
        state.rollForFirstPlayer()
        assertNotNull(state.game!!.lastRoll, "no die involved, and it still decides")
        assertNull(state.rollOff)
    }

    // --- rolling -----------------------------------------------------------------------

    @Test
    fun `outside a roll-off the die is simply a d20`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()

        roll(link, 14)

        val thrown = assertNotNull(state.lastThrow)
        assertEquals(20, thrown.sides)
        assertEquals(listOf(14), thrown.values)
        assertNull(state.game!!.lastRoll, "a loose roll does not decide who starts")
    }

    @Test
    fun `a die that says it is a d6 is reported as one`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs(dieType = PixelsDieType.D6)

        roll(link, 4)

        assertEquals(6, assertNotNull(state.lastThrow).sides)
    }

    @Test
    fun `the die goes round the board rather than round the seat numbers`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()

        state.startRollOff()

        // Seat two sits opposite seat one on a two-by-two board, not next to it, so the
        // die passes 0, 1, 3, 2 — the order people are actually sitting in.
        assertEquals(listOf(0, 1, 3, 2), assertNotNull(state.rollOff).contenders)
    }

    @Test
    fun `a roll-off goes round the table and starts the game`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()
        state.startRollOff()

        roll(link, 3)
        roll(link, 11)
        roll(link, 19)
        assertNotNull(state.rollOff, "still going round")
        assertNull(state.game!!.startingSeat)

        roll(link, 7)

        // The third roll went to seat three, which is third round the board.
        assertEquals(3, state.game!!.startingSeat, "the nineteen wins")
        assertEquals(3, state.game!!.turnSeat)
        assertEquals(mapOf(0 to 3, 1 to 11, 3 to 19, 2 to 7), state.game!!.lastRoll?.openingRoll)
    }

    @Test
    fun `a tie sends the die round again between the two who tied`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()
        state.startRollOff()

        listOf(19, 2, 19, 5).forEach { roll(link, it) }
        assertEquals(listOf(0, 3), assertNotNull(state.rollOff).contenders)
        assertNull(state.game!!.startingSeat, "nothing is decided yet")

        roll(link, 4)
        roll(link, 18)

        assertEquals(3, state.game!!.startingSeat)
        assertTrue(assertNotNull(state.game!!.lastRoll).wasTied)
    }

    @Test
    fun `one throw reported twice is still one throw`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()
        state.startRollOff()

        now += 2_000
        link.roll(12)
        // The same settle, reported again a moment later.
        now += 100
        link.roll(12)

        assertEquals(1, assertNotNull(state.rollOff).rolledThisRound)
        assertEquals(1, assertNotNull(state.rollOff).awaiting, "seat one is still to roll")
    }

    // --- the die's own lights ----------------------------------------------------------

    @Test
    fun `the die is lit in the colour of whoever it is waiting on`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()

        state.startRollOff()
        assertEquals(
            state.game!!.player(0).panel.argb and 0xFFFFFF,
            link.blinks.single().first,
            "seat zero is up first",
        )

        roll(link, 9)
        assertEquals(state.game!!.player(1).panel.argb and 0xFFFFFF, link.blinks.last().first)
    }

    @Test
    fun `the winner gets a longer flash of their own colour`() {
        val link = FakeLink()
        val state = appState(link).apply { start(players = 2) }
        link.connectAs()
        state.startRollOff()

        roll(link, 4)
        roll(link, 17)

        val (colour, count) = link.blinks.last()
        assertEquals(state.game!!.player(1).panel.argb and 0xFFFFFF, colour)
        assertEquals(3, count, "three flashes, not the single nudge")
    }

    // --- losing the die ----------------------------------------------------------------

    @Test
    fun `a die that wanders off mid-roll-off drops the roll-off`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()
        state.startRollOff()
        roll(link, 9)

        link.dropOut()

        assertNull(state.rollOff, "nobody is left waiting on a number that cannot arrive")
        assertNull(state.game!!.startingSeat)
    }

    @Test
    fun `leaving the game clears anything half-rolled`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()
        state.startRollOff()

        state.leaveGame()

        assertNull(state.rollOff)
    }

    @Test
    fun `players already knocked out do not get a turn with the die`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        state.eliminate(1, LossReason.Conceded)
        link.connectAs()

        state.startRollOff()

        assertEquals(listOf(0, 3, 2), assertNotNull(state.rollOff).contenders)
    }

    // --- scanning ----------------------------------------------------------------------

    // --- remembering it ----------------------------------------------------------------

    @Test
    fun `connecting to a die is remembered for next time`() {
        val link = FakeLink()
        val state = appState(link)
        state.pixels.connect(PixelsDevice("aa:bb", "Bulbasaur"))
        link.connectAs(name = "Bulbasaur")

        assertEquals("aa:bb", state.pixels.remembered.id)
        assertEquals("Bulbasaur", state.pixels.remembered.name)

        // A fresh state on the same device gets it back without a scan.
        val next = appState(FakeLink())
        assertEquals("aa:bb", next.pixels.remembered.id)
    }

    @Test
    fun `a die is not remembered until it actually answers`() {
        val link = FakeLink()
        val state = appState(link)
        state.pixels.connect(PixelsDevice("aa:bb", "Bulbasaur"))
        link.failToConnect()
        assertNull(state.pixels.remembered.id)
    }

    @Test
    fun `starting a game reaches for the die that was used last`() {
        val link = FakeLink()
        appState(link).apply {
            pixels.connect(PixelsDevice("aa:bb", "Bulbasaur"))
            link.connectAs()
        }

        val next = FakeLink()
        appState(next).start()

        assertEquals(listOf("aa:bb"), next.connects, "and without scanning for it")
        assertEquals(0, next.scans)
    }

    @Test
    fun `a remembered die that is not about says nothing`() {
        val link = FakeLink()
        appState(link).apply {
            pixels.connect(PixelsDevice("aa:bb", "Bulbasaur"))
            link.connectAs()
        }

        val next = FakeLink()
        val state = appState(next).also { it.start() }
        next.failToConnect()

        assertEquals(
            PixelsStatus.Idle,
            state.pixels.status,
            "nobody asked for it, so a die left in its bag is not an error",
        )
    }

    @Test
    fun `a die asked for by hand does report that it could not be reached`() {
        val link = FakeLink()
        val state = appState(link)
        state.pixels.connect(PixelsDevice("aa:bb", "Bulbasaur"))
        link.failToConnect()
        assertIs<PixelsStatus.Failed>(state.pixels.status)
    }

    @Test
    fun `forgetting a die keeps the lighting preference`() {
        val link = FakeLink()
        val state = appState(link)
        state.pixels.connect(PixelsDevice("aa:bb", "Bulbasaur"))
        link.connectAs()
        state.pixels.lightsTheTable = false

        state.pixels.forgetDie()

        assertNull(state.pixels.remembered.id)
        assertFalse(state.pixels.lightsTheTable)
    }

    // --- lighting the table ------------------------------------------------------------

    @Test
    fun `passing the turn lights the die in the new player's colour`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()
        state.rollForFirstPlayer()
        link.blinks.clear()

        state.nextTurn()

        val seat = state.game!!.turnSeat!!
        assertEquals(state.game!!.player(seat).panel.argb and 0xFFFFFF, link.blinks.single().first)
    }

    @Test
    fun `turning the table light off stops the turns but not the rolls`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()
        state.pixels.lightsTheTable = false
        state.rollForFirstPlayer()
        link.blinks.clear()

        state.nextTurn()
        assertTrue(link.blinks.isEmpty(), "the app talking, which was switched off")

        state.startRollOff()
        assertTrue(link.blinks.isNotEmpty(), "the die answering for itself, which was not")
    }

    @Test
    fun `taking the monarchy lights the die`() {
        val link = FakeLink()
        val state = appState(link).apply { start() }
        link.connectAs()
        link.blinks.clear()

        state.setMonarch(2)

        assertEquals(state.game!!.player(2).panel.argb and 0xFFFFFF, link.blinks.single().first)
    }

    // --- scanning ----------------------------------------------------------------------

    @Test
    fun `the same die found twice is listed once`() {
        val link = FakeLink()
        val state = appState(link)
        link.find(PixelsDevice("aa", "Bulbasaur"))
        link.find(PixelsDevice("aa", "Bulbasaur"))
        link.find(PixelsDevice("bb", "Charmander"))

        assertEquals(listOf("Bulbasaur", "Charmander"), state.pixels.found.map { it.name })
    }
}
