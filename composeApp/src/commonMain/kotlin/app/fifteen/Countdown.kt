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

/** A new countdown starts today at 11 pm (or an hour from now if it's already later). */
fun defaultCountdownAt(now: Long): Long {
    val today = epochDay(now)
    val elevenPm = dayStartMs(today) + 23 * 3_600_000L
    return if (now < elevenPm - 3_600_000L) elevenPm else (now / 3_600_000L + 2) * 3_600_000L
}

/** How much of the countdown's time is left, 1 → 0, measured from when it was set. */
fun countdownFraction(p: Profile, now: Long): Float {
    val from = if (p.countdownFrom in 1 until p.countdownAt) p.countdownFrom else p.countdownAt - 86_400_000L
    val span = (p.countdownAt - from).coerceAtLeast(1)
    return ((p.countdownAt - now).toFloat() / span).coerceIn(0f, 1f)
}

/**
 * The time-left pill: a rounded bar that shrinks as the deadline nears, with a glowing head. It warms from
 * mellow yellow to coral in the last fifth of the time.
 */
@Composable
fun CountdownPill(fraction: Float, modifier: Modifier = Modifier) {
    val warm = ((0.2f - fraction) / 0.2f).coerceIn(0f, 1f)
    val color = androidx.compose.ui.graphics.lerp(Ink.mellow, Ink.coral, warm)
    androidx.compose.foundation.Canvas(modifier) {
        val h = size.height
        val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
        drawRoundRect(androidx.compose.ui.graphics.Color(0x1FFFFFFF), cornerRadius = r)
        if (fraction > 0f) {
            val w = (size.width * fraction).coerceAtLeast(h)
            drawRoundRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color), 0f, w),
                size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = r,
            )
            val head = androidx.compose.ui.geometry.Offset(w - h / 2, h / 2)
            drawCircle(color.copy(alpha = 0.18f), radius = h * 1.6f, center = head)
            drawCircle(color.copy(alpha = 0.35f), radius = h * 1.05f, center = head)
            drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f), radius = h * 0.32f, center = head)
        }
    }
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
                Spacer(Modifier.height(12.dp))
                val fraction = countdownFraction(p, v.now)
                CountdownPill(fraction, Modifier.fillMaxWidth().height(10.dp))
                Spacer(Modifier.height(10.dp))
                Row {
                    Muted("until ${countdownWhen(p.countdownAt)}", size = 12, modifier = Modifier.weight(1f))
                    Muted("${(fraction * 100).toInt()}% of the time left", size = 12)
                }
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
    val at = if (p.countdownAt > 0) p.countdownAt else defaultCountdownAt(v.now)
    val day = epochDay(at)
    val minute = minuteOfDay(at)
    fun save(newDay: Int = day, newMinute: Int = minute) =
        store.updateProfile { it.copy(countdownAt = dayStartMs(newDay) + newMinute * 60_000L, countdownFrom = nowMs()) }

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
    if (platform.isDesktop) {
        Spacer(Modifier.height(10.dp))
        TickBox("Show on the pet", v.prefs.countdownOnPet) { on -> store.updatePrefs { it.copy(countdownOnPet = on) } }
        Muted("While you rest the pet shows the countdown. During a block, right-click the pet to switch between the two.", size = 12)
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
