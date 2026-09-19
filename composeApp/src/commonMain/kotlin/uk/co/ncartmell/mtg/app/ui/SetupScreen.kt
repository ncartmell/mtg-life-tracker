package uk.co.ncartmell.mtg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.ncartmell.mtg.app.AppState
import uk.co.ncartmell.mtg.app.Screen
import uk.co.ncartmell.mtg.engine.GameSettings
import uk.co.ncartmell.mtg.engine.PlayerColour
import uk.co.ncartmell.mtg.engine.SeatSetup

@Composable
fun SetupScreen(state: AppState) {
    var playerCount by remember { mutableStateOf(4) }
    var startingLife by remember { mutableStateOf(40) }
    var commanderDamage by remember { mutableStateOf(true) }
    var poison by remember { mutableStateOf(true) }
    var star by remember { mutableStateOf(false) }
    // Star is a five-player format, so the toggle cannot outlive a change of player count.
    val starAvailable = playerCount == GameSettings.STAR_PLAYERS
    if (!starAvailable && star) star = false

    // Seat assignments, indexed by seat. Null profile means a guest.
    val seatProfiles = remember { mutableStateListOfNulls(GameSettings.MAX_PLAYERS) }
    val seatCommanders = remember { mutableStateListOfOnes(GameSettings.MAX_PLAYERS) }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
            Section("Players") {
                ChoiceRow(
                    options = (GameSettings.MIN_PLAYERS..GameSettings.MAX_PLAYERS).toList(),
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
                if (starAvailable) {
                    ToggleRow("Star format", star) { star = it }
                    Text(
                        "Everyone sits in seat order. Your opponents are the two players " +
                            "you are not sitting next to, and you win when both are out — " +
                            "even with three players still in.",
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
                selectedProfileId = seatProfiles[seat],
                takenProfileIds = seatProfiles.take(playerCount).filterNotNull().toSet(),
                commanderCount = seatCommanders[seat],
                commanderDamageEnabled = commanderDamage,
                onProfile = { seatProfiles[seat] = it },
                onCommanderCount = { seatCommanders[seat] = it },
            )
        }

        item { AddProfileCard(state) }

        item {
            Button(
                onClick = {
                    val settings = GameSettings(
                        playerCount = playerCount,
                        startingLife = startingLife,
                        commanderDamageEnabled = commanderDamage,
                        poisonEnabled = poison,
                        starFormat = star && starAvailable,
                    )
                    val seats = (0 until playerCount).map { seat ->
                        val profile = seatProfiles[seat]?.let { state.book[it] }
                        SeatSetup(
                            name = profile?.name ?: "Player ${seat + 1}",
                            colour = profile?.colour ?: PlayerColour.entries[seat],
                            profileId = profile?.id,
                            commanderCount = if (commanderDamage) seatCommanders[seat] else 1,
                        )
                    }
                    state.startGame(settings, seats)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start game") }
        }
    }
}

@Composable
private fun SeatCard(
    state: AppState,
    seat: Int,
    selectedProfileId: String?,
    takenProfileIds: Set<String>,
    commanderCount: Int,
    commanderDamageEnabled: Boolean,
    onProfile: (String?) -> Unit,
    onCommanderCount: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Seat ${seat + 1}", fontWeight = FontWeight.SemiBold)

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
        content()
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            tint?.let {
                Box(Modifier.size(10.dp).clip(CircleShape).background(it))
                Box(Modifier.size(6.dp))
            }
            Text(label)
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

private fun mutableStateListOfNulls(size: Int) =
    androidx.compose.runtime.mutableStateListOf<String?>().apply { repeat(size) { add(null) } }

private fun mutableStateListOfOnes(size: Int) =
    androidx.compose.runtime.mutableStateListOf<Int>().apply { repeat(size) { add(1) } }
