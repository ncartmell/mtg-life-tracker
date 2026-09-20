package uk.co.ncartmell.mtg.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Painting a panel added fields to types that are already sitting in people's storage.
 * Losing a profile list to a failed read would cost somebody their leaderboard, so the
 * old shape has to keep loading.
 */
class PanelPaintTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a profile saved before panels could be painted still loads`() {
        val old = """{"id":"a","name":"Nathan","colour":"BLUE","wins":3,"losses":1}"""
        val profile = json.decodeFromString<PlayerProfile>(old)

        assertEquals("Nathan", profile.name)
        assertEquals(3, profile.wins)
        assertNull(profile.paint, "nothing was stored, so nothing is invented")
    }

    @Test
    fun `an unpainted profile falls back to its named colour and style`() {
        val profile = PlayerProfile(id = "a", name = "Nathan", colour = PlayerColour.BLUE)
        assertEquals(PlayerColour.BLUE.argb.toInt(), profile.panel.argb)
        assertEquals(PanelStyle.SOLID, profile.panel.style)
        assertNull(profile.panel.secondArgb)
    }

    @Test
    fun `an unpainted profile keeps whatever style it had saved`() {
        val profile = PlayerProfile(
            id = "a",
            name = "Nathan",
            colour = PlayerColour.RED,
            style = PanelStyle.FADE,
        )
        assertEquals(PanelStyle.FADE, profile.panel.style)
    }

    @Test
    fun `a painted profile is read back exactly`() {
        val paint = PanelPaint(argb = 0x11223344, secondArgb = 0x55667788, style = PanelStyle.CORNER)
        val profile = PlayerProfile(
            id = "a",
            name = "Nathan",
            colour = PlayerColour.BLUE,
            paint = paint,
        )
        val round = json.decodeFromString<PlayerProfile>(json.encodeToString(profile))

        assertEquals(paint, round.panel, "the paint wins over the named colour")
    }

    @Test
    fun `a second colour is ignored on a style that does not run between two`() {
        val solid = PanelPaint(argb = 0x11223344, secondArgb = 0x55667788, style = PanelStyle.SOLID)
        assertNull(solid.gradientTo, "solid is one colour however many are stored")

        val fade = solid.copy(style = PanelStyle.FADE)
        assertEquals(0x55667788, fade.gradientTo)
    }

    @Test
    fun `the paint a seat starts with is the paint the player keeps`() {
        val paint = PanelPaint(argb = 0x11223344, secondArgb = 0x55667788, style = PanelStyle.FADE)
        val state = GameEngine.newGame(
            GameSettings(playerCount = 2, startingLife = 20),
            listOf(
                SeatSetup(name = "A", colour = PlayerColour.BLUE, paint = paint),
                SeatSetup(name = "B", colour = PlayerColour.RED),
            ),
        )

        assertEquals(paint, state.player(0).panel)
        assertEquals(
            PlayerColour.RED.argb.toInt(),
            state.player(1).panel.argb,
            "a seat with no paint of its own still has its named colour",
        )
    }
}
