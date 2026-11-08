package app.nimisham

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NimishamApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store by lazy { Store(DirTextFiles(filesDir), scope) }

    companion object {
        fun store(ctx: Context) = (ctx.applicationContext as NimishamApplication).store
    }
}

fun startTracker(ctx: Context) {
    ContextCompat.startForegroundService(ctx, Intent(ctx, TrackerService::class.java))
}

class MainActivity : ComponentActivity() {
    private val store get() = NimishamApplication.store(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startTracker(this)
        val activity = this
        val platform = object : PlatformUi {
            override val isDesktop = false
            override fun openCheckIn() = openCheckIn(activity)
            override fun keepScreenOn(on: Boolean) {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            @Composable
            override fun PlatformSettings() = AndroidSettings(store)
        }
        setContent {
            Box(Modifier.fillMaxSize().imePadding()) { NimishamApp(store, platform) }
        }
    }

    override fun onPause() {
        super.onPause()
        AppVisibility.inFront = false
        startTracker(this)
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.inFront = true
        startTracker(this)
        store.kick.trySend(Unit)
    }
}

fun openCheckIn(ctx: Context) {
    ctx.startActivity(Intent(ctx, CheckInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The check-in as a floating card over whatever is on screen. */
class CheckInActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); AppVisibility.inFront = true; startTracker(this) }
    override fun onPause() { super.onPause(); AppVisibility.inFront = false; startTracker(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = NimishamApplication.store(this)
        setContent {
            NimishamTheme {
                Box(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() }
                        .systemBarsPadding().imePadding().padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink.bg)
                            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp))
                            .clickable(remember { MutableInteractionSource() }, null) {}
                            .verticalScroll(rememberScrollState()),
                    ) {
                        CheckInPanel(store, Modifier.fillMaxWidth()) { finish() }
                    }
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only react to the system's own boot / app-updated broadcasts.
        val ok = intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON")
        if (ok) runCatching { startTracker(context) }
    }
}

/** Notification buttons and the block-end alarm. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = NimishamApplication.store(context)
        when (intent.action) {
            ACTION_RATE -> store.rateAndContinue(intent.getIntExtra("rating", 75))
            ACTION_STOP -> store.stopTracking()
            ACTION_EXTEND -> store.extend(5)
            ACTION_RESUME -> {
                val last = store.db.value.blocks.filter { !it.deleted }.maxByOrNull { it.startAt }
                val cat = last?.categoryId ?: store.activeCategories().firstOrNull()?.id
                if (cat != null) store.start(cat, store.db.value.profile.defaultMinutes, last?.intent ?: "")
            }
            ACTION_NUDGE -> store.nudges.answer(intent.getStringExtra("nudge_action") ?: "close")
            ACTION_ALARM -> {
                // Block ended: the companion's sound, then the check-in right away if we may draw over other apps.
                if (store.phase() == Phase.Due) Sounds.play(Sfx.Due, store.db.value.profile.avatar)
                if (store.phase() == Phase.Due && Settings.canDrawOverlays(context)) runCatching { openCheckIn(context) }
            }
        }
        runCatching { startTracker(context) }
    }

    companion object {
        const val ACTION_RATE = "app.nimisham.RATE"
        const val ACTION_STOP = "app.nimisham.STOP"
        const val ACTION_EXTEND = "app.nimisham.EXTEND"
        const val ACTION_RESUME = "app.nimisham.RESUME"
        const val ACTION_ALARM = "app.nimisham.ALARM"
        const val ACTION_NUDGE = "app.nimisham.NUDGE"
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun AndroidSettings(store: Store) {
    val ctx = LocalContext.current
    val prefs by store.prefs.collectAsState()
    // Re-read permission states whenever the screen recomposes after returning from system settings.
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); tick.intValue++ } }
    tick.intValue
    val pm = ctx.getSystemService(PowerManager::class.java)
    val unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    val canOverlay = Settings.canDrawOverlays(ctx)

    SectionTitle("On this phone")
    Toggle("Timer notification", prefs.richNotification, "Permanent notification with a live countdown and quick check-in buttons. Off = a quiet minimal one.") { on ->
        store.updatePrefs { it.copy(richNotification = on) }; startTracker(ctx)
    }
    Toggle("Floating bar over other apps", prefs.overlay && canOverlay, if (canOverlay) "A thin progress line (or a small pill) that stays on top of everything." else "Needs the 'display over other apps' permission — tap to grant.") { on ->
        if (on && !Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        store.updatePrefs { it.copy(overlay = on) }; startTracker(ctx)
    }
    if (prefs.overlay && canOverlay) {
        Spacer(Modifier.height(6.dp))
        Segmented(listOf("Line at the top", "Pill"), if (prefs.overlayStyle == "pill") 1 else 0) { i ->
            store.updatePrefs { it.copy(overlayStyle = if (i == 1) "pill" else "line") }; startTracker(ctx)
        }
        Spacer(Modifier.height(10.dp))
    }
    val addonsOn = store.db.collectAsState().value.profile.addons
    if (addonsOn) {
        Toggle(
            "Add-on bubbles over other apps", prefs.nudgeOverlay && canOverlay,
            if (canOverlay) "Your companion's eye-break, water and check-in chats float on top of whatever you're using." else "Needs the 'display over other apps' permission — tap to grant.",
        ) { on ->
            if (on && !Settings.canDrawOverlays(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            store.updatePrefs { it.copy(nudgeOverlay = on) }; startTracker(ctx)
        }
    }
    Spacer(Modifier.height(10.dp))
    Hairline()
    Spacer(Modifier.height(14.dp))
    Label("Keep it alive")
    Spacer(Modifier.height(10.dp))
    Muted(
        if (unrestricted) "Battery optimisation is off for Nimisham — it won't be killed in the background."
        else "Phones kill background apps to save battery. Allow Nimisham to run unrestricted so the timer and check-ins never stop.",
    )
    Spacer(Modifier.height(12.dp))
    Row {
        if (!unrestricted) {
            PrimaryButton("Allow background running") {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Spacer(Modifier.padding(5.dp))
        }
        GhostButton("App settings") {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    Spacer(Modifier.height(10.dp))
    Muted("On Xiaomi, Oppo, Vivo, Realme or Samsung also turn on 'Autostart' / set battery to 'No restrictions' in App settings. Nimisham starts itself after a reboot.", size = 12)
    Spacer(Modifier.height(12.dp))
    Muted("Tip: long-press your home screen → Widgets → Nimisham for a live companion widget. Tap \"Focus view\" on the Now tab for a calm always-on screen.", size = 12)
}
