package uk.co.ncartmell.mtg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
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
import uk.co.ncartmell.mtg.app.pixels.PixelsDieType
import uk.co.ncartmell.mtg.app.pixels.PixelsStatus
import uk.co.ncartmell.mtg.app.store.nowMillis
import uk.co.ncartmell.mtg.app.Screen
import uk.co.ncartmell.mtg.engine.CommanderId
import uk.co.ncartmell.mtg.engine.Counter
import uk.co.ncartmell.mtg.engine.PanelStyle
import uk.co.ncartmell.mtg.engine.TimeOfDay
import uk.co.ncartmell.mtg.engine.UndercityRoom
import uk.co.ncartmell.mtg.engine.PlanarFace
import uk.co.ncartmell.mtg.engine.GameOutcome
import uk.co.ncartmell.mtg.engine.GameState
import uk.co.ncartmell.mtg.engine.LossReason
import uk.co.ncartmell.mtg.engine.PlayerState
import uk.co.ncartmell.mtg.engine.RollOff

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
            boardLayout(game.settings.playerCount, game.settings.format).forEach { row ->
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
        game.outcome?.let { outcome ->
            GameOverDialog(
                state = state,
                game = game,
                winners = outcome.winningSeats,
                onRestart = restartAndClose,
                onSeeBoard = { resultDismissed = true },
            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardMenu(
    state: AppState,
    game: GameState,
    onDismiss: () -> Unit,
    onOpenRoll: () -> Unit,
) {
    AlertDialog(
        modifier = rememberMenuEntry(),
        onDismissRequest = onDismiss,
        title = { Text("Game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Available whether or not anyone rolled: knowing who starts is useful,
                // but it should not be the price of counting turns at all.
                game.turnSeat?.let { seat ->
                    Text(
                        "Turn ${game.turnCount} — ${game.player(seat).name}",
                        fontWeight = FontWeight.Medium,
                    )
                }
                Button(
                    onClick = { state.nextTurn(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (game.turnSeat == null) "Start turn one" else "Pass the turn") }

                game.startedAt?.let { started ->
                    val now = tickingNow()
                    Text(
                        "Game " + elapsed(now - started) +
                            (game.turnStartedAt?.let { "  ·  turn " + elapsed(now - it) } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // One value for the table, so it belongs here rather than on a panel.
                Text(
                    "Day and night",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeOfDay.entries.forEach { value ->
                        if (game.timeOfDay == value) {
                            Button(onClick = { state.setTimeOfDay(value) }) { Text(value.label) }
                        } else {
                            OutlinedButton(
                                onClick = { state.setTimeOfDay(value) },
                            ) { Text(value.label) }
                        }
                    }
                }

                // Near the top because it is reached in a hurry: the glyphs repeat while
                // held, so the usual reason to open this menu at all is having overshot.
                OutlinedButton(
                    onClick = { state.undo(); onDismiss() },
                    enabled = state.canUndo,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Undo last change") }

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
    val panel = player.panel
    val background = panel.baseColor()
    val runsTo = panel.secondColor()
    val ink = readableOnBlend(background, runsTo)
    val goesFirst = game.startingSeat == player.seat
    val hasWon = player.seat in (game.outcome?.winningSeats ?: emptyList())
    val shape = RoundedCornerShape(12.dp)
    // A player who is out keeps their colour, but drained of it, so the board reads at a
    // glance: whoever still has a colour is still in.
    // The step back below only makes sense while some panel is being stepped back *from*.
    // Before the first turn, after somebody has won, and in the gap after the player whose
    // turn it is gets knocked out, there is no such panel — and dimming all of them at
    // once, with nothing left bright, just reads as a broken board.
    val turnInProgress = game.outcome == null &&
        game.turnSeat?.let { !game.player(it).isOut } == true
    val isTheirTurn = turnInProgress && game.turnSeat == player.seat && !player.isOut
    // Whose turn it is, said with the card rather than a word on it: theirs is the only
    // panel at full strength, and the table reads that from across the room without
    // anybody having to find a label.
    val step = { colour: Color ->
        when {
            player.isOut -> colour.drained()
            !turnInProgress || isTheirTurn -> colour
            else -> colour.resting()
        }
    }
    val cardColour by animateColorAsState(step(background), tween(400), label = "card")
    // Both ends of a gradient are stepped by the same amount on the same spec, so a
    // two-colour panel recedes as one thing rather than pulling apart while it animates.
    val cardRunsTo by animateColorAsState(
        targetValue = step(runsTo ?: background),
        animationSpec = tween(400),
        label = "cardTo",
    )

    val ringWidth by animateDpAsState(
        targetValue = when {
            hasWon -> WIN_RING_WIDTH
            goesFirst -> RING_WIDTH
            else -> 0.dp
        },
        animationSpec = tween(320),
        label = "ring",
    )

    // A drag on the middle column opens this player's detail. While that drag is live the
    // card says so: it draws back a little and lifts off the board, so a gesture that has
    // begun looks different from a touch that has not.
    var grabbed by remember { mutableStateOf(false) }
    val grabScale by animateFloatAsState(
        targetValue = if (grabbed) 0.965f else 1f,
        animationSpec = tween(140),
        label = "grab",
    )
    val grabLift by animateDpAsState(
        targetValue = if (grabbed) 10.dp else 0.dp,
        animationSpec = tween(140),
        label = "lift",
    )

    Card(
        modifier = modifier.scale(grabScale),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cardColour),
        elevation = CardDefaults.cardElevation(defaultElevation = grabLift),
    ) {
        val paint = if (player.isOut) {
            null
        } else {
            panel.style.brushFor(cardColour, runsTo?.let { cardRunsTo })
        }
        BoxWithConstraints(
            // Whoever won the roll gets a ring rather than a label: at six players a panel
            // is a third of the screen wide and a label is the first thing to get clipped.
            //
            // The ring is inset so that it lies wholly on the card. Its colour is the
            // card's own ink, which is chosen to contrast with the card — but seat one's
            // card is cream, so its ink is near-black, and a ring drawn on the card's edge
            // sat against the near-black board and vanished. Every other seat has a light
            // ink, which is why only seat one looked like it was never highlighted.
            //
            // Turned first, so that everything below it — the paint especially — is laid
            // out the way this player reads the panel rather than the way the board does.
            Modifier.fillMaxSize()
                .facing(facing)
                // Over the whole card, before the ring is inset off the edge. Painting it
                // after the inset left a three-pixel rim of the base colour round the
                // gradient, which read as a border nobody asked for.
                .then(paint?.let { Modifier.background(it) } ?: Modifier)
                .padding(RING_INSET)
                .then(
                    // The winner's ring is heavier than the first-player one, and
                    // outlives it: the roll stops mattering once someone has won. Its
                    // width animates, so it grows onto the card rather than appearing.
                    if (ringWidth > 0.dp) Modifier.border(ringWidth, ink, RING_SHAPE)
                    else Modifier,
                ),
        ) {
            // Six players on a phone leaves each panel about a third of the screen, so the
            // life row shrinks to fit rather than running off the edge of the card.
            val tight = maxWidth < 170.dp
            val glyphSize = if (tight) 22.sp else 30.sp
            // A panel does not grow to suit a three-figure total, so the total shrinks to
            // suit the panel. Sizes are stepped rather than continuous so that a life
            // total does not visibly resize on every single point of damage.
            val lifeSize = when (player.life.toString().length) {
                in 0..2 -> if (tight) 40.sp else 62.sp
                3 -> if (tight) 30.sp else 48.sp
                else -> if (tight) 23.sp else 36.sp
            }

            val openDetail = onOpenDetail

            // Forgiving targets: the whole left third of a panel takes a life off and the
            // whole right third puts one on, so nobody has to hit a glyph mid-game. The
            // middle third does nothing, so the card can still be touched safely. This sits
            // under the content, which only steals the taps it has a button for.
            if (player.isOut) {
                // Nothing here to adjust, so the whole card opens the detail. Without
                // this a player who is out has no target at all — the life columns are
                // gone and the swipe lived on the middle one, which left no way back in.
                Box(Modifier.fillMaxSize().clickable { openDetail() })
            } else {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .repeatingPress { state.adjustLife(player.seat, -1) },
                    )
                    // The middle column changes nothing, so it is free to carry the
                    // gesture that opens this player's detail. Swiping here cannot be
                    // confused with a life change, because nothing here alters life.
                    //
                    // Any direction, not just vertical: the panel is rotated to face its
                    // player, and that rotates the gesture with it — a swipe up the screen
                    // arrives here as a sideways drag on a panel turned a quarter turn.
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .pointerInput(Unit) {
                                // Opening on the very first delta left no room for the
                                // panel to show that anything was happening — the dialog
                                // was simply there. A short threshold gives the card time
                                // to react, and stops a stray twitch from opening it.
                                val threshold = 24.dp.toPx()
                                var travelled = Offset.Zero
                                detectDragGestures(
                                    onDragStart = {
                                        travelled = Offset.Zero
                                        grabbed = true
                                    },
                                    onDragEnd = { grabbed = false },
                                    onDragCancel = { grabbed = false },
                                ) { _, delta ->
                                    travelled += delta
                                    if (travelled.getDistance() > threshold) {
                                        travelled = Offset.Zero
                                        grabbed = false
                                        openDetail()
                                    }
                                }
                            },
                    )
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .repeatingPress { state.adjustLife(player.seat, 1) },
                    )
                }
            }

            // Whose turn it is, marked on the card. The colour step below carries most of
            // it, but it cannot carry all of it: a player whose colour is already close to
            // the board's own dark has nowhere to recede to, so on their turn the board
            // would not change at all. The bar is drawn in the card's ink, which is picked
            // to contrast with that card whatever colour it is, so it reads on all five.
            val barWidth by animateDpAsState(
                targetValue = if (isTheirTurn) (if (tight) 30.dp else 44.dp) else 0.dp,
                animationSpec = tween(220),
                label = "turnBar",
            )
            if (barWidth > 0.dp) {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .padding(top = 5.dp)
                        .width(barWidth)
                        .height(4.dp)
                        .background(ink, RoundedCornerShape(2.dp)),
                )
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
                                player.defeatMessage ?: player.lostTo.describe(game),
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
                            // Slides the way the total moved, so a change is visible
                            // even to someone who was not looking at that panel.
                            AnimatedContent(
                                targetState = player.life,
                                transitionSpec = {
                                    val down = targetState < initialState
                                    val h = if (down) -1 else 1
                                    (
                                        slideInVertically(tween(180)) { it * h / 3 } +
                                            fadeIn(tween(180))
                                        ) togetherWith (
                                        slideOutVertically(tween(180)) { -it * h / 3 } +
                                            fadeOut(tween(180))
                                        )
                                },
                                modifier = Modifier.padding(horizontal = 2.dp),
                                label = "life",
                            ) { life ->
                                Text(
                                    life.toString(),
                                    color = ink,
                                    fontSize = lifeSize,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            StepGlyph("+", ink, glyphSize, Modifier.weight(1f))
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // No button here any more: the name gets the whole row, and the
                    // detail opens by swiping the middle of the panel. The button's
                    // minimum width was what truncated "Player 3" to "Player".
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
                    AnimatedVisibility(
                        visible = game.monarchSeat == player.seat,
                        enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                        exit = fadeOut(tween(150)),
                    ) { TokenBadge("Monarch", ink, tight) }
                    AnimatedVisibility(
                        visible = game.initiativeSeat == player.seat,
                        enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                        exit = fadeOut(tween(150)),
                    ) { TokenBadge("Initiative", ink, tight) }
                    AnimatedVisibility(
                        visible = player.citysBlessing,
                        enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                        exit = fadeOut(tween(150)),
                    ) { TokenBadge(if (tight) "Bless" else "City's blessing", ink, tight) }
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

                    game.alliesOf(player.seat)
                        .takeIf { it.isNotEmpty() }
                        ?.let { allies ->
                            Text(
                                "with " + allies.joinToString("\u00b7") { "P${it + 1}" },
                                color = ink,
                                style = MaterialTheme.typography.labelMedium,
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

                    player.activeCounters.forEach { (counter, value) ->
                        Counter(counter.short, value, null, ink)
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

                // While a real die is going round, the buttons stand aside: half a
                // roll-off and a "roll it for me" button next to each other is an
                // invitation to decide the same thing twice.
                val rollOff = state.rollOff
                if (rollOff != null) {
                    RollOffProgress(game, rollOff, onCancel = state::cancelRollOff)
                } else {
                    Button(
                        onClick = state::rollForFirstPlayer,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (game.lastRoll == null) "Roll for first player" else "Roll again") }

                    // A second way to do the same thing, offered only when there is
                    // actually a die on the table to do it with.
                    if (state.pixels.isConnected) {
                        OutlinedButton(
                            onClick = state::startRollOff,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Pass the die round instead") }
                    }
                }

                game.lastRoll?.takeIf { rollOff == null }?.let { roll ->
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

                if (game.settings.planechase) {
                    Text("Planechase", fontWeight = FontWeight.SemiBold)
                    game.currentPlane?.let {
                        Text(
                            "$it  ·  ${game.planeswalks} planeswalks",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = state::rollPlanarDie,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Roll the planar die") }
                    state.lastPlanarFace?.let { face ->
                        Text(
                            face.label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = when (face) {
                                PlanarFace.BLANK -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    var plane by remember { mutableStateOf(game.currentPlane.orEmpty()) }
                    OutlinedTextField(
                        value = plane,
                        onValueChange = { plane = it },
                        label = { Text("Plane in play") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { state.planeswalkTo(plane) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Planeswalk here") }
                }

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

                PixelsPanel(state)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The roll-off as it goes round the table.
 *
 * Whose turn it is to roll is the only thing that matters here, so it is the one thing set
 * in the player's own colour and full size — the die is flashing that colour at the same
 * time, and the two together are what tell somebody across the table that it is them.
 */
@Composable
private fun RollOffProgress(game: GameState, rollOff: RollOff, onCancel: () -> Unit) {
    rollOff.awaiting?.let { seat ->
        val player = game.player(seat)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(player.panel.baseColor()))
            Text(
                "Pass the die to ${player.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            buildString {
                if (rollOff.tieBreakNumber > 0) append("Tie-break ${rollOff.tieBreakNumber} — ")
                append("${rollOff.rolledThisRound} of ${rollOff.contenders.size} rolled")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    rollOff.current.entries.sortedByDescending { it.value }.forEach { (seat, value) ->
        RollRow(
            colour = game.player(seat).panel.baseColor(),
            name = game.player(seat).name,
            value = value,
            won = false,
        )
    }

    TextButton(onClick = onCancel) { Text("Cancel") }
}

/**
 * The physical die: finding one, and what it is doing.
 *
 * Absent entirely where the platform has no Bluetooth, rather than shown and disabled —
 * there is nothing a desktop player could do about it. Everything else in this dialog
 * works exactly as it did before any of this existed, with or without a die.
 */
@Composable
private fun PixelsPanel(state: AppState) {
    val pixels = state.pixels
    if (!pixels.supported) return

    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .size(width = 1.dp, height = 1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
    Text("Pixels die", fontWeight = FontWeight.SemiBold)

    /** A die found by a scan, offered as something to connect to. */
    @Composable
    fun found() = pixels.found.forEach { device ->
        OutlinedButton(
            onClick = { pixels.connect(device) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(device.name) }
    }

    @Composable
    fun note(text: String, colour: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
        Text(text, style = MaterialTheme.typography.labelMedium, color = colour)

    when (val status = pixels.status) {
        is PixelsStatus.Connected -> {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    buildString {
                        append(status.name)
                        if (status.dieType != PixelsDieType.UNKNOWN) {
                            append(" · ${status.dieType.label}")
                        }
                        status.batteryPercent?.let { append(" · $it%") }
                    },
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                TextButton(onClick = pixels::disconnect) { Text("Disconnect") }
            }
            note("Roll it and the number lands here, as a d${pixels.dieFaces}.")
        }

        is PixelsStatus.Connecting -> note("Connecting to ${status.name}…")

        PixelsStatus.Scanning -> {
            note("Looking for dice — give one a shake to wake it up.")
            found()
            TextButton(onClick = pixels::stopScan) { Text("Stop looking") }
        }

        is PixelsStatus.Unavailable -> {
            note(status.reason)
            OutlinedButton(
                onClick = pixels::scan,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Try again") }
        }

        is PixelsStatus.Failed -> {
            note(status.reason, MaterialTheme.colorScheme.error)
            OutlinedButton(
                onClick = pixels::scan,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Look again") }
        }

        PixelsStatus.Idle -> {
            found()
            OutlinedButton(
                onClick = pixels::scan,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Look for a die") }
        }

        // Never reached: the whole panel is skipped when dice are unsupported.
        PixelsStatus.Unsupported -> Unit
    }
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

/**
 * Wall-clock now, refreshed once a second while anything is reading it.
 *
 * Only composed inside the menu, so nothing ticks while the board is up — a timer that
 * recomposes the whole board every second is a timer that flattens the battery.
 */
@Composable
private fun tickingNow(): Long {
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = nowMillis()
        }
    }
    return now
}

/** Milliseconds as m:ss, or h:mm:ss once a game has been going that long. */
private fun elapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    val ss = if (seconds < 10) "0$seconds" else "$seconds"
    if (hours == 0L) return "$minutes:$ss"
    val mm = if (minutes < 10) "0$minutes" else "$minutes"
    return "$hours:$mm:$ss"
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

/** A held token — the monarchy, the initiative — marked on whoever has it. */
@Composable
private fun TokenBadge(label: String, ink: Color, tight: Boolean) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .border(1.dp, ink, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            if (tight) label.take(1) else label,
            color = ink,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
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
 * The rise and scale a menu opens on.
 *
 * Without it a dialog simply exists on the next frame, and after a swipe that reads as the
 * board having jumped rather than as the panel having opened — the gesture and the thing
 * it produced look unrelated. Applied to the dialog's own surface, so the scrim behind it
 * still comes in flat.
 */
@Composable
private fun rememberMenuEntry(): Modifier {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, animationSpec = tween(220, easing = FastOutSlowInEasing))
    }
    return Modifier.graphicsLayer {
        alpha = appear.value
        val grow = 0.90f + 0.10f * appear.value
        scaleX = grow
        scaleY = grow
        translationY = (1f - appear.value) * 20.dp.toPx()
    }
}

/** The board behind the panels; both steps below move a colour towards it. */
private const val BOARD_R = 0.078f
private const val BOARD_G = 0.086f
private const val BOARD_B = 0.102f

/**
 * A panel waiting its turn: still plainly itself, just a step back towards the board.
 *
 * A straight lerp, not a multiply with a lift. The lift was there to stop colours going
 * muddy, but it very nearly cancelled the multiply for a colour that was already dark —
 * the grey-purple panel moved by one part in eighty, which on screen is nothing at all.
 */
private fun Color.resting(): Color = Color(
    red = red * 0.58f + BOARD_R * 0.42f,
    green = green * 0.58f + BOARD_G * 0.42f,
    blue = blue * 0.58f + BOARD_B * 0.42f,
    alpha = alpha,
)

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
        modifier = rememberMenuEntry(),
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(player.colour.composeColor()),
                )
                Text(player.name, Modifier.padding(start = 10.dp))
            }
        },
        text = {
            // Five opponents' commander damage plus the Star pairing overran the screen
            // and took the removal buttons with it, so this scrolls.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (game.settings.poisonEnabled) {
                    DialogSection("Poison")
                    Adjuster(
                        label = "${player.poison} of ${game.settings.poisonThreshold}",
                        onMinus = { state.adjustPoison(player.seat, -1) },
                        onPlus = { state.adjustPoison(player.seat, 1) },
                    )
                }

                if (game.settings.commanderDamageEnabled) {
                    DialogSection("Commander damage taken")
                    game.opposingCommanders(player.seat).forEach { commander ->
                        val owner = game.player(commander.seat)
                        val suffix = if (owner.commanderCount > 1) " #${commander.index + 1}" else ""
                        Adjuster(
                            label = "${owner.name}$suffix",
                            value = player.damageFrom(commander),
                            tint = owner.colour.composeColor(),
                            onMinus = { state.adjustCommanderDamage(player.seat, commander, -1) },
                            onPlus = { state.adjustCommanderDamage(player.seat, commander, 1) },
                        )
                    }

                    DialogSection("This player's commanders")
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

                    // Tax is charged per commander, so it belongs here with them rather
                    // than in the counter list, where one number had to stand for both.
                    repeat(player.commanderCount) { index ->
                        val name = if (player.commanderCount > 1) {
                            "Casts of #${index + 1}"
                        } else {
                            "Times cast"
                        }
                        // The surcharge only once there is one: "(+0)" is noise, and the
                        // row is one line, so anything longer is simply cut off.
                        val surcharge = player.taxOn(index) * 2
                        Adjuster(
                            label = name + if (surcharge > 0) " (+$surcharge)" else "",
                            value = player.taxOn(index),
                            onMinus = { state.adjustCommanderTax(player.seat, index, -1) },
                            onPlus = { state.adjustCommanderTax(player.seat, index, 1) },
                        )
                    }
                }

                // Every counter used to be on show whether or not it was in play, which
                // was four ragged rows of pills for numbers that were all zero — and one
                // row worse with each counter added. The board has always shown only what
                // is in play; this now agrees with it. A counter in play gets the same
                // full-width row as poison and commander damage, and the rest are one tap
                // away rather than permanently underfoot.
                DialogSection("Counters")
                var opened by remember(player.seat) {
                    mutableStateOf(player.activeCounters.map { it.first }.toSet())
                }
                val inPlay = Counter.entries.filter { it in opened || player[it] != 0 }
                inPlay.forEach { counter ->
                    Adjuster(
                        label = counter.label +
                            (counter.max?.let { " (max $it)" } ?: ""),
                        value = player[counter],
                        onMinus = { state.adjustCounter(player.seat, counter, -1) },
                        onPlus = { state.adjustCounter(player.seat, counter, 1) },
                    )
                }
                val unused = Counter.entries.filterNot { it in inPlay }
                if (unused.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        unused.forEach { counter ->
                            OutlinedButton(
                                // Kept on screen once opened, even back at zero, so a row
                                // does not vanish from under the finger that just used it.
                                onClick = { opened = opened + counter },
                            ) { Text("+ " + counter.label) }
                        }
                    }
                }

                DialogSection("Who holds what")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val isMonarch = game.monarchSeat == player.seat
                    val hasInitiative = game.initiativeSeat == player.seat
                    if (isMonarch) {
                        Button(onClick = { state.setMonarch(null) }) { Text("Monarch") }
                    } else {
                        OutlinedButton(
                            onClick = { state.setMonarch(player.seat) },
                        ) { Text("Take monarchy") }
                    }
                    if (hasInitiative) {
                        Button(onClick = { state.setInitiative(null) }) { Text("Initiative") }
                    } else {
                        OutlinedButton(
                            onClick = { state.setInitiative(player.seat) },
                        ) { Text("Take initiative") }
                    }
                    // Sits with the other two because it reads like them, but it is not
                    // taken from anybody: Ascend is permanent, and the whole table can
                    // have it at once.
                    if (player.citysBlessing) {
                        Button(
                            onClick = { state.setCitysBlessing(player.seat, false) },
                        ) { Text("City's blessing") }
                    } else {
                        OutlinedButton(
                            onClick = { state.setCitysBlessing(player.seat, true) },
                        ) { Text("Ascend") }
                    }
                }

                // The initiative sends its holder into the Undercity, so the dungeon
                // lives next to it rather than behind a rule of its own.
                DialogSection("Undercity")
                player.undercityRoom?.let { room ->
                    Text(room.label, fontWeight = FontWeight.SemiBold)
                    Text(room.effect, style = MaterialTheme.typography.labelMedium)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val options = state.ventureOptions(player.seat)
                    val fresh = player.undercityRoom == null
                    options.forEach { room ->
                        OutlinedButton(onClick = { state.ventureTo(player.seat, room) }) {
                            Text(
                                when {
                                    fresh -> "Venture in"
                                    options.size == 1 && room == UndercityRoom.SECRET_ENTRANCE ->
                                        "Venture again"
                                    else -> room.label
                                },
                            )
                        }
                    }
                    if (!fresh) {
                        TextButton(
                            onClick = { state.leaveUndercity(player.seat) },
                        ) { Text("Leave") }
                    }
                }

                game.starOpponents(player.seat)
                    .takeIf { it.isNotEmpty() }
                    ?.let { opponents ->
                        DialogSection("Opponents in Star")
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
                    DialogSection("Remove from the game")
                    // One way out rather than three. The engine still records how a
                    // player went when it worked it out itself — out of life, poisoned,
                    // commander damage — but nobody mid-game wants to choose between
                    // milled, killed and conceded to say "they are out".
                    OutlinedButton(
                        onClick = { state.eliminate(player.seat, LossReason.Effect) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Knock out") }
                }
            }
        },
    )
}

@Composable
private fun Adjuster(
    label: String,
    value: Int? = null,
    tint: Color? = null,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        tint?.let {
            Box(Modifier.size(10.dp).clip(CircleShape).background(it))
            Box(Modifier.size(8.dp))
        }
        Text(label, Modifier.weight(1f), maxLines = 1)
        value?.let {
            Text(it.toString(), Modifier.padding(end = 10.dp), fontWeight = FontWeight.Bold)
        }
        StepperButton("−", onMinus)
        Box(Modifier.size(8.dp))
        StepperButton("+", onPlus)
    }
}

/** A heading with a rule under it, so the dialog reads as sections rather than a list. */
@Composable
private fun DialogSection(title: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        )
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
    winners: List<Int>,
    onRestart: () -> Unit,
    onSeeBoard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSeeBoard,
        title = {
            Text(
                when {
                    winners.isEmpty() -> "A draw"
                    winners.size == 1 -> "${game.player(winners.single()).name} wins"
                    else -> winners.joinToString(" and ") { game.player(it).name } + " win"
                },
            )
        },
        text = {
            Column {
                game.players.sortedBy { it.seat }.forEach { player ->
                    Text(
                        if (player.seat in winners) "${player.name} — won"
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
    LossReason.Effect -> "knocked out"
    LossReason.Conceded -> "conceded"
    is LossReason.CommanderDamage -> commanderDamageLabel(this.from, game)
}

private fun commanderDamageLabel(from: CommanderId, game: GameState): String {
    val owner = game.player(from.seat)
    val suffix = if (owner.commanderCount > 1) " #${from.index + 1}" else ""
    return "commander damage from ${owner.name}$suffix"
}
