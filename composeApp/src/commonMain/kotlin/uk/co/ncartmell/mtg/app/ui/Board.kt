package uk.co.ncartmell.mtg.app.ui

import androidx.compose.ui.graphics.Color
import uk.co.ncartmell.mtg.engine.PlayerColour

/**
 * Which edge of the device a seat is read from, as a rotation applied to its panel.
 *
 * Positive degrees turn clockwise, so a player at the left edge — whose "up" is the
 * screen's right — reads a panel turned a quarter turn clockwise.
 */
enum class Facing(val degrees: Float) {
    BOTTOM(0f),
    TOP(180f),
    LEFT(90f),
    RIGHT(-90f),
}

/** One seat's position on the board: which player, and which way their panel faces. */
data class BoardSeat(val seat: Int, val facing: Facing)

/** One row of panels across the board. */
data class BoardRow(val seats: List<BoardSeat>)

/**
 * Arranges seats around a shared table.
 *
 * Everyone sits around one device, so each panel is turned to face the player it belongs
 * to. That is the whole reason the layout changes with the player count rather than being
 * a scrolling list.
 *
 * Four is the case worth noting: two players sit down each long side rather than two at
 * each end, so their panels turn a quarter turn and read along the card's long axis. A
 * panel in a 2x2 grid is roughly twice as tall as it is wide, so that is roughly twice
 * the room for a life total.
 */
fun boardLayout(playerCount: Int, star: Boolean = false): List<BoardRow> {
    // Star only makes sense if you can see who is next to whom, so its five seats are
    // laid out as the points of a star rather than as two rows: one at the top, two down
    // each side. Read clockwise from the top and seat order is the seating order, which
    // is what the opposing pairs are defined in terms of.
    if (star && playerCount == 5) {
        return listOf(
            BoardRow(listOf(BoardSeat(0, Facing.TOP))),
            BoardRow(listOf(BoardSeat(4, Facing.LEFT), BoardSeat(1, Facing.RIGHT))),
            BoardRow(listOf(BoardSeat(3, Facing.BOTTOM), BoardSeat(2, Facing.BOTTOM))),
        )
    }
    return standardLayout(playerCount)
}

private fun standardLayout(playerCount: Int): List<BoardRow> = when (playerCount) {
    2 -> listOf(
        BoardRow(listOf(BoardSeat(0, Facing.TOP))),
        BoardRow(listOf(BoardSeat(1, Facing.BOTTOM))),
    )
    3 -> listOf(
        BoardRow(listOf(BoardSeat(0, Facing.TOP), BoardSeat(1, Facing.TOP))),
        BoardRow(listOf(BoardSeat(2, Facing.BOTTOM))),
    )
    4 -> listOf(
        BoardRow(listOf(BoardSeat(0, Facing.LEFT), BoardSeat(1, Facing.RIGHT))),
        BoardRow(listOf(BoardSeat(2, Facing.LEFT), BoardSeat(3, Facing.RIGHT))),
    )
    5 -> listOf(
        BoardRow(listOf(BoardSeat(0, Facing.TOP), BoardSeat(1, Facing.TOP))),
        BoardRow(
            listOf(
                BoardSeat(2, Facing.BOTTOM),
                BoardSeat(3, Facing.BOTTOM),
                BoardSeat(4, Facing.BOTTOM),
            ),
        ),
    )
    6 -> listOf(
        BoardRow(
            listOf(
                BoardSeat(0, Facing.TOP),
                BoardSeat(1, Facing.TOP),
                BoardSeat(2, Facing.TOP),
            ),
        ),
        BoardRow(
            listOf(
                BoardSeat(3, Facing.BOTTOM),
                BoardSeat(4, Facing.BOTTOM),
                BoardSeat(5, Facing.BOTTOM),
            ),
        ),
    )
    else -> listOf(BoardRow((0 until playerCount).map { BoardSeat(it, Facing.BOTTOM) }))
}

fun PlayerColour.composeColor(): Color = Color(argb.toULong().toLong() or 0xFF000000L)

/**
 * Text colour that stays readable on a given player colour.
 *
 * Uses relative luminance rather than a per-colour lookup so it keeps working if someone
 * adds a colour to the enum.
 */
fun Color.readableOn(): Color {
    val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
    return if (luminance > 0.55) Color(0xFF16181D) else Color(0xFFF6F6F4)
}
