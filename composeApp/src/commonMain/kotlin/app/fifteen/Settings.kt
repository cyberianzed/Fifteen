package app.fifteen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(store: Store, platform: PlatformUi, wide: Boolean) {
    val db by store.db.collectAsState()
    Page(wide) {
        Label("Settings")
        Spacer(Modifier.height(6.dp))
        Text("Make it yours", color = Ink.text, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(26.dp))
        Card(Modifier.fillMaxWidth()) { CategoriesEditor(store, db) }
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Rhythm")
                Text("Daily goal: ${db.profile.dailyGoal} points", color = Ink.text, fontSize = 14.sp)
                Muted("About ${db.profile.dailyGoal / 8} good 15-minute blocks. Reaching it makes your companion glow.", size = 12)
                Slider(
                    value = db.profile.dailyGoal.toFloat(), valueRange = 30f..240f, steps = 20,
                    onValueChange = { val g = (it / 10).toInt() * 10; if (g != db.profile.dailyGoal) store.updateProfile { p -> p.copy(dailyGoal = g) } },
                    colors = SliderDefaults.colors(thumbColor = Ink.mellow, activeTrackColor = Ink.mellow, inactiveTrackColor = Ink.surface2),
                )
                Spacer(Modifier.height(10.dp))
                Text("Default block length", color = Ink.text, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Segmented(DURATIONS.map { "$it min" }, DURATIONS.indexOf(db.profile.defaultMinutes).coerceAtLeast(0)) { i ->
                    store.updateProfile { it.copy(defaultMinutes = DURATIONS[i]) }
                }
                Spacer(Modifier.height(10.dp))
                Toggle("Quotes on the bar", db.profile.quotes, "A short line rides along with each block.") { q -> store.updateProfile { it.copy(quotes = q) } }
                val prefs by store.prefs.collectAsState()
                Toggle("Sound effects", prefs.sounds, "Little sounds that match your companion: marimba for Tree, bubbly bells for Clouda, power-ups for Hunter.") { on ->
                    store.updatePrefs { it.copy(sounds = on) }
                    if (on) Sounds.play(Sfx.Good, db.profile.avatar)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Add-ons")
                Toggle(
                    "Add-ons", db.profile.addons,
                    "Little extras your pet can do for you: eye-break reminders, water nudges and friendly check-ins. Turn this on to give them to your pet. You'll get an Add-ons tab (a small + on phones) to choose which ones.",
                ) { on -> store.updateProfile { it.copy(addons = on) } }
            }
        }
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) { Column { platform.PlatformSettings() } }
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) { SyncSettings(store) }
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) { HowPointsWork() }
        platform.PlatformFooter()
        Spacer(Modifier.height(28.dp))
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Like Fifteen? Support this project 💛", color = Ink.muted, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(50))
                .clickable { runCatching { uri.openUri("https://t.me/the_jkj?text=I%20want%20to%20support%20this%20project") } }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoriesEditor(store: Store, db: LocalDb) {
    var newName by remember { mutableStateOf("") }
    var palette by remember { mutableStateOf<String?>(null) }
    Column {
        SectionTitle("Categories", "Tap a dot to recolour. Breaks are honoured instead of rated.")
        val cats = store.activeCategories(db)
        cats.forEachIndexed { i, c ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(c.color.asColor()).clickable { palette = if (palette == c.id) null else c.id })
                Spacer(Modifier.width(14.dp))
                var name by remember(c.id) { mutableStateOf(c.name) }
                BasicTextField(
                    name, { name = it; if (it.isNotBlank()) store.saveCategory(c.copy(name = it)) },
                    singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 14.sp), cursorBrush = SolidColor(Ink.mellow),
                    modifier = Modifier.weight(1f),
                )
                Text(if (c.rest) "break" else "work", color = if (c.rest) Ink.sky else Ink.faint, fontSize = 12.sp,
                    modifier = Modifier.clip(CircleShape).clickable { store.saveCategory(c.copy(rest = !c.rest)) }.padding(8.dp))
                Text("↑", color = Ink.muted, modifier = Modifier.clip(CircleShape).clickable { store.moveCategory(c.id, -1) }.padding(8.dp))
                Text("↓", color = Ink.muted, modifier = Modifier.clip(CircleShape).clickable { store.moveCategory(c.id, 1) }.padding(8.dp))
                Text("×", color = Ink.faint, fontSize = 16.sp, modifier = Modifier.clip(CircleShape).clickable { store.saveCategory(c.copy(archived = true)) }.padding(8.dp))
            }
            if (palette == c.id) {
                FlowRow(Modifier.padding(start = 32.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORY_COLORS.forEach { col ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(col.asColor())
                                .border(2.dp, if (col == c.color) Ink.text else col.asColor(), CircleShape)
                                .clickable { store.saveCategory(c.copy(color = col)); palette = null },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InputField(newName, { newName = it }, "New category", Modifier.weight(1f)) {
                if (newName.isNotBlank()) { store.addCategory(newName, CATEGORY_COLORS[cats.size % CATEGORY_COLORS.size]); newName = "" }
            }
            Spacer(Modifier.width(10.dp))
            GhostButton("Add") {
                if (newName.isNotBlank()) { store.addCategory(newName, CATEGORY_COLORS[cats.size % CATEGORY_COLORS.size]); newName = "" }
            }
        }
        val archived = db.categories.filter { it.archived && !it.deleted }
        if (archived.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Muted("Hidden (tap to bring back):", size = 12)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                archived.forEach { c -> Pill(c.name, dot = c.color.asColor()) { store.saveCategory(c.copy(archived = false)) } }
            }
        }
    }
}

@Composable
private fun SyncSettings(store: Store) {
    val prefs by store.prefs.collectAsState()
    val status by store.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(prefs.email) }
    var password by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(prefs.supabaseUrl) }
    var key by remember { mutableStateOf(prefs.supabaseKey) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val baked = BakedConfig.SUPABASE_URL.isNotBlank()

    Column {
        SectionTitle("Sync between phone and computer", "One Supabase database; every check-in shows up on the other device within seconds.")
        val (dot, text) = when (status.state) {
            SyncState.NotConfigured -> Ink.faint to "Not set up — everything stays on this device for now."
            SyncState.SignedOut -> Ink.faint to (status.message.ifBlank { "Signed out." })
            SyncState.Syncing -> Ink.sky to "Syncing…"
            SyncState.Synced -> Ink.good to "Synced as ${prefs.email}" + (if (status.lastOk > 0) " · ${clockText(status.lastOk)}" else "")
            SyncState.Offline -> Ink.mellow to status.message
            SyncState.Error -> Ink.coral to status.message
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Text(text, color = Ink.text, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (!baked) {
            InputField(url, { url = it }, "Supabase project URL (https://xxxx.supabase.co)", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            InputField(key, { key = it }, "Publishable / anon key", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        if (prefs.refreshToken.isBlank()) {
            InputField(email, { email = it }, "Email", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            InputField(password, { password = it }, "Password", Modifier.fillMaxWidth(), password = true)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (create in listOf(false, true)) {
                    val label = if (create) "Create account" else "Sign in"
                    val action = {
                        if (!baked) store.updatePrefs { it.copy(supabaseUrl = url.trim(), supabaseKey = key.trim()) }
                        busy = true
                        scope.launch {
                            message = store.sync.signIn(email, password, create)
                            busy = false
                            if (message == null) password = ""
                        }
                        Unit
                    }
                    if (create) GhostButton(label, onClick = action) else PrimaryButton(if (busy) "…" else label, enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = action)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Sync now") { scope.launch { store.sync.syncNow() } }
                GhostButton("Sign out") { store.sync.signOut() }
            }
        }
        message?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Ink.coral, fontSize = 12.sp) }
    }
}

@Composable
private fun HowPointsWork() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("How points work", "Built so a rough block never costs you anything.")
        listOf(
            "Per 15 minutes: 100% → 10, 90% → 9, 75% → 7, 50% → 4.",
            "Drifted? +1 for an honest check-in, and your next good block (75%+) counts double.",
            "Momentum: good blocks back-to-back give ×1.2, then ×1.5. A drift pauses momentum; it doesn't erase it.",
            "Golden blocks (×3) and warming eggs appear at random — finish them at 75%+.",
            "Artifacts drop at random; dry spells raise the odds until something drops.",
            "Breaks earn 3 points: resting on purpose is part of the plan.",
            "XP and levels only ever go up. Each day starts a fresh companion.",
        ).forEach { Muted(it) }
    }
}
