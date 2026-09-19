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
import uk.co.ncartmell.mtg.engine.Format
import uk.co.ncartmell.mtg.engine.GameRecord
import kotlin.math.roundToInt

@Composable
fun LeaderboardScreen(state: AppState) {
    val standings = state.book.leaderboard()
    val recent = state.games.games

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

        if (standings.isEmpty() && recent.isEmpty()) {
            Text(
                "No games recorded yet. Add profiles on the setup screen and play a game — " +
                    "the table is only kept for seats with a profile, though every finished " +
                    "game is listed below it.",
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

            if (recent.isNotEmpty()) {
                item {
                    Text(
                        "Recent games",
                        Modifier.padding(top = 20.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(recent) { record -> GameRow(record) }
            }
        }
    }
}

/**
 * One finished game.
 *
 * The totals above are a summary of these, so this is the part that can answer "who
 * actually beat whom", which running win counts on their own never can.
 */
@Composable
private fun GameRow(record: GameRecord) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val winner = record.winner
                if (winner != null) {
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(winner.colour.composeColor()),
                    )
                    Text(
                        record.winners.joinToString(" & ") { it.name } + " won",
                        Modifier.padding(start = 10.dp).weight(1f),
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text("Draw", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                }
                Text(
                    buildString {
                        append("${record.seats.size}p")
                        append("  ·  ${record.settings.startingLife}")
                        if (record.settings.format != Format.FREE_FOR_ALL) {
                            append("  ·  ${record.settings.format.label}")
                        }
                        if (record.turns > 0) append("  ·  ${record.turns} turns")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                record.seats
                    .filterNot { it.seat in record.winningSeats }
                    .joinToString(", ") { it.name },
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
