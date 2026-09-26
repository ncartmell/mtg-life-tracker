package uk.co.ncartmell.mtg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.co.ncartmell.mtg.app.AppState
import uk.co.ncartmell.mtg.app.Screen
import uk.co.ncartmell.mtg.app.store.SetupMemory
import uk.co.ncartmell.mtg.engine.Format
import uk.co.ncartmell.mtg.engine.GameSettings
import uk.co.ncartmell.mtg.engine.PanelPaint
import uk.co.ncartmell.mtg.engine.PanelStyle
import uk.co.ncartmell.mtg.engine.PlayerColour
import uk.co.ncartmell.mtg.engine.SeatSetup

@Composable
fun SetupScreen(state: AppState) {
    // Picks up where the last game left off rather than starting from the defaults.
    val remembered = state.lastSetup
    var playerCount by remember { mutableStateOf(remembered.playerCount) }
    var startingLife by remember { mutableStateOf(remembered.startingLife) }
    var commanderDamage by remember { mutableStateOf(remembered.commanderDamage) }
    var poison by remember { mutableStateOf(remembered.poison) }
    var planechase by remember { mutableStateOf(remembered.planechase) }
    var format by remember { mutableStateOf(remembered.format) }

    // Seat assignments, indexed by seat. Null profile means a guest.
    val seatProfiles = remember { mutableStateListOfNulls(GameSettings.MAX_PLAYERS) }
    val seatCommanders = remember { mutableStateListOfOnes(GameSettings.MAX_PLAYERS) }
    // Paint lives on the seat, not only on a profile. Personalising a profile used to be
    // the only way to change a panel, and it reached the board only if that profile had
    // also been put in a seat — so on a table of guests, which is the ordinary case,
    // nothing anybody chose ever showed up.
    val seatPaints = remember { mutableStateListOfPaints(GameSettings.MAX_PLAYERS) }

    fun paintFor(seat: Int): PanelPaint =
        seatPaints[seat]
            ?: seatProfiles[seat]?.let { state.book[it] }?.panel
            ?: PanelPaint.of(PlayerColour.entries[seat])

    val start = {
        val settings = GameSettings(
            playerCount = playerCount,
            startingLife = startingLife,
            commanderDamageEnabled = commanderDamage,
            poisonEnabled = poison,
            format = format,
            planechase = planechase,
            poisonThreshold = GameSettings.defaultsFor(format).poisonThreshold,
        )
        val seats = (0 until playerCount).map { seat ->
            val profile = seatProfiles[seat]?.let { state.book[it] }
            SeatSetup(
                name = profile?.name ?: "Player ${seat + 1}",
                colour = profile?.colour ?: PlayerColour.entries[seat],
                profileId = profile?.id,
                commanderCount = if (commanderDamage) seatCommanders[seat] else 1,
                defeatMessage = profile?.defeatMessage,
                style = profile?.style ?: PanelStyle.SOLID,
                paint = paintFor(seat),
            )
        }
        state.rememberSetup(
            SetupMemory(
                format = format,
                playerCount = playerCount,
                startingLife = startingLife,
                commanderDamage = commanderDamage,
                poison = poison,
                planechase = planechase,
            ),
        )
        state.startGame(settings, seats)
    }

    // The bar is laid over the list rather than below it, so the list can run underneath
    // and fade out at the button instead of being sliced off square at its top edge.
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 84.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("New game", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { state.show(Screen.Leaderboard) }) { Text("Leaderboard") }
            }
        }

        item {
            Section("Format") {
                ChoiceRow(
                    options = Format.entries,
                    selected = format,
                    label = { it.label },
                    onSelect = { picked ->
                        format = picked
                        // Every format but free-for-all fixes or narrows the table, so
                        // choosing one sets a count that is actually legal for it.
                        val defaults = GameSettings.defaultsFor(picked)
                        playerCount = playerCount.coerceIn(picked.players)
                        if (picked != Format.FREE_FOR_ALL) {
                            playerCount = defaults.playerCount
                            startingLife = defaults.startingLife
                        }
                    },
                )
                Text(
                    format.describe(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Section("Players") {
                ChoiceRow(
                    options = format.players.toList(),
                    selected = playerCount,
                    label = { it.toString() },
                    onSelect = { playerCount = it },
                )
            }
        }

        item {
            Section("Starting life") {
                ChoiceRow(
                    options = GameSettings.COMMON_LIFE_TOTALS,
                    selected = startingLife,
                    label = { it.toString() },
                    onSelect = { startingLife = it },
                )
            }
        }

        item {
            Section("Rules") {
                ToggleRow("Commander damage", commanderDamage) { commanderDamage = it }
                ToggleRow("Poison counters", poison) { poison = it }
                ToggleRow("Planechase", planechase) { planechase = it }
                if (planechase) {
                    Text(
                        "Adds the planar die and a note of whichever plane is in play. " +
                            "Layers over whatever format you are playing.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            }
        }

        item { Text("Seats", style = MaterialTheme.typography.titleMedium) }

        items((0 until playerCount).toList()) { seat ->
            SeatCard(
                state = state,
                seat = seat,
                seatLabel = format.seatLabel(seat),
                selectedProfileId = seatProfiles[seat],
                paint = paintFor(seat),
                onPaint = { painted ->
                    seatPaints[seat] = painted
                    // A guest has nowhere to save to; a profile keeps it for next time.
                    seatProfiles[seat]?.let { state.setProfilePaint(it, painted) }
                },
                takenProfileIds = seatProfiles.take(playerCount).filterNotNull().toSet(),
                commanderCount = seatCommanders[seat],
                commanderDamageEnabled = commanderDamage,
                onProfile = { seatProfiles[seat] = it },
                onCommanderCount = { seatCommanders[seat] = it },
            )
        }

        item { AddProfileCard(state) }

        item { ProfileTrimmings(state) }

        item { Box(Modifier.size(4.dp)) }
    }

    // Pinned, so starting a game never means scrolling past six seat cards to find the
    // button — which is the single thing this screen exists to do.
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(20.dp).background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                ),
            ),
        )
        Box(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Button(onClick = start, modifier = Modifier.fillMaxWidth()) {
                Text("Start game")
            }
        }
    }
    }
}

@Composable
private fun SeatCard(
    state: AppState,
    seat: Int,
    seatLabel: String,
    selectedProfileId: String?,
    paint: PanelPaint,
    onPaint: (PanelPaint) -> Unit,
    takenProfileIds: Set<String>,
    commanderCount: Int,
    commanderDamageEnabled: Boolean,
    onProfile: (String?) -> Unit,
    onCommanderCount: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var painting by remember { mutableStateOf(false) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelSwatch(paint, Modifier.size(width = 34.dp, height = 20.dp))
                Text(
                    seatLabel,
                    Modifier.padding(start = 8.dp).weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { painting = true }) { Text("Panel") }
            }

            if (painting) {
                PanelPaintDialog(
                    initial = paint,
                    onDismiss = { painting = false },
                    onApply = { onPaint(it); painting = false },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Guest", selected = selectedProfileId == null) { onProfile(null) }
                state.book.profiles.forEach { profile ->
                    val takenElsewhere =
                        profile.id in takenProfileIds && profile.id != selectedProfileId
                    Chip(
                        label = profile.name,
                        selected = profile.id == selectedProfileId,
                        enabled = !takenElsewhere,
                        tint = profile.colour.composeColor(),
                    ) { onProfile(profile.id) }
                }
            }

            if (commanderDamageEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Commanders", Modifier.weight(1f))
                    listOf(1, 2).forEach { count ->
                        Chip("$count", selected = commanderCount == count) { onCommanderCount(count) }
                    }
                }
            }
        }
    }
}

/**
 * Everything about a saved profile that is not its name: how its panel is painted, and
 * what it says when the player is knocked out.
 */
@Composable
private fun ProfileTrimmings(state: AppState) {
    val profiles = state.book.profiles
    if (profiles.isEmpty()) return

    var editing by remember { mutableStateOf(profiles.first().id) }
    val profile = profiles.firstOrNull { it.id == editing } ?: profiles.first()
    var message by remember(profile.id) { mutableStateOf(profile.defeatMessage.orEmpty()) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Personalise a profile", fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach {
                    Chip(
                        label = it.name,
                        selected = it.id == profile.id,
                        tint = it.colour.composeColor(),
                    ) { editing = it.id }
                }
            }

            var painting by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelSwatch(profile.panel, Modifier.size(width = 40.dp, height = 24.dp))
                Text(
                    "Panel",
                    Modifier.padding(start = 8.dp).weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = { painting = true }) { Text("Change") }
            }
            if (painting) {
                PanelPaintDialog(
                    initial = profile.panel,
                    onDismiss = { painting = false },
                    onApply = { state.setProfilePaint(profile.id, it); painting = false },
                )
            }

            OutlinedTextField(
                value = message,
                onValueChange = {
                    message = it
                    state.setDefeatMessage(profile.id, it)
                },
                label = { Text("What their panel says when they lose") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AddProfileCard(state: AppState) {
    var name by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf(PlayerColour.RED) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add a player profile", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayerColour.entries.forEach { option ->
                    val taken = option in state.book.usedColours
                    Box(
                        Modifier.size(if (colour == option) 34.dp else 26.dp)
                            .clip(CircleShape)
                            .background(option.composeColor())
                            .alphaIf(taken)
                            .clickable { colour = option },
                    )
                }
            }
            Button(
                onClick = {
                    state.addProfile(name, colour)
                    name = ""
                },
                enabled = name.isNotBlank(),
            ) { Text("Add profile") }
        }
    }
}

// --- small building blocks -----------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        )
        content()
    }
}

/**
 * Wraps rather than running off the edge. Five formats with names like "Two-Headed Giant"
 * do not fit across a phone, and a Row would simply have put the last two off-screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            Chip(label(option), selected = option == selected) { onSelect(option) }
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            tint?.let {
                Box(Modifier.size(10.dp).clip(CircleShape).background(it))
                Box(Modifier.size(6.dp))
            }
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun Modifier.alphaIf(dim: Boolean): Modifier =
    if (dim) this.then(Modifier.background(Color(0x33000000), CircleShape)) else this

/** A panel drawn exactly as the board will draw it, so the preview cannot drift. */
@Composable
private fun PanelSwatch(paint: PanelPaint, modifier: Modifier = Modifier) {
    val base = paint.baseColor()
    val to = paint.secondColor()
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(base)
            .then(paint.style.brushFor(base, to)?.let { Modifier.background(it) } ?: Modifier),
    )
}

/**
 * Picks a panel: how it is laid on, and the one or two colours it is laid on in.
 *
 * Ten named colours were never enough for six people who all want blue, so the colour is
 * mixed rather than chosen from a list. The presets stay as the quick way in, because at
 * a table nobody wants to mix a colour from nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelPaintDialog(
    initial: PanelPaint,
    onDismiss: () -> Unit,
    onApply: (PanelPaint) -> Unit,
) {
    var style by remember { mutableStateOf(initial.style) }
    var first by remember { mutableStateOf(initial.argb) }
    var second by remember {
        mutableStateOf(initial.secondArgb ?: initial.argb.shifted())
    }
    var editingSecond by remember { mutableStateOf(false) }

    val gradient = style != PanelStyle.SOLID
    val editing = if (gradient && editingSecond) second else first
    val preview = PanelPaint(first, second.takeIf { gradient }, style)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onApply(preview) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Panel") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PanelSwatch(preview, Modifier.fillMaxWidth().height(64.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelStyle.entries.forEach {
                        Chip(it.label, selected = style == it) {
                            style = it
                            if (it == PanelStyle.SOLID) editingSecond = false
                        }
                    }
                }

                // Only worth asking which colour is being mixed when there are two.
                if (gradient) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("From", selected = !editingSecond) { editingSecond = false }
                        Chip("To", selected = editingSecond) { editingSecond = true }
                    }
                }

                Text(editing.hex6(), style = MaterialTheme.typography.labelMedium)

                val set = { value: Int ->
                    if (gradient && editingSecond) second = value else first = value
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlayerColour.entries.forEach { preset ->
                        val argb = preset.argb.toInt()
                        Box(
                            Modifier.size(24.dp)
                                .clip(CircleShape)
                                .background(preset.composeColor())
                                .clickable { set(argb) },
                        )
                    }
                }

                listOf("R" to 16, "G" to 8, "B" to 0).forEach { (label, shift) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = editing.channel(shift).toFloat(),
                            onValueChange = { set(editing.withChannel(shift, it.toInt())) },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            editing.channel(shift).toString(),
                            Modifier.width(32.dp),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        },
    )
}

private fun Int.channel(shift: Int): Int = (this shr shift) and 0xFF

private fun Int.withChannel(shift: Int, value: Int): Int =
    (this and (0xFF shl shift).inv()) or ((value.coerceIn(0, 255)) shl shift) or OPAQUE_BITS

/** A starting point for a second colour that is visibly not the first. */
private fun Int.shifted(): Int = listOf(16, 8, 0).fold(this) { acc, shift ->
    acc.withChannel(shift, (acc.channel(shift) * 0.45f).toInt() + 28)
}

private fun Int.hex6(): String {
    val digits = (this and 0xFFFFFF).toString(16).uppercase()
    return "#" + "0".repeat(6 - digits.length) + digits
}

private const val OPAQUE_BITS = 0xFF shl 24

private fun mutableStateListOfPaints(size: Int) =
    androidx.compose.runtime.mutableStateListOf<PanelPaint?>().apply { repeat(size) { add(null) } }

private fun mutableStateListOfNulls(size: Int) =
    androidx.compose.runtime.mutableStateListOf<String?>().apply { repeat(size) { add(null) } }

private fun mutableStateListOfOnes(size: Int) =
    androidx.compose.runtime.mutableStateListOf<Int>().apply { repeat(size) { add(1) } }

/** A sentence on what a format actually is, for the setup screen. */
private fun Format.describe(): String = when (this) {
    Format.FREE_FOR_ALL -> "Last player standing wins."
    Format.STAR ->
        "Five in a ring. Your opponents are the two players you are not sitting next to, " +
            "and you win when both are out — even with three players still in."
    Format.TWO_HEADED_GIANT ->
        "Two teams of two, sitting in pairs. Each team shares one life total and one " +
            "set of poison counters."
    Format.ARCHENEMY -> "Seat one against everybody else. The archenemy wins alone."
    Format.EMPEROR ->
        "Two teams of three, each with its emperor in the middle seat. A team loses the " +
            "moment its emperor does, however healthy its generals are."
}

/** What a seat is, where the format gives it a job. */
private fun Format.seatLabel(seat: Int): String = when (this) {
    Format.TWO_HEADED_GIANT -> "Seat ${seat + 1} — team ${seat / 2 + 1}"
    Format.ARCHENEMY -> if (seat == 0) "Seat 1 — archenemy" else "Seat ${seat + 1}"
    Format.EMPEROR ->
        "Seat ${seat + 1} — team ${seat / 3 + 1}" + if (seat % 3 == 1) ", emperor" else ""
    else -> "Seat ${seat + 1}"
}
