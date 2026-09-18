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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.ncartmell.mtg.app.AppState
import uk.co.ncartmell.mtg.app.Screen
import uk.co.ncartmell.mtg.engine.CommanderId
import uk.co.ncartmell.mtg.engine.GameOutcome
import uk.co.ncartmell.mtg.engine.GameState
import uk.co.ncartmell.mtg.engine.LossReason
import uk.co.ncartmell.mtg.engine.PlayerState

@Composable
fun GameScreen(state: AppState) {
    val game = state.game ?: return
    var detailSeat by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        GameHeader(state, game, Modifier.fillMaxWidth())

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            boardLayout(game.settings.playerCount).forEach { row ->
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.seats.forEach { seat ->
                        PlayerPanel(
                            state = state,
                            game = game,
                            player = game.player(seat),
                            rotated = row.rotated,
                            onOpenDetail = { detailSeat = seat },
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    detailSeat?.let { seat ->
        PlayerDetailDialog(state, game, game.player(seat), onDismiss = { detailSeat = null })
    }

    (game.outcome as? GameOutcome.Winner)?.let { GameOverDialog(state, game, it.seat) }
    if (game.outcome is GameOutcome.Draw) GameOverDialog(state, game, winningSeat = null)
}

@Composable
private fun GameHeader(state: AppState, game: GameState, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = state::leaveGame) { Text("Setup") }
        OutlinedButton(onClick = state::restart) { Text("Restart") }
        OutlinedButton(onClick = state::rollForFirstPlayer) { Text("Who goes first?") }
        OutlinedButton(onClick = { state.show(Screen.Leaderboard) }) { Text("Leaderboard") }

        Box(Modifier.weight(1f))

        game.startingSeat?.let { seat ->
            Text(
                "${game.player(seat).name} goes first" +
                    (game.lastRoll?.let { " (rolled ${it.highest})" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PlayerPanel(
    state: AppState,
    game: GameState,
    player: PlayerState,
    rotated: Boolean,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = player.colour.composeColor()
    val ink = background.readableOn()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Box(
            Modifier.fillMaxSize()
                .rotate(if (rotated) 180f else 0f)
                .alpha(if (player.isOut) 0.45f else 1f),
        ) {
            Column(
                Modifier.fillMaxSize().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(player.name, color = ink, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onOpenDetail) { Text("More", color = ink) }
                }

                if (player.isOut) {
                    Text(
                        player.lostTo.describe(game),
                        color = ink,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepButton("−", ink) { state.adjustLife(player.seat, -1) }
                        Text(
                            player.life.toString(),
                            color = ink,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        StepButton("+", ink) { state.adjustLife(player.seat, 1) }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (game.settings.poisonEnabled) {
                        Counter("Poison", player.poison, game.settings.poisonThreshold, ink)
                    }
                    if (game.settings.commanderDamageEnabled) {
                        Counter(
                            "Cmdr",
                            player.highestCommanderDamage,
                            game.settings.commanderDamageThreshold,
                            ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, ink: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Text(label, color = ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Counter(
    label: String,
    value: Int,
    threshold: Int,
    ink: androidx.compose.ui.graphics.Color,
) {
    Text(
        "$label $value/$threshold",
        color = ink,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (value > 0) FontWeight.Bold else FontWeight.Normal,
    )
}

/** Everything that does not fit on a panel: poison, commander damage, and losing. */
@Composable
private fun PlayerDetailDialog(
    state: AppState,
    game: GameState,
    player: PlayerState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text(player.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (game.settings.poisonEnabled) {
                    Adjuster(
                        label = "Poison (${player.poison}/${game.settings.poisonThreshold})",
                        onMinus = { state.adjustPoison(player.seat, -1) },
                        onPlus = { state.adjustPoison(player.seat, 1) },
                    )
                }

                if (game.settings.commanderDamageEnabled) {
                    Text("Commander damage taken", fontWeight = FontWeight.SemiBold)
                    game.opposingCommanders(player.seat).forEach { commander ->
                        val owner = game.player(commander.seat)
                        val suffix = if (owner.commanderCount > 1) " #${commander.index + 1}" else ""
                        Adjuster(
                            label = "${owner.name}$suffix — ${player.damageFrom(commander)}",
                            onMinus = { state.adjustCommanderDamage(player.seat, commander, -1) },
                            onPlus = { state.adjustCommanderDamage(player.seat, commander, 1) },
                        )
                    }

                    Text("This player's commanders", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2).forEach { count ->
                            val selected = player.commanderCount == count
                            if (selected) {
                                Button(onClick = {}) { Text("$count") }
                            } else {
                                OutlinedButton(
                                    onClick = { state.setCommanderCount(player.seat, count) },
                                ) { Text("$count") }
                            }
                        }
                    }
                }

                if (player.isOut) {
                    Text("Out: ${player.lostTo.describe(game)}", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { state.restore(player.seat) }) {
                        Text("Bring back in")
                    }
                } else {
                    Text("Remove from the game", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { state.eliminate(player.seat, LossReason.Milled) },
                        ) { Text("Milled") }
                        OutlinedButton(
                            onClick = { state.eliminate(player.seat, LossReason.Effect) },
                        ) { Text("Killed") }
                        OutlinedButton(
                            onClick = { state.eliminate(player.seat, LossReason.Conceded) },
                        ) { Text("Conceded") }
                    }
                }
            }
        },
    )
}

@Composable
private fun Adjuster(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Box(Modifier.size(8.dp))
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun GameOverDialog(state: AppState, game: GameState, winningSeat: Int?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (winningSeat == null) "A draw" else "${game.player(winningSeat).name} wins") },
        text = {
            Column {
                game.players.sortedBy { it.seat }.forEach { player ->
                    Text(
                        if (player.seat == winningSeat) "${player.name} — won"
                        else "${player.name} — ${player.lostTo.describe(game)}",
                    )
                }
            }
        },
        confirmButton = { Button(onClick = state::restart) { Text("Play again") } },
        dismissButton = { TextButton(onClick = state::leaveGame) { Text("Back to setup") } },
    )
}

private fun LossReason?.describe(game: GameState): String = when (this) {
    null -> "still in"
    LossReason.LifeDepleted -> "out of life"
    LossReason.Poison -> "poisoned"
    LossReason.Milled -> "milled"
    LossReason.Effect -> "killed"
    LossReason.Conceded -> "conceded"
    is LossReason.CommanderDamage -> commanderDamageLabel(this.from, game)
}

private fun commanderDamageLabel(from: CommanderId, game: GameState): String {
    val owner = game.player(from.seat)
    val suffix = if (owner.commanderCount > 1) " #${from.index + 1}" else ""
    return "commander damage from ${owner.name}$suffix"
}
