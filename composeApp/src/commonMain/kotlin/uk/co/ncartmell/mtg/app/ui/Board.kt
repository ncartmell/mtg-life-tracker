package uk.co.ncartmell.mtg.app.ui

import androidx.compose.ui.graphics.Color
import uk.co.ncartmell.mtg.engine.PlayerColour

/** One row of seats on the board, and whether it faces the opposite way. */
data class BoardRow(val seats: List<Int>, val rotated: Boolean)

/**
 * Arranges seats around a shared table.
 *
 * Everyone sits around one device, so the panels belonging to players on the far side
 * are rotated to face them. That is the whole reason the layout changes with the player
 * count rather than being a scrolling list.
 */
fun boardLayout(playerCount: Int): List<BoardRow> = when (playerCount) {
    2 -> listOf(BoardRow(listOf(0), rotated = true), BoardRow(listOf(1), rotated = false))
    3 -> listOf(BoardRow(listOf(0, 1), rotated = true), BoardRow(listOf(2), rotated = false))
    4 -> listOf(BoardRow(listOf(0, 1), rotated = true), BoardRow(listOf(2, 3), rotated = false))
    5 -> listOf(BoardRow(listOf(0, 1), rotated = true), BoardRow(listOf(2, 3, 4), rotated = false))
    6 -> listOf(BoardRow(listOf(0, 1, 2), rotated = true), BoardRow(listOf(3, 4, 5), rotated = false))
    else -> listOf(BoardRow((0 until playerCount).toList(), rotated = false))
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
