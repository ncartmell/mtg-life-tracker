package uk.co.ncartmell.mtg.app.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import uk.co.ncartmell.mtg.engine.Format
import uk.co.ncartmell.mtg.engine.PanelPaint
import uk.co.ncartmell.mtg.engine.PanelStyle
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
fun boardLayout(playerCount: Int, format: Format = Format.FREE_FOR_ALL): List<BoardRow> {
    // Teams sit together, so a team format seats each side facing its own edge: partners
    // shoulder to shoulder, opponents across the table. Reading the board tells you who
    // is on whose side without consulting anything.
    if (format == Format.TWO_HEADED_GIANT && playerCount == 4) {
        return listOf(
            BoardRow(listOf(BoardSeat(0, Facing.TOP), BoardSeat(1, Facing.TOP))),
            BoardRow(listOf(BoardSeat(2, Facing.BOTTOM), BoardSeat(3, Facing.BOTTOM))),
        )
    }
    if (format == Format.EMPEROR && playerCount == 6) {
        return listOf(
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
    }
    // The archenemy takes the whole near edge, with the alliance ranged opposite.
    if (format == Format.ARCHENEMY) {
        return listOf(
            BoardRow((1 until playerCount).map { BoardSeat(it, Facing.TOP) }),
            BoardRow(listOf(BoardSeat(0, Facing.BOTTOM))),
        )
    }
    val star = format == Format.STAR
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

/** A panel's first colour, forced opaque — a see-through panel would show the board. */
fun PanelPaint.baseColor(): Color = Color(argb or OPAQUE)

/** The colour the panel runs to, or null when it is one colour. */
fun PanelPaint.secondColor(): Color? = gradientTo?.let { Color(it or OPAQUE) }

private const val OPAQUE = 0xFF000000.toInt()

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

/**
 * Text colour for a panel that runs between two colours.
 *
 * Judged on the midpoint rather than on either end, because the total sits across the
 * middle of the panel. Picking on the first colour alone puts near-black text over a
 * panel that fades to near-black by the time it reaches the bottom.
 */
fun readableOnBlend(first: Color, second: Color?): Color {
    if (second == null) return first.readableOn()
    return Color(
        red = (first.red + second.red) / 2f,
        green = (first.green + second.green) / 2f,
        blue = (first.blue + second.blue) / 2f,
    ).readableOn()
}

/**
 * The order the seats run clockwise round the table, read off the board itself.
 *
 * Turns pass clockwise, and "clockwise" means what a player sees, not what the seat
 * numbers happen to be: on a 2x2 board seats 0,1,2,3 sit top-left, top-right, bottom-left,
 * bottom-right, so going round is 0, 1, 3, 2. Deriving it from the layout rather than
 * writing it out per format means the two cannot drift apart.
 *
 * The traversal is the ring: across the top, down the right, back across the bottom, and
 * up the left. Rows between the first and last are expected to hold a left seat and a
 * right seat, which is what the Star board does.
 */
fun clockwiseOrder(rows: List<BoardRow>): List<Int> {
    if (rows.isEmpty()) return emptyList()
    if (rows.size == 1) return rows.single().seats.map { it.seat }

    val top = rows.first().seats.map { it.seat }
    val bottom = rows.last().seats.map { it.seat }.reversed()
    val middle = rows.subList(1, rows.size - 1)
    val downTheRight = middle.mapNotNull { it.seats.lastOrNull()?.seat }
    val upTheLeft = middle.reversed().mapNotNull { row ->
        row.seats.firstOrNull()?.seat?.takeIf { row.seats.size > 1 }
    }
    return top + downTheRight + bottom + upTheLeft
}

/**
 * How a panel is painted over its base colour.
 *
 * Ten colours run out before ten players do, and two people on neighbouring shades of
 * blue is a real way to misread a board, so a profile can also choose how its panel is
 * shaded. Kept to gradients of the player's own colour rather than images: an image
 * picker is a per-platform lift, and this needs no permissions and no storage.
 */
fun PanelStyle.brushFor(base: Color, to: Color?): Brush? = when (this) {
    PanelStyle.SOLID -> null
    // With a second colour the panel runs between the two. Without one it runs between
    // shades of its own, which is what every panel painted before this did and what a
    // profile that has never been repainted still does.
    PanelStyle.FADE -> Brush.verticalGradient(
        to?.let { listOf(base, it) } ?: listOf(base.shade(1.18f), base, base.shade(0.82f)),
    )
    PanelStyle.CORNER -> Brush.linearGradient(
        to?.let { listOf(base, it) } ?: listOf(base.shade(1.22f), base, base.shade(0.86f)),
    )
}

fun Color.shade(factor: Float): Color = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = alpha,
)
