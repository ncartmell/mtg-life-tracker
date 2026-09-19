package uk.co.ncartmell.mtg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
            itemsIndexed(standings) { index, profile ->
                val leading = index == 0 && profile.wins > 0
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A place, not just an order: a list read top to bottom does not
                        // say who is winning, and that is what a leaderboard is for.
                        Text(
                            "${index + 1}",
                            Modifier.width(22.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (leading) FontWeight.Bold else FontWeight.Normal,
                            color = if (leading) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Box(
                            Modifier.size(if (leading) 22.dp else 18.dp).clip(CircleShape)
                                .background(profile.colour.composeColor()),
                        )
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(
                                profile.name,
                                fontWeight = if (leading) FontWeight.Bold else FontWeight.Medium,
                                style = if (leading) {
                                    MaterialTheme.typography.titleMedium
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                            )
                            profile.winRate?.let {
                                Text(
                                    "${(it * 100).roundToInt()}% of ${profile.gamesPlayed} games",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "${profile.wins}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "  /${profile.losses}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item {
                    Column(Modifier.padding(top = 20.dp, bottom = 2.dp)) {
                        Text("Recent games", style = MaterialTheme.typography.titleMedium)
                        Box(
                            Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp)
                                .background(
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                ),
                        )
                    }
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
