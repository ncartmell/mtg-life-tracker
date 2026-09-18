package uk.co.ncartmell.mtg.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import uk.co.ncartmell.mtg.app.ui.GameScreen
import uk.co.ncartmell.mtg.app.ui.LeaderboardScreen
import uk.co.ncartmell.mtg.app.ui.SetupScreen

private val Scheme = darkColorScheme(
    primary = Color(0xFFB8912F),
    background = Color(0xFF14161A),
    surface = Color(0xFF1C1F25),
)

@Composable
fun App(state: AppState = remember { AppState() }) {
    MaterialTheme(colorScheme = Scheme) {
        Surface(Modifier.fillMaxSize()) {
            when (state.screen) {
                Screen.Setup -> SetupScreen(state)
                Screen.Game -> GameScreen(state)
                Screen.Leaderboard -> LeaderboardScreen(state)
            }
        }
    }
}
