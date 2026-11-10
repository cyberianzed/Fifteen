package app.fifteen

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
import kotlinx.coroutines.launch

class FifteenApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store by lazy {
        Store(DirTextFiles(filesDir), scope).also { s ->
            // Add-ons talk through the floating pet, so they only run while it's out.
            s.nudges.petOut = { p -> p.overlay && p.overlayStyle == "pet" && !p.petHidden && !p.serviceOff && Settings.canDrawOverlays(this) }
            s.nudges.onStartRequested = { openCheckIn(this) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Starting a block while the service is off (e.g. in the app) turns it back on.
        scope.launch {
            store.db.collect {
                if (store.prefs.value.serviceOff && store.phase() != Phase.Idle) {
                    store.updatePrefs { p -> p.copy(serviceOff = false) }
                    runCatching { startTracker(this@FifteenApplication) }
                }
            }
        }
    }

    companion object {
        fun store(ctx: Context) = (ctx.applicationContext as FifteenApplication).store
    }
}

fun startTracker(ctx: Context) {
    if (FifteenApplication.store(ctx).prefs.value.serviceOff) return
    ContextCompat.startForegroundService(ctx, Intent(ctx, TrackerService::class.java))
}

/** Opening the app (or a restart) turns the background service back on. */
fun turnOn(ctx: Context) {
    FifteenApplication.store(ctx).updatePrefs { it.copy(serviceOff = false) }
    startTracker(ctx)
}

class MainActivity : ComponentActivity() {
    private val store get() = FifteenApplication.store(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        turnOn(this)
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
            @Composable
            override fun PlatformFooter() = KeepRunning()
            @Composable
            override fun KeyQr(text: String) {}
        }
        acceptKeyLink(intent)
        setContent {
            Box(Modifier.fillMaxSize().imePadding()) { FifteenApp(store, platform) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptKeyLink(intent)
    }

    /** A scanned QR code (fifteen://key/F15-...) opens Fifteen; Settings then offers to sync with that key. */
    private fun acceptKeyLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "fifteen") PendingSyncKey.value = data.toString()
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
        val store = FifteenApplication.store(this)
        setContent {
            FifteenTheme {
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
        if (ok) runCatching { turnOn(context) }
    }
}

/** Notification buttons and the block-end alarm. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = FifteenApplication.store(context)
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
            ACTION_OFF -> { if (store.phase() == Phase.Idle) turnOff(context); return }
            ACTION_ALARM -> {
                // Block ended: the companion's sound, then the check-in right away if we may draw over other apps.
                if (store.phase() == Phase.Due) Sounds.play(Sfx.Due, store.db.value.profile.avatar)
                if (store.phase() == Phase.Due && Settings.canDrawOverlays(context)) runCatching { openCheckIn(context) }
            }
        }
        runCatching { startTracker(context) }
    }

    companion object {
        const val ACTION_RATE = "app.fifteen.RATE"
        const val ACTION_STOP = "app.fifteen.STOP"
        const val ACTION_EXTEND = "app.fifteen.EXTEND"
        const val ACTION_RESUME = "app.fifteen.RESUME"
        const val ACTION_ALARM = "app.fifteen.ALARM"
        const val ACTION_NUDGE = "app.fifteen.NUDGE"
        const val ACTION_OFF = "app.fifteen.OFF"
    }
}

@Composable
private fun AndroidSettings(store: Store) {
    val ctx = LocalContext.current
    val prefs by store.prefs.collectAsState()
    // Re-read the permission whenever the screen recomposes after returning from system settings.
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); tick.intValue++ } }
    tick.intValue
    val canOverlay = Settings.canDrawOverlays(ctx)

    SectionTitle("On this phone")
    Toggle(
        "Float over other apps", prefs.overlay && canOverlay,
        if (canOverlay) "Keeps Fifteen on screen while you use other apps." else "Needs permission to display over other apps. Tap to allow.",
    ) { on ->
        if (on && !Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        store.updatePrefs { it.copy(overlay = on, petHidden = false) }; startTracker(ctx)
    }
    if (prefs.overlay && canOverlay) {
        Spacer(Modifier.height(6.dp))
        val styles = listOf("pet", "line", "pill")
        Segmented(listOf("Pet", "Thin line", "Small pill"), styles.indexOf(prefs.overlayStyle).coerceAtLeast(0)) { i ->
            store.updatePrefs { it.copy(overlayStyle = styles[i], petHidden = false) }; startTracker(ctx)
        }
        Spacer(Modifier.height(8.dp))
        Muted(
            when (prefs.overlayStyle) {
                "line" -> "A thin progress line across the top of the screen."
                "pill" -> "A small timer you can drag anywhere."
                else -> "Your companion floats on screen. Long-press it to hide it or quit."
            },
            size = 12,
        )
    }
}

/** Last box in Settings: only matters on phones that close background apps. */
@SuppressLint("BatteryLife")
@Composable
private fun KeepRunning() {
    val ctx = LocalContext.current
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); tick.intValue++ } }
    tick.intValue
    val unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    Spacer(Modifier.height(24.dp))
    Card(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("Keep it running")
            Muted(
                if (unrestricted) "All set. Fifteen can run in the background."
                else "Some phones close background apps to save battery. If the timer or pet stops by itself, allow Fifteen to run in the background.",
                size = 13,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                if (!unrestricted) {
                    GhostButton("Allow background running") {
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
        }
    }
}
