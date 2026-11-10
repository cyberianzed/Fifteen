package app.fifteen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Sync settings. The main way is a sync key: create one here, then paste or scan it on the other device.
 * Email sign-in is still there for accounts that started that way (and to recover a key saved in one).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SyncSettings(store: Store, platform: PlatformUi) {
    val prefs by store.prefs.collectAsState()
    val status by store.sync.status.collectAsState()
    val pending by PendingSyncKey
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf(prefs.supabaseUrl) }
    var apiKey by remember { mutableStateOf(prefs.supabaseKey) }
    var typed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showEmail by remember { mutableStateOf(false) }
    val baked = BakedConfig.SUPABASE_URL.isNotBlank()
    val keyMode = prefs.syncKey.isNotBlank()
    val emailMode = !keyMode && prefs.refreshToken.isNotBlank()

    fun saveServer() { if (!baked) store.updatePrefs { it.copy(supabaseUrl = url.trim(), supabaseKey = apiKey.trim()) } }
    fun run(action: suspend () -> String?) {
        saveServer()
        busy = true
        message = null
        scope.launch {
            message = action()
            busy = false
            if (message == null) { typed = ""; PendingSyncKey.value = null }
        }
    }

    Column {
        SectionTitle(
            "Sync between phone and computer",
            "Use the same sync key on both. No account or email needed, and your data is encrypted with the key before it leaves the device.",
        )
        val (dot, text) = when (status.state) {
            SyncState.NotConfigured -> Ink.faint to "Not set up. Everything stays on this device for now."
            SyncState.SignedOut -> Ink.faint to status.message.ifBlank { "Not syncing. Everything stays on this device." }
            SyncState.Syncing -> Ink.sky to "Syncing…"
            SyncState.Synced -> Ink.good to (if (keyMode) "Synced with your key" else "Synced as ${prefs.email}") +
                (if (status.lastOk > 0) " · ${clockText(status.lastOk)}" else "")
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
            InputField(apiKey, { apiKey = it }, "Publishable / anon key", Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }

        // Opened from a scanned QR code: offer to join that key.
        pending?.let { link ->
            val key = SyncKey.parse(link)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.mellow.copy(alpha = 0.1f)).padding(14.dp)) {
                Text(if (key != null) "Sync with this key?" else "That link isn't a valid sync key.", color = Ink.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (key != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(key, color = Ink.muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (key != null) PrimaryButton(if (busy) "…" else "Sync", enabled = !busy) { run { store.sync.useKey(key) } }
                    GhostButton("Not now") { PendingSyncKey.value = null }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when {
            keyMode -> {
                var revealed by remember { mutableStateOf(false) }
                var qr by remember { mutableStateOf(false) }
                var confirmStop by remember { mutableStateOf(false) }
                Text("Your sync key", color = Ink.muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (revealed) prefs.syncKey else prefs.syncKey.take(4) + "••••-••••-••••-••••-••••-" + prefs.syncKey.takeLast(4),
                    color = Ink.text, fontSize = 16.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(if (revealed) "Hide" else "Show") { revealed = !revealed }
                    GhostButton("Copy") { clipboard.setText(AnnotatedString(prefs.syncKey)); message = "Copied. Paste it in Fifteen on your other device." }
                    if (platform.isDesktop) GhostButton(if (qr) "Hide QR code" else "QR code for your phone") { qr = !qr }
                    GhostButton("Sync now") { scope.launch { store.sync.syncNow() } }
                }
                if (qr) {
                    Spacer(Modifier.height(14.dp))
                    platform.KeyQr(SyncKey.link(prefs.syncKey))
                    Spacer(Modifier.height(6.dp))
                    Muted("Scan with your phone's camera to open Fifteen with this key, or type the key in Fifteen on the phone.", size = 12)
                }
                Spacer(Modifier.height(14.dp))
                Muted(
                    "Keep this key somewhere safe, like a password manager. Anyone with it can see and change your Fifteen data. " +
                        if (prefs.email.isNotBlank()) "It's also saved in your account ${prefs.email}: signing in with it gets the key back."
                        else "If you lose every device and the key, the synced copy can't be recovered (your devices keep their own data).",
                    size = 12,
                )
                Spacer(Modifier.height(12.dp))
                GhostButton(if (confirmStop) "Tap again to stop syncing here" else "Stop syncing on this device", color = if (confirmStop) Ink.coral else Ink.muted) {
                    if (confirmStop) { store.sync.forgetKey(); confirmStop = false } else confirmStop = true
                }
            }

            emailMode -> {
                if (prefs.linkedKey.isNotBlank()) {
                    Muted("This account is linked to a sync key.", size = 13)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(if (busy) "…" else "Use the linked key", enabled = !busy) { run { store.sync.useKey(prefs.linkedKey) } }
                } else {
                    Muted(
                        "Move to a sync key: no more sign-in on other devices, and your data is encrypted. " +
                            "The key is saved in this account too, and the account keeps its current copy of your data as a backup.",
                        size = 13,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(if (busy) "…" else "Move to a sync key", enabled = !busy) { run { store.sync.createKey() } }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Sync now") { scope.launch { store.sync.syncNow() } }
                    GhostButton("Sign out") { store.sync.signOut() }
                }
            }

            else -> {
                PrimaryButton(if (busy) "…" else "Create a sync key", enabled = !busy) { run { store.sync.createKey() } }
                Spacer(Modifier.height(14.dp))
                Muted("Already have a key on another device?", size = 13)
                Spacer(Modifier.height(8.dp))
                InputField(typed, { typed = it }, "F15-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX", Modifier.fillMaxWidth(), onDone = { run { store.sync.useKey(typed) } })
                Spacer(Modifier.height(10.dp))
                GhostButton(if (busy) "…" else "Sync with this key") { if (!busy) run { store.sync.useKey(typed) } }
                Spacer(Modifier.height(14.dp))
                GhostButton(if (showEmail) "Hide email sign-in" else "Sign in with email instead") { showEmail = !showEmail }
                if (showEmail) {
                    Spacer(Modifier.height(10.dp))
                    EmailSignIn(store, onBeforeSignIn = { saveServer() })
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = if (it.startsWith("Copied")) Ink.good else Ink.coral, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmailSignIn(store: Store, onBeforeSignIn: () -> Unit) {
    val prefs by store.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(prefs.email) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Muted("For accounts that started with email. A key saved in the account can be used after signing in.", size = 12)
    Spacer(Modifier.height(8.dp))
    InputField(email, { email = it }, "Email", Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    InputField(password, { password = it }, "Password", Modifier.fillMaxWidth(), password = true)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (create in listOf(false, true)) {
            val action = {
                onBeforeSignIn()
                busy = true
                scope.launch {
                    message = store.sync.signIn(email, password, create)
                    busy = false
                    if (message == null) password = ""
                }
                Unit
            }
            if (create) GhostButton("Create account", onClick = action)
            else PrimaryButton(if (busy) "…" else "Sign in", enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = action)
        }
    }
    message?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Ink.coral, fontSize = 12.sp) }
}
