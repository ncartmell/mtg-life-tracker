package uk.co.ncartmell.mtg.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import uk.co.ncartmell.mtg.app.ui.GameScreen
import uk.co.ncartmell.mtg.app.ui.LeaderboardScreen
import uk.co.ncartmell.mtg.app.ui.SetupScreen

private val Ink = Color(0xFF16181D)
private val Parchment = Color(0xFFF6F6F4)

/**
 * Gold on near-black, so the table can read life totals across a room.
 *
 * The "on" colours are set explicitly rather than left to the Material defaults: the
 * baseline dark scheme pairs its primary with a deep purple, which is unreadable against
 * this gold on a selected chip or a switch thumb.
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFFB8912F),
    onPrimary = Ink,
    secondary = Color(0xFFB8912F),
    onSecondary = Ink,
    background = Color(0xFF14161A),
    onBackground = Parchment,
    surface = Color(0xFF1C1F25),
    onSurface = Parchment,
    surfaceVariant = Color(0xFF262A31),
    onSurfaceVariant = Color(0xFFD6D8DC),
    outline = Color(0xFF6B7078),
)

@Composable
fun App(state: AppState = remember { AppState() }) {
    MaterialTheme(colorScheme = Scheme) {
        // The background paints edge to edge; only the content is inset, so the status
        // bar and home indicator sit on the dark ground rather than over a life total.
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                when (state.screen) {
                    Screen.Setup -> SetupScreen(state)
                    Screen.Game -> GameScreen(state)
                    Screen.Leaderboard -> LeaderboardScreen(state)
                }
            }
        }
    }
}
