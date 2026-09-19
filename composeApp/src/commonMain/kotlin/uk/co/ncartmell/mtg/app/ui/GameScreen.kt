package uk.co.ncartmell.mtg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.ncartmell.mtg.app.AppState
import uk.co.ncartmell.mtg.app.KeepScreenAwake
import uk.co.ncartmell.mtg.app.Screen
import uk.co.ncartmell.mtg.engine.CommanderId
import uk.co.ncartmell.mtg.engine.GameOutcome
import uk.co.ncartmell.mtg.engine.GameState
import uk.co.ncartmell.mtg.engine.LossReason
import uk.co.ncartmell.mtg.engine.PlayerState

@Composable
fun GameScreen(state: AppState) {
    val game = state.game ?: return
    KeepScreenAwake()
    var detailSeat by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // Keyed on the outcome so a new result always shows, but a dismissed one stays
    // dismissed — the winner is marked on the board, and that is worth being able to see.
    var resultDismissed by remember(game.outcome) { mutableStateOf(false) }
    var rollOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(8.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            boardLayout(game.settings.playerCount, game.settings.starFormat).forEach { row ->
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.seats.forEach { boardSeat ->
                        PlayerPanel(
                            state = state,
                            game = game,
                            player = game.player(boardSeat.seat),
                            facing = boardSeat.facing,
                            onOpenDetail = { detailSeat = boardSeat.seat },
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

    if (menuOpen) {
        BoardMenu(
            state = state,
            game = game,
            onDismiss = { menuOpen = false },
            onOpenRoll = { menuOpen = false; rollOpen = true },
        )
    }

    if (rollOpen) RollDialog(state, game) { rollOpen = false; state.clearThrow() }

    detailSeat?.let { seat ->
        PlayerDetailDialog(state, game, game.player(seat), onDismiss = { detailSeat = null })
    }

    // Restarting leaves this screen composed, so a dialog opened over the board survives
    // into the new game unless it is closed here — a player poisoned to death left the
    // detail dialog sitting in front of the fresh board.
    val restartAndClose = {
        detailSeat = null
        menuOpen = false
        state.restart()
    }

    if (!resultDismissed) {
        (game.outcome as? GameOutcome.Winner)?.let {
            GameOverDialog(state, game, it.seat, restartAndClose) { resultDismissed = true }
        }
        if (game.outcome is GameOutcome.Draw) {
            GameOverDialog(state, game, null, restartAndClose) { resultDismissed = true }
        }
    }
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
private fun BoardMenu(
    state: AppState,
    game: GameState,
    onDismiss: () -> Unit,
    onOpenRoll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                game.turnSeat?.let { seat ->
                    Text(
                        "Turn ${game.turnCount} — ${game.player(seat).name}",
                        fontWeight = FontWeight.Medium,
                    )
                    Button(
                        onClick = { state.nextTurn(); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pass the turn") }
                }

                OutlinedButton(
                    onClick = onOpenRoll,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Roll dice") }
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
    facing: Facing,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = player.colour.composeColor()
    val ink = background.readableOn()
    val goesFirst = game.startingSeat == player.seat
    val hasWon = (game.outcome as? GameOutcome.Winner)?.seat == player.seat
    val shape = RoundedCornerShape(12.dp)
    // A player who is out keeps their colour, but drained of it, so the board reads at a
    // glance: whoever still has a colour is still in.
    val cardColour = if (player.isOut) player.colour.composeColor().drained() else background

    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cardColour),
    ) {
        BoxWithConstraints(
            // Whoever won the roll gets a ring rather than a label: at six players a panel
            // is a third of the screen wide and a label is the first thing to get clipped.
            //
            // The ring is inset so that it lies wholly on the card. Its colour is the
            // card's own ink, which is chosen to contrast with the card — but seat one's
            // card is cream, so its ink is near-black, and a ring drawn on the card's edge
            // sat against the near-black board and vanished. Every other seat has a light
            // ink, which is why only seat one looked like it was never highlighted.
            Modifier.fillMaxSize()
                .padding(RING_INSET)
                .then(
                    when {
                        // The winner's ring is heavier than the first-player one, and
                        // outlives it: the roll stops mattering once someone has won.
                        hasWon -> Modifier.border(WIN_RING_WIDTH, ink, RING_SHAPE)
                        goesFirst -> Modifier.border(RING_WIDTH, ink, RING_SHAPE)
                        else -> Modifier
                    },
                )
                .facing(facing),
        ) {
            // Six players on a phone leaves each panel about a third of the screen, so the
            // life row shrinks to fit rather than running off the edge of the card.
            val tight = maxWidth < 170.dp
            val glyphSize = if (tight) 22.sp else 30.sp
            // A panel does not grow to suit a three-figure total, so the total shrinks to
            // suit the panel. Sizes are stepped rather than continuous so that a life
            // total does not visibly resize on every single point of damage.
            val lifeSize = when (player.life.toString().length) {
                in 0..2 -> if (tight) 34.sp else 48.sp
                3 -> if (tight) 26.sp else 40.sp
                else -> if (tight) 20.sp else 30.sp
            }

            // Forgiving targets: the whole left third of a panel takes a life off and the
            // whole right third puts one on, so nobody has to hit a glyph mid-game. The
            // middle third does nothing, so the card can still be touched safely. This sits
            // under the content, which only steals the taps it has a button for.
            if (!player.isOut) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .repeatingPress { state.adjustLife(player.seat, -1) },
                    )
                    Box(Modifier.weight(1f).fillMaxHeight())
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .repeatingPress { state.adjustLife(player.seat, 1) },
                    )
                }
            }

            // Overlaid rather than stacked. Stacking centred the total in whatever was
            // left between the name and the counters, and those two bands are different
            // heights, so the total sat off centre by the difference.
            Box(Modifier.fillMaxSize().padding(if (tight) 6.dp else 10.dp)) {

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (player.isOut) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutMark(ink, if (tight) 34.dp else 46.dp)
                            Text(
                                "OUT",
                                color = ink,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.18.em,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                player.lostTo.describe(game),
                                color = ink,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    } else if (hasWon) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "WINNER",
                                color = ink,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.em,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                player.life.toString(),
                                color = ink,
                                fontSize = lifeSize,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The total takes the width it needs and the glyphs split
                            // what is left. Giving the total a fixed third instead cut the
                            // digits off at the edges. Splitting the remainder still leaves
                            // each glyph inside the third that responds to it.
                            StepGlyph("\u2212", ink, glyphSize, Modifier.weight(1f))
                            Text(
                                player.life.toString(),
                                color = ink,
                                fontSize = lifeSize,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                            StepGlyph("+", ink, glyphSize, Modifier.weight(1f))
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        player.name + if (goesFirst && !tight) " \u00b7 first" else "",
                        color = ink,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        style = if (tight) MaterialTheme.typography.labelMedium
                        else MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MoreButton(ink, tight, onOpenDetail)
                }

                FlowRow(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // In Star, which two seats you have to outlast is the whole game, so
                    // it belongs on the panel rather than behind More.
                    game.starOpponents(player.seat)
                        .takeIf { it.isNotEmpty() }
                        ?.let { opponents ->
                            val beaten = opponents.count { game.player(it).isOut }
                            Text(
                                "vs " + opponents.joinToString("\u00b7") { "P${it + 1}" } +
                                    "  $beaten/2",
                                color = ink,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (beaten > 0) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }

                    if (game.turnSeat == player.seat && !player.isOut) {
                        Text(
                            "TURN " + game.turnCount,
                            color = ink,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    if (player.cannotLose) {
                        Text(
                            "Can't lose",
                            color = ink,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    game.lastRoll?.openingRoll?.get(player.seat)?.let { rolled ->
                        Counter("Roll", rolled, null, ink)
                    }

                    // Like commander damage: shown once it exists, not as a standing zero.
                    if (game.settings.poisonEnabled && player.poison > 0) {
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
 * Everything to do with rolling, in one place.
 *
 * The first-player roll used to be a single menu entry and a small number on each panel,
 * which was easy to miss entirely. Here the whole table's roll is laid out in order, a
 * tie-break is shown as the separate round it actually was, and the same dialog throws
 * ordinary dice — which a game needs constantly and the app had no answer for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RollDialog(state: AppState, game: GameState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Roll") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Who goes first", fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = state::rollForFirstPlayer,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (game.lastRoll == null) "Roll for first player" else "Roll again") }

                game.lastRoll?.let { roll ->
                    Text(
                        "${game.player(roll.winningSeat).name} goes first, " +
                            "with ${roll.winningRoll}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    roll.openingRoll.entries
                        .sortedByDescending { it.value }
                        .forEach { (seat, value) ->
                            RollRow(
                                colour = game.player(seat).colour.composeColor(),
                                name = game.player(seat).name,
                                value = value,
                                won = !roll.wasTied && seat == roll.winningSeat,
                            )
                        }
                    roll.tieBreaks.forEachIndexed { index, round ->
                        Text(
                            "Tied on ${round.keys.mapNotNull { roll.openingRoll[it] }.maxOrNull()}" +
                                " — re-roll ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        round.entries.sortedByDescending { it.value }.forEach { (seat, value) ->
                            RollRow(
                                colour = game.player(seat).colour.composeColor(),
                                name = game.player(seat).name,
                                value = value,
                                won = index == roll.tieBreaks.lastIndex &&
                                    seat == roll.winningSeat,
                            )
                        }
                    }
                }

                Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .size(width = 1.dp, height = 1.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )

                Text("Dice", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(4, 6, 8, 10, 12, 20).forEach { sides ->
                        OutlinedButton(onClick = { state.rollDice(sides) }) { Text("d$sides") }
                    }
                    OutlinedButton(onClick = { state.rollDice(2) }) { Text("Coin") }
                }

                state.lastThrow?.let { thrown ->
                    Text(
                        if (thrown.isCoin) {
                            if (thrown.values.single() == 2) "Heads" else "Tails"
                        } else {
                            thrown.total.toString()
                        },
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!thrown.isCoin) {
                        Text(
                            "d${thrown.sides}" +
                                if (thrown.values.size > 1) {
                                    " — " + thrown.values.joinToString(" + ")
                                } else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RollRow(colour: Color, name: String, value: Int, won: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
        Text(
            name,
            Modifier.weight(1f),
            fontWeight = if (won) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        Text(
            value.toString(),
            fontWeight = if (won) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * Turns a panel's contents to face a given edge of the device.
 *
 * A quarter turn swaps the constraints before rotating. [Modifier.rotate] on its own only
 * turns what is drawn, so the content would still be measured against the panel's short
 * side and then drawn along its long one — the life total would be laid out in 190dp and
 * merely displayed sideways. Swapping first is what actually buys the space, and it means
 * BoxWithConstraints above reports the reading width rather than the screen width.
 */
private fun Modifier.facing(facing: Facing): Modifier = when (facing) {
    Facing.BOTTOM -> this
    Facing.TOP -> this.rotate(facing.degrees)
    Facing.LEFT, Facing.RIGHT -> this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(
                Constraints(
                    minWidth = constraints.minHeight,
                    maxWidth = constraints.maxHeight,
                    minHeight = constraints.minWidth,
                    maxHeight = constraints.maxWidth,
                ),
            )
            // Report the slot's own orientation back to the parent, and offset the child
            // so that its centre still lands on the slot's centre once rotated.
            layout(placeable.height, placeable.width) {
                placeable.place(
                    x = (placeable.height - placeable.width) / 2,
                    y = (placeable.width - placeable.height) / 2,
                )
            }
        }
        .rotate(facing.degrees)
}

/** The winner's ring, kept clear of the card's edge so it never meets the board behind. */
private val RING_INSET = 3.dp
private val RING_WIDTH = 3.dp
private val WIN_RING_WIDTH = 6.dp
private val RING_SHAPE = RoundedCornerShape(9.dp)

private const val HOLD_BEFORE_REPEAT_MS = 400L
private const val FIRST_REPEAT_MS = 180L
private const val FASTEST_REPEAT_MS = 45L
private const val REPEAT_RAMP_MS = 10L

/**
 * Fires once on press, then repeats while held, getting faster the longer it is held.
 *
 * Losing twenty life to one attack is ordinary; tapping twenty times to record it is not.
 * The callback is read through [rememberUpdatedState] so that the recomposition caused by
 * each step does not restart the gesture and cut the hold short.
 */
@Composable
private fun Modifier.repeatingPress(onStep: () -> Unit): Modifier {
    val step by rememberUpdatedState(onStep)
    return pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                step()
                coroutineScope {
                    val repeat = launch {
                        delay(HOLD_BEFORE_REPEAT_MS)
                        var interval = FIRST_REPEAT_MS
                        while (isActive) {
                            step()
                            delay(interval)
                            interval = (interval - REPEAT_RAMP_MS).coerceAtLeast(FASTEST_REPEAT_MS)
                        }
                    }
                    tryAwaitRelease()
                    repeat.cancel()
                }
            },
        )
    }
}

/**
 * "More" spelled out where there is room, and three dots where there is not.
 *
 * At five and six players a panel is a third of the screen wide, and the word competes
 * with the player's name for that width — which is how "Player 3" became "Player".
 */
@Composable
private fun MoreButton(ink: Color, tight: Boolean, onClick: () -> Unit) {
    // A plain box, not a TextButton: the button's 58dp minimum width is wider than the
    // dots and was taking the room the name needed, which is what truncated it.
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (tight) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                repeat(3) { Box(Modifier.size(3.5.dp).clip(CircleShape).background(ink)) }
            }
        } else {
            Text("More", color = ink, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A crossed-out circle, drawn rather than iconed so it needs no icon dependency and no
 * font that happens to carry a skull.
 */
@Composable
private fun OutMark(ink: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.09f
        val inset = stroke / 2f
        drawCircle(
            color = ink,
            radius = this.size.minDimension / 2f - inset,
            style = Stroke(width = stroke),
        )
        val pad = this.size.minDimension * 0.3f
        drawLine(
            color = ink,
            start = Offset(pad, pad),
            end = Offset(this.size.width - pad, this.size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = Offset(this.size.width - pad, pad),
            end = Offset(pad, this.size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * A colour drained toward the board behind it — keeps the hue, loses the life.
 *
 * Alpha would have done this too, but alpha dimmed the winner's ring and the "OUT" mark
 * along with everything else, which is exactly what has to stay legible.
 */
private fun Color.drained(): Color = Color(
    red = red * 0.32f + 0.078f,
    green = green * 0.32f + 0.086f,
    blue = blue * 0.32f + 0.102f,
    alpha = 1f,
)

/** Only a label — the press is handled by the full-height column behind it. */
@Composable
private fun StepGlyph(label: String, ink: Color, glyph: TextUnit, modifier: Modifier = Modifier) {
    Text(
        label,
        color = ink,
        fontSize = glyph,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
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
            // Five opponents' commander damage plus the Star pairing overran the screen
            // and took the removal buttons with it, so this scrolls.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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

                game.starOpponents(player.seat)
                    .takeIf { it.isNotEmpty() }
                    ?.let { opponents ->
                        Text("Opponents in Star", fontWeight = FontWeight.SemiBold)
                        Text(
                            opponents.joinToString(" and ") { seat ->
                                game.player(seat).name +
                                    if (game.player(seat).isOut) " (out)" else ""
                            } + " — win when both are out.",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cannot lose the game", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Counters keep counting. Everything that built up applies the " +
                                "moment this is turned off.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = player.cannotLose,
                        onCheckedChange = { state.setCannotLose(player.seat, it) },
                    )
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
        StepperButton("−", onMinus)
        Box(Modifier.size(8.dp))
        StepperButton("+", onPlus)
    }
}

/**
 * An outlined button in all but name. It is hand-rolled because a [OutlinedButton] owns
 * its own click handling, which would fire alongside the hold and double every step.
 */
@Composable
private fun StepperButton(label: String, onStep: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.size(width = 64.dp, height = 40.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .repeatingPress(onStep),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GameOverDialog(
    state: AppState,
    game: GameState,
    winningSeat: Int?,
    onRestart: () -> Unit,
    onSeeBoard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSeeBoard,
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
        confirmButton = { Button(onClick = onRestart) { Text("Play again") } },
        dismissButton = {
            Row {
                TextButton(onClick = onSeeBoard) { Text("See board") }
                TextButton(onClick = state::leaveGame) { Text("Setup") }
            }
        },
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
