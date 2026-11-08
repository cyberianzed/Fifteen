package app.nimisham

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.abs
import kotlin.system.exitProcess

private const val INSTANCE_PORT = 47615

fun main(args: Array<String>) {
    // One instance only: a second launch just asks the first to show its window.
    val server = runCatching { ServerSocket(INSTANCE_PORT, 4, InetAddress.getLoopbackAddress()) }.getOrNull()
    if (server == null) {
        runCatching { Socket(InetAddress.getLoopbackAddress(), INSTANCE_PORT).use { it.getOutputStream().write(1) } }
        exitProcess(0)
    }
    val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Nimisham")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = Store(DirTextFiles(dir), scope)
    scope.launch { store.syncLoop { 5_000 } }
    Runtime.getRuntime().addShutdownHook(Thread { store.flush() })
    if (store.prefs.value.autostart) Autostart.set(true)

    val showRequests = mutableStateOf(0)
    Thread {
        while (true) runCatching { server.accept().use { showRequests.value++ } }
    }.apply { isDaemon = true }.start()

    val startMinimized = "--minimized" in args
    application { DesktopApp(store, startMinimized, showRequests.value) }
}

@Composable
private fun ApplicationScope.DesktopApp(store: Store, startMinimized: Boolean, showRequest: Int) {
    var mainOpen by remember { mutableStateOf(!startMinimized) }
    var checkInOpen by remember { mutableStateOf(false) }
    val prefs by store.prefs.collectAsState()
    val v = rememberView(store)

    LaunchedEffect(showRequest) { if (showRequest > 0) mainOpen = true }

    // Ask what's first when the computer starts (or the app opens) and nothing is running.
    LaunchedEffect(Unit) {
        delay(1500)
        if (store.prefs.value.askOnStart && store.phase() == Phase.Idle) checkInOpen = true
    }
    // Pop the check-in when a block ends; nudge again every 10 minutes if it's still waiting.
    var promptedAt by remember { mutableStateOf(0L to "") }
    LaunchedEffect(v.phase, v.live?.id, v.now / 60_000) {
        val id = v.live?.id ?: return@LaunchedEffect
        if (v.phase != Phase.Due) return@LaunchedEffect
        if (promptedAt.second != id || (!checkInOpen && v.now - promptedAt.first > 10 * 60_000L)) {
            promptedAt = v.now to id
            if (!checkInOpen) Sounds.play(Sfx.Due, store.db.value.profile.avatar)
            checkInOpen = true
        }
    }

    val platform = remember {
        object : PlatformUi {
            override val isDesktop = true
            override fun openCheckIn() { checkInOpen = true }
            @Composable
            override fun PlatformSettings() = DesktopSettings(store)
        }
    }

    val progressBucket = (v.progress * 40).toInt()
    val trayIcon = remember(progressBucket, v.phase, v.category?.color) {
        RingPainter(if (v.phase == Phase.Running) v.progress else if (v.phase == Phase.Due) 1f else 0f, v.category?.color?.asColor() ?: Ink.mellow, v.phase)
    }
    Tray(
        icon = trayIcon,
        tooltip = when (v.phase) {
            Phase.Running -> "${v.category?.name ?: ""} · ${durationText(v.remaining)} left"
            Phase.Due -> "Block done — check in"
            Phase.Idle -> "Nimisham · resting"
        },
        onAction = { mainOpen = true },
        menu = {
            Item("Open Nimisham", onClick = { mainOpen = true })
            Item(if (v.phase == Phase.Due) "Check in" else if (v.phase == Phase.Idle) "Start a block" else "Switch activity", onClick = { checkInOpen = true })
            if (v.phase != Phase.Idle) Item("Stop tracking", onClick = { store.stopTracking() })
            Separator()
            CheckboxItem("Floating bar", prefs.showBar) { on -> store.updatePrefs { it.copy(showBar = on) } }
            CheckboxItem("Companion on the bar", prefs.showPet) { on -> store.updatePrefs { it.copy(showPet = on) } }
            CheckboxItem("Pet mode (double-click the bar)", prefs.barMode == "pet") { on -> store.updatePrefs { it.copy(barMode = if (on) "pet" else "bar", showPet = true) } }
            Separator()
            Item("Quit", onClick = ::exitApplication)
        },
    )

    if (mainOpen) {
        val state = rememberWindowState(size = DpSize(1280.dp, 880.dp), position = WindowPosition(Alignment.Center))
        Window(onCloseRequest = { mainOpen = false }, title = "Nimisham", state = state, icon = remember { RingPainter(0.7f, Ink.mellow, Phase.Running) }) {
            LaunchedEffect(showRequest) { window.toFront() }
            NimishamApp(store, platform)
        }
    }

    if (prefs.showBar) FloatingBar(store, v, prefs, onOpen = { if (v.phase == Phase.Running) mainOpen = true else checkInOpen = true })

    if (checkInOpen) {
        val state = rememberWindowState(size = DpSize(640.dp, 760.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = { checkInOpen = false }, title = "Check in", state = state,
            undecorated = true, transparent = true, alwaysOnTop = true, resizable = false,
            onPreviewKeyEvent = { if (it.key == Key.Escape) { checkInOpen = false; true } else false },
        ) {
            LaunchedEffect(Unit) { window.toFront(); window.requestFocus() }
            NimishamTheme {
                Box(
                    Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(28.dp)).background(Ink.bg)
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                        CheckInPanel(store, Modifier.fillMaxWidth()) { checkInOpen = false }
                    }
                }
            }
        }
    }
}

/**
 * The always-on-top companion window. Two shapes: the full bar (companion + calm bar) and pet mode
 * (a bigger companion standing on its own with a tiny timer). Double-click switches; a single click on the
 * companion makes it react; drag anywhere to move it.
 */
@Composable
private fun FloatingBar(store: Store, v: View, prefs: LocalPrefs, onOpen: () -> Unit) {
    val petMode = prefs.showPet && prefs.barMode == "pet"
    val barW = 460
    val petW = if (prefs.showPet) 104 else 0
    val (width, h) = when {
        petMode -> 210 to 250
        prefs.showPet -> (barW + petW) to 104
        else -> barW to 46
    }
    var poke by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var pendingOpen by remember { mutableStateOf<Job?>(null) }
    val window = remember {
        ComposeWindow().apply {
            type = java.awt.Window.Type.UTILITY
            isUndecorated = true
            isTransparent = true
            isAlwaysOnTop = true
            focusableWindowState = false
            background = java.awt.Color(0, 0, 0, 0)
            title = "Nimisham bar"
        }
    }
    Window(
        create = { window },
        dispose = { it.dispose() },
        update = { w ->
            if (w.width != width || w.height != h) {
                if (w.isVisible && w.width > 0) {
                    // Switching shape: keep the bottom-left corner (where the companion stands) in place.
                    val bottom = w.y + w.height
                    w.setBounds(w.x, bottom - h, width, h)
                } else {
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    val x = if (prefs.barX >= 0) prefs.barX else screen.x + (screen.width - width) / 2
                    val y = if (prefs.barY >= 0) prefs.barY else screen.y + 6
                    w.setBounds(x, y, width, h)
                }
            }
            if (!w.isVisible) w.isVisible = true
        },
    ) {
        NimishamTheme {
            // Callbacks read the latest state but stay stable, so the gesture detector never restarts mid-drag.
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val onClickLatest = rememberUpdatedState<(Float) -> Unit> { x ->
                    if (petMode || x <= petW * density) poke++
                    else {
                        pendingOpen?.cancel()
                        pendingOpen = scope.launch { delay(330); onOpen() }
                    }
            }
            val onDoubleLatest = rememberUpdatedState {
                pendingOpen?.cancel()
                if (prefs.showPet) store.updatePrefs { it.copy(barMode = if (it.barMode == "pet") "bar" else "pet") }
            }
            val drag = Modifier.fillMaxSize().moveWindowOnDrag(
                window,
                onClick = { x -> onClickLatest.value(x) },
                onDoubleClick = { onDoubleLatest.value() },
                onMoved = { x, y -> store.updatePrefs { it.copy(barX = x, barY = y) } },
            )
            if (petMode) {
                Column(drag, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    PetView(v.db.profile.avatar, v.petState(), Modifier.size(196.dp), interactive = false, poke = poke)
                    MiniTimer(v)
                    Spacer(Modifier.height(6.dp))
                }
            } else {
                Row(drag, verticalAlignment = Alignment.Bottom) {
                    if (prefs.showPet) {
                        PetView(v.db.profile.avatar, v.petState(), Modifier.size(100.dp), interactive = false, poke = poke)
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(Modifier.width(barW.dp).padding(bottom = 4.dp, end = 4.dp)) {
                        FocusBar(v, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/**
 * Drag moves the window (screen coordinates, so no jitter). A press without movement is a click; two clicks
 * within 350 ms are a double-click.
 */
private fun Modifier.moveWindowOnDrag(
    window: ComposeWindow,
    onClick: (Float) -> Unit,
    onDoubleClick: () -> Unit,
    onMoved: (Int, Int) -> Unit,
) = pointerInput(Unit) {
    var lastClick = 0L
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            if (down.changes.none { it.pressed }) continue
            val startMouse = MouseInfo.getPointerInfo().location
            val startWin = window.location
            var moved = false
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val p = MouseInfo.getPointerInfo().location
                val dx = p.x - startMouse.x; val dy = p.y - startMouse.y
                if (abs(dx) + abs(dy) > 4) moved = true
                if (moved) window.setLocation(startWin.x + dx, startWin.y + dy)
                if (e.changes.none { it.pressed }) break
            }
            if (moved) {
                onMoved(window.x, window.y); lastClick = 0
            } else {
                val now = System.currentTimeMillis()
                if (now - lastClick < 350) { lastClick = 0; onDoubleClick() }
                else { lastClick = now; onClick(down.changes.first().position.x) }
            }
        }
    }
}

@Composable
private fun DesktopSettings(store: Store) {
    val prefs by store.prefs.collectAsState()
    SectionTitle("On this computer")
    Toggle("Floating bar", prefs.showBar, "A thin always-on-top strip with the time left. Drag it anywhere.") { on -> store.updatePrefs { it.copy(showBar = on) } }
    Toggle("Companion on the bar", prefs.showPet, "Your tree / spirit / hunter sits beside the bar. Click it to play, double-click for pet mode.") { on -> store.updatePrefs { it.copy(showPet = on) } }
    Toggle("Pet mode", prefs.barMode == "pet", "A bigger companion on its own with a tiny timer. Double-click the floating window to switch.") { on ->
        store.updatePrefs { it.copy(barMode = if (on) "pet" else "bar", showPet = true) }
    }
    Toggle("Ask what's first at startup", prefs.askOnStart) { on -> store.updatePrefs { it.copy(askOnStart = on) } }
    Toggle(
        "Start with Windows", prefs.autostart,
        if (Autostart.available) "Starts quietly in the tray when you sign in." else "Available in the installed app (MSI).",
    ) { on -> store.updatePrefs { it.copy(autostart = on) }; Autostart.set(on) }
    Toggle("Reset bar position", false, "Moves the bar back to the top centre.") { store.updatePrefs { it.copy(barX = -1, barY = -1) } }
}

/** Tray / window icon: a ring that fills as the block runs. */
private class RingPainter(val progress: Float, val color: Color, val phase: Phase) : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val s = size.minDimension
        val stroke = s * 0.16f
        val tl = Offset(stroke / 2, stroke / 2)
        val sz = Size(s - stroke, s - stroke)
        drawCircle(Color(0xFF16181E), radius = s / 2)
        drawArc(Color(0x55FFFFFF), 0f, 360f, false, tl, sz, style = Stroke(stroke))
        drawArc(if (phase == Phase.Due) Ink.mellow else color, -90f, 360f * progress, false, tl, sz, style = Stroke(stroke))
        if (phase == Phase.Idle) drawCircle(Color(0xFF9A9CA5), radius = s * 0.12f)
    }
}

/** Windows sign-in startup via the per-user Run key (only for the installed app). */
object Autostart {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val exe: String? by lazy {
        System.getProperty("jpackage.app-path")
            ?: ProcessHandle.current().info().command().orElse(null)?.takeIf { it.endsWith("Nimisham.exe", ignoreCase = true) }
    }
    val available get() = exe != null && System.getProperty("os.name").startsWith("Windows")

    fun set(enabled: Boolean) {
        if (!available) return
        val cmd = if (enabled) listOf("reg", "add", KEY, "/v", "Nimisham", "/t", "REG_SZ", "/d", "\"$exe\" --minimized", "/f")
        else listOf("reg", "delete", KEY, "/v", "Nimisham", "/f")
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() }
    }
}
