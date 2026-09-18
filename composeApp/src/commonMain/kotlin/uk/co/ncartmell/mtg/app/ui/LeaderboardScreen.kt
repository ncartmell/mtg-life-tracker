package uk.co.ncartmell.mtg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.ncartmell.mtg.app.AppState
import uk.co.ncartmell.mtg.app.Screen
import kotlin.math.roundToInt

@Composable
fun LeaderboardScreen(state: AppState) {
    val standings = state.book.leaderboard()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Leaderboard", style = MaterialTheme.typography.headlineSmall)
            TextButton(
                onClick = { state.show(if (state.game == null) Screen.Setup else Screen.Game) },
            ) { Text("Back") }
        }

        if (standings.isEmpty()) {
            Text(
                "No games recorded yet. Add profiles on the setup screen and play a game — " +
                    "results are only recorded for seats with a profile.",
                Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LazyColumn(
            Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(standings) { profile ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape)
                                .background(profile.colour.composeColor()),
                        )
                        Text(
                            profile.name,
                            Modifier.padding(start = 12.dp).weight(1f),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            buildString {
                                append("${profile.wins}W ${profile.losses}L")
                                profile.winRate?.let {
                                    append("  ·  ${(it * 100).roundToInt()}%")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
