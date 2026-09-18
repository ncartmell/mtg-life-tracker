package uk.co.ncartmell.mtg.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MTG Life Tracker",
        state = rememberWindowState(size = DpSize(1100.dp, 780.dp)),
    ) {
        App()
    }
}
