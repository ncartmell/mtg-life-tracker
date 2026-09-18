package uk.co.ncartmell.mtg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(8.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        // The only chrome on the board. It sits where the panels meet, so the table can
        // reach it from any seat and no player loses room to a toolbar.
        MenuButton(onClick = { menuOpen = true }, modifier = Modifier.align(Alignment.Center))
    }

    if (menuOpen) BoardMenu(state, game, onDismiss = { menuOpen = false })

    detailSeat?.let { seat ->
        PlayerDetailDialog(state, game, game.player(seat), onDismiss = { detailSeat = null })
    }

    (game.outcome as? GameOutcome.Winner)?.let { GameOverDialog(state, game, it.seat) }
    if (game.outcome is GameOutcome.Draw) GameOverDialog(state, game, winningSeat = null)
}

/** Three bars, drawn rather than iconed, so it needs no icon dependency. */
@Composable
private fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .border(2.dp, tint, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { Box(Modifier.size(width = 20.dp, height = 2.dp).background(tint)) }
        }
    }
}

@Composable
private fun BoardMenu(state: AppState, game: GameState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                game.startingSeat?.let { seat ->
                    Text(
                        "${game.player(seat).name} goes first" +
                            (game.lastRoll?.let { " (rolled ${it.highest})" } ?: ""),
                        fontWeight = FontWeight.Medium,
                    )
                }
                // Closes on roll: every seat's number appears on its own panel, which is
                // the point of rolling at a shared table.
                OutlinedButton(
                    onClick = { state.rollForFirstPlayer(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Who goes first?") }
                OutlinedButton(
                    onClick = { state.restart(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restart") }
                OutlinedButton(
                    onClick = { state.show(Screen.Leaderboard) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Leaderboard") }
                OutlinedButton(
                    onClick = state::leaveGame,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back to setup") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    val goesFirst = game.startingSeat == player.seat
    val shape = RoundedCornerShape(12.dp)

    Card(
        // Whoever won the roll is outlined rather than labelled: at six players a panel is
        // a third of the screen wide and a label is the first thing to get clipped.
        modifier = if (goesFirst) modifier.border(3.dp, ink, shape) else modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .rotate(if (rotated) 180f else 0f)
                .alpha(if (player.isOut) 0.45f else 1f),
        ) {
            // Six players on a phone leaves each panel about a third of the screen, so the
            // life row shrinks to fit rather than running off the edge of the card.
            val tight = maxWidth < 170.dp
            val lifeSize = if (tight) 34.sp else 48.sp
            val stepSize = if (tight) 40.dp else 56.dp
            val glyphSize = if (tight) 22.sp else 30.sp

            Column(
                Modifier.fillMaxSize().padding(if (tight) 6.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        player.name + if (goesFirst && !tight) " · first" else "",
                        color = ink,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        style = if (tight) MaterialTheme.typography.labelMedium
                        else MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(
                        onClick = onOpenDetail,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("More", color = ink, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (player.isOut) {
                    Text(
                        player.lostTo.describe(game),
                        color = ink,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StepButton("−", ink, stepSize, glyphSize) {
                            state.adjustLife(player.seat, -1)
                        }
                        Text(
                            player.life.toString(),
                            color = ink,
                            fontSize = lifeSize,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                        StepButton("+", ink, stepSize, glyphSize) {
                            state.adjustLife(player.seat, 1)
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    game.lastRoll?.results?.get(player.seat)?.let { rolled ->
                        Counter("Roll", rolled, null, ink)
                    }

                    if (game.settings.poisonEnabled) {
                        Counter("Poison", player.poison, game.settings.poisonThreshold, ink)
                    }

                    // One entry per commander that has actually connected. A single
                    // "highest damage" figure hides which commander is about to kill you,
                    // and twenty-one is per commander, not per player.
                    if (game.settings.commanderDamageEnabled) {
                        game.opposingCommanders(player.seat)
                            .map { it to player.damageFrom(it) }
                            .filter { (_, amount) -> amount > 0 }
                            .forEach { (commander, amount) ->
                                CommanderDamage(
                                    owner = game.player(commander.seat),
                                    index = commander.index,
                                    amount = amount,
                                    threshold = game.settings.commanderDamageThreshold,
                                    ink = ink,
                                )
                            }
                    }
                }
            }
        }
    }
}

/**
 * A plain tappable box rather than a [TextButton]: the button's minimum width and content
 * padding push the glyph past the edge of a narrow panel, which clipped the "+".
 */
@Composable
private fun StepButton(
    label: String,
    ink: Color,
    size: Dp,
    glyph: TextUnit,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = ink,
            fontSize = glyph,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun Counter(label: String, value: Int, threshold: Int?, ink: Color) {
    Text(
        "$label $value" + (threshold?.let { "/$it" } ?: ""),
        color = ink,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        softWrap = false,
        fontWeight = if (value > 0) FontWeight.Bold else FontWeight.Normal,
    )
}

/**
 * Damage from one commander, tagged with its owner's colour rather than their name: the
 * colour is already how this board identifies a player, and it costs a panel far less
 * width than a name does.
 */
@Composable
private fun CommanderDamage(
    owner: PlayerState,
    index: Int,
    amount: Int,
    threshold: Int,
    ink: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(9.dp)
                .clip(CircleShape)
                .background(owner.colour.composeColor())
                .border(1.dp, ink, CircleShape),
        )
        Box(Modifier.size(4.dp))
        Text(
            (if (owner.commanderCount > 1) "#${index + 1} " else "") + "$amount/$threshold",
            color = ink,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (amount >= threshold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Everything that does not fit on a panel: poison, commander damage, and losing. */
@OptIn(ExperimentalLayoutApi::class)
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
                    // Wraps: three buttons do not fit across a dialog on a phone, and a
                    // Row squeezes the last one until its label breaks mid-word.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
