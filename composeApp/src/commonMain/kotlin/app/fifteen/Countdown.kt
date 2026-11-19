package app.fifteen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ---------- Countdown add-on: a deadline (a name, a date and a time) counting down on the Now page ----------

/** True when the countdown add-on is on and has a date set. */
fun countdownActive(p: Profile) = p.addons && p.countdownOn && p.countdownAt > 0

/** The big countdown on the Now page: "3d 04:12:05", "04:12:05" or "12:05". */
fun countdownText(msLeft: Long): String {
    val total = (msLeft.coerceAtLeast(0) + 999) / 1000
    val d = total / 86_400
    val h = total % 86_400 / 3600
    val m = total % 3600 / 60
    val s = total % 60
    fun two(x: Long) = x.toString().padStart(2, '0')
    return when {
        d > 0 -> "${d}d ${two(h)}:${two(m)}:${two(s)}"
        h > 0 -> "${two(h)}:${two(m)}:${two(s)}"
        else -> "${two(m)}:${two(s)}"
    }
}

/** The short form for the pet's little timer: "3d 04h", "04:12:05" or "12:05". */
fun countdownShort(msLeft: Long): String {
    val total = (msLeft.coerceAtLeast(0) + 999) / 1000
    val d = total / 86_400
    return if (d > 0) "${d}d ${(total % 86_400 / 3600).toString().padStart(2, '0')}h" else countdownText(msLeft)
}

/** "Tue 25 Nov · 9:00am" for the deadline. */
fun countdownWhen(at: Long): String = "${formatDay(epochDay(at), "EEE d MMM")} · ${clockText(at)}"

/** The box on the Now page. */
@Composable
fun CountdownCard(store: Store, v: View) {
    val p = v.db.profile
    val left = p.countdownAt - v.now
    Card(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle(p.countdownName.ifBlank { "Countdown" })
            if (left > 0) {
                Text(countdownText(left), color = Ink.text, fontSize = 40.sp, fontWeight = FontWeight.ExtraLight,
                    style = TextStyle(fontFeatureSettings = "tnum"))
                Spacer(Modifier.height(4.dp))
                Muted("until ${countdownWhen(p.countdownAt)}", size = 12)
            } else {
                Text("Time's up", color = Ink.mellow, fontSize = 28.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(4.dp))
                Muted(countdownWhen(p.countdownAt), size = 12)
                Spacer(Modifier.height(12.dp))
                GhostButton("Clear countdown") { store.updateProfile { it.copy(countdownOn = false) } }
            }
        }
    }
}

/** Settings inside the add-on card: name, date and time, and (on the computer) whether the pet shows it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownSettings(store: Store, v: View, platform: PlatformUi) {
    val p = v.db.profile
    var name by remember(p.countdownName) { mutableStateOf(p.countdownName) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    // Until a date is chosen, default to tomorrow at 9:00 am.
    val at = if (p.countdownAt > 0) p.countdownAt else dayStartMs(v.today + 1) + 9 * 3_600_000L
    val day = epochDay(at)
    val minute = minuteOfDay(at)
    fun save(newDay: Int = day, newMinute: Int = minute) =
        store.updateProfile { it.copy(countdownAt = dayStartMs(newDay) + newMinute * 60_000L) }

    Text("Name", color = Ink.muted, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    InputField(name, { name = it; store.updateProfile { pr -> pr.copy(countdownName = it.take(40)) } }, "Exam, launch, trip…", Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Text("Counts down to", color = Ink.muted, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(formatDay(day, "EEE d MMM yyyy")) { pickDate = true }
        GhostButton(clockText(at)) { pickTime = true }
    }
    if (p.countdownAt == 0L) {
        Spacer(Modifier.height(8.dp))
        Muted("Pick a date to start it.", size = 12)
    }
    if (platform.isDesktop) {
        Spacer(Modifier.height(10.dp))
        Toggle(
            "Show on the pet", v.prefs.countdownOnPet,
            "The pet's little timer shows the countdown while you rest. During a block, right-click the pet to switch between the two.",
        ) { on -> store.updatePrefs { it.copy(countdownOnPet = on) } }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { save(newDay = (it / 86_400_000L).toInt()) }
                    pickDate = false
                }) { Text("OK", color = Ink.mellow) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel", color = Ink.muted) } },
            colors = DatePickerDefaults.colors(containerColor = Ink.surface),
        ) {
            DatePicker(state, colors = DatePickerDefaults.colors(containerColor = Ink.surface), showModeToggle = false)
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = false)
        Dialog(onDismissRequest = { pickTime = false }) {
            Card(padding = 24.dp) {
                Column {
                    Text("Time", color = Ink.text, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    TimeInput(state)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pickTime = false }) { Text("Cancel", color = Ink.muted) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { save(newMinute = state.hour * 60 + state.minute); pickTime = false }) { Text("OK", color = Ink.mellow) }
                    }
                }
            }
        }
    }
}
